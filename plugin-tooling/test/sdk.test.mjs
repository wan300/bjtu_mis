import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  BjtuPluginError,
  PROTOCOL_VERSION,
  createBjtuPluginMigrationSdk,
  createBjtuPluginSdk
} from '../packages/plugin-sdk/dist/index.js';
import { createMockTransport } from '../packages/plugin-sdk/dist/mock.js';
import {
  BASE64URL_CHUNK_BYTES,
  WebViewBridgeTransport
} from '../packages/plugin-sdk/dist/internal/webview-transport.js';

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
  assert.deepEqual(result.binaryTransports, ['arraybuffer', 'base64url-chunks-v1']);
  assert.equal(result.preferredBinaryTransport, 'arraybuffer');
  assert.equal(transport.negotiatedBinaryTransport, 'arraybuffer');
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

  await sdk.runtime.handshake();
  const payload = new Uint8Array([1, 2, 3, 4]).buffer;
  await sdk.storage.blob.put(payload, 'application/octet-stream');
  assert.equal(transport.requests.at(-1).binaryBytes, 4);
  assert.equal(transport.requests.at(-1).binaryTransport, 'arraybuffer');
  await sdk.storage.blob.put(new ArrayBuffer(0), 'application/octet-stream');
  assert.equal(transport.requests.at(-1).binaryBytes, 0);
  assert.equal(transport.requests.at(-1).request.params.size, 0);
});

test('SDK exposes typed Android automation namespaces and events', async () => {
  const transport = createMockTransport();
  const sdk = createBjtuPluginSdk(transport);
  const received = [];
  const remove = sdk.android.accessibility.events.onReceived((event) => {
    received.push(event);
  });

  const status = await sdk.android.accessibility.events.getStatus();
  await sdk.android.accessibility.events.subscribe({
    eventTypes: ['viewClicked'],
    packageNames: ['com.example.app'],
    persistent: true,
    includeSource: true
  });
  await sdk.android.accessibility.nodes.find({ selector: { clickable: true }, maxResults: 4 });
  await sdk.android.accessibility.actions.performGlobal({
    idempotencyKey: 'go-home-1',
    action: 'home'
  });
  await sdk.android.packages.list({ includeSystem: false });
  await sdk.android.settings.open({ action: 'android.settings.ACCESSIBILITY_SETTINGS' });
  await transport.emit('android.accessibility.events@1', 'received', {
    subscriptionId: 'subscription-1',
    eventType: 'viewClicked',
    packageName: 'com.example.app',
    className: 'android.widget.Button',
    eventTime: 42,
    source: null
  });
  remove();

  assert.equal(status.connected, true);
  assert.deepEqual(
    transport.requests.map(({ request }) => `${request.capability}#${request.method}`),
    [
      'android.accessibility.events@1#getStatus',
      'android.accessibility.events@1#subscribe',
      'android.accessibility.nodes@1#find',
      'android.accessibility.actions@1#performGlobal',
      'android.packages.read@1#list',
      'android.settings.open@1#open'
    ]
  );
  assert.equal(received[0].eventType, 'viewClicked');
});

test('SDK exposes persistent Android native namespaces and foreground gate', async () => {
  const transport = createMockTransport();
  const sdk = createBjtuPluginSdk(transport);
  const networkEvents = [];
  const dispose = sdk.android.network.onChanged((event) => networkEvents.push(event));

  const device = await sdk.android.device.getInfo();
  const network = await sdk.android.network.getStatus();
  await sdk.android.battery.subscribe({ persistent: true });
  await sdk.android.haptics.perform({ durationMs: 12 });
  await sdk.android.notifications.show({
    idempotencyKey: 'notice-1',
    id: 'notice',
    title: 'Mock',
    body: 'Body'
  });
  await sdk.android.calendar.create({
    idempotencyKey: 'calendar-1',
    title: 'Mock event',
    startMs: 1,
    endMs: 2
  });
  await transport.emit('android.network.status@1', 'changed', {
    online: true,
    validated: true,
    metered: false,
    transport: 'wifi'
  });

  assert.equal(device.platform, 'android');
  assert.equal(network.transport, 'wifi');
  assert.equal(networkEvents.length, 1);
  assert.ok(
    transport.requests.some(
      ({ request }) => request.capability === 'android.calendar.write@1' && request.method === 'create'
    )
  );
  dispose();

  const background = createBjtuPluginSdk(createMockTransport({ lifecycle: 'background' }));
  await assert.rejects(
    background.android.files.pick(),
    (error) => error instanceof BjtuPluginError && error.code === 'foreground_required'
  );
});

