import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import type { Database } from '../db.js';
import type { PlatformConfig } from '../config.js';

export interface RouteContext {
  db: Database;
  config: PlatformConfig;
}

export function apiError(reply: FastifyReply, status: number, code: string, message: string) {
  return reply.code(status).send({ error: { code, message } });
}

export async function rejectLegacyMutation(request: FastifyRequest, reply: FastifyReply) {
  const pathName = request.url.split('?', 1)[0] ?? '';
  const legacyMutation = request.method !== 'GET' &&
    pathName.startsWith('/api/v1/') &&
    pathName !== '/api/v1/plugins/resolve-updates' &&
    pathName !== '/api/v1/auth/logout';
  if (legacyMutation) {
    return apiError(
      reply,
      410,
      'legacy_api_read_only',
      'Manifest v1/v2 API 已进入只读迁移期；请改用 /api/v2'
    );
  }
  const frozenV2Mutation = request.method !== 'GET' &&
    pathName.startsWith('/api/v2/') &&
    pathName !== '/api/v2/plugins/resolve-updates';
  if (frozenV2Mutation) {
    return apiError(
      reply,
      410,
      'legacy_catalog_read_only',
      '/api/v2 已冻结为 P0-A 只读目录；请迁移至 /api/v3'
    );
  }
}

export function registerLegacyWriteRoutes(app: FastifyInstance) {
  // Preserve the frozen routes and their 410 responses without unreachable business logic.
  for (const url of [
    '/api/v2/submissions',
    '/api/v1/submissions',
    '/api/v1/plugins/:id/revalidate',
    '/api/v1/plugins/:id/unpublish',
    '/api/v1/plugins/:id/reports',
    '/api/v1/admin/plugins/:id/unpublish',
    '/api/v1/admin/plugins/:id/restore',
    '/api/v1/admin/reports/:id/resolve',
    '/api/v2/plugins/:id/revalidate',
    '/api/v2/plugins/:id/unpublish',
    '/api/v2/plugins/:id/reports',
    '/api/v2/admin/plugins/:id/unpublish',
    '/api/v2/admin/plugins/:id/restore',
    '/api/v2/admin/plugins/:id/publisher-transfer/approve',
    '/api/v2/admin/plugins/:id/publisher-transfer/reject',
    '/api/v2/admin/reports/:id/resolve'
  ]) {
    app.post(url, rejectLegacyMutation);
  }
}
