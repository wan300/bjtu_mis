import { Capacitor, registerPlugin } from '@capacitor/core';

type NativeHttpRequest = {
	requestId: string;
	url: string;
	method: string;
	headers?: Record<string, string>;
	body?: string;
	timeoutMs?: number;
};

type NativeHttpResponse = {
	requestId: string;
	status: number;
	headers?: Record<string, string>;
	body?: string;
};

type NativeHttpPlugin = {
	request(options: NativeHttpRequest): Promise<NativeHttpResponse>;
	abort(options: { requestId: string }): Promise<void>;
};

const NativeHttp = registerPlugin<NativeHttpPlugin>('NativeHttp');

const createRequestId = () =>
	globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;

export const supportsNativeHttp = () =>
	Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';

export const requestNativeHttp = async (
	options: Omit<NativeHttpRequest, 'requestId'>
): Promise<[Response, AbortController]> => {
	const requestId = createRequestId();
	const abortController = new AbortController();

	abortController.signal.addEventListener('abort', () => {
		void NativeHttp.abort({ requestId });
	});

	try {
		const response = await NativeHttp.request({ requestId, ...options });
		if (abortController.signal.aborted) {
			throw new DOMException('Aborted', 'AbortError');
		}

		return [
			new Response(response.body ?? '', {
				status: response.status || 200,
				headers: response.headers
			}),
			abortController
		];
	} catch (error) {
		if (abortController.signal.aborted) {
			throw new DOMException('Aborted', 'AbortError');
		}
		throw error;
	}
};
