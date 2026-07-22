import { createReadStream, promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import Fastify, { type FastifyReply, type FastifyRequest } from 'fastify';
import cookie from '@fastify/cookie';
import rateLimit from '@fastify/rate-limit';
import { loadConfig, type PlatformConfig } from './config.js';
import { encryptSecret } from './crypto.js';
import { createDatabase, transaction, type Database } from './db.js';
import { id, parseGitHubRepositoryUrl } from './domain.js';

interface SessionRow {
  id: string;
  user_id: string | null;
  oauth_state: string | null;
  csrf_token: string;
  expires_at: Date;
}

interface UserRow {
  id: string;
  github_id: string;
  login: string;
  avatar_url: string;
}

interface CatalogRow {
  plugin_id: string;
  source_url: string;
  repo_owner: string;
  repo_name: string;
  version_id: string;
  version: string;
  commit_sha: string;
  manifest_json: Record<string, unknown>;
  warnings_json: unknown[];
  archive_sha256: string;
  package_digest_sha256: string;
  package_bytes: string | number;
  package_file_count: number;
  artifact_path: string;
  icon_path: string;
  published_at: Date;
}

function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString('base64url');
}

function apiError(reply: FastifyReply, status: number, code: string, message: string) {
  return reply.code(status).send({ error: { code, message } });
}

function safePath(root: string, candidate: string): string {
  const resolved = path.resolve(candidate);
  const prefix = `${path.resolve(root)}${path.sep}`;
  if (!resolved.startsWith(prefix)) throw new Error('Artifact path is outside configured root');
  return resolved;
}

function catalogPlugin(row: CatalogRow, config: PlatformConfig) {
  const manifest = row.manifest_json;
  const marketplace = (manifest.marketplace && typeof manifest.marketplace === 'object' ? manifest.marketplace : {}) as Record<string, unknown>;
  const permissions = (manifest.permissions && typeof manifest.permissions === 'object' ? manifest.permissions : {}) as Record<string, unknown>;
  return {
    id: row.plugin_id,
    name: String(manifest.name ?? ''),
    description: String(manifest.description ?? ''),
    version: row.version,
    author: String(manifest.author ?? ''),
    category: String(marketplace.category ?? 'other'),
    tags: Array.isArray(marketplace.tags) ? marketplace.tags : [],
    license: typeof marketplace.license === 'string' ? marketplace.license : null,
    repositoryUrl: row.source_url,
    repository: `${row.repo_owner}/${row.repo_name}`,
    commitSha: row.commit_sha,
    archiveSha256: row.archive_sha256,
    packageDigestSha256: row.package_digest_sha256,
    packageBytes: Number(row.package_bytes),
    packageFileCount: row.package_file_count,
    permissions: {
      required: Array.isArray(permissions.required) ? permissions.required : [],
      optional: Array.isArray(permissions.optional) ? permissions.optional : []
    },
    allowedOrigins: Array.isArray(manifest.allowed_origins) ? manifest.allowed_origins : [],
    configuration: Array.isArray(manifest.configuration) ? manifest.configuration : [],
    validationWarnings: Array.isArray(row.warnings_json) ? row.warnings_json : [],
    verificationLevel: 'automated',
    publishedAt: row.published_at.toISOString(),
    iconUrl: `${config.publicBaseUrl}/api/v1/plugins/${encodeURIComponent(row.plugin_id)}/versions/${row.commit_sha}/icon`,
    artifactUrl: `${config.publicBaseUrl}/api/v1/plugins/${encodeURIComponent(row.plugin_id)}/versions/${row.commit_sha}/artifact`
  };
}

function catalogSelect(): string {
  return `SELECT p.plugin_id, p.source_url, p.repo_owner, p.repo_name, v.id AS version_id, v.version,
    v.commit_sha, v.manifest_json, v.warnings_json, v.archive_sha256, v.package_digest_sha256,
    v.package_bytes, v.package_file_count, v.artifact_path, v.icon_path, v.published_at
    FROM plugins p JOIN plugin_versions v ON v.id=p.latest_version_id`;
}

async function getSession(request: FastifyRequest, db: Database, config: PlatformConfig): Promise<SessionRow | null> {
  const value = request.cookies[config.sessionCookieName];
  if (!value) return null;
  const result = await db.query<SessionRow>('SELECT * FROM sessions WHERE id=$1 AND expires_at > now()', [value]);
  return result.rows[0] ?? null;
}

