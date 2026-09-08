import type { FastifyInstance } from 'fastify';
import type { RouteContext } from './context.js';
import type { FastifyRequest, FastifyReply } from 'fastify';
import { randomBytes } from 'node:crypto';
import type { Database } from '../db.js';
import type { PlatformConfig } from '../config.js';
import { id } from '../domain.js';
import { encryptSecret } from '../crypto.js';
import { apiError } from './context.js';

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

function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString('base64url');
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

export async function requireUser(request: FastifyRequest, reply: FastifyReply, db: Database, config: PlatformConfig, csrf = true): Promise<{ session: SessionRow; user: UserRow } | null> {
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

export function isAdmin(user: UserRow, config: PlatformConfig): boolean {
  return config.adminGithubIds.has(user.github_id);
}

export function registerAuthRoutes(app: FastifyInstance, { db, config }: RouteContext) {
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
}
