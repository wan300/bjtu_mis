import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import path from 'node:path';
import test from 'node:test';
import type { Database } from '../../src/db.js';
import { buildServer } from '../../src/server.js';

function catalogRow() {
  return {
    plugin_id: 'bjtu.demo',
    source_url: 'https://github.com/alice/demo',
    repo_owner: 'alice',
    repo_name: 'demo',
    publisher_subject_id: 'github-owner:12345',
    publisher_owner_id: '12345',
    publisher_owner_login: 'alice',
    publisher_owner_type: 'User',
    publisher_transfer_status: 'none',
    version_id: 'version-1',
    version: '1.0.0',
    commit_sha: 'a'.repeat(40),
    manifest_json: {
      schema_version: 3,
      name: 'Demo',
      description: 'Manifest v3 demo',
      author: 'Alice',
      required_capabilities: ['runtime.lifecycle.v1'],
      optional_capabilities: ['storage.kv.v1'],
      data_schema_version: 1,
      connect_origins: ['https://api.example.com'],
      media_origins: [],
      frame_origins: [],
      navigation_origins: ['https://example.com'],
      bridge_origins: ['self'],
      permissions: { required: [], optional: [] },
      marketplace: { category: 'other', tags: ['demo'], license: 'MIT' },
      configuration: []
    },
    warnings_json: [],
    archive_sha256: 'b'.repeat(64),
    package_digest_sha256: 'c'.repeat(64),
    package_bytes: 512,
    package_file_count: 2,
    artifact_path: 'unused.zip',
    icon_path: 'unused.svg',
    published_at: new Date('2026-07-28T00:00:00Z'),
    manifest_schema_version: 3,
    runtime_version: 1,
    min_runtime_version: 1,
    data_schema_version: 1,
    compatibility_state: 'compatible',
    verification_level: 'automated',
    contract_profile: 'legacy_v3_p0a',
    runtime_floor: 1,
    capabilities_json: {
      required: ['runtime.lifecycle.v1'],
      optional: ['storage.kv.v1']
    },
    marketplace_json: {
      description: 'Manifest v3 demo',
      author: 'Alice',
      category: 'other',
      tags: ['demo'],
      license: 'MIT'
    },
    manifest_file_name: 'bjtu-service.json'
  };
}

test('v2 catalog exposes v3 security metadata and excludes legacy through SQL', async (t) => {
  const queries: string[] = [];
  const database = {
    query: async (sql: string) => {
      queries.push(sql);
      return { rows: [catalogRow()], rowCount: 1 };
    },
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
      repositoryRoot: path.resolve('..', '..'),
      pollIntervalMinutes: 30
    }
  });
  t.after(() => server.close());

  const response = await server.inject({ method: 'GET', url: '/api/v2/plugins' });

  assert.equal(response.statusCode, 200);
  const payload = response.json();
  assert.equal(payload.apiVersion, 2);
  assert.equal(payload.items[0].schemaVersion, 3);
  assert.equal(payload.items[0].publisherSubjectId, 'github-owner:12345');
  assert.deepEqual(payload.items[0].bridgeOrigins, ['self']);
  assert.deepEqual(payload.items[0].requiredCapabilities, ['runtime.lifecycle.v1']);
  assert.equal('allowedOrigins' in payload.items[0], false);
  assert.match(payload.items[0].iconUrl, /\/api\/v2\//);
  assert.ok(queries.some((sql) =>
    sql.includes('v.manifest_schema_version=3') &&
    sql.includes("v.compatibility_state='compatible'") &&
    sql.includes("v.contract_profile='legacy_v3_p0a'")
  ));
});

test('legacy v1 mutation endpoints are read-only', async (t) => {
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
      repositoryRoot: path.resolve('..', '..'),
      pollIntervalMinutes: 30
    }
  });
  t.after(() => server.close());

  const response = await server.inject({
    method: 'POST',
    url: '/api/v1/submissions',
    payload: { repositoryUrl: 'https://github.com/alice/demo' }
  });

  assert.equal(response.statusCode, 410);
  assert.equal(response.json().error.code, 'legacy_api_read_only');
});

test('v2 mutation endpoints are frozen while resolve-updates remains readable', async (t) => {
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
      repositoryRoot: path.resolve('..', '..'),
      pollIntervalMinutes: 30
    }
  });
  t.after(() => server.close());

  const frozen = await server.inject({
    method: 'POST',
    url: '/api/v2/submissions',
    payload: { repositoryUrl: 'https://github.com/alice/demo' }
  });
  assert.equal(frozen.statusCode, 410);
  assert.equal(frozen.json().error.code, 'legacy_catalog_read_only');

  const resolve = await server.inject({
    method: 'POST',
    url: '/api/v2/plugins/resolve-updates',
    payload: { installed: [] }
  });
  assert.equal(resolve.statusCode, 200);
  assert.equal(resolve.json().apiVersion, 2);
});
