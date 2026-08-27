import {
  CAPABILITY_IDS,
  CAPABILITY_MOCK_RESPONSES,
  CAPABILITY_REGISTRY,
  PROTOCOL_VERSION,
  type CapabilityId,
  type PluginErrorCode
} from './generated/contracts.js';
import type {
  BinaryTransport,
  BjtuPluginSdk,
  PluginEventV2,
  PluginRequestV2,
  PluginResponseV2
} from './index.js';
import { createBjtuPluginSdk } from './index.js';

export interface MockHostScenario {
  capabilities?: Partial<Record<CapabilityId, boolean>>;
  permissions?: Partial<Record<string, boolean>>;
  device?: 'phone' | 'tablet' | 'desktop';
  theme?: 'light' | 'dark' | 'system';
  network?: 'online' | 'offline' | 'timeout';
  quota?: 'normal' | 'exceeded';
  migrationFailure?: boolean;
  lifecycle?: 'active' | 'background' | 'destroyed';
  accessibilityService?: 'connected' | 'disconnected';
  cspViolation?: boolean;
  originViolation?: boolean;
  binaryTransports?: readonly BinaryTransport[];
  preferredBinaryTransport?: BinaryTransport;
  responseDelayMs?: number;
  responses?: Record<string, unknown>;
}

export interface MockRequestRecord {
  request: PluginRequestV2;
  binaryBytes: number;
  binaryTransport?: BinaryTransport;
  cancelled: boolean;
}

export interface MockPluginTransport {
  readonly binaryTransports: readonly BinaryTransport[];
  readonly negotiatedBinaryTransport?: BinaryTransport;
  readonly requests: MockRequestRecord[];
  send(request: PluginRequestV2, binary?: ArrayBuffer): Promise<PluginResponseV2>;
  cancel(requestId: string): void;
  subscribe(
    listener: (
      event: PluginEventV2
    ) => boolean | void | Promise<boolean | void>
  ): () => void;
  configureBinaryTransport(transport: BinaryTransport | undefined): void;
  emit(
    capability: CapabilityId,
    event: string,
    data?: unknown,
    requestId?: string,
    requiresAcknowledgement?: boolean
  ): Promise<boolean>;
  setScenario(next: Partial<MockHostScenario>): void;
  currentScenario(): Readonly<MockHostScenario>;
}

