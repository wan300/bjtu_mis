import {
  CAPABILITY_IDS,
  CAPABILITY_REGISTRY,
  CONTRACT_PROFILE,
  PROTOCOL_VERSION,
  RUNTIME_FLOOR,
  type CapabilityId,
  type CapabilityEventData,
  type CapabilityEventRoute,
  type CapabilityMethodMap,
  type CapabilityRequest,
  type CapabilityResponse,
  type CapabilityRoute,
  type PluginErrorCode
} from './generated/contracts.js';

export * from './generated/contracts.js';

export const SDK_VERSION = '0.1.0';
const BINARY_CHUNK_BYTES = 256 * 1024;
const PRIVATE_BRIDGE_KEY = '__BJTU_PLUGIN_BRIDGE_V2__';
const PRIVATE_MIGRATION_BRIDGE_KEY = '__BJTU_PLUGIN_MIGRATION_V2__';

export interface PluginRequestV2 {
  protocolVersion: typeof PROTOCOL_VERSION;
  requestId: string;
  capability: CapabilityId;
  method: string;
  params: unknown;
  binary?: {
    size: number;
    chunks: number;
  };
}

export interface PluginSuccessV2 {
  protocolVersion: typeof PROTOCOL_VERSION;
  requestId: string;
  ok: true;
  result: unknown;
}

export interface PluginFailureV2 {
  protocolVersion: typeof PROTOCOL_VERSION;
  requestId: string;
  ok: false;
  error: {
    code: PluginErrorCode;
    message: string;
    retryable?: boolean;
    details?: unknown;
  };
}

export type PluginResponseV2 = PluginSuccessV2 | PluginFailureV2;

export interface PluginEventV2 {
  protocolVersion: typeof PROTOCOL_VERSION;
  eventId: string;
  capability: CapabilityId;
  event: string;
  requestId?: string;
  data?: unknown;
  requiresAcknowledgement?: boolean;
}

export interface InvokeOptions {
  signal?: AbortSignal;
  onProgress?: (progress: PluginProgress) => void;
  timeoutMs?: number;
}

export interface PluginProgress {
  loaded: number;
  total?: number;
  phase?: string;
}

interface PluginTransport {
  readonly binarySupported: boolean;
  send(request: PluginRequestV2, binary?: ArrayBuffer): Promise<PluginResponseV2>;
  cancel(requestId: string): void;
  subscribe(listener: PluginEventListener): () => void;
}

type PluginEventListener = (
  event: PluginEventV2
) => boolean | void | Promise<boolean | void>;

interface PrivateBridge {
  binarySupported?: boolean;
  postMessage(message: unknown, transfer?: Transferable[]): void;
  addEventListener(listener: (message: unknown) => void): () => void;
}

interface PluginMigrationBridge {
  invoke(
    capability: 'storage.kv@2' | 'runtime.migration@1',
    method: string,
    params?: Record<string, unknown>
  ): Promise<PluginResponseV2>;
}

export class BjtuPluginError extends Error {
  readonly code: PluginErrorCode;
  readonly retryable: boolean;
  readonly details: unknown;

  constructor(
    code: PluginErrorCode,
    message: string,
    options: { retryable?: boolean; details?: unknown; cause?: unknown } = {}
  ) {
    super(message, { cause: options.cause });
    this.name = 'BjtuPluginError';
    this.code = code;
    this.retryable = options.retryable ?? false;
    this.details = options.details;
  }
}

class WebViewBridgeTransport implements PluginTransport {
  readonly binarySupported: boolean;
  private readonly bridge: PrivateBridge;
  private readonly pending = new Map<
    string,
    {
      resolve: (response: PluginResponseV2) => void;
      reject: (error: unknown) => void;
    }
  >();
  private readonly eventListeners = new Set<PluginEventListener>();
  private readonly removeBridgeListener: () => void;

  constructor(bridge: PrivateBridge = getPrivateBridge()) {
    this.bridge = bridge;
    this.binarySupported = bridge.binarySupported === true;
    this.removeBridgeListener = bridge.addEventListener((message) => this.onMessage(message));
  }

