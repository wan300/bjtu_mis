import { PROTOCOL_VERSION, type PluginErrorCode } from '../generated/contracts.js';
import type {
  BinaryTransport,
  PluginEventV2,
  PluginRequestV2,
  PluginResponseV2
} from '../index.js';

const ARRAY_BUFFER_CHUNK_BYTES = 256 * 1024;
export const BASE64URL_CHUNK_BYTES = 48 * 1024;
const PRIVATE_BRIDGE_KEY = '__BJTU_PLUGIN_BRIDGE_V2__';

type PluginEventListener = (
  event: PluginEventV2
) => boolean | void | Promise<boolean | void>;

export interface PrivateBridge {
  postMessage(message: unknown, transfer?: Transferable[]): void;
  addEventListener(listener: (message: unknown) => void): () => void;
}

export class WebViewTransportError extends Error {
  readonly code: PluginErrorCode;

  constructor(code: PluginErrorCode, message: string) {
    super(message);
    this.name = 'WebViewTransportError';
    this.code = code;
  }
}

export class WebViewBridgeTransport {
  private readonly bridge: PrivateBridge;
  private readonly pending = new Map<
    string,
    {
      resolve: (response: PluginResponseV2) => void;
      reject: (error: unknown) => void;
    }
  >();
  private readonly chunkAcknowledgements = new Map<
    string,
    {
      index: number;
      resolve: () => void;
      reject: (error: unknown) => void;
    }
  >();
  private readonly binaryPreparations = new Set<string>();
  private readonly eventListeners = new Set<PluginEventListener>();
  private readonly removeBridgeListener: () => void;
  private binaryTransport: BinaryTransport | undefined;

  constructor(bridge: PrivateBridge = getPrivateBridge()) {
    this.bridge = bridge;
    this.removeBridgeListener = bridge.addEventListener((message) => this.onMessage(message));
  }

  configureBinaryTransport(transport: BinaryTransport | undefined): void {
    this.binaryTransport = transport;
  }

