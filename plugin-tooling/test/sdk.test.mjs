import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  BjtuPluginError,
  PROTOCOL_VERSION,
  createBjtuPluginMigrationSdk,
  createBjtuPluginSdk
} from '../packages/plugin-sdk/dist/index.js';
import { createMockTransport } from '../packages/plugin-sdk/dist/mock.js';

test('SDK public declarations do not expose the host transport', async () => {
  const declarations = await readFile(
    new URL('../packages/plugin-sdk/dist/index.d.ts', import.meta.url),
    'utf8'
  );
  assert.doesNotMatch(declarations, /PluginTransport|WebViewBridgeTransport|PluginMigrationBridge/);
  assert.match(
    declarations,
    /declare function createBjtuPluginSdk\(\): BjtuPluginSdk/
  );
  assert.match(
    declarations,
    /declare function createBjtuPluginMigrationSdk\(\): BjtuPluginMigrationSdk/
  );
});

test('SDK performs protocol v2 handshake', async () => {
  const transport = createMockTransport();
  const sdk = createBjtuPluginSdk(transport);
  const result = await sdk.runtime.handshake();
  assert.equal(result.protocolVersion, PROTOCOL_VERSION);
  assert.equal(result.contractProfile, 'contract_v1');
  assert.equal(transport.requests[0].request.capability, 'runtime.lifecycle@1');
  assert.equal(transport.requests[0].request.method, 'handshake');
});

test('SDK closes the runtime through the lifecycle capability', async () => {
  const transport = createMockTransport();
  const sdk = createBjtuPluginSdk(transport);
  await sdk.runtime.close();
  assert.equal(
    `${transport.requests[0].request.capability}#${transport.requests[0].request.method}`,
    'runtime.lifecycle@1#close'
  );
});

test('SDK converts host errors to BjtuPluginError', async () => {
  const sdk = createBjtuPluginSdk(
    createMockTransport({
      permissions: {
        'identity.profile.read': false
      }
    })
  );
  await assert.rejects(
    sdk.campus.getProfile(),
    (error) => error instanceof BjtuPluginError && error.code === 'permission_denied'
  );
});

test('SDK propagates AbortSignal to transport cancellation', async () => {
  let cancelled;
  let resolveResponse;
  const transport = {
    binarySupported: true,
    send(request) {
      return new Promise((resolve) => {
        resolveResponse = () =>
          resolve({
            protocolVersion: PROTOCOL_VERSION,
            requestId: request.requestId,
            ok: true,
            result: {}
          });
      });
    },
    cancel(requestId) {
      cancelled = requestId;
      resolveResponse();
    },
    subscribe() {
      return () => {};
    }
  };
  const controller = new AbortController();
  const sdk = createBjtuPluginSdk(transport);
  const promise = sdk.network.request(
    {
      url: 'https://api.example.com'
    },
    {
      signal: controller.signal
    }
  );
  controller.abort();
  await assert.rejects(
    promise,
    (error) => error instanceof BjtuPluginError && error.code === 'user_cancelled'
  );
  assert.equal(typeof cancelled, 'string');
});

test('SDK routes events and binary payloads', async () => {
  const transport = createMockTransport();
  const sdk = createBjtuPluginSdk(transport);
  let lifecycle;
  const remove = sdk.runtime.on('theme', (value) => {
    lifecycle = value;
  });
  await transport.emit('runtime.lifecycle@1', 'theme', {
    colorScheme: 'dark',
    reducedMotion: false,
    highContrast: false
  });
  remove();
  assert.deepEqual(lifecycle, {
    colorScheme: 'dark',
    reducedMotion: false,
    highContrast: false
  });

  const payload = new Uint8Array([1, 2, 3, 4]).buffer;
  await sdk.storage.blob.put(payload, 'application/octet-stream');
  assert.equal(transport.requests.at(-1).binaryBytes, 4);
  await sdk.storage.blob.put(new ArrayBuffer(0), 'application/octet-stream');
  assert.equal(transport.requests.at(-1).binaryBytes, 0);
  assert.equal(transport.requests.at(-1).request.params.size, 0);
});

test('SDK enforces generated deadlines and cancels the host request', async () => {
  const transport = createMockTransport({ responseDelayMs: 50 });
  const sdk = createBjtuPluginSdk(transport);
  await assert.rejects(
    sdk.runtime.handshake({ timeoutMs: 10 }),
    (error) => error instanceof BjtuPluginError && error.code === 'request_timeout'
  );
  assert.equal(transport.requests[0].cancelled, true);
});