  send(request: PluginRequestV2, binary?: ArrayBuffer): Promise<PluginResponseV2> {
    if (binary && !this.binarySupported) {
      return Promise.reject(
        new BjtuPluginError(
          'capability_unavailable',
          'The host WebView does not support ArrayBuffer transport.'
        )
      );
    }
    return new Promise<PluginResponseV2>((resolve, reject) => {
      this.pending.set(request.requestId, { resolve, reject });
      try {
        if (!binary) {
          this.bridge.postMessage(request);
          return;
        }
        const chunks = Math.ceil(binary.byteLength / BINARY_CHUNK_BYTES);
        this.bridge.postMessage({
          ...request,
          binary: {
            size: binary.byteLength,
            chunks
          }
        });
        for (let index = 0; index < chunks; index += 1) {
          const start = index * BINARY_CHUNK_BYTES;
          const payload = binary.slice(start, Math.min(start + BINARY_CHUNK_BYTES, binary.byteLength));
          this.bridge.postMessage(
            {
              protocolVersion: PROTOCOL_VERSION,
              kind: 'binaryChunk',
              requestId: request.requestId,
              index,
              last: index === chunks - 1,
              payload
            },
            [payload]
          );
        }
      } catch (error) {
        this.pending.delete(request.requestId);
        reject(error);
      }
    });
  }

  cancel(requestId: string): void {
    const pending = this.pending.get(requestId);
    if (pending) {
      this.pending.delete(requestId);
      pending.reject(new BjtuPluginError('user_cancelled', 'The request was cancelled.'));
    }
    this.bridge.postMessage({
      protocolVersion: PROTOCOL_VERSION,
      kind: 'cancel',
      requestId
    });
  }

  subscribe(listener: PluginEventListener): () => void {
    this.eventListeners.add(listener);
    return () => this.eventListeners.delete(listener);
  }

  close(): void {
    this.removeBridgeListener();
    for (const { reject } of this.pending.values()) {
      reject(new BjtuPluginError('user_cancelled', 'Plugin transport was closed.'));
    }
    this.pending.clear();
    this.eventListeners.clear();
  }

  private onMessage(message: unknown): void {
    if (!isObject(message) || message.protocolVersion !== PROTOCOL_VERSION) return;
    if (typeof message.eventId === 'string' && typeof message.event === 'string') {
      const event = message as unknown as PluginEventV2;
      void this.dispatchEvent(event);
      return;
    }
    if (typeof message.requestId !== 'string' || typeof message.ok !== 'boolean') return;
    const pending = this.pending.get(message.requestId);
    if (!pending) return;
    this.pending.delete(message.requestId);
    pending.resolve(message as unknown as PluginResponseV2);
  }

  private async dispatchEvent(event: PluginEventV2): Promise<void> {
    const handled = (
      await Promise.all(
        [...this.eventListeners].map(async (listener) => {
          try {
            return (await listener(event)) === true;
          } catch {
            return false;
          }
        })
      )
    ).some(Boolean);
    if (event.requiresAcknowledgement === true && event.requestId) {
      this.bridge.postMessage({
        protocolVersion: PROTOCOL_VERSION,
        kind: 'eventAck',
        eventId: event.eventId,
        requestId: event.requestId,
        handled
      });
    }
  }
}

export interface CampusReadMeta {
  syncedAt: string;
  source: 'cache' | 'network' | 'mixed';
  coverage: 'complete' | 'partial' | 'unknown';
  fromCache: boolean;
}

export interface CampusReadResult<T = unknown> {
  data: T;
  meta: CampusReadMeta;
}

export interface NetworkRequest {
  url: string;
  method?: 'GET' | 'HEAD' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  headers?: Record<string, string>;
  body?: unknown;
  bodyType?: 'json' | 'text' | 'formData' | 'blob';
  timeoutMs?: number;
}