  async send(request: PluginRequestV2, binary?: ArrayBuffer): Promise<PluginResponseV2> {
    if (!binary) return this.sendRequest(request);
    const transport = this.binaryTransport;
    if (!transport) {
      throw new WebViewTransportError(
        'capability_unavailable',
        'Binary transport is not negotiated. Call runtime.handshake() before Blob/Cache writes.'
      );
    }
    const chunkBytes = transport === 'arraybuffer'
      ? ARRAY_BUFFER_CHUNK_BYTES
      : BASE64URL_CHUNK_BYTES;
    const chunks = Math.ceil(binary.byteLength / chunkBytes);
    if (this.pending.has(request.requestId) || this.binaryPreparations.has(request.requestId)) {
      throw new WebViewTransportError('invalid_request', 'Duplicate active request ID.');
    }
    this.binaryPreparations.add(request.requestId);
    let sha256: string;
    try {
      sha256 = await sha256Hex(binary);
    } catch (error) {
      this.binaryPreparations.delete(request.requestId);
      throw error;
    }
    if (!this.binaryPreparations.delete(request.requestId)) {
      throw new WebViewTransportError('user_cancelled', 'The request was cancelled.');
    }
    const response = this.createPendingResponse(request.requestId);
    // Cancellation can reject the final response while a compatibility chunk is
    // still waiting for its ACK. Attach a handler now so that both rejection
    // paths are observed even when the ACK path wins the race.
    void response.catch(() => undefined);
    try {
      this.bridge.postMessage({
        ...request,
        binary: {
          transport,
          size: binary.byteLength,
          chunks,
          sha256
        }
      });
      if (transport === 'arraybuffer') {
        for (let index = 0; index < chunks; index += 1) {
          const start = index * chunkBytes;
          const payload = binary.slice(start, Math.min(start + chunkBytes, binary.byteLength));
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
      } else {
        for (let index = 0; index < chunks; index += 1) {
          const start = index * chunkBytes;
          const bytes = new Uint8Array(
            binary,
            start,
            Math.min(chunkBytes, binary.byteLength - start)
          );
          await this.postBase64UrlChunk(request.requestId, index, chunks, bytes);
        }
      }
      return await response;
    } catch (error) {
      this.pending.delete(request.requestId);
      this.rejectChunkAcknowledgement(request.requestId, error);
      throw error;
    }
  }

  cancel(requestId: string): void {
    const error = new WebViewTransportError('user_cancelled', 'The request was cancelled.');
    this.binaryPreparations.delete(requestId);
    const pending = this.pending.get(requestId);
    if (pending) {
      this.pending.delete(requestId);
      pending.reject(error);
    }
    this.rejectChunkAcknowledgement(requestId, error);
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
    const error = new WebViewTransportError('user_cancelled', 'Plugin transport was closed.');
    this.binaryPreparations.clear();
    for (const { reject } of this.pending.values()) reject(error);
    for (const requestId of this.chunkAcknowledgements.keys()) {
      this.rejectChunkAcknowledgement(requestId, error);
    }
    this.pending.clear();
    this.eventListeners.clear();
  }

  private sendRequest(request: PluginRequestV2): Promise<PluginResponseV2> {
    const response = this.createPendingResponse(request.requestId);
    try {
      this.bridge.postMessage(request);
    } catch (error) {
      this.pending.delete(request.requestId);
      return Promise.reject(error);
    }
    return response;
  }

  private createPendingResponse(requestId: string): Promise<PluginResponseV2> {
    if (this.pending.has(requestId)) {
      throw new WebViewTransportError('invalid_request', 'Duplicate active request ID.');
    }
    return new Promise<PluginResponseV2>((resolve, reject) => {
      this.pending.set(requestId, { resolve, reject });
    });
  }

  private postBase64UrlChunk(
    requestId: string,
    index: number,
    chunks: number,
    bytes: Uint8Array
  ): Promise<void> {
    if (this.chunkAcknowledgements.has(requestId)) {
      return Promise.reject(
        new WebViewTransportError('invalid_request', 'A binary chunk is already in flight.')
      );
    }
    return new Promise<void>((resolve, reject) => {
      this.chunkAcknowledgements.set(requestId, { index, resolve, reject });
      try {
        this.bridge.postMessage({
          protocolVersion: PROTOCOL_VERSION,
          kind: 'binaryChunk',
          requestId,
          index,
          last: index === chunks - 1,
          payload: encodeBase64Url(bytes)
        });
      } catch (error) {
        this.chunkAcknowledgements.delete(requestId);
        reject(error);
      }
    });
  }

  private onMessage(message: unknown): void {
    if (!isObject(message) || message.protocolVersion !== PROTOCOL_VERSION) return;
    if (
      message.kind === 'binaryChunkAck' &&
      typeof message.requestId === 'string' &&
      typeof message.index === 'number'
    ) {
      const acknowledgement = this.chunkAcknowledgements.get(message.requestId);
      if (!acknowledgement || acknowledgement.index !== message.index) return;
      this.chunkAcknowledgements.delete(message.requestId);
      acknowledgement.resolve();
      return;
    }
    if (typeof message.eventId === 'string' && typeof message.event === 'string') {
      void this.dispatchEvent(message as unknown as PluginEventV2);
      return;
    }
    if (typeof message.requestId !== 'string' || typeof message.ok !== 'boolean') return;
    const pending = this.pending.get(message.requestId);
    if (!pending) return;
    this.pending.delete(message.requestId);
    const response = message as unknown as PluginResponseV2;
    if (!response.ok) {
      this.rejectChunkAcknowledgement(
        message.requestId,
        new WebViewTransportError(response.error.code, response.error.message)
      );
    }
    pending.resolve(response);
  }

  private rejectChunkAcknowledgement(requestId: string, error: unknown): void {
    const acknowledgement = this.chunkAcknowledgements.get(requestId);
    if (!acknowledgement) return;
    this.chunkAcknowledgements.delete(requestId);
    acknowledgement.reject(error);
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

export function encodeBase64Url(bytes: Uint8Array): string {
  let binary = '';
  const blockBytes = 0x4000;
  for (let offset = 0; offset < bytes.length; offset += blockBytes) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + blockBytes));
  }
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '');
}

async function sha256Hex(binary: ArrayBuffer): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new WebViewTransportError(
      'capability_unavailable',
      'Web Crypto SHA-256 is unavailable in this WebView.'
    );
  }
  const digest = new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', binary));
  return [...digest].map((value) => value.toString(16).padStart(2, '0')).join('');
}

function getPrivateBridge(): PrivateBridge {
  const bridge = globalThis[PRIVATE_BRIDGE_KEY as keyof typeof globalThis] as
    | PrivateBridge
    | undefined;
  if (!bridge || typeof bridge.postMessage !== 'function' || typeof bridge.addEventListener !== 'function') {
    throw new WebViewTransportError(
      'capability_unavailable',
      'BJTU plugin host transport is unavailable.'
    );
  }
  return bridge;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}