test('Mock Host models disconnected accessibility and Android quotas', async () => {
  const disconnected = createBjtuPluginSdk(
    createMockTransport({ accessibilityService: 'disconnected' })
  );
  assert.equal((await disconnected.android.accessibility.events.getStatus()).connected, false);
  await assert.rejects(
    disconnected.android.accessibility.nodes.getRoot(),
    (error) => error instanceof BjtuPluginError && error.code === 'capability_unavailable'
  );

  const quota = createBjtuPluginSdk(createMockTransport({ quota: 'exceeded' }));
  await assert.rejects(
    quota.android.accessibility.actions.performGlobal({
      idempotencyKey: 'go-home-2',
      action: 'home'
    }),
    (error) => error instanceof BjtuPluginError && error.code === 'quota_exceeded'
  );
  await assert.rejects(
    quota.android.settings.open({ action: 'android.settings.ACCESSIBILITY_SETTINGS' }),
    (error) => error instanceof BjtuPluginError && error.code === 'quota_exceeded'
  );
});

test('SDK rejects binary writes before a successful handshake', async () => {
  const transport = createMockTransport();
  const sdk = createBjtuPluginSdk(transport);
  await assert.rejects(
    sdk.storage.blob.put(new Uint8Array([1]).buffer, 'application/octet-stream'),
    (error) => error instanceof BjtuPluginError && error.code === 'capability_unavailable'
  );
});

test('SDK enforces the binary handshake gate independently of the transport implementation', async () => {
  const records = [];
  const transport = {
    async send(request, binary) {
      records.push({ request, binary });
      if (request.capability === 'runtime.lifecycle@1') {
        return success(request.requestId, {
          protocolVersion: PROTOCOL_VERSION,
          contractProfile: 'contract_v1',
          runtimeFloor: 2,
          availableCapabilities: ['runtime.lifecycle@1', 'storage.blob@1'],
          binaryTransports: ['base64url-chunks-v1'],
          preferredBinaryTransport: 'base64url-chunks-v1'
        });
      }
      return success(request.requestId, resourceResult());
    },
    cancel() {},
    subscribe() {
      return () => undefined;
    }
  };
  const sdk = createBjtuPluginSdk(transport);
  const payload = new Uint8Array([1]).buffer;

  await assert.rejects(
    sdk.storage.blob.put(payload, 'application/octet-stream'),
    (error) => error instanceof BjtuPluginError && error.code === 'capability_unavailable'
  );
  assert.equal(records.length, 0);
  await sdk.runtime.handshake();
  await sdk.storage.blob.put(payload, 'application/octet-stream');
  assert.equal(records.at(-1).binary, payload);
});

test('SDK clears a previous binary negotiation before a failed re-handshake', async () => {
  const transport = createMockTransport();
  const sdk = createBjtuPluginSdk(transport);
  await sdk.runtime.handshake();
  assert.equal(transport.negotiatedBinaryTransport, 'arraybuffer');

  transport.setScenario({ capabilities: { 'runtime.lifecycle@1': false } });
  await assert.rejects(
    sdk.runtime.handshake(),
    (error) => error instanceof BjtuPluginError && error.code === 'capability_unavailable'
  );
  assert.equal(transport.negotiatedBinaryTransport, undefined);
  await assert.rejects(
    sdk.storage.blob.put(new Uint8Array([1]).buffer, 'application/octet-stream'),
    (error) => error instanceof BjtuPluginError && error.code === 'capability_unavailable'
  );
});

test('SDK cancellation remains immediate when the transport cancel hook throws', async () => {
  const transport = {
    send() {
      return new Promise(() => undefined);
    },
    cancel() {
      throw new Error('bridge detached');
    },
    subscribe() {
      return () => undefined;
    }
  };
  const sdk = createBjtuPluginSdk(transport);
  const controller = new AbortController();
  const pending = sdk.configuration.get('example', { signal: controller.signal });
  controller.abort();

  await assert.rejects(
    pending,
    (error) => error instanceof BjtuPluginError && error.code === 'user_cancelled'
  );
});

test('SDK normalizes the legacy host binaryTransport handshake', async () => {
  const transport = createMockTransport({
    responses: {
      'runtime.lifecycle@1#handshake': {
        protocolVersion: PROTOCOL_VERSION,
        contractProfile: 'contract_v1',
        runtimeFloor: 2,
        availableCapabilities: [],
        binaryTransport: true
      }
    }
  });
  const sdk = createBjtuPluginSdk(transport);
  const result = await sdk.runtime.handshake();
  assert.deepEqual(result.binaryTransports, ['arraybuffer']);
  assert.equal(result.preferredBinaryTransport, 'arraybuffer');
  assert.equal(transport.negotiatedBinaryTransport, 'arraybuffer');
});