async function ensureSession(request: FastifyRequest, reply: FastifyReply, db: Database, config: PlatformConfig): Promise<SessionRow> {
  const existing = await getSession(request, db, config);
  if (existing) return existing;
  const session: SessionRow = {
    id: randomToken(),
    user_id: null,
    oauth_state: null,
    csrf_token: randomToken(),
    expires_at: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000)
  };
  await db.query('INSERT INTO sessions(id,csrf_token,expires_at) VALUES($1,$2,$3)', [session.id, session.csrf_token, session.expires_at]);
  reply.setCookie(config.sessionCookieName, session.id, {
    httpOnly: true,
    secure: config.nodeEnv === 'production',
    sameSite: 'lax',
    path: '/',
    expires: session.expires_at
  });
  return session;
}

async function requireUser(request: FastifyRequest, reply: FastifyReply, db: Database, config: PlatformConfig, csrf = true): Promise<{ session: SessionRow; user: UserRow } | null> {
  const session = await getSession(request, db, config);
  if (!session?.user_id) {
    apiError(reply, 401, 'authentication_required', '请先使用 GitHub 登录');
    return null;
  }
  if (csrf && request.headers['x-csrf-token'] !== session.csrf_token) {
    apiError(reply, 403, 'csrf_failed', '请求校验失败，请刷新页面后重试');
    return null;
  }
  const result = await db.query<UserRow>('SELECT id,github_id,login,avatar_url FROM users WHERE id=$1', [session.user_id]);
  const user = result.rows[0];
  if (!user) {
    apiError(reply, 401, 'authentication_required', '登录会话已失效');
    return null;
  }
  return { session, user };
}

function isAdmin(user: UserRow, config: PlatformConfig): boolean {
  return config.adminGithubIds.has(user.github_id);
}

async function createSubmission(db: Database, userId: string, sourceUrl: string): Promise<string> {
  const repository = parseGitHubRepositoryUrl(sourceUrl);
  const active = await db.query(`SELECT 1 FROM submissions
    WHERE lower(repo_owner)=lower($1) AND lower(repo_name)=lower($2)
      AND status IN ('queued','validating') LIMIT 1`, [repository.owner, repository.repo]);
  if (active.rowCount) throw new Error('该仓库已有校验任务正在进行');
  const daily = await db.query<{ count: string }>("SELECT count(*)::text AS count FROM submissions WHERE user_id=$1 AND created_at > now() - interval '1 day'", [userId]);
  if (Number(daily.rows[0]?.count ?? 0) >= 10) throw new Error('每天最多提交或重校验 10 次');
  const submissionId = id();
  try {
    await transaction(db, async (client) => {
      await client.query(`INSERT INTO submissions(id,user_id,source_url,repo_owner,repo_name,status)
        VALUES($1,$2,$3,$4,$5,'queued')`, [submissionId, userId, repository.canonicalUrl, repository.owner, repository.repo]);
      await client.query("INSERT INTO validation_jobs(id,submission_id,status) VALUES($1,$2,'queued')", [id(), submissionId]);
    });
  } catch (error) {
    if (isConstraintViolation(error, 'submissions_active_repository_idx')) {
      throw new Error('该仓库已有校验任务正在进行');
    }
    throw error;
  }
  return submissionId;
}

function isConstraintViolation(error: unknown, constraint: string): boolean {
  if (!error || typeof error !== 'object') return false;
  const databaseError = error as { code?: unknown; constraint?: unknown };
  return databaseError.code === '23505' && databaseError.constraint === constraint;
}