export type ResourceHandle = CapabilityResponse<'storage.blob@1#getInfo'>;
export type NetworkResponse = CapabilityResponse<'network.request@1#request'>;
export type KvGetResult = CapabilityResponse<'storage.kv@2#get'>;
export type KvSetResult = CapabilityResponse<'storage.kv@2#set'>;
export type KvRemoveResult = CapabilityResponse<'storage.kv@2#remove'>;
export type KvKeysResult = CapabilityResponse<'storage.kv@2#keys'>;
export type KvUsageResult = CapabilityResponse<'storage.kv@2#usage'>;
export type KvBatchResult = CapabilityResponse<'storage.kv@2#batch'>;
export type KvTransactionResult = CapabilityResponse<'storage.kv@2#transaction'>;
export type KvImportResult = CapabilityResponse<'storage.kv@2#import'>;
export type KvChangedEvent = CapabilityEventData<'storage.kv@2#changed'>;
export type StudentProfileReadResult = CapabilityResponse<'identity.profile@1#getProfile'>;
export type TimetableReadResult = CapabilityResponse<'academic.timetable@1#getTimetable'>;
export type ScoresReadResult = CapabilityResponse<'academic.scores@1#getScores'>;
export type ExamsReadResult = CapabilityResponse<'academic.exams@1#getExams'>;
export type CalendarReadResult = CapabilityResponse<'academic.calendar@1#getCalendar'>;
export type ProgressReadResult = CapabilityResponse<'academic.progress@1#getProgress'>;
export type HomeworkReadResult = CapabilityResponse<'academic.homework@1#getHomework'>;
export type CourseResourcesReadResult =
  CapabilityResponse<'academic.resources@1#getCourseResources'>;
export type CampusRequestResult = CapabilityResponse<'campus.request@1#request'>;
export type MailFoldersReadResult = CapabilityResponse<'mail.read@1#listFolders'>;
export type MailMessagesReadResult = CapabilityResponse<'mail.read@1#listMessages'>;
export type MailMessageReadResult = CapabilityResponse<'mail.read@1#getMessage'>;
export type CommandReceipt =
  | CapabilityResponse<'academic.userCourses.command@1#save'>
  | CapabilityResponse<'academic.homework.submit@1#submit'>
  | CapabilityResponse<'mail.send@1#send'>;

type RuntimeEventRoute = Extract<CapabilityEventRoute, `runtime.lifecycle@1#${string}`>;
export type RuntimeEventName =
  RuntimeEventRoute extends `runtime.lifecycle@1#${infer Name}` ? Name : never;
export type RuntimeEventData<Name extends RuntimeEventName> = CapabilityEventData<
  Extract<RuntimeEventRoute, `runtime.lifecycle@1#${Name}`>
>;
export type RuntimeEventListener<Name extends RuntimeEventName> = (
  data: RuntimeEventData<Name>,
  envelope: PluginEventV2
) => boolean | void | Promise<boolean | void>;