test('SDK lifecycle back event reports whether a listener handled it', async () => {
  const transport = createMockTransport();
  const sdk = createBjtuPluginSdk(transport);
  const remove = sdk.runtime.on('back', () => true);
  assert.equal(
    await transport.emit('runtime.lifecycle@1', 'back', {}, 'back-1', true),
    true
  );
  remove();
  assert.equal(
    await transport.emit('runtime.lifecycle@1', 'back', {}, 'back-2', true),
    false
  );
});

test('SDK exposes cache handle promotion and explicit handle deletion', async () => {
  const transport = createMockTransport();
  const sdk = createBjtuPluginSdk(transport);
  const promoted = await sdk.cache.promote('cache-download', 'avatars/current', {
    pinned: true
  });
  await sdk.cache.deleteHandle(promoted.handle);

  assert.deepEqual(
    transport.requests.slice(-2).map(
      ({ request }) => `${request.capability}#${request.method}`
    ),
    ['cache.resource@1#promote', 'cache.resource@1#deleteHandle']
  );
  assert.deepEqual(transport.requests.at(-2).request.params, {
    handle: 'cache-download',
    key: 'avatars/current',
    pinned: true
  });
});

test('migration SDK exposes only shadow storage and explicit commit', async () => {
  const calls = [];
  const migration = createBjtuPluginMigrationSdk({
    async invoke(capability, method, params = {}) {
      calls.push({ capability, method, params });
      return {
        protocolVersion: PROTOCOL_VERSION,
        requestId: `migration-${calls.length}`,
        ok: true,
        result: method === 'get' ? 'old-value' : {}
      };
    }
  });

  assert.equal(await migration.storage.get('schema'), 'old-value');
  await migration.storage.set('schema', 2);
  await migration.commit();
  assert.deepEqual(
    calls.map(({ capability, method }) => `${capability}#${method}`),
    ['storage.kv@2#get', 'storage.kv@2#set', 'runtime.migration@1#commit']
  );
  assert.equal('network' in migration, false);
});

test('Mock Host covers quota, migration and origin scenarios', async () => {
  for (const [scenario, invoke, expected] of [
    [{ quota: 'exceeded' }, (sdk) => sdk.storage.kv.set('key', 'value'), 'quota_exceeded'],
    [{ migrationFailure: true }, (sdk) => sdk.runtime.ready(), 'migration_failed'],
    [
      { originViolation: true },
      (sdk) => sdk.network.request({ url: 'https://api.example.com' }),
      'origin_denied'
    ]
  ]) {
    const sdk = createBjtuPluginSdk(createMockTransport(scenario));
    await assert.rejects(
      invoke(sdk),
      (error) => error instanceof BjtuPluginError && error.code === expected
    );
  }
});

test('Mock Host models layout, theme, lifecycle and binary feature gates', async () => {
  const transport = createMockTransport({
    binarySupported: false,
    capabilities: {
      'mail.send@1': false
    }
  });
  const sdk = createBjtuPluginSdk(transport);
  const events = [];
  const remove = sdk.runtime.on('theme', (data) => events.push(data));
  const removeLayout = sdk.runtime.on('resize', (data) => events.push(data));
  const removeState = sdk.runtime.on('pause', (data) => events.push(data));

  const handshake = await sdk.runtime.handshake();
  assert.equal(handshake.binaryTransport, false);
  assert.equal(handshake.availableCapabilities.includes('storage.blob@1'), false);
  assert.equal(handshake.availableCapabilities.includes('mail.send@1'), false);

  transport.setScenario({
    device: 'tablet',
    theme: 'dark',
    lifecycle: 'background'
  });
  assert.deepEqual(events, [
    {
      colorScheme: 'dark',
      reducedMotion: false,
      highContrast: false
    },
    {
      viewportWidthPx: 800,
      viewportHeightPx: 1280,
      density: 1,
      fontScale: 1,
      orientation: 'portrait',
      safeAreaTopPx: 0,
      safeAreaRightPx: 0,
      safeAreaBottomPx: 0,
      safeAreaLeftPx: 0,
      imeHeightPx: 0
    },
    {}
  ]);
  assert.equal(transport.currentScenario().device, 'tablet');

  transport.setScenario({ lifecycle: 'destroyed' });
  await assert.rejects(
    sdk.runtime.ready(),
    (error) => error instanceof BjtuPluginError && error.code === 'capability_unavailable'
  );
  remove();
  removeLayout();
  removeState();
});