export function createMockTransport(initial: MockHostScenario = {}): MockPluginTransport {
  let scenario: MockHostScenario = {
    device: 'phone',
    theme: 'system',
    network: 'online',
    quota: 'normal',
    lifecycle: 'active',
    accessibilityService: 'connected',
    binaryTransports: ['arraybuffer', 'base64url-chunks-v1'],
    preferredBinaryTransport: 'arraybuffer',
    ...initial
  };
  let negotiatedBinaryTransport: BinaryTransport | undefined;
  const listeners = new Set<
    (event: PluginEventV2) => boolean | void | Promise<boolean | void>
  >();
  const requests: MockRequestRecord[] = [];
  const emitEvent = (
    capability: CapabilityId,
    event: string,
    data?: unknown,
    requestId?: string,
    requiresAcknowledgement = false
  ): Promise<boolean> => {
    const envelope: PluginEventV2 = {
      protocolVersion: PROTOCOL_VERSION,
      eventId: `mock-${Date.now()}-${listeners.size}`,
      capability,
      event,
      requestId,
      data,
      requiresAcknowledgement
    };
    return Promise.all(
      [...listeners].map(async (listener) => (await listener(envelope)) === true)
    ).then((results) => results.some(Boolean));
  };

  return {
    get binaryTransports() {
      return Object.freeze([...(scenario.binaryTransports ?? [])]);
    },
    get negotiatedBinaryTransport() {
      return negotiatedBinaryTransport;
    },
    requests,
    async send(request, binary) {
      const record: MockRequestRecord = {
        request,
        binaryBytes: binary?.byteLength ?? 0,
        ...(binary === undefined ? {} : { binaryTransport: negotiatedBinaryTransport }),
        cancelled: false
      };
      requests.push(record);
      if (scenario.responseDelayMs && scenario.responseDelayMs > 0) {
        await new Promise((resolve) => setTimeout(resolve, scenario.responseDelayMs));
      }
      const descriptor = CAPABILITY_REGISTRY.capabilities.find(
        (item) => item.id === request.capability
      );
      if (!descriptor || scenario.capabilities?.[request.capability] === false) {
        return failure(request.requestId, 'capability_unavailable', 'Mock capability is disabled.');
      }
      if (binary !== undefined && !negotiatedBinaryTransport) {
        return failure(
          request.requestId,
          'capability_unavailable',
          'Mock binary transport is not negotiated. Call runtime.handshake() first.'
        );
      }
      if (
        descriptor.permission &&
        scenario.permissions?.[descriptor.permission.id] === false
      ) {
        return failure(request.requestId, 'permission_denied', 'Mock permission is disabled.');
      }
      const route = `${request.capability}#${request.method}`;
      if (
        scenario.lifecycle === 'destroyed' &&
        route !== 'runtime.lifecycle@1#handshake' &&
        route !== 'runtime.lifecycle@1#close'
      ) {
        return failure(request.requestId, 'capability_unavailable', 'Mock runtime is destroyed.');
      }
      if (scenario.originViolation || scenario.cspViolation) {
        return failure(request.requestId, 'origin_denied', 'Mock origin/CSP policy rejected the call.');
      }
      if (
        scenario.lifecycle === 'background' &&
        [
          'android.files.pick@1',
          'android.files.save@1',
          'android.media.pick@1',
          'android.share.open@1',
          'android.camera.capture@1',
          'android.audio.record@1',
          'android.biometric.verify@1',
          'android.location.read@1'
        ].includes(request.capability)
      ) {
        return failure(
          request.requestId,
          'foreground_required',
          'Mock Android system UI capability requires the foreground plugin page.'
        );
      }
      if (
        request.capability.startsWith('android.accessibility.') &&
        request.method !== 'getStatus' &&
        scenario.accessibilityService === 'disconnected'
      ) {
        return failure(
          request.requestId,
          'capability_unavailable',
          'Mock Android accessibility service is disconnected.'
        );
      }
      if (request.capability === 'network.request@1' && scenario.network === 'offline') {
        return failure(request.requestId, 'http_error', 'Mock host is offline.', true);
      }
      if (request.capability === 'network.request@1' && scenario.network === 'timeout') {
        return failure(request.requestId, 'network_timeout', 'Mock request timed out.', true);
      }
      if (
        scenario.quota === 'exceeded' &&
        (
          request.capability.startsWith('storage.') ||
          request.capability === 'cache.resource@1' ||
          request.capability === 'android.accessibility.actions@1' ||
          request.capability === 'android.settings.open@1'
        )
      ) {
        return failure(request.requestId, 'quota_exceeded', 'Mock quota was exceeded.');
      }
      if (scenario.migrationFailure && route === 'runtime.lifecycle@1#ready') {
        return failure(request.requestId, 'migration_failed', 'Mock migration failed.');
      }
      const configured = Object.prototype.hasOwnProperty.call(scenario.responses ?? {}, route)
        ? scenario.responses?.[route]
        : undefined;
      return {
        protocolVersion: PROTOCOL_VERSION,
        requestId: request.requestId,
        ok: true,
        result: configured !== undefined ? configured : defaultResponse(request, scenario)
      };
    },
    cancel(requestId) {
      const record = [...requests].reverse().find((item) => item.request.requestId === requestId);
      if (record) record.cancelled = true;
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    configureBinaryTransport(transport) {
      negotiatedBinaryTransport = transport !== undefined &&
          (scenario.binaryTransports ?? []).includes(transport)
        ? transport
        : undefined;
    },
    emit(capability, event, data, requestId, requiresAcknowledgement) {
      return emitEvent(capability, event, data, requestId, requiresAcknowledgement);
    },
    setScenario(next) {
      const previous = scenario;
      scenario = { ...scenario, ...next };
      if (next.binaryTransports !== undefined || next.preferredBinaryTransport !== undefined) {
        negotiatedBinaryTransport = undefined;
      }
      if (next.theme !== undefined && next.theme !== previous.theme) {
        void emitEvent('runtime.lifecycle@1', 'theme', {
          colorScheme: next.theme === 'dark' ? 'dark' : 'light',
          reducedMotion: false,
          highContrast: false
        });
      }
      if (next.device !== undefined && next.device !== previous.device) {
        const landscape = next.device === 'desktop';
        const width = next.device === 'phone' ? 412 : next.device === 'tablet' ? 800 : 1440;
        const height = next.device === 'phone' ? 915 : next.device === 'tablet' ? 1280 : 900;
        void emitEvent('runtime.lifecycle@1', 'resize', {
          viewportWidthPx: width,
          viewportHeightPx: height,
          density: 1,
          fontScale: 1,
          orientation: landscape ? 'landscape' : 'portrait',
          safeAreaTopPx: 0,
          safeAreaRightPx: 0,
          safeAreaBottomPx: 0,
          safeAreaLeftPx: 0,
          imeHeightPx: 0
        });
      }
      if (next.lifecycle !== undefined && next.lifecycle !== previous.lifecycle) {
        void emitEvent(
          'runtime.lifecycle@1',
          next.lifecycle === 'active' ? 'resume' : 'pause',
          {}
        );
      }
      if (next.network !== undefined && next.network !== previous.network) {
        const online = next.network === 'online';
        void emitEvent('runtime.lifecycle@1', 'network', {
          online,
          validated: online,
          metered: false,
          transport: online ? 'wifi' : 'none'
        });
      }
    },
    currentScenario() {
      return Object.freeze({ ...scenario });
    }
  };
}

export function createMockSdk(initial: MockHostScenario = {}): BjtuPluginSdk {
  const internalFactory = createBjtuPluginSdk as unknown as (
    transport: MockPluginTransport
  ) => BjtuPluginSdk;
  return internalFactory(createMockTransport(initial));
}

export const mockCapabilityDefaults: Record<CapabilityId, boolean> = Object.fromEntries(
  CAPABILITY_IDS.map((id) => [id, true])
) as Record<CapabilityId, boolean>;

function defaultResponse(request: PluginRequestV2, scenario: MockHostScenario): unknown {
  const route = `${request.capability}#${request.method}`;
  if (route === 'runtime.lifecycle@1#handshake') {
    const availableCapabilities = CAPABILITY_REGISTRY.capabilities
      .filter((capability) => scenario.capabilities?.[capability.id] !== false)
      .map((capability) => capability.id);
    const binaryTransports = [...(scenario.binaryTransports ?? [])];
    const preferredBinaryTransport = scenario.preferredBinaryTransport !== undefined &&
        binaryTransports.includes(scenario.preferredBinaryTransport)
      ? scenario.preferredBinaryTransport
      : binaryTransports[0];
    return {
      protocolVersion: PROTOCOL_VERSION,
      contractProfile: CAPABILITY_REGISTRY.contractProfile,
      runtimeFloor: CAPABILITY_REGISTRY.runtimeFloor,
      availableCapabilities,
      binaryTransports,
      ...(preferredBinaryTransport === undefined ? {} : { preferredBinaryTransport })
    };
  }
  if (route === 'runtime.lifecycle@1#ready') return { ready: true };
  if (route === 'runtime.lifecycle@1#close') return { closed: true };
  if (route === 'configuration.read@1#get') return { value: null };
  if (route === 'android.accessibility.events@1#getStatus') {
    const connected = scenario.accessibilityService !== 'disconnected';
    return { enabled: connected, connected, subscriptionCount: 0 };
  }
  if (route === 'android.device.info@1#getInfo') {
    return {
      platform: 'android',
      sdkInt: 35,
      manufacturer: 'Mock',
      model: 'Mock device',
      locale: 'zh-CN',
      timezone: 'Asia/Shanghai',
      appVersion: '0.0.0-mock'
    };
  }
  if (route === 'android.network.status@1#getStatus') {
    return { online: scenario.network === 'online', validated: scenario.network === 'online', metered: false, transport: scenario.network === 'online' ? 'wifi' : 'none' };
  }
  if (route === 'android.battery.status@1#getStatus') {
    return { level: 80, charging: false, status: 'discharging' };
  }
  if (route === 'navigation.external@1#open') return { opened: true };
  if (
    route.endsWith('#getInfo') ||
    route.endsWith('#put') ||
    route.endsWith('#export') ||
    route.endsWith('#promote')
  ) {
    return {
      handle: 'mock-resource',
      size: 0,
      contentType: 'application/octet-stream',
      url: '/__bjtu/resources/mock-resource'
    };
  }
  return cloneMockResponse(
    (CAPABILITY_MOCK_RESPONSES as Record<string, unknown>)[route] ?? {}
  );
}

function cloneMockResponse(value: unknown): unknown {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
}

function failure(
  requestId: string,
  code: PluginErrorCode,
  message: string,
  retryable = false
): PluginResponseV2 {
  return {
    protocolVersion: PROTOCOL_VERSION,
    requestId,
    ok: false,
    error: {
      code,
      message,
      retryable
    }
  };
}
