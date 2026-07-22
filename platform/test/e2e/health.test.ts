import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import path from 'node:path';
import test from 'node:test';
import type { Database } from '../../src/db.js';
import { buildServer } from '../../src/server.js';

test('health endpoint is available without exposing configuration', async (t) => {
  const database = {
    query: async () => ({ rows: [], rowCount: 0 }),
    end: async () => undefined
  } as unknown as Database;
  const server = await buildServer({
    db: database,
    config: {
      nodeEnv: 'test',
      host: '127.0.0.1',
      port: 15020,
      publicBaseUrl: 'https://bjtu.cc',
      databaseUrl: 'postgres://unused',
      githubClientId: 'test',
      githubClientSecret: 'test',
      tokenEncryptionKey: randomBytes(32),
      sessionCookieName: 'test_session',
      adminGithubIds: new Set(),
      reservedPluginIds: new Set(),
      artifactRoot: path.resolve('.data-test'),
      repositoryRoot: path.resolve('..'),
      pollIntervalMinutes: 30
    }
  });
  t.after(() => server.close());
  const response = await server.inject({ method: 'GET', url: '/health' });
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { ok: true });
});
