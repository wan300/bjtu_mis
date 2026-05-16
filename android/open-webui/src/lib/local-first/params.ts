export type LocalProviderId =
	| 'openai'
	| 'deepseek'
	| 'openrouter'
	| 'groq'
	| 'xai'
	| 'mistral'
	| 'gemini-openai'
	| 'custom';

type JsonRecord = Record<string, any>;

const OPEN_WEBUI_ONLY_PARAMS = new Set([
	'stream_response',
	'stream_delta_chunk_size',
	'function_calling',
	'reasoning_tags',
	'system'
]);

const CONSERVATIVE_OPENAI_COMPATIBLE_PARAMS = new Set([
	'temperature',
	'top_p',
	'max_tokens',
	'max_completion_tokens',
	'stop',
	'seed',
	'response_format'
]);

const COMMON_OPENAI_COMPATIBLE_PARAMS = new Set([
	...CONSERVATIVE_OPENAI_COMPATIBLE_PARAMS,
	'frequency_penalty',
	'presence_penalty'
]);

const EXTENDED_OPENAI_COMPATIBLE_PARAMS = new Set([
	...COMMON_OPENAI_COMPATIBLE_PARAMS,
	'reasoning_effort',
	'logit_bias'
]);

const FLOAT_PARAMS = new Set(['temperature', 'top_p', 'frequency_penalty', 'presence_penalty']);
const INTEGER_PARAMS = new Set(['max_tokens', 'max_completion_tokens']);

export const getLocalMessageText = (content: unknown) => {
	if (typeof content === 'string') return content;
	if (!Array.isArray(content)) return '';

	return content
		.map((part) => {
			if (typeof part === 'string') return part;
			if (part?.type === 'text') return part.text ?? '';
			return '';
		})
		.join('\n')
		.trim();
};

const appendTextToMessage = (message: JsonRecord, text: string) => {
	if (!text) {
		return message;
	}

	if (typeof message.content === 'string') {
		return { ...message, content: `${message.content}\n\n${text}` };
	}

	if (Array.isArray(message.content)) {
		return {
			...message,
			content: [...message.content, { type: 'text', text }]
		};
	}

	return { ...message, content: text };
};

const getFileContext = (files: any[] = []) =>
	files
		.filter((file) => file?.data?.content && !file?.type?.startsWith?.('image'))
		.map((file) => `[File: ${file.filename ?? file.name ?? 'attachment'}]\n${file.data.content}`)
		.join('\n\n');

export const normalizeLocalMessages = (messages: JsonRecord[] = [], files: any[] = []) => {
	if (!messages.length) {
		return messages;
	}

	const fileContext = getFileContext(files);
	if (!fileContext) {
		return messages;
	}

	let applied = false;
	return [...messages]
		.reverse()
		.map((message) => {
			if (!applied && message.role === 'user') {
				applied = true;
				return appendTextToMessage(message, fileContext);
			}
			return message;
		})
		.reverse();
};

const isPlainObject = (value: unknown): value is JsonRecord =>
	typeof value === 'object' && value !== null && !Array.isArray(value);

const isEmptyValue = (value: unknown) => value === undefined || value === null || value === '';

const deepMerge = (target: JsonRecord, source: JsonRecord): JsonRecord => {
	const output = { ...target };

	for (const [key, value] of Object.entries(source)) {
		if (isPlainObject(value) && isPlainObject(output[key])) {
			output[key] = deepMerge(output[key], value);
		} else {
			output[key] = value;
		}
	}

	return output;
};

const tryParseJson = (value: unknown) => {
	if (typeof value !== 'string') {
		return value;
	}

	try {
		return JSON.parse(value);
	} catch {
		return value;
	}
};