test('SDK keeps non-binary calls usable on a legacy host without ArrayBuffer', async () => {
  const transport = createMockTransport({
    responses: {
      'runtime.lifecycle@1#handshake': {
        protocolVersion: PROTOCOL_VERSION,
        contractProfile: 'contract_v1',
        runtimeFloor: 2,
        availableCapabilities: ['runtime.lifecycle@1', 'configuration.read@1'],
        binaryTransport: false
      }
    }
  });
  const sdk = createBjtuPluginSdk(transport);
  const result = await sdk.runtime.handshake();
  assert.deepEqual(result.binaryTransports, []);
  assert.equal(result.preferredBinaryTransport, undefined);
  await sdk.configuration.get('example');
  await assert.rejects(
    sdk.storage.blob.put(new Uint8Array([1]).buffer, 'application/octet-stream'),
    (error) => error instanceof BjtuPluginError && error.code === 'capability_unavailable'
  );
});

test('WebView transport sends ArrayBuffer chunks with a SHA-256 declaration', async () => {
  const messages = [];
  const listeners = new Set();
  const emit = (message) => listeners.forEach((listener) => listener(message));
  const bridge = {
    postMessage(message) {
      messages.push(message);
      if (message.capability === 'runtime.lifecycle@1' && message.method === 'handshake') {
        queueMicrotask(() => emit(success(message.requestId, {
          protocolVersion: PROTOCOL_VERSION,
          contractProfile: 'contract_v1',
          runtimeFloor: 2,
          availableCapabilities: ['storage.blob@1'],
          binaryTransports: ['arraybuffer', 'base64url-chunks-v1'],
          preferredBinaryTransport: 'arraybuffer'
        })));
      } else if (message.binary?.chunks === 0) {
        queueMicrotask(() => emit(success(message.requestId, resourceResult())));
      } else if (message.kind === 'binaryChunk' && message.last === true) {
        queueMicrotask(() => emit(success(message.requestId, resourceResult())));
      }
    },
    addEventListener(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    }
  };
  const sdk = createBjtuPluginSdk(new WebViewBridgeTransport(bridge));
  await sdk.runtime.handshake();

  const bytes = new Uint8Array(256 * 1024 + 1);
  bytes.fill(0xa5);
  await sdk.storage.blob.put(bytes.buffer, 'application/octet-stream');
  const declaration = messages.find((message) => message.binary?.size === bytes.byteLength);
  const chunks = messages.filter(
    (message) => message.kind === 'binaryChunk' && message.requestId === declaration.requestId
  );
  assert.deepEqual(declaration.binary, {
    transport: 'arraybuffer',
    size: bytes.byteLength,
    chunks: 2,
    sha256: createHash('sha256').update(bytes).digest('hex')
  });
  assert.equal(chunks.length, 2);
  assert.ok(chunks.every((chunk) => chunk.payload instanceof ArrayBuffer));

  await sdk.storage.blob.put(new ArrayBuffer(0), 'application/octet-stream');
  const empty = messages.find((message) => message.binary?.size === 0);
  assert.equal(empty.binary.chunks, 0);
  assert.equal(empty.binary.sha256, createHash('sha256').digest('hex'));
});

test('WebView compatibility transport uses 48 KiB unpadded Base64URL chunks with ACK backpressure', async () => {
  const messages = [];
  const listeners = new Set();
  const emit = (message) => listeners.forEach((listener) => listener(message));
  const bridge = {
    postMessage(message) {
      messages.push(message);
      if (message.capability === 'runtime.lifecycle@1' && message.method === 'handshake') {
        queueMicrotask(() => emit(success(message.requestId, {
          protocolVersion: PROTOCOL_VERSION,
          contractProfile: 'contract_v1',
          runtimeFloor: 2,
          availableCapabilities: ['cache.resource@1'],
          binaryTransports: ['base64url-chunks-v1'],
          preferredBinaryTransport: 'base64url-chunks-v1'
        })));
      }
    },
    addEventListener(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    }
  };
  const sdk = createBjtuPluginSdk(new WebViewBridgeTransport(bridge));
  await sdk.runtime.handshake();
  const bytes = new Uint8Array(BASE64URL_CHUNK_BYTES + 1);
  for (let index = 0; index < bytes.length; index += 1) bytes[index] = index % 256;
  const pending = sdk.cache.put('generated', bytes.buffer, 'application/octet-stream');
  await waitFor(() => messages.some((message) => message.kind === 'binaryChunk'));

  const declaration = messages.find((message) => message.binary?.size === bytes.byteLength);
  let chunks = messages.filter((message) => message.kind === 'binaryChunk');
  assert.equal(declaration.binary.transport, 'base64url-chunks-v1');
  assert.equal(declaration.binary.chunks, 2);
  assert.equal(declaration.binary.sha256, createHash('sha256').update(bytes).digest('hex'));
  assert.equal(chunks.length, 1);
  assert.match(chunks[0].payload, /^[A-Za-z0-9_-]+$/u);
  assert.equal(chunks[0].payload.includes('='), false);
  assert.equal(chunks[0].payload.length, 64 * 1024);

  emit({
    protocolVersion: PROTOCOL_VERSION,
    kind: 'binaryChunkAck',
    requestId: declaration.requestId,
    index: 0
  });
  await waitFor(() => messages.filter((message) => message.kind === 'binaryChunk').length === 2);
  chunks = messages.filter((message) => message.kind === 'binaryChunk');
  assert.equal(chunks[1].payload, 'AA');
  emit({
    protocolVersion: PROTOCOL_VERSION,
    kind: 'binaryChunkAck',
    requestId: declaration.requestId,
    index: 1
  });
  emit(success(declaration.requestId, resourceResult()));
  await pending;
});