export interface BjtuPluginSdk {
  readonly runtime: {
    handshake(options?: InvokeOptions): Promise<
      CapabilityResponse<'runtime.lifecycle@1#handshake'>
    >;
    ready(options?: InvokeOptions): Promise<void>;
    close(options?: InvokeOptions): Promise<void>;
    on<Name extends RuntimeEventName>(
      event: Name,
      listener: RuntimeEventListener<Name>
    ): () => void;
  };
  readonly configuration: {
    get(key: string, options?: InvokeOptions): Promise<string | null>;
  };
  readonly network: {
    request(request: NetworkRequest, options?: InvokeOptions): Promise<NetworkResponse>;
  };
  readonly storage: {
    readonly kv: {
      get(key: string, options?: InvokeOptions): Promise<KvGetResult>;
      set(key: string, value: unknown, ifRevision?: number, options?: InvokeOptions): Promise<KvSetResult>;
      remove(key: string, ifRevision?: number, options?: InvokeOptions): Promise<KvRemoveResult>;
      keys(options?: InvokeOptions): Promise<KvKeysResult>;
      usage(options?: InvokeOptions): Promise<KvUsageResult>;
      batch(operations: Array<Record<string, unknown>>, options?: InvokeOptions): Promise<KvBatchResult>;
      transaction(
        ifRevision: number,
        operations: Array<Record<string, unknown>>,
        options?: InvokeOptions
      ): Promise<KvTransactionResult>;
      export(options?: InvokeOptions): Promise<ResourceHandle>;
      import(handle: string, ifRevision?: number, options?: InvokeOptions): Promise<KvImportResult>;
      watch(listener: (data: KvChangedEvent) => void): () => void;
    };
    readonly blob: {
      put(data: ArrayBuffer, contentType: string, options?: InvokeOptions): Promise<ResourceHandle>;
      getInfo(handle: string, options?: InvokeOptions): Promise<ResourceHandle>;
      delete(handle: string, options?: InvokeOptions): Promise<boolean>;
    };
  };
  readonly cache: {
    put(
      key: string,
      data: ArrayBuffer,
      contentType: string,
      options?: InvokeOptions & { pin?: boolean }
    ): Promise<ResourceHandle>;
    promote(
      handle: string,
      key: string,
      options?: InvokeOptions & { pinned?: boolean }
    ): Promise<ResourceHandle>;
    deleteHandle(handle: string, options?: InvokeOptions): Promise<boolean>;
    match(key: string, options?: InvokeOptions): Promise<ResourceHandle | null>;
    delete(
      key: string,
      options?: InvokeOptions
    ): Promise<CapabilityResponse<'cache.resource@1#delete'>>;
    pin(
      key: string,
      pinned: boolean,
      options?: InvokeOptions
    ): Promise<CapabilityResponse<'cache.resource@1#pin'>>;
    usage(options?: InvokeOptions): Promise<CapabilityResponse<'cache.resource@1#usage'>>;
  };
  readonly navigation: {
    open(url: string, options?: InvokeOptions): Promise<boolean>;
  };
  readonly campus: {
    getProfile(options?: InvokeOptions & { forceRefresh?: boolean }): Promise<StudentProfileReadResult>;
    getTimetable(options?: InvokeOptions & { forceRefresh?: boolean }): Promise<TimetableReadResult>;
    getScores(
      request?: { term?: string; courseType?: string; forceRefresh?: boolean },
      options?: InvokeOptions
    ): Promise<ScoresReadResult>;
    getHistoryScores(
      request?: { term?: string; forceRefresh?: boolean },
      options?: InvokeOptions
    ): Promise<ScoresReadResult>;
    getExams(
      request?: { term?: string; forceRefresh?: boolean },
      options?: InvokeOptions
    ): Promise<ExamsReadResult>;
    getCalendar(
      request?: { month?: string; forceRefresh?: boolean },
      options?: InvokeOptions
    ): Promise<CalendarReadResult>;
    getProgress(options?: InvokeOptions & { forceRefresh?: boolean }): Promise<ProgressReadResult>;
    getHomework(
      request?: { status?: string; forceRefresh?: boolean },
      options?: InvokeOptions
    ): Promise<HomeworkReadResult>;
    getCourseResources(
      request: CapabilityRequest<'academic.resources@1#getCourseResources'>,
      options?: InvokeOptions
    ): Promise<CourseResourcesReadResult>;
    request(
      request: CapabilityRequest<'campus.request@1#request'>,
      options?: InvokeOptions
    ): Promise<CampusRequestResult>;
    saveUserCourse(
      idempotencyKey: string,
      course: Record<string, unknown>,
      options?: InvokeOptions
    ): Promise<CapabilityResponse<'academic.userCourses.command@1#save'>>;
    deleteUserCourse(
      idempotencyKey: string,
      id: number,
      options?: InvokeOptions
    ): Promise<CapabilityResponse<'academic.userCourses.command@1#delete'>>;
    submitHomework(
      request: CapabilityRequest<'academic.homework.submit@1#submit'>,
      options?: InvokeOptions
    ): Promise<CapabilityResponse<'academic.homework.submit@1#submit'>>;
  };
  readonly mail: {
    listFolders(options?: InvokeOptions & { forceRefresh?: boolean }): Promise<MailFoldersReadResult>;
    listMessages(
      request?: CapabilityRequest<'mail.read@1#listMessages'>,
      options?: InvokeOptions
    ): Promise<MailMessagesReadResult>;
    getMessage(
      messageId: string,
      mailbox?: string,
      options?: InvokeOptions
    ): Promise<MailMessageReadResult>;
    send(
      request: CapabilityRequest<'mail.send@1#send'>,
      options?: InvokeOptions
    ): Promise<CapabilityResponse<'mail.send@1#send'>>;
  };
}

export interface BjtuPluginMigrationSdk {
  readonly storage: {
    get(key: string): Promise<unknown>;
    set(key: string, value: unknown): Promise<unknown>;
    remove(key: string): Promise<unknown>;
    keys(): Promise<unknown>;
    usage(): Promise<unknown>;
    clear(): Promise<unknown>;
  };
  commit(): Promise<void>;
}

/**
 * Creates the restricted client available only inside a declared migration
 * entrypoint. Normal runtime, network, and command capabilities stay unavailable.
 */