const decodeEscapedString = (value: string) => {
	try {
		const escaped = value
			.replace(/"/g, '\\"')
			.replace(/\n/g, '\\n')
			.replace(/\r/g, '\\r')
			.replace(/\t/g, '\\t');
		return JSON.parse(`"${escaped}"`);
	} catch {
		return value;
	}
};

const normalizeStop = (value: unknown) => {
	const tokens = Array.isArray(value) ? value : typeof value === 'string' ? value.split(',') : [];

	const normalized = tokens
		.map((token) => String(token).trim())
		.filter(Boolean)
		.map(decodeEscapedString);

	return normalized.length > 0 ? normalized : undefined;
};

const normalizeResponseFormat = (value: unknown) => {
	if (isPlainObject(value)) {
		return value;
	}

	const parsed = tryParseJson(value);
	if (isPlainObject(parsed)) {
		return parsed;
	}

	console.warn('Ignoring invalid response_format for local provider request.');
	return undefined;
};

const normalizeLogitBias = (value: unknown) => {
	if (isPlainObject(value)) {
		return value;
	}

	const parsed = tryParseJson(value);
	if (isPlainObject(parsed)) {
		return parsed;
	}

	if (typeof value !== 'string' || !value.trim()) {
		return undefined;
	}

	const bias: Record<string, number> = {};
	for (const pair of value.split(',')) {
		const [token, rawBias, ...rest] = pair.split(':');
		if (!token?.trim() || rawBias === undefined || rest.length > 0) {
			console.warn('Ignoring invalid logit_bias for local provider request.');
			return undefined;
		}

		const parsedBias = Number.parseInt(rawBias.trim(), 10);
		if (!Number.isFinite(parsedBias)) {
			console.warn('Ignoring invalid logit_bias for local provider request.');
			return undefined;
		}

		bias[token.trim()] = Math.max(-100, Math.min(100, parsedBias));
	}

	return Object.keys(bias).length > 0 ? bias : undefined;
};

const normalizeNumericParam = (key: string, value: unknown) => {
	const numeric = typeof value === 'number' ? value : Number(value);
	if (!Number.isFinite(numeric)) {
		console.warn(`Ignoring invalid ${key} for local provider request.`);
		return undefined;
	}

	return INTEGER_PARAMS.has(key) ? Math.trunc(numeric) : numeric;
};

const normalizeKnownParam = (key: string, value: unknown) => {
	if (isEmptyValue(value)) {
		return undefined;
	}

	if (FLOAT_PARAMS.has(key) || INTEGER_PARAMS.has(key)) {
		return normalizeNumericParam(key, value);
	}

	if (key === 'stop') {
		return normalizeStop(value);
	}

	if (key === 'logit_bias') {
		return normalizeLogitBias(value);
	}

	if (key === 'response_format') {
		return normalizeResponseFormat(value);
	}

	if (key === 'reasoning_effort') {
		return String(value);
	}

	return value;
};

const getAllowedParamsForProvider = (provider: LocalProviderId) => {
	if (provider === 'gemini-openai') {
		return CONSERVATIVE_OPENAI_COMPATIBLE_PARAMS;
	}

	if (provider === 'deepseek' || provider === 'groq' || provider === 'mistral') {
		return COMMON_OPENAI_COMPATIBLE_PARAMS;
	}

	return EXTENDED_OPENAI_COMPATIBLE_PARAMS;
};

const getProviderId = (config: JsonRecord = {}): LocalProviderId => {
	const provider = config?.provider;
	if (
		provider === 'openai' ||
		provider === 'deepseek' ||
		provider === 'openrouter' ||
		provider === 'groq' ||
		provider === 'xai' ||
		provider === 'mistral' ||
		provider === 'gemini-openai' ||
		provider === 'custom'
	) {
		return provider;
	}

	return 'custom';
};

const normalizeCustomParams = (customParams: unknown) => {
	if (!isPlainObject(customParams)) {
		return {};
	}

	const normalized: JsonRecord = {};
	for (const [key, rawValue] of Object.entries(customParams)) {
		if (OPEN_WEBUI_ONLY_PARAMS.has(key) || isEmptyValue(rawValue)) {
			continue;
		}

		const parsedValue = tryParseJson(rawValue);
		const knownValue = normalizeKnownParam(key, parsedValue);
		if (knownValue !== undefined) {
			normalized[key] = knownValue;
		}
	}

	return normalized;
};

export const buildLocalProviderBody = (
	body: JsonRecord,
	modelId: string,
	config: JsonRecord = {}
): JsonRecord => {
	const provider = getProviderId(config);
	const allowed = getAllowedParamsForProvider(provider);
	const params = isPlainObject(body?.params) ? body.params : {};

	let providerBody: JsonRecord = {
		model: modelId,
		messages: normalizeLocalMessages(body.messages ?? [], body.files ?? []),
		stream: body.stream ?? true
	};

	for (const key of allowed) {
		const value = body[key] ?? params[key];
		const normalized = normalizeKnownParam(key, value);
		if (normalized !== undefined) {
			providerBody[key] = normalized;
		}
	}

	if (allowed.has('max_tokens') && params?.num_predict && providerBody.max_tokens === undefined) {
		const normalized = normalizeKnownParam('max_tokens', params.num_predict);
		if (normalized !== undefined) {
			providerBody.max_tokens = normalized;
		}
	}

	const customParams = normalizeCustomParams(params.custom_params);
	if (Object.keys(customParams).length > 0) {
		providerBody = deepMerge(providerBody, customParams);
	}

	if (providerBody.stream) {
		providerBody.stream_options = {
			include_usage: true
		};
	}

	return providerBody;
};
