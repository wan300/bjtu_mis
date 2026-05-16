import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';

type NativeSseRequest = {
	requestId: string;
	url: string;
	method: string;
	headers: Record<string, string>;
	body: string;
};

type NativeSseResponse = {
	requestId: string;
	status: number;
	headers?: Record<string, string>;
};

type NativeSseEvent = {
	requestId: string;
	data?: string;
	message?: string;
};

type NativeSsePlugin = {
	request(options: NativeSseRequest): Promise<NativeSseResponse>;
	abort(options: { requestId: string }): Promise<void>;
	addListener(
		eventName: 'nativeSseChunk' | 'nativeSseDone' | 'nativeSseError',
		listenerFunc: (event: NativeSseEvent) => void
	): Promise<PluginListenerHandle>;
};

const NativeSse = registerPlugin<NativeSsePlugin>('NativeSse');

const createRequestId = () =>
	globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;

export const supportsNativeSse = () =>
	Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';

export const requestNativeSse = async (
	options: Omit<NativeSseRequest, 'requestId'>
): Promise<[Response, AbortController]> => {
	const requestId = createRequestId();
	const abortController = new AbortController();
	const encoder = new TextEncoder();

	let closed = false;
	let streamController: ReadableStreamDefaultController<Uint8Array> | null = null;
	const handles: PluginListenerHandle[] = [];

	const cleanup = async () => {
		const pending = handles.splice(0, handles.length).map((handle) => handle.remove());
		await Promise.allSettled(pending);
	};

	const close = () => {
		if (closed) {
			return;
		}
		closed = true;
		streamController?.close();
		void cleanup();
	};

	const fail = (message: string) => {
		if (closed) {
			return;
		}
		closed = true;
		streamController?.error(
			message === 'AbortError' ? new DOMException('Aborted', 'AbortError') : new Error(message)
		);
		void cleanup();
	};

	const stream = new ReadableStream<Uint8Array>({
		start(controller) {
			streamController = controller;
		},
		cancel() {
			void NativeSse.abort({ requestId });
			void cleanup();
		}
	});

	handles.push(
		await NativeSse.addListener('nativeSseChunk', (event) => {
			if (event.requestId !== requestId || closed || !event.data) {
				return;
			}
			streamController?.enqueue(encoder.encode(event.data));
		})
	);
	handles.push(
		await NativeSse.addListener('nativeSseDone', (event) => {
			if (event.requestId === requestId) {
				close();
			}
		})
	);
	handles.push(
		await NativeSse.addListener('nativeSseError', (event) => {
			if (event.requestId === requestId) {
				fail(event.message ?? 'Native SSE request failed');
			}
		})
	);

	abortController.signal.addEventListener('abort', () => {
		void NativeSse.abort({ requestId });
		fail('AbortError');
	});

	try {
		const response = await NativeSse.request({ requestId, ...options });
		return [
			new Response(stream, {
				status: response.status || 200,
				headers: response.headers
			}),
			abortController
		];
	} catch (error) {
		await cleanup();
		if (abortController.signal.aborted) {
			throw new DOMException('Aborted', 'AbortError');
		}
		throw error;
	}
};