export function createBjtuPluginMigrationSdk(): BjtuPluginMigrationSdk;
export function createBjtuPluginMigrationSdk(
  bridge: PluginMigrationBridge = getMigrationBridge()
): BjtuPluginMigrationSdk {
  const invoke = async (
    capability: 'storage.kv@2' | 'runtime.migration@1',
    method: string,
    params: Record<string, unknown> = {}
  ): Promise<unknown> => {
    const response = await bridge.invoke(capability, method, params);
    if (!response.ok) {
      throw new BjtuPluginError(response.error.code, response.error.message, {
        retryable: response.error.retryable,
        details: response.error.details
      });
    }
    return response.result;
  };
  return {
    storage: {
      get: (key) => invoke('storage.kv@2', 'get', { key }),
      set: (key, value) => invoke('storage.kv@2', 'set', { key, value }),
      remove: (key) => invoke('storage.kv@2', 'remove', { key }),
      keys: () => invoke('storage.kv@2', 'keys'),
      usage: () => invoke('storage.kv@2', 'usage'),
      clear: () => invoke('storage.kv@2', 'clear')
    },
    commit: async () => {
      await invoke('runtime.migration@1', 'commit');
    }
  };
}

export function createBjtuPluginSdk(): BjtuPluginSdk;
export function createBjtuPluginSdk(
  transport: PluginTransport = new WebViewBridgeTransport()
): BjtuPluginSdk {
  const invoke = async <Route extends CapabilityRoute>(
    route: Route,
    params: CapabilityRequest<Route>,
    options: InvokeOptions = {},
    binary?: ArrayBuffer
  ): Promise<CapabilityResponse<Route>> => {
    const [capability, method] = route.split('#') as [CapabilityId, string];
    const requestId = createRequestId();
    if (options.signal?.aborted) {
      throw new BjtuPluginError('user_cancelled', 'The request was cancelled before dispatch.');
    }
    const removeEventListener = options.onProgress
      ? transport.subscribe((event) => {
          if (
            event.requestId === requestId &&
            event.event === 'progress' &&
            isProgress(event.data)
          ) {
            options.onProgress?.(event.data);
          }
        })
      : () => undefined;
    const onAbort = () => transport.cancel(requestId);
    options.signal?.addEventListener('abort', onAbort, { once: true });
    const timeoutMs = resolveTimeoutMs(capability, params, options.timeoutMs);
    let timeoutHandle: ReturnType<typeof setTimeout> | undefined;
    const timeout = new Promise<never>((_, reject) => {
      if (timeoutMs === undefined) return;
      timeoutHandle = setTimeout(() => {
        const error = new BjtuPluginError(
          'request_timeout',
          `The ${capability} request exceeded its ${timeoutMs} ms deadline.`,
          { retryable: true }
        );
        reject(error);
        transport.cancel(requestId);
      }, timeoutMs);
    });
    try {
      const response = await Promise.race([
        transport.send(
          {
            protocolVersion: PROTOCOL_VERSION,
            requestId,
            capability,
            method,
            params
          },
          binary
        ),
        timeout
      ]);
      if (options.signal?.aborted) {
        throw new BjtuPluginError('user_cancelled', 'The request was cancelled.');
      }
      if (!response.ok) {
        throw new BjtuPluginError(response.error.code, response.error.message, {
          retryable: response.error.retryable,
          details: response.error.details
        });
      }
      return response.result as CapabilityResponse<Route>;
    } catch (error) {
      if (options.signal?.aborted) {
        throw new BjtuPluginError('user_cancelled', 'The request was cancelled.', { cause: error });
      }
      if (error instanceof BjtuPluginError) throw error;
      throw new BjtuPluginError('capability_unavailable', 'Plugin transport failed.', {
        cause: error
      });
    } finally {
      if (timeoutHandle !== undefined) clearTimeout(timeoutHandle);
      removeEventListener();
      options.signal?.removeEventListener('abort', onAbort);
    }
  };

  const subscribe = (
    capability: CapabilityId,
    event: string,
    listener: (
      data: unknown,
      envelope: PluginEventV2
    ) => boolean | void | Promise<boolean | void>
  ) =>
    transport.subscribe((envelope) => {
      if (envelope.capability === capability && envelope.event === event) {
        return listener(envelope.data, envelope);
      }
      return false;
    });

  return {
    runtime: {
      handshake: (options) =>
        invoke('runtime.lifecycle@1#handshake', { sdkVersion: SDK_VERSION }, options),
      ready: async (options) => {
        await invoke('runtime.lifecycle@1#ready', {}, options);
      },
      close: async (options) => {
        await invoke('runtime.lifecycle@1#close', {}, options);
      },
      on: (event, listener) =>
        subscribe(
          'runtime.lifecycle@1',
          event,
          listener as (
            data: unknown,
            envelope: PluginEventV2
          ) => boolean | void | Promise<boolean | void>
        )
    },
    configuration: {
      get: async (key, options) =>
        (await invoke('configuration.read@1#get', { key }, options)).value
    },
    network: {
      request: (request, options) =>
        invoke('network.request@1#request', request, options)
    },
    storage: {
      kv: {
        get: (key, options) => invoke('storage.kv@2#get', { key }, options),
        set: (key, value, ifRevision, options) =>
          invoke(
            'storage.kv@2#set',
            {
              key,
              value,
              ...(ifRevision === undefined ? {} : { ifRevision })
            },
            options
          ),
        remove: (key, ifRevision, options) =>
          invoke(
            'storage.kv@2#remove',
            {
              key,
              ...(ifRevision === undefined ? {} : { ifRevision })
            },
            options
          ),
        keys: (options) => invoke('storage.kv@2#keys', {}, options),
        usage: (options) => invoke('storage.kv@2#usage', {}, options),
        batch: (operations, options) => invoke('storage.kv@2#batch', { operations }, options),
        transaction: (ifRevision, operations, options) =>
          invoke('storage.kv@2#transaction', { ifRevision, operations }, options),
        export: (options) => invoke('storage.kv@2#export', {}, options),
        import: (handle, ifRevision, options) =>
          invoke(
            'storage.kv@2#import',
            {
              handle,
              ...(ifRevision === undefined ? {} : { ifRevision })
            },
            options
          ),
        watch: (listener) =>
          subscribe('storage.kv@2', 'changed', (data) => listener(data as KvChangedEvent))
      },
      blob: {
        put: (data, contentType, options) =>
          invoke('storage.blob@1#put', { contentType, size: data.byteLength }, options, data),
        getInfo: (handle, options) =>
          invoke('storage.blob@1#getInfo', { handle }, options),
        delete: async (handle, options) =>
          (await invoke('storage.blob@1#delete', { handle }, options)).deleted
      }
    },
    cache: {
      put: (key, data, contentType, options) =>
        invoke(
          'cache.resource@1#put',
          {
            key,
            contentType,
            size: data.byteLength,
            ...(options?.pin === undefined ? {} : { pin: options.pin })
          },
          options,
          data
        ),
      match: (key, options) =>
        invoke('cache.resource@1#match', { key }, options) as Promise<ResourceHandle | null>,
      promote: (handle, key, options) =>
        invoke(
          'cache.resource@1#promote',
          {
            handle,
            key,
            ...(options?.pinned === undefined ? {} : { pinned: options.pinned })
          },
          options
        ),
      deleteHandle: async (handle, options) =>
        (await invoke('cache.resource@1#deleteHandle', { handle }, options)).deleted,
      delete: (key, options) => invoke('cache.resource@1#delete', { key }, options),
      pin: (key, pinned, options) =>
        invoke('cache.resource@1#pin', { key, pinned }, options),
      usage: (options) => invoke('cache.resource@1#usage', {}, options)
    },
    navigation: {
      open: async (url, options) =>
        (await invoke('navigation.external@1#open', { url }, options)).opened
    },
    campus: {
      getProfile: (options = {}) =>
        invoke(
          'identity.profile@1#getProfile',
          { forceRefresh: options.forceRefresh },
          options
        ),
      getTimetable: (options = {}) =>
        invoke(
          'academic.timetable@1#getTimetable',
          { forceRefresh: options.forceRefresh },
          options
        ),
      getScores: (request = {}, options) =>
        invoke('academic.scores@1#getScores', request, options),
      getHistoryScores: (request = {}, options) =>
        invoke('academic.scores@1#getHistoryScores', request, options),
      getExams: (request = {}, options) =>
        invoke('academic.exams@1#getExams', request, options),
      getCalendar: (request = {}, options) =>
        invoke('academic.calendar@1#getCalendar', request, options),
      getProgress: (options = {}) =>
        invoke(
          'academic.progress@1#getProgress',
          { forceRefresh: options.forceRefresh },
          options
        ),
      getHomework: (request = {}, options) =>
        invoke('academic.homework@1#getHomework', request, options),
      getCourseResources: (request, options) =>
        invoke(
          'academic.resources@1#getCourseResources',
          request,
          options
        ),
      request: (request, options) =>
        invoke(
          'campus.request@1#request',
          request,
          options
        ),
      saveUserCourse: (idempotencyKey, course, options) =>
        invoke('academic.userCourses.command@1#save', { idempotencyKey, course }, options),
      deleteUserCourse: (idempotencyKey, id, options) =>
        invoke('academic.userCourses.command@1#delete', { idempotencyKey, id }, options),
      submitHomework: (request, options) =>
        invoke('academic.homework.submit@1#submit', request, options)
    },
    mail: {
      listFolders: (options = {}) =>
        invoke('mail.read@1#listFolders', { forceRefresh: options.forceRefresh }, options),
      listMessages: (request = {}, options) =>
        invoke('mail.read@1#listMessages', request, options),
      getMessage: (messageId, mailbox, options) =>
        invoke(
          'mail.read@1#getMessage',
          {
            messageId,
            ...(mailbox === undefined ? {} : { mailbox })
          },
          options
        ),
      send: (request, options) =>
        invoke('mail.send@1#send', request, options)
    }
  };
}

