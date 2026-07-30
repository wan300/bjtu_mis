import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import path from 'node:path';
import test from 'node:test';
import type { Database } from '../../src/db.js';
import { buildServer } from '../../src/server.js';

function contractRow() {
  return {
    plugin_id: 'io.example.demo',
    source_url: 'https://github.com/alice/demo',
    repo_owner: 'alice',
    repo_name: 'demo',
    publisher_subject_id: 'github-owner:12345',
    publisher_owner_id: '12345',
    publisher_owner_login: 'alice',
    publisher_owner_type: 'User',
    publisher_transfer_status: 'none',
    version_id: 'version-2',
    version: '2.0.0',
    commit_sha: 'd'.repeat(40),
    manifest_json: {
      schema_version: 3,
      id: 'io.example.demo',
      name: 'Contract demo',
      version: '2.0.0',
      entrypoint: 'index.html',
      icon: 'icon.svg',
      capabilities: {
        required: ['runtime.lifecycle@1', 'network.request@1'],
        optional: ['storage.blob@1']
      },
      origins: {
        connect: ['https://api.example.com'],
        media: ['https://cdn.example.com']
      },
      data_schema_version: 1
    },
    warnings_json: [],
    archive_sha256: 'e'.repeat(64),
    package_digest_sha256: 'f'.repeat(64),
    package_bytes: 640,
    package_file_count: 2,
    artifact_path: 'unused.zip',
    icon_path: 'unused.svg',
    published_at: new Date('2026-07-29T00:00:00Z'),
    manifest_schema_version: 3,
    runtime_version: 2,
    min_runtime_version: 2,
    data_schema_version: 1,
    compatibility_state: 'compatible',
    verification_level: 'automated',
    contract_profile: 'contract_v1',
    runtime_floor: 2,
    capabilities_json: {
      required: ['runtime.lifecycle@1', 'network.request@1'],
      optional: ['storage.blob@1']
    },
    marketplace_json: {
      description: 'Contract plugin',
      author: 'Alice',
      category: 'other',
      tags: ['demo'],
      license: 'MIT',
      screenshots: []
    },
    manifest_file_name: 'bjtu-plugin.json'
  };
}

function config() {
  return {
    nodeEnv: 'test' as const,
    host: '127.0.0.1',
    port: 15020,
    publicBaseUrl: 'https://bjtu.cc',
    databaseUrl: 'postgres://unused',
    githubClientId: 'test',
    githubClientSecret: 'test',
    tokenEncryptionKey: randomBytes(32),
    sessionCookieName: 'test_session',
    adminGithubIds: new Set<string>(),
    reservedPluginIds: new Set<string>(),
    artifactRoot: path.resolve('.data-test'),
    repositoryRoot: path.resolve('..', '..'),
    pollIntervalMinutes: 30
  };
}

test('v3 catalog exposes contract profile, derived floor and separate marketplace', async (t) => {
  const queries: string[] = [];
  const database = {
    query: async (sql: string) => {
      queries.push(sql);
      return { rows: [contractRow()], rowCount: 1 };
    },
    end: async () => undefined
  } as unknown as Database;
  const server = await buildServer({ db: database, config: config() });
  t.after(() => server.close());

  const response = await server.inject({ method: 'GET', url: '/api/v3/plugins' });

  assert.equal(response.statusCode, 200);
  const payload = response.json();
  assert.equal(payload.apiVersion, 3);
  assert.equal(payload.contractProfile, 'contract_v1');
  assert.equal(payload.items[0].runtimeFloor, 2);
  assert.equal(payload.items[0].description, 'Contract plugin');
  assert.deepEqual(payload.items[0].capabilities.optional, ['storage.blob@1']);
  assert.deepEqual(payload.items[0].origins.connect, ['https://api.example.com']);
  assert.equal('bridgeOrigins' in payload.items[0], false);
  assert.equal('permissions' in payload.items[0], false);
  assert.match(payload.items[0].artifactUrl, /\/api\/v3\//);
  assert.ok(
    queries.some(
      (sql) =>
        sql.includes("v.contract_profile='contract_v1'") &&
        sql.includes("v.manifest_file_name='bjtu-plugin.json'")
    )
  );
});

test('v3 update resolution marks P0-A replacement without weakening publisher binding', async (t) => {
  const database = {
    query: async () => ({ rows: [contractRow()], rowCount: 1 }),
    end: async () => undefined
  } as unknown as Database;
  const server = await buildServer({ db: database, config: config() });
  t.after(() => server.close());

  const response = await server.inject({
    method: 'POST',
    url: '/api/v3/plugins/resolve-updates',
    payload: {
      installed: [
        {
          id: 'io.example.demo',
          commitSha: 'a'.repeat(40),
          publisherSubjectId: 'github-owner:12345',
          contractProfile: 'legacy_v3_p0a'
        }
      ]
    }
  });

  assert.equal(response.statusCode, 200);
  assert.equal(response.json().items[0].updateAvailable, true);
  assert.equal(response.json().items[0].publisherMismatch, false);
  assert.equal(response.json().items[0].replacesLegacyP0a, true);
});
