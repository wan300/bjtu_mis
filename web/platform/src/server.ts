import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Fastify from 'fastify';
import cookie from '@fastify/cookie';
import rateLimit from '@fastify/rate-limit';
import { loadConfig, type PlatformConfig } from './config.js';
import { createDatabase, type Database } from './db.js';
import { rejectLegacyMutation, registerLegacyWriteRoutes } from './routes/context.js';
import { registerAuthRoutes } from './routes/auth.js';
import { registerCatalogRoutes } from './routes/catalog.js';
import { registerSubmissionsRoutes } from './routes/submissions.js';
import { registerManagementRoutes } from './routes/management.js';

export async function buildServer(options: { config?: PlatformConfig; db?: Database } = {}) {
  const config = options.config ?? loadConfig();
  const db = options.db ?? createDatabase(config.databaseUrl);
  const app = Fastify({ logger: { redact: ['req.headers.authorization', 'req.headers.cookie', 'res.headers.set-cookie'] } });
  await app.register(cookie);
  await app.register(rateLimit, { max: 120, timeWindow: '1 minute' });
  app.addHook('onRequest', rejectLegacyMutation);

  app.get('/health', async (_request, reply) => {
    await db.query('SELECT 1');
    return reply.send({ ok: true });
  });

  registerAuthRoutes(app, { db, config });
  registerCatalogRoutes(app, { db, config });
  registerSubmissionsRoutes(app, { db, config });
  registerManagementRoutes(app, { db, config });
  registerLegacyWriteRoutes(app);

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