export function assertCapabilityId(value: string): asserts value is CapabilityId {
  if (!(CAPABILITY_IDS as readonly string[]).includes(value)) {
    throw new BjtuPluginError('invalid_request', `Unknown capability: ${value}`);
  }
}

function createRequestId(): string {
  return globalThis.crypto?.randomUUID?.() ??
    `bjtu-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

function resolveTimeoutMs(
  capability: CapabilityId,
  params: unknown,
  override: number | undefined
): number | undefined {
  const descriptor = CAPABILITY_REGISTRY.capabilities.find(
    (item) => item.id === capability
  ) as
    | {
        timeoutMs: number;
        maxTimeoutMs?: number;
      }
    | undefined;
  if (!descriptor) {
    throw new BjtuPluginError('capability_unavailable', `Unknown capability: ${capability}`);
  }
  const requestTimeout =
    capability === 'network.request@1' && isObject(params) && typeof params.timeoutMs === 'number'
      ? params.timeoutMs
      : undefined;
  const timeoutMs = override ?? requestTimeout ?? descriptor.timeoutMs;
  if (timeoutMs === 0 && override === undefined && requestTimeout === undefined) return undefined;
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) {
    throw new BjtuPluginError('invalid_request', 'timeoutMs must be a positive integer.');
  }
  const maximum = descriptor.maxTimeoutMs ?? descriptor.timeoutMs;
  if (timeoutMs > maximum) {
    throw new BjtuPluginError(
      'invalid_request',
      `timeoutMs exceeds the ${maximum} ms capability limit.`
    );
  }
  return timeoutMs;
}

function getPrivateBridge(): PrivateBridge {
  const bridge = globalThis[PRIVATE_BRIDGE_KEY as keyof typeof globalThis] as
    | PrivateBridge
    | undefined;
  if (!bridge || typeof bridge.postMessage !== 'function' || typeof bridge.addEventListener !== 'function') {
    throw new BjtuPluginError(
      'capability_unavailable',
      `BJTU plugin host transport is unavailable for ${CONTRACT_PROFILE} runtime ${RUNTIME_FLOOR}.`
    );
  }
  return bridge;
}

function getMigrationBridge(): PluginMigrationBridge {
  const bridge = globalThis[
    PRIVATE_MIGRATION_BRIDGE_KEY as keyof typeof globalThis
  ] as PluginMigrationBridge | undefined;
  if (!bridge || typeof bridge.invoke !== 'function') {
    throw new BjtuPluginError(
      'migration_failed',
      'BJTU plugin migration transport is unavailable.'
    );
  }
  return bridge;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}

function isProgress(value: unknown): value is PluginProgress {
  return (
    isObject(value) &&
    typeof value.loaded === 'number' &&
    (value.total === undefined || typeof value.total === 'number') &&
    (value.phase === undefined || typeof value.phase === 'string')
  );
}

export type { CapabilityMethodMap };
