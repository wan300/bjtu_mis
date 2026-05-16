import { getLocalSettings } from './db';
import {
	requestLocalAgentChatCompletion,
	shouldUseLocalAgentLoop,
	type LocalProviderRequest
} from './agent';
import { requestNativeHttp, supportsNativeHttp } from './native-http';
import { requestNativeSse, supportsNativeSse } from './native-stream';
import { buildLocalProviderBody, getLocalMessageText } from './params';
import { resolveDirectConnectionKey } from './secrets';

export const LOCAL_PROVIDER_PRESETS = [
	{ id: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1' },
	{ id: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com' },
	{ id: 'openrouter', label: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1' },
	{ id: 'groq', label: 'Groq', baseUrl: 'https://api.groq.com/openai/v1' },
	{ id: 'xai', label: 'xAI', baseUrl: 'https://api.x.ai/v1' },
	{ id: 'mistral', label: 'Mistral', baseUrl: 'https://api.mistral.ai/v1' },
	{
		id: 'gemini-openai',
		label: 'Gemini OpenAI-compatible',
		baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai'
	},
	{ id: 'custom', label: 'Custom OpenAI-compatible', baseUrl: '' }
];

const trimSlash = (value = '') => value.replace(/\/+$/, '');

const getConnectionForModel = async (modelId: string, modelItem?: Record<string, any>) => {
	const settings = await getLocalSettings();
	const directConnections = (settings?.directConnections ?? {}) as Record<string, any>;
	const urls = (directConnections.OPENAI_API_BASE_URLS ?? []) as string[];
	const configs = (directConnections.OPENAI_API_CONFIGS ?? {}) as Record<string, any>;

	let urlIdx = modelItem?.urlIdx ?? modelItem?.openai?.urlIdx ?? null;

	if (urlIdx === null || urlIdx === undefined) {
		for (const idx in urls) {
			const config = configs[String(idx)] ?? {};
			const prefix = config?.prefix_id ? `${config.prefix_id}.` : '';
			const manualIds = config?.model_ids ?? [];

			if (
				modelId.startsWith(prefix) ||
				manualIds.includes(modelId) ||
				manualIds.includes(modelId.replace(prefix, ''))
			) {
				urlIdx = idx;
				break;
			}
		}
	}

	if (urlIdx === null || urlIdx === undefined || !urls[urlIdx]) {
		throw new Error('Please add an OpenAI-compatible connection in Settings > Connections.');
	}

	const config = configs[String(urlIdx)] ?? {};
	let providerModelId = modelId;

	if (config?.prefix_id) {
		providerModelId = providerModelId.replace(`${config.prefix_id}.`, '');
	}

	return {
		url: trimSlash(urls[urlIdx]),
		key: await resolveDirectConnectionKey(directConnections, Number(urlIdx)),
		config,
		modelId: providerModelId
	};
};

const buildProviderHeaders = (key: string, config: Record<string, any> = {}) => {
	const authType = config?.auth_type ?? 'bearer';
	const headers: Record<string, string> = {
		'Content-Type': 'application/json',
		...(config?.headers ?? {})
	};

	if (authType === 'bearer' && key) {
		headers.Authorization = `Bearer ${key}`;
	}

	return headers;
};

const isAbortError = (error: unknown) => (error as { name?: string })?.name === 'AbortError';

const requestSingleProviderChatCompletion = async ({
	url,
	headers,
	providerBody
}: {
	url: string;
	headers: Record<string, string>;
	providerBody: Record<string, any>;
}): Promise<[Response | null, AbortController]> => {
	const requestBody = JSON.stringify(providerBody);

	if (providerBody.stream && supportsNativeSse()) {
		try {
			return await requestNativeSse({
				url: `${url}/chat/completions`,
				method: 'POST',
				headers,
				body: requestBody
			});
		} catch (error) {
			if (isAbortError(error)) {
				throw error;
			}
			if (supportsNativeHttp()) {
				throw error;
			}
			console.warn('Native SSE request failed, falling back to fetch:', error);
		}
	}

	const controller = new AbortController();

	if (supportsNativeHttp()) {
		return requestNativeHttp({
			url: `${url}/chat/completions`,
			method: 'POST',
			headers,
			body: requestBody
		});
	}

	const res = await fetch(`${url}/chat/completions`, {
		method: 'POST',
		signal: controller.signal,
		headers,
		body: requestBody
	}).catch((error) => {
		if (isAbortError(error)) {
			throw error;
		}
		console.error(error);
		return null;
	});

	return [res, controller];
};

export const requestLocalChatCompletion = async (
	body: Record<string, any>
): Promise<[Response | null, AbortController]> => {
	const modelId = body?.model ?? body?.model_item?.id;
	const connection = await getConnectionForModel(modelId, body?.model_item);
	const providerBody = buildLocalProviderBody(body, connection.modelId, connection.config);
	const headers = buildProviderHeaders(connection.key, connection.config);
	const requestProvider: LocalProviderRequest = (nextProviderBody) =>
		requestSingleProviderChatCompletion({
			url: connection.url,
			headers,
			providerBody: nextProviderBody
		});

	if (shouldUseLocalAgentLoop(body)) {
		return requestLocalAgentChatCompletion({
			body,
			providerBody,
			requestProvider
		});
	}

	return requestProvider(providerBody);
};

export const getLocalProviderModels = async (
	url: string,
	key: string,
	config: Record<string, any> = {}
) => {
	const headers = buildProviderHeaders(key, config);
	const res = supportsNativeHttp()
		? (
				await requestNativeHttp({
					url: `${trimSlash(url)}/models`,
					method: 'GET',
					headers
				})
			)[0]
		: await fetch(`${trimSlash(url)}/models`, {
				method: 'GET',
				headers
			});

	if (!res.ok) {
		throw await res.json().catch(() => ({ detail: 'Network Problem' }));
	}

	return res.json();
};

export const verifyLocalProviderConnection = async (connection: Record<string, any>) => {
	const { url, key, config } = connection;
	return getLocalProviderModels(url, key, config ?? {});
};

export { getLocalMessageText };
