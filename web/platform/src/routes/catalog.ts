import type { FastifyInstance } from 'fastify';
import type { RouteContext } from './context.js';
import { createReadStream, promises as fs } from 'node:fs';
import path from 'node:path';
import type { PlatformConfig } from '../config.js';
import { apiError } from './context.js';

interface CatalogRow {
  plugin_id: string;
  source_url: string;
  repo_owner: string;
  repo_name: string;
  publisher_subject_id: string | null;
  publisher_owner_id: string | null;
  publisher_owner_login: string | null;
  publisher_owner_type: string | null;
  publisher_transfer_status: string;
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
  manifest_schema_version: number;
  runtime_version: number;
  min_runtime_version: number;
  data_schema_version: number;
  compatibility_state: string;
  verification_level: string;
  contract_profile: string;
  runtime_floor: number;
  capabilities_json: Record<string, unknown>;
  marketplace_json: Record<string, unknown> | null;
  manifest_file_name: string;
}

function safePath(root: string, candidate: string): string {
  const resolved = path.resolve(candidate);
  const prefix = `${path.resolve(root)}${path.sep}`;
  if (!resolved.startsWith(prefix)) throw new Error('Artifact path is outside configured root');
  return resolved;
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function catalogPlugin(row: CatalogRow, config: PlatformConfig, apiVersion: 'v1' | 'v2' = 'v2') {
  const manifest = row.manifest_json;
  const marketplace = (manifest.marketplace && typeof manifest.marketplace === 'object' ? manifest.marketplace : {}) as Record<string, unknown>;
  const permissions = (manifest.permissions && typeof manifest.permissions === 'object' ? manifest.permissions : {}) as Record<string, unknown>;
  const plugin = {
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
    schemaVersion: row.manifest_schema_version,
    runtimeVersion: row.runtime_version,
    minRuntimeVersion: row.min_runtime_version,
    requiredCapabilities: stringList(manifest.required_capabilities),
    optionalCapabilities: stringList(manifest.optional_capabilities),
    dataSchemaVersion: row.data_schema_version,
    migrationEntrypoint: typeof manifest.migration_entrypoint === 'string' ? manifest.migration_entrypoint : null,
    connectOrigins: stringList(manifest.connect_origins),
    mediaOrigins: stringList(manifest.media_origins),
    frameOrigins: stringList(manifest.frame_origins),
    navigationOrigins: stringList(manifest.navigation_origins),
    bridgeOrigins: stringList(manifest.bridge_origins),
    configuration: Array.isArray(manifest.configuration) ? manifest.configuration : [],
    validationWarnings: Array.isArray(row.warnings_json) ? row.warnings_json : [],
    verificationLevel: row.verification_level,
    compatibilityState: row.compatibility_state,
    publisherSubjectId: row.publisher_subject_id,
    publisherIdentity: {
      subjectId: row.publisher_subject_id,
      ownerId: row.publisher_owner_id,
      login: row.publisher_owner_login,
      type: row.publisher_owner_type,
      transferStatus: row.publisher_transfer_status
    },
    publishedAt: row.published_at.toISOString(),
    iconUrl: `${config.publicBaseUrl}/api/${apiVersion}/plugins/${encodeURIComponent(row.plugin_id)}/versions/${row.commit_sha}/icon`,
    artifactUrl: `${config.publicBaseUrl}/api/${apiVersion}/plugins/${encodeURIComponent(row.plugin_id)}/versions/${row.commit_sha}/artifact`
  };
  return apiVersion === 'v1'
    ? { ...plugin, allowedOrigins: stringList(manifest.allowed_origins) }
    : plugin;
}

function contractCatalogPlugin(row: CatalogRow, config: PlatformConfig) {
  const manifest = row.manifest_json;
  const capabilities = row.capabilities_json ?? {};
  const marketplace = row.marketplace_json ?? {};
  const origins = (
    manifest.origins && typeof manifest.origins === 'object'
      ? manifest.origins
      : {}
  ) as Record<string, unknown>;
  return {
    id: row.plugin_id,
    name: String(manifest.name ?? ''),
    version: row.version,
    description: String(marketplace.description ?? ''),
    author: String(marketplace.author ?? ''),
    category: String(marketplace.category ?? 'other'),
    tags: stringList(marketplace.tags),
    license: typeof marketplace.license === 'string' ? marketplace.license : null,
    screenshots: Array.isArray(marketplace.screenshots) ? marketplace.screenshots : [],
    repositoryUrl: row.source_url,
    repository: `${row.repo_owner}/${row.repo_name}`,
    commitSha: row.commit_sha,
    archiveSha256: row.archive_sha256,
    packageDigestSha256: row.package_digest_sha256,
    packageBytes: Number(row.package_bytes),
    packageFileCount: row.package_file_count,
    schemaVersion: row.manifest_schema_version,
    contractProfile: row.contract_profile,
    runtimeFloor: row.runtime_floor,
    capabilities: {
      required: stringList(capabilities.required),
      optional: stringList(capabilities.optional)
    },
    origins: {
      connect: stringList(origins.connect),
      media: stringList(origins.media),
      frame: stringList(origins.frame),
      navigation: stringList(origins.navigation)
    },
    dataSchemaVersion: row.data_schema_version || null,
    migrationEntrypoint:
      typeof manifest.migration_entrypoint === 'string' ? manifest.migration_entrypoint : null,
    configuration: Array.isArray(manifest.configuration) ? manifest.configuration : [],
    validationWarnings: Array.isArray(row.warnings_json) ? row.warnings_json : [],
    verificationLevel: row.verification_level,
    compatibilityState: row.compatibility_state,
    publisherSubjectId: row.publisher_subject_id,
    publisherIdentity: {
      subjectId: row.publisher_subject_id,
      ownerId: row.publisher_owner_id,
      login: row.publisher_owner_login,
      type: row.publisher_owner_type,
      transferStatus: row.publisher_transfer_status
    },
    publishedAt: row.published_at.toISOString(),
    iconUrl: `${config.publicBaseUrl}/api/v3/plugins/${encodeURIComponent(row.plugin_id)}/versions/${row.commit_sha}/icon`,
    artifactUrl: `${config.publicBaseUrl}/api/v3/plugins/${encodeURIComponent(row.plugin_id)}/versions/${row.commit_sha}/artifact`
  };
}

function catalogSelect(): string {
  return `SELECT p.plugin_id, p.source_url, p.repo_owner, p.repo_name, p.publisher_subject_id,
    p.publisher_owner_id, p.publisher_owner_login, p.publisher_owner_type, p.publisher_transfer_status,
    v.id AS version_id, v.version,
    v.commit_sha, v.manifest_json, v.warnings_json, v.archive_sha256, v.package_digest_sha256,
    v.package_bytes, v.package_file_count, v.artifact_path, v.icon_path, v.published_at,
    v.manifest_schema_version, v.runtime_version, v.min_runtime_version, v.data_schema_version,
    v.compatibility_state, v.verification_level, v.contract_profile, v.runtime_floor,
    v.capabilities_json, v.marketplace_json, v.manifest_file_name
    FROM plugins p JOIN plugin_versions v ON v.id=p.latest_version_id`;
}

export function registerCatalogRoutes(app: FastifyInstance, { db, config }: RouteContext) {
  app.get('/api/v1/plugins', async (request) => {
    const query = request.query as { query?: string; category?: string; cursor?: string; limit?: string };
    const limit = Math.min(50, Math.max(1, Number.parseInt(query.limit ?? '20', 10) || 20));
    const parameters: unknown[] = [];
    const clauses = ["p.status='published'", 'NOT p.admin_suspended', "v.compatibility_state='legacy'"];
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
      items: rows.map((row) => catalogPlugin(row, config, 'v1')),
      nextCursor: hasMore && last ? Buffer.from(`${last.published_at.toISOString()}|${last.plugin_id}`).toString('base64url') : null
    };
  });

  app.get('/api/v1/plugins/:id', async (request, reply) => {
    const pluginId = (request.params as { id: string }).id;
    const result = await db.query<CatalogRow>(`${catalogSelect()} WHERE p.plugin_id=$1 AND p.status='published'
      AND NOT p.admin_suspended AND v.compatibility_state='legacy'`, [pluginId]);
    const row = result.rows[0];
    if (!row) return apiError(reply, 404, 'plugin_not_found', '插件不存在或已下架');
    return catalogPlugin(row, config, 'v1');
  });

  app.post('/api/v1/plugins/resolve-updates', async (request, reply) => {
    const body = request.body as { installed?: Array<{ id?: string; commitSha?: string }> };
    const installed = Array.isArray(body?.installed) ? body.installed.slice(0, 100) : [];
    const ids = installed.map((item) => item.id || '').filter(Boolean);
    if (!ids.length) return { items: [] };
    const result = await db.query<CatalogRow>(`${catalogSelect()} WHERE p.plugin_id=ANY($1::text[])
      AND p.status='published' AND NOT p.admin_suspended AND v.compatibility_state='legacy'`, [ids]);
    const current = new Map(installed.map((item) => [item.id, item.commitSha]));
    return { items: result.rows.map((row) => ({ ...catalogPlugin(row, config, 'v1'), updateAvailable: current.get(row.plugin_id) !== row.commit_sha })) };
  });

  app.get('/api/v1/plugins/:id/versions/:commit/artifact', async (request, reply) => {
    const params = request.params as { id: string; commit: string };
    const result = await db.query<{ status: string; admin_suspended: boolean; artifact_path: string; archive_sha256: string }>(`
      SELECT p.status,p.admin_suspended,v.artifact_path,v.archive_sha256 FROM plugins p
      JOIN plugin_versions v ON v.plugin_id=p.plugin_id WHERE p.plugin_id=$1 AND v.commit_sha=$2
        AND v.compatibility_state='legacy'
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
      WHERE p.plugin_id=$1 AND v.commit_sha=$2 AND v.compatibility_state='legacy'
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

  app.get('/api/v2/plugins', async (request) => {
    const query = request.query as { query?: string; category?: string; cursor?: string; limit?: string };
    const limit = Math.min(50, Math.max(1, Number.parseInt(query.limit ?? '20', 10) || 20));
    const parameters: unknown[] = [];
    const clauses = [
      "p.status='published'",
      'NOT p.admin_suspended',
      'v.manifest_schema_version=3',
      "v.compatibility_state='compatible'",
      "v.contract_profile='legacy_v3_p0a'",
      "p.publisher_subject_id ~ '^github-owner:[1-9][0-9]*$'",
      "p.publisher_owner_id ~ '^[1-9][0-9]*$'"
    ];
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
      apiVersion: 2,
      items: rows.map((row) => catalogPlugin(row, config, 'v2')),
      nextCursor: hasMore && last ? Buffer.from(`${last.published_at.toISOString()}|${last.plugin_id}`).toString('base64url') : null
    };
  });

  app.get('/api/v2/plugins/:id', async (request, reply) => {
    const pluginId = (request.params as { id: string }).id;
    const result = await db.query<CatalogRow>(`${catalogSelect()} WHERE p.plugin_id=$1
      AND p.status='published' AND NOT p.admin_suspended
      AND v.manifest_schema_version=3 AND v.compatibility_state='compatible'
      AND v.contract_profile='legacy_v3_p0a'
      AND p.publisher_subject_id ~ '^github-owner:[1-9][0-9]*$'
      AND p.publisher_owner_id ~ '^[1-9][0-9]*$'`, [pluginId]);
    const row = result.rows[0];
    if (!row) return apiError(reply, 404, 'plugin_not_found', '插件不存在、已下架或不兼容 Manifest v3');
    return catalogPlugin(row, config, 'v2');
  });

  app.post('/api/v2/plugins/resolve-updates', async (request) => {
    const body = request.body as {
      installed?: Array<{ id?: string; commitSha?: string; publisherSubjectId?: string }>;
    };
    const installed = Array.isArray(body?.installed) ? body.installed.slice(0, 100) : [];
    const ids = installed.map((item) => item.id || '').filter(Boolean);
    if (!ids.length) return { apiVersion: 2, items: [] };
    const result = await db.query<CatalogRow>(`${catalogSelect()} WHERE p.plugin_id=ANY($1::text[])
      AND p.status='published' AND NOT p.admin_suspended
      AND v.manifest_schema_version=3 AND v.compatibility_state='compatible'
      AND v.contract_profile='legacy_v3_p0a'
      AND p.publisher_subject_id ~ '^github-owner:[1-9][0-9]*$'
      AND p.publisher_owner_id ~ '^[1-9][0-9]*$'`, [ids]);
    const current = new Map(installed.map((item) => [item.id, item]));
    return {
      apiVersion: 2,
      items: result.rows.map((row) => {
        const installedVersion = current.get(row.plugin_id);
        const publisherMismatch = Boolean(
          installedVersion?.publisherSubjectId &&
          installedVersion.publisherSubjectId !== row.publisher_subject_id
        );
        return {
          ...catalogPlugin(row, config, 'v2'),
          updateAvailable: !publisherMismatch && installedVersion?.commitSha !== row.commit_sha,
          publisherMismatch
        };
      })
    };
  });

  app.get('/api/v2/plugins/:id/versions/:commit/artifact', async (request, reply) => {
    const params = request.params as { id: string; commit: string };
    const result = await db.query<{
      status: string;
      admin_suspended: boolean;
      artifact_path: string;
      archive_sha256: string;
    }>(`
      SELECT p.status,p.admin_suspended,v.artifact_path,v.archive_sha256 FROM plugins p
      JOIN plugin_versions v ON v.plugin_id=p.plugin_id
      WHERE p.plugin_id=$1 AND v.commit_sha=$2
        AND v.manifest_schema_version=3 AND v.compatibility_state='compatible'
        AND v.contract_profile='legacy_v3_p0a'
    `, [params.id, params.commit]);
    const row = result.rows[0];
    if (!row) return apiError(reply, 404, 'artifact_not_found', 'Manifest v3 插件版本不存在');
    if (row.status !== 'published' || row.admin_suspended) return apiError(reply, 410, 'plugin_unlisted', '插件已下架');
    const file = safePath(config.artifactRoot, row.artifact_path);
    await fs.access(file);
    reply.header('Content-Type', 'application/zip');
    reply.header('Content-Disposition', `attachment; filename="${params.id}-${params.commit.slice(0, 8)}.zip"`);
    reply.header('ETag', `"${row.archive_sha256}"`);
    reply.header('Digest', `sha-256=${Buffer.from(row.archive_sha256, 'hex').toString('base64')}`);
    reply.header('X-Content-Type-Options', 'nosniff');
    return reply.send(createReadStream(file));
  });

  app.get('/api/v2/plugins/:id/versions/:commit/icon', async (request, reply) => {
    const params = request.params as { id: string; commit: string };
    const result = await db.query<{ status: string; admin_suspended: boolean; icon_path: string }>(`
      SELECT p.status,p.admin_suspended,v.icon_path FROM plugins p
      JOIN plugin_versions v ON v.plugin_id=p.plugin_id
      WHERE p.plugin_id=$1 AND v.commit_sha=$2
        AND v.manifest_schema_version=3 AND v.compatibility_state='compatible'
        AND v.contract_profile='legacy_v3_p0a'
    `, [params.id, params.commit]);
    const row = result.rows[0];
    if (!row || row.status !== 'published' || row.admin_suspended) {
      return apiError(reply, 404, 'icon_not_found', '插件图标不存在');
    }
    const file = safePath(config.artifactRoot, row.icon_path);
    const contentTypes: Record<string, string> = {
      '.svg': 'image/svg+xml',
      '.png': 'image/png',
      '.webp': 'image/webp',
      '.jpg': 'image/jpeg',
      '.jpeg': 'image/jpeg'
    };
    reply.header('Content-Type', contentTypes[path.extname(file).toLowerCase()] || 'application/octet-stream');
    reply.header('Content-Security-Policy', "sandbox; default-src 'none'");
    reply.header('X-Content-Type-Options', 'nosniff');
    return reply.send(createReadStream(file));
  });

  app.get('/api/v3/plugins', async (request) => {
    const query = request.query as {
      query?: string;
      category?: string;
      cursor?: string;
      limit?: string;
    };
    const limit = Math.min(50, Math.max(1, Number.parseInt(query.limit ?? '20', 10) || 20));
    const parameters: unknown[] = [];
    const clauses = [
      "p.status='published'",
      'NOT p.admin_suspended',
      'v.manifest_schema_version=3',
      "v.compatibility_state='compatible'",
      "v.contract_profile='contract_v1'",
      "v.manifest_file_name='bjtu-plugin.json'",
      "p.publisher_subject_id ~ '^github-owner:[1-9][0-9]*$'",
      "p.publisher_owner_id ~ '^[1-9][0-9]*$'"
    ];
    if (query.query?.trim()) {
      parameters.push(`%${query.query.trim()}%`);
      clauses.push(`(
        v.manifest_json->>'name' ILIKE $${parameters.length}
        OR v.marketplace_json->>'description' ILIKE $${parameters.length}
        OR v.marketplace_json->>'author' ILIKE $${parameters.length}
        OR p.plugin_id ILIKE $${parameters.length}
        OR (v.marketplace_json->'tags')::text ILIKE $${parameters.length}
      )`);
    }
    if (query.category?.trim()) {
      parameters.push(query.category.trim());
      clauses.push(`v.marketplace_json->>'category'=$${parameters.length}`);
    }
    if (query.cursor) {
      const [publishedAt, pluginId] = Buffer.from(query.cursor, 'base64url')
        .toString('utf8')
        .split('|');
      if (publishedAt && pluginId) {
        parameters.push(publishedAt, pluginId);
        clauses.push(
          `(v.published_at, p.plugin_id) < ($${parameters.length - 1}::timestamptz, $${parameters.length})`
        );
      }
    }
    parameters.push(limit + 1);
    const result = await db.query<CatalogRow>(
      `${catalogSelect()} WHERE ${clauses.join(' AND ')}
       ORDER BY v.published_at DESC, p.plugin_id DESC LIMIT $${parameters.length}`,
      parameters
    );
    const hasMore = result.rows.length > limit;
    const rows = result.rows.slice(0, limit);
    const last = rows.at(-1);
    return {
      apiVersion: 3,
      contractProfile: 'contract_v1',
      items: rows.map((row) => contractCatalogPlugin(row, config)),
      nextCursor:
        hasMore && last
          ? Buffer.from(`${last.published_at.toISOString()}|${last.plugin_id}`).toString(
              'base64url'
            )
          : null
    };
  });

  app.get('/api/v3/plugins/:id', async (request, reply) => {
    const pluginId = (request.params as { id: string }).id;
    const result = await db.query<CatalogRow>(
      `${catalogSelect()} WHERE p.plugin_id=$1
       AND p.status='published' AND NOT p.admin_suspended
       AND v.manifest_schema_version=3 AND v.compatibility_state='compatible'
       AND v.contract_profile='contract_v1' AND v.manifest_file_name='bjtu-plugin.json'
       AND p.publisher_subject_id ~ '^github-owner:[1-9][0-9]*$'
       AND p.publisher_owner_id ~ '^[1-9][0-9]*$'`,
      [pluginId]
    );
    const row = result.rows[0];
    if (!row) {
      return apiError(reply, 404, 'plugin_not_found', '插件不存在、已下架或不是 contract_v1');
    }
    return {
      apiVersion: 3,
      ...contractCatalogPlugin(row, config)
    };
  });

  app.post('/api/v3/plugins/resolve-updates', async (request) => {
    const body = request.body as {
      installed?: Array<{
        id?: string;
        commitSha?: string;
        publisherSubjectId?: string;
        contractProfile?: string;
      }>;
    };
    const installed = Array.isArray(body?.installed) ? body.installed.slice(0, 100) : [];
    const ids = installed.map((item) => item.id || '').filter(Boolean);
    if (!ids.length) return { apiVersion: 3, contractProfile: 'contract_v1', items: [] };
    const result = await db.query<CatalogRow>(
      `${catalogSelect()} WHERE p.plugin_id=ANY($1::text[])
       AND p.status='published' AND NOT p.admin_suspended
       AND v.manifest_schema_version=3 AND v.compatibility_state='compatible'
       AND v.contract_profile='contract_v1' AND v.manifest_file_name='bjtu-plugin.json'
       AND p.publisher_subject_id ~ '^github-owner:[1-9][0-9]*$'
       AND p.publisher_owner_id ~ '^[1-9][0-9]*$'`,
      [ids]
    );
    const current = new Map(installed.map((item) => [item.id, item]));
    return {
      apiVersion: 3,
      contractProfile: 'contract_v1',
      items: result.rows.map((row) => {
        const installedVersion = current.get(row.plugin_id);
        const publisherMismatch = Boolean(
          installedVersion?.publisherSubjectId &&
            installedVersion.publisherSubjectId !== row.publisher_subject_id
        );
        const profileMismatch =
          installedVersion?.contractProfile !== undefined &&
          installedVersion.contractProfile !== 'contract_v1';
        return {
          ...contractCatalogPlugin(row, config),
          updateAvailable:
            !publisherMismatch &&
            installedVersion?.commitSha !== row.commit_sha,
          publisherMismatch,
          replacesLegacyP0a: profileMismatch
        };
      })
    };
  });

  app.get('/api/v3/plugins/:id/versions/:commit/artifact', async (request, reply) => {
    const params = request.params as { id: string; commit: string };
    const result = await db.query<{
      status: string;
      admin_suspended: boolean;
      artifact_path: string;
      archive_sha256: string;
    }>(`
      SELECT p.status,p.admin_suspended,v.artifact_path,v.archive_sha256
      FROM plugins p JOIN plugin_versions v ON v.plugin_id=p.plugin_id
      WHERE p.plugin_id=$1 AND v.commit_sha=$2
        AND v.manifest_schema_version=3 AND v.compatibility_state='compatible'
        AND v.contract_profile='contract_v1' AND v.manifest_file_name='bjtu-plugin.json'
    `, [params.id, params.commit]);
    const row = result.rows[0];
    if (!row) return apiError(reply, 404, 'artifact_not_found', 'contract_v1 插件版本不存在');
    if (row.status !== 'published' || row.admin_suspended) {
      return apiError(reply, 410, 'plugin_unlisted', '插件已下架');
    }
    const file = safePath(config.artifactRoot, row.artifact_path);
    await fs.access(file);
    reply.header('Content-Type', 'application/zip');
    reply.header(
      'Content-Disposition',
      `attachment; filename="${params.id}-${params.commit.slice(0, 8)}.zip"`
    );
    reply.header('ETag', `"${row.archive_sha256}"`);
    reply.header(
      'Digest',
      `sha-256=${Buffer.from(row.archive_sha256, 'hex').toString('base64')}`
    );
    reply.header('X-Content-Type-Options', 'nosniff');
    return reply.send(createReadStream(file));
  });

  app.get('/api/v3/plugins/:id/versions/:commit/icon', async (request, reply) => {
    const params = request.params as { id: string; commit: string };
    const result = await db.query<{
      status: string;
      admin_suspended: boolean;
      icon_path: string;
    }>(`
      SELECT p.status,p.admin_suspended,v.icon_path
      FROM plugins p JOIN plugin_versions v ON v.plugin_id=p.plugin_id
      WHERE p.plugin_id=$1 AND v.commit_sha=$2
        AND v.manifest_schema_version=3 AND v.compatibility_state='compatible'
        AND v.contract_profile='contract_v1' AND v.manifest_file_name='bjtu-plugin.json'
    `, [params.id, params.commit]);
    const row = result.rows[0];
    if (!row || row.status !== 'published' || row.admin_suspended) {
      return apiError(reply, 404, 'icon_not_found', '插件图标不存在');
    }
    const file = safePath(config.artifactRoot, row.icon_path);
    const contentTypes: Record<string, string> = {
      '.svg': 'image/svg+xml',
      '.png': 'image/png',
      '.webp': 'image/webp',
      '.jpg': 'image/jpeg',
      '.jpeg': 'image/jpeg'
    };
    reply.header(
      'Content-Type',
      contentTypes[path.extname(file).toLowerCase()] || 'application/octet-stream'
    );
    reply.header('Content-Security-Policy', "sandbox; default-src 'none'");
    reply.header('X-Content-Type-Options', 'nosniff');
    return reply.send(createReadStream(file));
  });
}