test('WebView compatibility upload cancellation clears a stalled chunk ACK', async () => {
  const messages = [];
  const listeners = new Set();
  const emit = (message) => listeners.forEach((listener) => listener(message));
  const bridge = {
    postMessage(message) {
      messages.push(message);
      if (message.capability === 'runtime.lifecycle@1' && message.method === 'handshake') {
        queueMicrotask(() => emit(success(message.requestId, {
          protocolVersion: PROTOCOL_VERSION,
          contractProfile: 'contract_v1',
          runtimeFloor: 2,
          availableCapabilities: ['storage.blob@1'],
          binaryTransports: ['base64url-chunks-v1'],
          preferredBinaryTransport: 'base64url-chunks-v1'
        })));
      }
    },
    addEventListener(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    }
  };
  const sdk = createBjtuPluginSdk(new WebViewBridgeTransport(bridge));
  await sdk.runtime.handshake();
  await assert.rejects(
    sdk.storage.blob.put(new Uint8Array([1]).buffer, 'application/octet-stream', {
      timeoutMs: 10
    }),
    (error) => error instanceof BjtuPluginError && error.code === 'request_timeout'
  );
  assert.ok(messages.some((message) => message.kind === 'cancel'));
});

test('WebView transport cancellation during SHA-256 preparation never starts an upload', async () => {
  const messages = [];
  const bridge = {
    postMessage(message) {
      messages.push(message);
    },
    addEventListener() {
      return () => undefined;
    }
  };
  const transport = new WebViewBridgeTransport(bridge);
  transport.configureBinaryTransport('base64url-chunks-v1');
  const request = {
    protocolVersion: PROTOCOL_VERSION,
    requestId: 'cancel-during-digest',
    capability: 'storage.blob@1',
    method: 'put',
    params: { contentType: 'application/octet-stream', size: 1 }
  };

  const pending = transport.send(request, new Uint8Array([1]).buffer);
  transport.cancel(request.requestId);
  await assert.rejects(
    pending,
    (error) => error?.code === 'user_cancelled'
  );
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(messages.filter((message) => message.binary !== undefined).length, 0);
  assert.equal(messages.filter((message) => message.kind === 'binaryChunk').length, 0);
  assert.equal(messages.filter((message) => message.kind === 'cancel').length, 1);
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

test('Mock Host models layout, theme, lifecycle and compatibility transport offers', async () => {
  const transport = createMockTransport({
    binaryTransports: ['base64url-chunks-v1'],
    preferredBinaryTransport: 'base64url-chunks-v1',
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
  assert.deepEqual(handshake.binaryTransports, ['base64url-chunks-v1']);
  assert.equal(handshake.preferredBinaryTransport, 'base64url-chunks-v1');
  assert.equal(transport.negotiatedBinaryTransport, 'base64url-chunks-v1');
  assert.equal(handshake.availableCapabilities.includes('storage.blob@1'), true);
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

function success(requestId, result) {
  return {
    protocolVersion: PROTOCOL_VERSION,
    requestId,
    ok: true,
    result
  };
}

function resourceResult() {
  return {
    handle: 'test-resource',
    size: 0,
    contentType: 'application/octet-stream',
    url: '/__bjtu/resources/test-resource'
  };
}

async function waitFor(predicate) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 1));
  }
  assert.fail('Timed out waiting for WebView transport activity.');
}