export async function buildServer(options: { config?: PlatformConfig; db?: Database } = {}) {
  const config = options.config ?? loadConfig();
  const db = options.db ?? createDatabase(config.databaseUrl);
  const app = Fastify({ logger: { redact: ['req.headers.authorization', 'req.headers.cookie', 'res.headers.set-cookie'] } });
  await app.register(cookie);
  await app.register(rateLimit, { max: 120, timeWindow: '1 minute' });

  app.get('/health', async (_request, reply) => {
    await db.query('SELECT 1');
    return reply.send({ ok: true });
  });

  app.get('/api/v1/auth/github/start', async (request, reply) => {
    const session = await ensureSession(request, reply, db, config);
    const state = randomToken();
    await db.query('UPDATE sessions SET oauth_state=$2 WHERE id=$1', [session.id, state]);
    const authorize = new URL('https://github.com/login/oauth/authorize');
    authorize.searchParams.set('client_id', config.githubClientId);
    authorize.searchParams.set('redirect_uri', `${config.publicBaseUrl}/api/v1/auth/github/callback`);
    authorize.searchParams.set('state', state);
    return reply.redirect(authorize.toString());
  });

  app.get('/api/v1/auth/github/callback', async (request, reply) => {
    const query = request.query as { code?: string; state?: string };
    const session = await getSession(request, db, config);
    if (!session || !query.code || !query.state || query.state !== session.oauth_state) return apiError(reply, 400, 'oauth_state_invalid', 'GitHub 登录状态校验失败');
    const tokenResponse = await fetch('https://github.com/login/oauth/access_token', {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'User-Agent': 'bjtu-plugin-platform' },
      body: JSON.stringify({ client_id: config.githubClientId, client_secret: config.githubClientSecret, code: query.code })
    });
    const tokenPayload = await tokenResponse.json() as { access_token?: string; error_description?: string };
    if (!tokenPayload.access_token) return apiError(reply, 502, 'oauth_exchange_failed', tokenPayload.error_description || 'GitHub 登录失败');
    const userResponse = await fetch('https://api.github.com/user', {
      headers: { Accept: 'application/vnd.github+json', Authorization: `Bearer ${tokenPayload.access_token}`, 'User-Agent': 'bjtu-plugin-platform' }
    });
    if (!userResponse.ok) return apiError(reply, 502, 'github_user_failed', '无法读取 GitHub 用户信息');
    const githubUser = await userResponse.json() as { id: number; login: string; avatar_url?: string };
    const userId = id();
    const encryptedToken = encryptSecret(tokenPayload.access_token, config.tokenEncryptionKey);
    const result = await db.query<{ id: string }>(`
      INSERT INTO users(id,github_id,login,avatar_url,encrypted_oauth_token)
      VALUES($1,$2,$3,$4,$5)
      ON CONFLICT(github_id) DO UPDATE SET login=excluded.login, avatar_url=excluded.avatar_url,
        encrypted_oauth_token=excluded.encrypted_oauth_token, updated_at=now()
      RETURNING id
    `, [userId, String(githubUser.id), githubUser.login, githubUser.avatar_url || '', encryptedToken]);
    await db.query('UPDATE sessions SET user_id=$2, oauth_state=NULL WHERE id=$1', [session.id, result.rows[0]!.id]);
    return reply.redirect('/plugins/manage.html');
  });

  app.get('/api/v1/auth/me', async (request, reply) => {
    const session = await ensureSession(request, reply, db, config);
    if (!session.user_id) return { authenticated: false, csrfToken: session.csrf_token };
    const user = (await db.query<UserRow>('SELECT id,github_id,login,avatar_url FROM users WHERE id=$1', [session.user_id])).rows[0];
    if (!user) return { authenticated: false, csrfToken: session.csrf_token };
    return { authenticated: true, login: user.login, avatarUrl: user.avatar_url, admin: isAdmin(user, config), csrfToken: session.csrf_token };
  });

  app.post('/api/v1/auth/logout', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth) return;
    await db.query('DELETE FROM sessions WHERE id=$1', [auth.session.id]);
    reply.clearCookie(config.sessionCookieName, { path: '/' });
    return { ok: true };
  });

  app.get('/api/v1/plugins', async (request) => {
    const query = request.query as { query?: string; category?: string; cursor?: string; limit?: string };
    const limit = Math.min(50, Math.max(1, Number.parseInt(query.limit ?? '20', 10) || 20));
    const parameters: unknown[] = [];
    const clauses = ["p.status='published'", 'NOT p.admin_suspended'];
    if (query.query?.trim()) {
      parameters.push(`%${query.query.trim()}%`);
      clauses.push(`(v.manifest_json->>'name' ILIKE $${parameters.length} OR v.manifest_json->>'description' ILIKE $${parameters.length}
        OR v.manifest_json->>'author' ILIKE $${parameters.length} OR p.plugin_id ILIKE $${parameters.length}
        OR (v.manifest_json->'marketplace'->'tags')::text ILIKE $${parameters.length})`);
    }
    if (query.category?.trim()) {
      parameters.push(query.category.trim());
      clauses.push(`v.manifest_json->'marketplace'->>'category'=$${parameters.length}`);
    }
    if (query.cursor) {
      const [publishedAt, pluginId] = Buffer.from(query.cursor, 'base64url').toString('utf8').split('|');
      if (publishedAt && pluginId) {
        parameters.push(publishedAt, pluginId);
        clauses.push(`(v.published_at, p.plugin_id) < ($${parameters.length - 1}::timestamptz, $${parameters.length})`);
      }
    }
    parameters.push(limit + 1);
    const result = await db.query<CatalogRow>(`${catalogSelect()} WHERE ${clauses.join(' AND ')}
      ORDER BY v.published_at DESC, p.plugin_id DESC LIMIT $${parameters.length}`, parameters);
    const hasMore = result.rows.length > limit;
    const rows = result.rows.slice(0, limit);
    const last = rows.at(-1);
    return {
      items: rows.map((row) => catalogPlugin(row, config)),
      nextCursor: hasMore && last ? Buffer.from(`${last.published_at.toISOString()}|${last.plugin_id}`).toString('base64url') : null
    };
  });

  app.get('/api/v1/plugins/:id', async (request, reply) => {
    const pluginId = (request.params as { id: string }).id;
    const result = await db.query<CatalogRow>(`${catalogSelect()} WHERE p.plugin_id=$1 AND p.status='published' AND NOT p.admin_suspended`, [pluginId]);
    const row = result.rows[0];
    if (!row) return apiError(reply, 404, 'plugin_not_found', '插件不存在或已下架');
    return catalogPlugin(row, config);
  });

  app.post('/api/v1/plugins/resolve-updates', async (request, reply) => {
    const body = request.body as { installed?: Array<{ id?: string; commitSha?: string }> };
    const installed = Array.isArray(body?.installed) ? body.installed.slice(0, 100) : [];
    const ids = installed.map((item) => item.id || '').filter(Boolean);
    if (!ids.length) return { items: [] };
    const result = await db.query<CatalogRow>(`${catalogSelect()} WHERE p.plugin_id=ANY($1::text[]) AND p.status='published' AND NOT p.admin_suspended`, [ids]);
    const current = new Map(installed.map((item) => [item.id, item.commitSha]));
    return { items: result.rows.map((row) => ({ ...catalogPlugin(row, config), updateAvailable: current.get(row.plugin_id) !== row.commit_sha })) };
  });

  app.get('/api/v1/plugins/:id/versions/:commit/artifact', async (request, reply) => {
    const params = request.params as { id: string; commit: string };
    const result = await db.query<{ status: string; admin_suspended: boolean; artifact_path: string; archive_sha256: string }>(`
      SELECT p.status,p.admin_suspended,v.artifact_path,v.archive_sha256 FROM plugins p
      JOIN plugin_versions v ON v.plugin_id=p.plugin_id WHERE p.plugin_id=$1 AND v.commit_sha=$2
    `, [params.id, params.commit]);
    const row = result.rows[0];
    if (!row) return apiError(reply, 404, 'artifact_not_found', '插件版本不存在');
    if (row.status !== 'published' || row.admin_suspended) return apiError(reply, 410, 'plugin_unlisted', '插件已下架');
    const file = safePath(config.artifactRoot, row.artifact_path);
    await fs.access(file);
    reply.header('Content-Type', 'application/zip');
    reply.header('Content-Disposition', `attachment; filename="${params.id}-${params.commit.slice(0, 8)}.zip"`);
    reply.header('ETag', `"${row.archive_sha256}"`);
    reply.header('Digest', `sha-256=${Buffer.from(row.archive_sha256, 'hex').toString('base64')}`);
    return reply.send(createReadStream(file));
  });

  app.get('/api/v1/plugins/:id/versions/:commit/icon', async (request, reply) => {
    const params = request.params as { id: string; commit: string };
    const result = await db.query<{ status: string; admin_suspended: boolean; icon_path: string }>(`
      SELECT p.status,p.admin_suspended,v.icon_path FROM plugins p JOIN plugin_versions v ON v.plugin_id=p.plugin_id
      WHERE p.plugin_id=$1 AND v.commit_sha=$2
    `, [params.id, params.commit]);
    const row = result.rows[0];
    if (!row || row.status !== 'published' || row.admin_suspended) return apiError(reply, 404, 'icon_not_found', '插件图标不存在');
    const file = safePath(config.artifactRoot, row.icon_path);
    const contentTypes: Record<string, string> = { '.svg': 'image/svg+xml', '.png': 'image/png', '.webp': 'image/webp', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg' };
    reply.header('Content-Type', contentTypes[path.extname(file).toLowerCase()] || 'application/octet-stream');
    reply.header('Content-Security-Policy', "sandbox; default-src 'none'");
    reply.header('X-Content-Type-Options', 'nosniff');
    return reply.send(createReadStream(file));
  });

  app.post('/api/v1/submissions', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth) return;
    try {
      const submissionId = await createSubmission(db, auth.user.id, String((request.body as { repositoryUrl?: string })?.repositoryUrl ?? ''));
      return reply.code(202).send({ id: submissionId, status: 'queued' });
    } catch (error) {
      return apiError(reply, 400, 'submission_rejected', error instanceof Error ? error.message : String(error));
    }
  });

  app.get('/api/v1/submissions/:id', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config, false);
    if (!auth) return;
    const submissionId = (request.params as { id: string }).id;
    const result = await db.query('SELECT id,source_url,status,plugin_id,commit_sha,error_text,created_at,updated_at,user_id FROM submissions WHERE id=$1', [submissionId]);
    const row = result.rows[0];
    if (!row || (row.user_id !== auth.user.id && !isAdmin(auth.user, config))) return apiError(reply, 404, 'submission_not_found', '投稿不存在');
    const { user_id: _private, ...publicRow } = row;
    return publicRow;
  });

  app.get('/api/v1/me/plugins', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config, false);
    if (!auth) return;
    const submissions = await db.query(`SELECT id,source_url,status,plugin_id,commit_sha,error_text,created_at,updated_at
      FROM submissions WHERE user_id=$1 ORDER BY created_at DESC LIMIT 100`, [auth.user.id]);
    return { items: submissions.rows };
  });

  app.post('/api/v1/plugins/:id/revalidate', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth) return;
    const pluginId = (request.params as { id: string }).id;
    const result = await db.query<{ source_url: string; submitter_user_id: string; admin_suspended: boolean }>('SELECT source_url,submitter_user_id,admin_suspended FROM plugins WHERE plugin_id=$1', [pluginId]);
    const plugin = result.rows[0];
    if (!plugin || plugin.submitter_user_id !== auth.user.id) return apiError(reply, 404, 'plugin_not_found', '插件不存在');
    if (plugin.admin_suspended) return apiError(reply, 409, 'admin_suspended', '插件已被管理员下架');
    try {
      const submissionId = await createSubmission(db, auth.user.id, plugin.source_url);
      return reply.code(202).send({ id: submissionId, status: 'queued' });
    } catch (error) {
      return apiError(reply, 400, 'revalidation_rejected', error instanceof Error ? error.message : String(error));
    }
  });

  app.post('/api/v1/plugins/:id/unpublish', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth) return;
    const pluginId = (request.params as { id: string }).id;
    const result = await db.query("UPDATE plugins SET status='withdrawn',updated_at=now() WHERE plugin_id=$1 AND submitter_user_id=$2 AND NOT admin_suspended RETURNING plugin_id", [pluginId, auth.user.id]);
    if (!result.rowCount) return apiError(reply, 404, 'plugin_not_found', '插件不存在或无法下架');
    await db.query("INSERT INTO audit_log(id,actor_user_id,action,plugin_id) VALUES($1,$2,'developer_unpublished',$3)", [id(), auth.user.id, pluginId]);
    return { ok: true };
  });

  app.post('/api/v1/plugins/:id/reports', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth) return;
    const pluginId = (request.params as { id: string }).id;
    const body = request.body as { reason?: string; details?: string };
    const reason = String(body?.reason ?? '').trim().slice(0, 80);
    const details = String(body?.details ?? '').trim().slice(0, 1000);
    if (!reason) return apiError(reply, 400, 'reason_required', '请选择举报原因');
    const exists = await db.query("SELECT 1 FROM plugins WHERE plugin_id=$1 AND status='published'", [pluginId]);
    if (!exists.rowCount) return apiError(reply, 404, 'plugin_not_found', '插件不存在');
    await db.query('INSERT INTO reports(id,plugin_id,reporter_user_id,reason,details) VALUES($1,$2,$3,$4,$5)', [id(), pluginId, auth.user.id, reason, details]);
    return reply.code(201).send({ ok: true });
  });

  app.get('/api/v1/admin/overview', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config, false);
    if (!auth || !isAdmin(auth.user, config)) return auth ? apiError(reply, 403, 'admin_required', '需要管理员权限') : undefined;
    const [plugins, jobs, reports] = await Promise.all([
      db.query(`SELECT p.plugin_id,p.source_url,p.status,p.admin_suspended,p.updated_at,v.version,v.commit_sha
        FROM plugins p LEFT JOIN plugin_versions v ON v.id=p.latest_version_id ORDER BY p.updated_at DESC LIMIT 200`),
      db.query(`SELECT j.id,j.status,j.attempt,j.diagnostics_json,j.created_at,j.finished_at,s.source_url,s.plugin_id
        FROM validation_jobs j JOIN submissions s ON s.id=j.submission_id ORDER BY j.created_at DESC LIMIT 200`),
      db.query(`SELECT r.id,r.plugin_id,r.reason,r.details,r.status,r.created_at,u.login AS reporter
        FROM reports r JOIN users u ON u.id=r.reporter_user_id ORDER BY r.created_at DESC LIMIT 200`)
    ]);
    return { plugins: plugins.rows, jobs: jobs.rows, reports: reports.rows };
  });

  app.post('/api/v1/admin/plugins/:id/unpublish', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth || !isAdmin(auth.user, config)) return auth ? apiError(reply, 403, 'admin_required', '需要管理员权限') : undefined;
    const pluginId = (request.params as { id: string }).id;
    const reason = String((request.body as { reason?: string })?.reason ?? '').trim().slice(0, 500);
    const result = await db.query("UPDATE plugins SET status='unlisted',admin_suspended=true,updated_at=now() WHERE plugin_id=$1 RETURNING plugin_id", [pluginId]);
    if (!result.rowCount) return apiError(reply, 404, 'plugin_not_found', '插件不存在');
    await db.query("INSERT INTO audit_log(id,actor_user_id,action,plugin_id,metadata_json) VALUES($1,$2,'admin_unpublished',$3,$4::jsonb)", [id(), auth.user.id, pluginId, JSON.stringify({ reason })]);
    return { ok: true };
  });

  app.post('/api/v1/admin/plugins/:id/restore', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth || !isAdmin(auth.user, config)) return auth ? apiError(reply, 403, 'admin_required', '需要管理员权限') : undefined;
    const pluginId = (request.params as { id: string }).id;
    const result = await db.query("UPDATE plugins SET status='published',admin_suspended=false,source_failure_count=0,updated_at=now() WHERE plugin_id=$1 AND latest_version_id IS NOT NULL RETURNING plugin_id", [pluginId]);
    if (!result.rowCount) return apiError(reply, 404, 'plugin_not_found', '插件不存在或没有可恢复版本');
    await db.query("INSERT INTO audit_log(id,actor_user_id,action,plugin_id) VALUES($1,$2,'admin_restored',$3)", [id(), auth.user.id, pluginId]);
    return { ok: true };
  });

  app.post('/api/v1/admin/reports/:id/resolve', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth || !isAdmin(auth.user, config)) return auth ? apiError(reply, 403, 'admin_required', '需要管理员权限') : undefined;
    const reportId = (request.params as { id: string }).id;
    const status = (request.body as { status?: string })?.status === 'dismissed' ? 'dismissed' : 'resolved';
    const result = await db.query('UPDATE reports SET status=$2,resolved_at=now() WHERE id=$1 RETURNING id', [reportId, status]);
    if (!result.rowCount) return apiError(reply, 404, 'report_not_found', '举报不存在');
    return { ok: true };
  });

  app.addHook('onClose', async () => {
    if (!options.db) await db.end();
  });
  return app;
}

async function main() {
  const config = loadConfig();
  const app = await buildServer({ config });
  await app.listen({ host: config.host, port: config.port });
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  await main();
}
