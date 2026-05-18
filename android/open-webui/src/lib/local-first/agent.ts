import {
	getLocalSettings,
	getLocalChat,
	getLocalFileRecord,
	listLocalChats,
	listLocalFiles,
	type LocalChatRecord,
	type LocalFileRecord
} from './db';
import {
	createWebSearchSources,
	fetchUrl,
	getWebSearchStatus,
	searchWeb,
	supportsNativeWebSearch,
	type LocalFetchedPage,
	type LocalWebSearchResult
} from './web-search';
import { normalizeLocalWebSearchSettings, type LocalWebSearchSettings } from './web-search-config';
import {
	executeRegisteredLocalTool,
	getAvailableLocalTools,
	LOCAL_CORE_TOOL_SPECS,
	LOCAL_WEB_SEARCH_TOOL_SPECS,
	type LocalToolDefinition
} from './tools/registry';

type JsonRecord = Record<string, any>;

type LocalToolCall = {
	id: string;
	type: 'function';
	function: {
		name: string;
		arguments: string;
	};
};

type ProviderTurn = {
	content: string;
	toolCalls: LocalToolCall[];
	reasoning?: string;
	reasoningDone?: boolean;
	reasoningDuration?: number;
	usage?: JsonRecord;
	raw?: JsonRecord;
	sources?: JsonRecord[];
	statuses?: JsonRecord[];
};

type ParseProviderTurnOptions = {
	onPartialTurn?: (turn: ProviderTurn) => void;
};

type LocalToolMessage = {
	role: 'tool';
	tool_call_id: string;
	name: string;
	content: string;
};

type LocalDisplayOutputItem =
	| {
			type: 'message';
			content: string;
	  }
	| {
			type: 'function_call';
			id: string;
			call_id: string;
			name: string;
			arguments: string;
			status: 'in_progress' | 'completed';
	  }
	| {
			type: 'function_call_output';
			id: string;
			call_id: string;
			output: { type: 'input_text'; text: string }[];
			status: 'completed';
	  };

export type LocalProviderRequest = (
	providerBody: JsonRecord
) => Promise<[Response | null, AbortController]>;

export const DEFAULT_LOCAL_AGENT_MAX_TOOL_CALL_RETRIES = 30;
export const DEFAULT_LOCAL_WEB_SEARCH_MAX_MODEL_ROUNDS = 4;
export const DEFAULT_LOCAL_WEB_SEARCH_MAX_TOOL_CALLS = 6;

export const getLocalAgentMaxToolCallRetries = (env: JsonRecord = import.meta.env) => {
	const parsed = Number(env?.VITE_LOCAL_AGENT_MAX_TOOL_CALL_RETRIES);
	if (!Number.isFinite(parsed)) {
		return DEFAULT_LOCAL_AGENT_MAX_TOOL_CALL_RETRIES;
	}

	return Math.max(0, Math.trunc(parsed));
};

export const shouldUseLocalAgentLoop = (body: JsonRecord) =>
	body?.params?.function_calling === 'native' ||
	body?.features?.android_device_tools === true ||
	shouldUseLocalCodeInterpreterAgentLoop(body) ||
	(typeof body?.params?.agent_workspace_id === 'string' && body.params.agent_workspace_id.trim() !== '') ||
	shouldUseLocalWebSearchAgentLoop(body);

export const shouldUseLocalWebSearchAgentLoop = (body: JsonRecord) =>
	body?.features?.web_search === true && supportsNativeWebSearch();

export const shouldUseLocalCodeInterpreterAgentLoop = (body: JsonRecord) =>
	body?.features?.code_interpreter === true;

export const LOCAL_AGENT_LIMITATIONS = [
	'No MCP tools.',
	'No terminal tools.',
	'No server-side Python tools.',
	'No server-side RAG.',
	'No backend permission checks.',
	'Provider and model must support OpenAI-compatible native tool calling.'
];

const LEGACY_LOCAL_AGENT_TOOLS = [
	{
		type: 'function',
		function: {
			name: 'get_current_timestamp',
			description: 'Get the current Unix timestamp in seconds and current UTC ISO time.',
			parameters: {
				type: 'object',
				properties: {},
				additionalProperties: false
			}
		}
	},
	{
		type: 'function',
		function: {
			name: 'calculate_timestamp',
			description:
				'Calculate a Unix timestamp in seconds relative to now. Use this for date filters such as last week or three days ago.',
			parameters: {
				type: 'object',
				properties: {
					days_ago: {
						type: 'integer',
						description: 'Number of days to subtract from the current time.'
					},
					weeks_ago: {
						type: 'integer',
						description: 'Number of weeks to subtract from the current time.'
					},
					months_ago: {
						type: 'integer',
						description: 'Number of months to subtract from the current time.'
					},
					years_ago: {
						type: 'integer',
						description: 'Number of years to subtract from the current time.'
					}
				},
				additionalProperties: false
			}
		}
	},
	{
		type: 'function',
		function: {
			name: 'calculate_expression',
			description:
				'Calculate a numeric arithmetic expression. Supports numbers, parentheses, +, -, *, /, and ^ only.',
			parameters: {
				type: 'object',
				properties: {
					expression: {
						type: 'string',
						description: 'Arithmetic expression to evaluate, for example "(12 + 8) / 5".'
					}
				},
				required: ['expression'],
				additionalProperties: false
			}
		}
	},
	{
		type: 'function',
		function: {
			name: 'list_local_files',
			description: 'List files stored in the local-first on-device file database.',
			parameters: {
				type: 'object',
				properties: {
					limit: {
						type: 'integer',
						description: 'Maximum number of files to return. Defaults to 20.'
					}
				},
				additionalProperties: false
			}
		}
	},
	{
		type: 'function',
		function: {
			name: 'search_local_files',
			description: 'Search files stored in the local-first on-device file database.',
			parameters: {
				type: 'object',
				properties: {
					query: {
						type: 'string',
						description:
							'Search text matched against file names, types, metadata, and text content.'
					},
					limit: {
						type: 'integer',
						description: 'Maximum number of matching files to return. Defaults to 10.'
					}
				},
				required: ['query'],
				additionalProperties: false
			}
		}
	},
	{
		type: 'function',
		function: {
			name: 'view_local_file',
			description:
				'View a local-first on-device file record and its extracted text content when available.',
			parameters: {
				type: 'object',
				properties: {
					id: {
						type: 'string',
						description: 'Local file id.'
					},
					max_chars: {
						type: 'integer',
						description: 'Maximum content characters to include. Defaults to 20000.'
					}
				},
				required: ['id'],
				additionalProperties: false
			}
		}
	},
	{
		type: 'function',
		function: {
			name: 'search_local_chats',
			description: 'Search local-first on-device chat history by title and message text.',
			parameters: {
				type: 'object',
				properties: {
					query: {
						type: 'string',
						description: 'Search text matched against chat titles and message text.'
					},
					limit: {
						type: 'integer',
						description: 'Maximum number of matching chats to return. Defaults to 10.'
					}
				},
				required: ['query'],
				additionalProperties: false
			}
		}
	},
	{
		type: 'function',
		function: {
			name: 'view_local_chat',
			description: 'View a local-first on-device chat record and its messages.',
			parameters: {
				type: 'object',
				properties: {
					id: {
						type: 'string',
						description: 'Local chat id.'
					},
					max_chars: {
						type: 'integer',
						description: 'Maximum message content characters to include. Defaults to 20000.'
					}
				},
				required: ['id'],
				additionalProperties: false
			}
		}
	}
] as const;

const LEGACY_LOCAL_WEB_SEARCH_TOOLS = [
	{
		type: 'function',
		function: {
			name: 'search_web',
			description:
				'Search the public web from this Android device. Use this for current events, recent facts, prices, schedules, or anything that needs live internet context.',
			parameters: {
				type: 'object',
				properties: {
					query: {
						type: 'string',
						description: 'The web search query.'
					},
					count: {
						type: 'integer',
						description: 'Maximum number of search results to return. Defaults to 5.'
					}
				},
				required: ['query'],
				additionalProperties: false
			}
		}
	},
	{
		type: 'function',
		function: {
			name: 'fetch_url',
			description:
				'Fetch readable text from a specific web page URL using the Android native web search bridge.',
			parameters: {
				type: 'object',
				properties: {
					url: {
						type: 'string',
						description: 'The http or https URL to fetch.'
					}
				},
				required: ['url'],
				additionalProperties: false
			}
		}
	}
] as const;

const toProviderTool = (tool: LocalToolDefinition) => ({
	type: tool.type,
	function: tool.function
});

export const LOCAL_AGENT_TOOLS = LOCAL_CORE_TOOL_SPECS.map(toProviderTool);
export const LOCAL_WEB_SEARCH_TOOLS = LOCAL_WEB_SEARCH_TOOL_SPECS.map(toProviderTool);
void LEGACY_LOCAL_AGENT_TOOLS;
void LEGACY_LOCAL_WEB_SEARCH_TOOLS;

const jsonString = (value: unknown) => JSON.stringify(value);

const getErrorMessage = (error: unknown) =>
	error instanceof Error ? error.message : typeof error === 'string' ? error : String(error);

const clampInteger = (value: unknown, defaultValue: number, min: number, max: number) => {
	const numeric = Number(value);
	if (!Number.isFinite(numeric)) {
		return defaultValue;
	}

	return Math.max(min, Math.min(max, Math.trunc(numeric)));
};

const requiredString = (args: JsonRecord, key: string) => {
	const value = args?.[key];
	if (typeof value !== 'string' || !value.trim()) {
		throw new Error(`Missing required string argument "${key}".`);
	}

	return value.trim();
};

const contentToText = (content: unknown): string => {
	if (typeof content === 'string') {
		return content;
	}

	if (!Array.isArray(content)) {
		return '';
	}

	return content
		.map((part) => {
			if (typeof part === 'string') return part;
			if (part?.type === 'text') return part.text ?? '';
			return '';
		})
		.join('\n')
		.trim();
};

const truncateText = (value: string, maxChars: number) => {
	if (value.length <= maxChars) {
		return { text: value, truncated: false };
	}

	return { text: value.slice(0, maxChars), truncated: true };
};

const getFileContent = (file: LocalFileRecord) => {
	const data = file?.data ?? {};
	if (typeof data.content === 'string') return data.content;
	if (typeof data.text === 'string') return data.text;
	if (typeof data.markdown === 'string') return data.markdown;
	return '';
};

const fileSummary = (file: LocalFileRecord, maxPreviewChars = 240) => {
	const preview = truncateText(getFileContent(file).replace(/\s+/g, ' ').trim(), maxPreviewChars);
	return {
		id: file.id,
		filename: file.filename ?? file.name,
		name: file.name ?? file.filename,
		type: file.type,
		content_type: file.content_type,
		size: file.size,
		status: file.status,
		created_at: file.created_at,
		updated_at: file.updated_at,
		preview: preview.text,
		preview_truncated: preview.truncated
	};
};

const getChatMessages = (chat: LocalChatRecord): JsonRecord[] =>
	(Object.values(chat?.chat?.history?.messages ?? {}) as JsonRecord[]).sort((a, b) => {
		const left = a?.timestamp ?? a?.created_at ?? a?.updated_at ?? 0;
		const right = b?.timestamp ?? b?.created_at ?? b?.updated_at ?? 0;
		return left - right;
	});

const chatMessageSummary = (message: any, maxChars = 120) => {
	const preview = truncateText(
		contentToText(message?.content).replace(/\s+/g, ' ').trim(),
		maxChars
	);
	return {
		id: message?.id,
		role: message?.role,
		timestamp: message?.timestamp,
		content: preview.text,
		truncated: preview.truncated
	};
};

const chatSummary = (chat: LocalChatRecord) => ({
	id: chat.id,
	title: chat.title,
	created_at: chat.created_at,
	updated_at: chat.updated_at,
	pinned: chat.pinned ?? false,
	archived: chat.archived ?? false,
	preview: getChatMessages(chat)
		.slice(0, 4)
		.map((message) => chatMessageSummary(message))
});

type LocalAgentRunContext = {
	webSearchEnabled: boolean;
	webSearchSettings: LocalWebSearchSettings;
	sources: JsonRecord[];
	statuses: JsonRecord[];
	displayOutput: LocalDisplayOutputItem[];
	emitContentSnapshot?: (content: string) => void;
	toolCallCount: number;
	forceRagFallback: boolean;
	webSearchRetryReported: boolean;
	webSearchUnavailable: boolean;
	agentWorkspaceId?: string | null;
};

const createStatus = (description: string, extra: JsonRecord = {}) => ({
	action: 'web_search',
	description,
	done: true,
	...extra
});

const escapeHtml = (value: unknown) =>
	String(value ?? '').replace(/[&<>"']/g, (char) => {
		if (char === '&') return '&amp;';
		if (char === '<') return '&lt;';
		if (char === '>') return '&gt;';
		if (char === '"') return '&quot;';
		return '&#x27;';
	});

const getReasoningContent = (value: JsonRecord | null | undefined) =>
	contentToText(value?.reasoning_content ?? value?.reasoning ?? value?.thinking);

const renderReasoningDetails = (reasoning: string, done = true, duration = 0) => {
	const quoted = reasoning
		.trim()
		.split(/\r?\n/)
		.map((line) => (line.startsWith('>') ? line : `> ${line}`))
		.join('\n');
	const safeDuration = Math.max(0, Math.trunc(duration));
	const durationAttribute = done ? ` duration="${safeDuration}"` : '';

	return `<details type="reasoning" done="${done ? 'true' : 'false'}"${durationAttribute}>\n<summary>${
		done ? `Thought for ${safeDuration} seconds` : 'Thinking...'
	}</summary>\n${escapeHtml(quoted)}\n</details>`;
};

const convertReasoningTagsToDetails = (content = '') => {
	let converted = content;

	for (const tag of ['think', 'thinking', 'reasoning']) {
		const tagRegex = new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/${tag}>`, 'gi');
		converted = converted.replace(tagRegex, (_match: string, reasoning: string) =>
			renderReasoningDetails(reasoning ?? '', true, 0)
		);
	}

	return converted;
};

const formatTurnContent = (turn: ProviderTurn, options: { final?: boolean } = {}) => {
	const parts: string[] = [];
	const reasoning = turn.reasoning?.trim();
	if (reasoning) {
		const hasAnswerOrToolDelta = Boolean((turn.content ?? '').trim() || turn.toolCalls.length);
		const reasoningDone =
			options.final === true ? true : (turn.reasoningDone ?? hasAnswerOrToolDelta);
		parts.push(
			renderReasoningDetails(
				reasoning,
				reasoningDone,
				reasoningDone ? (turn.reasoningDuration ?? 0) : 0
			)
		);
	}

	const content = convertReasoningTagsToDetails(turn.content ?? '').trim();
	if (content) {
		parts.push(content);
	}

	if (options.final === false) {
		for (const toolCall of turn.toolCalls) {
			parts.push(
				renderLocalToolCallDetails({
					callId: toolCall.id,
					name: toolCall.function.name,
					argumentsText: toolCall.function.arguments ?? '',
					done: false
				})
			);
		}
	}

	return parts.join('\n\n').trim();
};

const outputId = (() => {
	let index = 0;
	return (prefix: string) => `local-${prefix}-${++index}`;
})();

const renderLocalToolCallDetails = ({
	callId,
	name,
	argumentsText,
	done,
	resultText
}: {
	callId: string;
	name: string;
	argumentsText: string;
	done: boolean;
	resultText?: string;
}) => {
	const escapedCallId = escapeHtml(callId);
	const escapedName = escapeHtml(name);
	const escapedArguments = escapeHtml(JSON.stringify(argumentsText ?? ''));

	if (!done) {
		return `<details type="tool_calls" done="false" id="${escapedCallId}" name="${escapedName}" arguments="${escapedArguments}">\n<summary>Executing...</summary>\n</details>`;
	}

	return `<details type="tool_calls" done="true" id="${escapedCallId}" name="${escapedName}" arguments="${escapedArguments}">\n<summary>Tool Executed</summary>\n${escapeHtml(
		JSON.stringify(resultText ?? '')
	)}\n</details>`;
};

const serializeLocalDisplayOutput = (output: LocalDisplayOutputItem[], finalContent = '') => {
	const parts: string[] = [];
	const toolOutputs = new Map<
		string,
		Extract<LocalDisplayOutputItem, { type: 'function_call_output' }>
	>();

	for (const item of output) {
		if (item.type === 'function_call_output') {
			toolOutputs.set(item.call_id, item);
		}
	}

	for (const item of output) {
		if (item.type === 'message') {
			const text = item.content.trim();
			if (text) {
				parts.push(text);
			}
			continue;
		}

		if (item.type !== 'function_call') {
			continue;
		}

		const resultItem = toolOutputs.get(item.call_id);

		if (resultItem) {
			const resultText = resultItem.output
				.filter((part) => part.type === 'input_text')
				.map((part) => part.text)
				.join('');
			parts.push(
				renderLocalToolCallDetails({
					callId: item.call_id,
					name: item.name,
					argumentsText: item.arguments ?? '',
					done: true,
					resultText
				})
			);
		} else {
			parts.push(
				renderLocalToolCallDetails({
					callId: item.call_id,
					name: item.name,
					argumentsText: item.arguments ?? '',
					done: false
				})
			);
		}
	}

	const finalText = finalContent.trim();
	if (finalText) {
		parts.push(finalText);
	}

	return parts.join('\n').trim();
};

const emitLocalDisplaySnapshot = (context: LocalAgentRunContext, finalContent = '') => {
	if (!context.emitContentSnapshot) {
		return;
	}

	const content = serializeLocalDisplayOutput(context.displayOutput, finalContent);
	if (content) {
		context.emitContentSnapshot(content);
	}
};

const emitLocalPartialTurnSnapshot = (context: LocalAgentRunContext, turn: ProviderTurn) => {
	emitLocalDisplaySnapshot(context, formatTurnContent(turn, { final: false }));
};

const finalizeLocalAgentTurn = (
	turn: ProviderTurn,
	context: LocalAgentRunContext
): ProviderTurn => {
	const content = formatTurnContent(turn);

	if (context.displayOutput.length === 0) {
		return {
			...turn,
			content
		};
	}

	return {
		...turn,
		content: serializeLocalDisplayOutput(context.displayOutput, content)
	};
};

const uniqueSources = (sources: JsonRecord[]) => {
	const seen = new Set<string>();
	return sources.filter((source) => {
		const id = source?.source?.id ?? source?.metadata?.[0]?.source ?? JSON.stringify(source);
		if (seen.has(id)) {
			return false;
		}
		seen.add(id);
		return true;
	});
};

const createSearchContext = (results: LocalWebSearchResult[]) =>
	results
		.map((result, index) => {
			const parts = [
				`[${index + 1}] ${result.title || result.url}`,
				result.url,
				result.snippet ? `Snippet: ${result.snippet}` : '',
				result.content ? `Content excerpt: ${result.content}` : '',
				result.error ? `Fetch error: ${result.error}` : ''
			].filter(Boolean);

			return parts.join('\n');
		})
		.join('\n\n');

const getLastUserQuery = (messages: JsonRecord[] = []) => {
	const userMessage = [...messages].reverse().find((message) => message?.role === 'user');
	return contentToText(userMessage?.content).replace(/\s+/g, ' ').trim();
};

class ArithmeticParser {
	private index = 0;

	constructor(private readonly input: string) {}

	parse() {
		const value = this.parseExpression();
		this.skipWhitespace();
		if (this.index !== this.input.length) {
			throw new Error(`Unexpected token "${this.input[this.index]}".`);
		}
		if (!Number.isFinite(value)) {
			throw new Error('Expression result is not finite.');
		}
		return value;
	}

	private parseExpression(): number {
		let value = this.parseTerm();

		while (true) {
			this.skipWhitespace();
			if (this.match('+')) {
				value += this.parseTerm();
			} else if (this.match('-')) {
				value -= this.parseTerm();
			} else {
				return value;
			}
		}
	}

	private parseTerm(): number {
		let value = this.parsePower();

		while (true) {
			this.skipWhitespace();
			if (this.match('*')) {
				value *= this.parsePower();
			} else if (this.match('/')) {
				const divisor = this.parsePower();
				if (divisor === 0) {
					throw new Error('Division by zero.');
				}
				value /= divisor;
			} else {
				return value;
			}
		}
	}

	private parsePower(): number {
		let value = this.parseUnary();
		this.skipWhitespace();
		if (this.match('^')) {
			value = value ** this.parsePower();
		}
		return value;
	}

	private parseUnary(): number {
		this.skipWhitespace();
		if (this.match('+')) return this.parseUnary();
		if (this.match('-')) return -this.parseUnary();
		return this.parsePrimary();
	}

	private parsePrimary(): number {
		this.skipWhitespace();
		if (this.match('(')) {
			const value = this.parseExpression();
			this.skipWhitespace();
			if (!this.match(')')) {
				throw new Error('Missing closing parenthesis.');
			}
			return value;
		}

		return this.parseNumber();
	}

	private parseNumber(): number {
		this.skipWhitespace();
		const start = this.index;

		while (/[0-9.]/.test(this.input[this.index] ?? '')) {
			this.index += 1;
		}

		const raw = this.input.slice(start, this.index);
		if (!raw || raw === '.' || (raw.match(/\./g) ?? []).length > 1) {
			throw new Error('Expected a number.');
		}

		const value = Number(raw);
		if (!Number.isFinite(value)) {
			throw new Error(`Invalid number "${raw}".`);
		}

		return value;
	}

	private skipWhitespace() {
		while (/\s/.test(this.input[this.index] ?? '')) {
			this.index += 1;
		}
	}

	private match(token: string) {
		if (this.input[this.index] !== token) {
			return false;
		}
		this.index += 1;
		return true;
	}
}

const calculateExpression = (expression: string) => {
	if (!/^[0-9+\-*/^().\s]+$/.test(expression)) {
		throw new Error('Expression contains unsupported characters.');
	}

	return new ArithmeticParser(expression).parse();
};

const currentTimestamp = () => {
	const now = new Date();
	return {
		current_timestamp: Math.floor(now.getTime() / 1000),
		current_iso: now.toISOString()
	};
};

const calculateRelativeTimestamp = (args: JsonRecord) => {
	const now = new Date();
	const adjusted = new Date(now.getTime());
	const daysAgo = clampInteger(args.days_ago, 0, 0, 100000);
	const weeksAgo = clampInteger(args.weeks_ago, 0, 0, 100000);
	const monthsAgo = clampInteger(args.months_ago, 0, 0, 100000);
	const yearsAgo = clampInteger(args.years_ago, 0, 0, 100000);

	adjusted.setUTCDate(adjusted.getUTCDate() - daysAgo - weeksAgo * 7);
	if (monthsAgo) {
		adjusted.setUTCMonth(adjusted.getUTCMonth() - monthsAgo);
	}
	if (yearsAgo) {
		adjusted.setUTCFullYear(adjusted.getUTCFullYear() - yearsAgo);
	}

	return {
		current_timestamp: Math.floor(now.getTime() / 1000),
		current_iso: now.toISOString(),
		calculated_timestamp: Math.floor(adjusted.getTime() / 1000),
		calculated_iso: adjusted.toISOString()
	};
};

const legacyExecuteLocalTool = async (name: string, args: JsonRecord = {}) => {
	try {
		if (name === 'get_current_timestamp') {
			return jsonString(currentTimestamp());
		}

		if (name === 'calculate_timestamp') {
			return jsonString(calculateRelativeTimestamp(args));
		}

		if (name === 'calculate_expression') {
			const expression = requiredString(args, 'expression');
			return jsonString({ expression, result: calculateExpression(expression) });
		}

		if (name === 'list_local_files') {
			const limit = clampInteger(args.limit, 20, 1, 100);
			const files = await listLocalFiles();
			return jsonString({ files: files.slice(0, limit).map((file) => fileSummary(file)) });
		}

		if (name === 'search_local_files') {
			const query = requiredString(args, 'query').toLowerCase();
			const limit = clampInteger(args.limit, 10, 1, 100);
			const files = await listLocalFiles();
			const matches = files.filter((file) => {
				const haystack = [
					file.filename,
					file.name,
					file.type,
					file.content_type,
					JSON.stringify(file.metadata ?? {}),
					getFileContent(file)
				]
					.join('\n')
					.toLowerCase();
				return haystack.includes(query);
			});
			return jsonString({ query, files: matches.slice(0, limit).map((file) => fileSummary(file)) });
		}

		if (name === 'view_local_file') {
			const id = requiredString(args, 'id');
			const maxChars = clampInteger(args.max_chars, 20000, 1, 200000);
			const file = await getLocalFileRecord(id);
			if (!file) {
				throw new Error(`Local file "${id}" was not found.`);
			}

			const content = truncateText(getFileContent(file), maxChars);
			return jsonString({
				...fileSummary(file),
				url: file.url ?? null,
				metadata: file.metadata ?? null,
				content: content.text,
				content_truncated: content.truncated
			});
		}

		if (name === 'search_local_chats') {
			const query = requiredString(args, 'query');
			const limit = clampInteger(args.limit, 10, 1, 100);
			const chats = await listLocalChats({ page: null, archived: false, search: query });
			return jsonString({ query, chats: chats.slice(0, limit).map((chat) => chatSummary(chat)) });
		}

		if (name === 'view_local_chat') {
			const id = requiredString(args, 'id');
			const maxChars = clampInteger(args.max_chars, 20000, 1, 200000);
			const chat = await getLocalChat(id);
			if (!chat) {
				throw new Error(`Local chat "${id}" was not found.`);
			}

			let remainingChars = maxChars;
			const messages = getChatMessages(chat).map((message) => {
				const text = contentToText(message?.content);
				const truncated = truncateText(text, Math.max(0, remainingChars));
				remainingChars -= truncated.text.length;
				return {
					id: message?.id,
					role: message?.role,
					timestamp: message?.timestamp,
					content: truncated.text,
					truncated: truncated.truncated || remainingChars <= 0
				};
			});

			return jsonString({
				...chatSummary(chat),
				messages
			});
		}

		throw new Error(`Unknown local tool "${name}".`);
	} catch (error) {
		return jsonString({ error: getErrorMessage(error), tool: name });
	}
};

export const executeLocalTool = executeRegisteredLocalTool;
void legacyExecuteLocalTool;

const parseToolArguments = (toolCall: LocalToolCall) => {
	const raw = toolCall.function.arguments?.trim?.();
	if (!raw) {
		return {};
	}

	try {
		const parsed = JSON.parse(raw);
		if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
			throw new Error('Tool arguments must be a JSON object.');
		}
		return parsed as JsonRecord;
	} catch (error) {
		throw new Error(`Tool call arguments are invalid JSON: ${getErrorMessage(error)}`);
	}
};

const decodeXmlText = (value: string) =>
	value.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (match, entity: string) => {
		const normalized = entity.toLowerCase();
		if (normalized === 'amp') return '&';
		if (normalized === 'lt') return '<';
		if (normalized === 'gt') return '>';
		if (normalized === 'quot') return '"';
		if (normalized === 'apos') return "'";
		if (normalized.startsWith('#x')) {
			const codePoint = Number.parseInt(normalized.slice(2), 16);
			return Number.isInteger(codePoint) && codePoint >= 0 && codePoint <= 0x10ffff
				? String.fromCodePoint(codePoint)
				: match;
		}
		if (normalized.startsWith('#')) {
			const codePoint = Number.parseInt(normalized.slice(1), 10);
			return Number.isInteger(codePoint) && codePoint >= 0 && codePoint <= 0x10ffff
				? String.fromCodePoint(codePoint)
				: match;
		}
		return match;
	});

const normalizeInlineToolArguments = (toolName: string, args: JsonRecord) => {
	const normalized = { ...args };

	if (
		toolName.startsWith('agent_') &&
		typeof normalized.filename === 'string' &&
		typeof normalized.path !== 'string'
	) {
		normalized.path = normalized.filename;
		delete normalized.filename;
	}

	return normalized;
};

const parseInlineXmlToolArguments = (toolName: string, body: string): JsonRecord => {
	const trimmed = body.trim();
	if (!trimmed) {
		return {};
	}

	if (trimmed.startsWith('{')) {
		const parsed = JSON.parse(trimmed);
		if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
			throw new Error('Inline tool arguments must be a JSON object.');
		}
		return normalizeInlineToolArguments(toolName, parsed as JsonRecord);
	}

	const args: JsonRecord = {};
	const fieldRegex = /<([A-Za-z_][\w.-]*)\b[^>]*>([\s\S]*?)<\/\1>/g;
	let match: RegExpExecArray | null;

	while ((match = fieldRegex.exec(body)) !== null) {
		const key = match[1];
		const value = decodeXmlText((match[2] ?? '').trim());
		if (key) {
			args[key] = value;
		}
	}

	return normalizeInlineToolArguments(toolName, args);
};

const extractInlineXmlToolCalls = (
	content: string,
	availableToolNames: Set<string>
): { content: string; toolCalls: LocalToolCall[] } => {
	if (!content.includes('<agent_')) {
		return { content, toolCalls: [] };
	}

	const toolCalls: LocalToolCall[] = [];
	const toolRegex = /<(agent_[A-Za-z0-9_]+)\b[^>]*>([\s\S]*?)<\/\1>/g;
	let strippedContent = '';
	let lastIndex = 0;
	let match: RegExpExecArray | null;

	while ((match = toolRegex.exec(content)) !== null) {
		const name = match[1];
		if (!availableToolNames.has(name)) {
			continue;
		}

		const rawArguments = match[2] ?? '';
		let argumentsText: string;
		try {
			argumentsText = jsonString(parseInlineXmlToolArguments(name, rawArguments));
		} catch {
			argumentsText = rawArguments.trim();
		}
		toolCalls.push({
			id: outputId('xml-fc'),
			type: 'function',
			function: {
				name,
				arguments: argumentsText
			}
		});
		strippedContent += content.slice(lastIndex, match.index);
		lastIndex = match.index + match[0].length;
	}

	if (toolCalls.length === 0) {
		return { content, toolCalls: [] };
	}

	strippedContent += content.slice(lastIndex);

	return {
		content: strippedContent.replace(/\n{3,}/g, '\n\n').trim(),
		toolCalls
	};
};

const normalizeInlineXmlToolTurn = (
	turn: ProviderTurn,
	availableToolNames: Set<string>
): ProviderTurn => {
	if (turn.toolCalls.length > 0) {
		return turn;
	}

	const inline = extractInlineXmlToolCalls(turn.content ?? '', availableToolNames);
	if (inline.toolCalls.length === 0) {
		return turn;
	}

	return {
		...turn,
		content: inline.content,
		toolCalls: inline.toolCalls
	};
};

const buildAssistantToolCallMessage = (turn: ProviderTurn): JsonRecord => {
	const message: JsonRecord = {
		role: 'assistant',
		content: turn.content || null,
		tool_calls: turn.toolCalls
	};
	const reasoning = turn.reasoning ?? '';

	// DeepSeek thinking mode requires reasoning_content to be replayed with
	// assistant tool-call messages when the tool result is sent back.
	if (reasoning.trim()) {
		message.reasoning_content = reasoning;
	}

	return message;
};

const normalizeToolCalls = (toolCalls: any[] = []): LocalToolCall[] =>
	toolCalls
		.map((toolCall, index) => ({
			id: toolCall?.id ?? `local-tool-call-${index}`,
			type: 'function' as const,
			function: {
				name: toolCall?.function?.name ?? '',
				arguments: toolCall?.function?.arguments ?? ''
			}
		}))
		.filter((toolCall) => toolCall.function.name);

const mergeToolCallDelta = (toolCalls: Map<number, LocalToolCall>, deltaToolCall: any) => {
	const index = Number.isInteger(deltaToolCall?.index) ? deltaToolCall.index : toolCalls.size;
	const existing =
		toolCalls.get(index) ??
		({
			id: deltaToolCall?.id ?? `local-tool-call-${index}`,
			type: 'function',
			function: { name: '', arguments: '' }
		} as LocalToolCall);

	if (deltaToolCall?.id) {
		existing.id = deltaToolCall.id;
	}

	if (deltaToolCall?.type) {
		existing.type = 'function';
	}

	if (typeof deltaToolCall?.function?.name === 'string') {
		existing.function.name += deltaToolCall.function.name;
	}

	if (typeof deltaToolCall?.function?.arguments === 'string') {
		existing.function.arguments += deltaToolCall.function.arguments;
	}

	toolCalls.set(index, existing);
};

const parseSseEvent = (eventText: string) =>
	eventText
		.split(/\r?\n/)
		.filter((line) => line.startsWith('data:'))
		.map((line) => line.slice(5).trimStart())
		.join('\n')
		.trim();

const createPartialStreamTurn = (
	content: string,
	reasoning: string,
	streamedToolCalls: Map<number, LocalToolCall>,
	startedAt: number,
	usage?: JsonRecord
): ProviderTurn => {
	const toolCalls = normalizeToolCalls([...streamedToolCalls.values()]);
	const reasoningDone = Boolean((content ?? '').trim() || toolCalls.length > 0);

	return {
		content,
		toolCalls,
		reasoning,
		reasoningDone,
		reasoningDuration: reasoning
			? Math.max(0, Math.round((Date.now() - startedAt) / 1000))
			: undefined,
		usage
	};
};

const parseStreamTurn = async (
	res: Response,
	options: ParseProviderTurnOptions = {}
): Promise<ProviderTurn> => {
	if (!res.body) {
		return { content: '', toolCalls: [] };
	}

	const reader = res.body.getReader();
	const decoder = new TextDecoder();
	let buffer = '';
	let content = '';
	let reasoning = '';
	let usage: JsonRecord | undefined;
	const streamedToolCalls = new Map<number, LocalToolCall>();
	const startedAt = Date.now();

	while (true) {
		const { value, done } = await reader.read();
		if (done) {
			break;
		}

		buffer += decoder.decode(value, { stream: true });
		let separatorIndex = buffer.search(/\r?\n\r?\n/);

		while (separatorIndex !== -1) {
			const eventText = buffer.slice(0, separatorIndex);
			buffer = buffer.slice(separatorIndex + (buffer[separatorIndex] === '\r' ? 4 : 2));
			const dataText = parseSseEvent(eventText);

			if (dataText && dataText !== '[DONE]') {
				const chunk = JSON.parse(dataText);
				if (chunk?.usage) {
					usage = chunk.usage;
				}

				let hasDelta = false;
				for (const choice of chunk?.choices ?? []) {
					const delta = choice?.delta ?? choice?.message ?? {};
					const contentDelta = contentToText(delta?.content);
					const reasoningDelta = getReasoningContent(delta);
					const toolCallDeltas = delta?.tool_calls ?? [];

					if (contentDelta) {
						content += contentDelta;
						hasDelta = true;
					}
					if (reasoningDelta) {
						reasoning += reasoningDelta;
						hasDelta = true;
					}
					for (const toolCallDelta of toolCallDeltas) {
						mergeToolCallDelta(streamedToolCalls, toolCallDelta);
					}
					if (toolCallDeltas.length > 0) {
						hasDelta = true;
					}
				}

				if (hasDelta) {
					options.onPartialTurn?.(
						createPartialStreamTurn(content, reasoning, streamedToolCalls, startedAt, usage)
					);
				}
			}

			separatorIndex = buffer.search(/\r?\n\r?\n/);
		}
	}

	return {
		content,
		toolCalls: normalizeToolCalls([...streamedToolCalls.values()]),
		reasoning,
		reasoningDone: true,
		reasoningDuration: reasoning
			? Math.max(0, Math.round((Date.now() - startedAt) / 1000))
			: undefined,
		usage
	};
};

const parseJsonTurn = async (res: Response): Promise<ProviderTurn> => {
	const raw = (await res.json()) as JsonRecord;
	const message = raw?.choices?.[0]?.message ?? {};

	return {
		content: contentToText(message?.content),
		toolCalls: normalizeToolCalls(message?.tool_calls ?? []),
		reasoning: getReasoningContent(message),
		reasoningDone: true,
		reasoningDuration: getReasoningContent(message) ? 0 : undefined,
		usage: raw?.usage,
		raw
	};
};

const parseProviderTurn = async (
	res: Response,
	stream: boolean,
	options: ParseProviderTurnOptions = {}
): Promise<ProviderTurn> => {
	if (!res.ok) {
		const text = await res.text().catch(() => '');
		throw new Error(text || `Local provider request failed with status ${res.status}.`);
	}

	return stream ? parseStreamTurn(res, options) : parseJsonTurn(res);
};

const runWebSearchToolCall = async (
	toolCall: LocalToolCall,
	args: JsonRecord,
	context: LocalAgentRunContext
) => {
	if (toolCall.function.name === 'search_web') {
		const query = requiredString(args, 'query');
		const count = clampInteger(args.count, context.webSearchSettings.resultCount, 1, 10);

		context.statuses.push({
			action: 'web_search_queries_generated',
			queries: [query],
			done: true
		});

		const results = await searchWeb({
			...context.webSearchSettings,
			query,
			resultCount: count
		});
		context.sources = uniqueSources([...context.sources, ...createWebSearchSources(results)]);
		context.statuses.push(getWebSearchStatus(results));

		return jsonString({
			query,
			results: results.map((result, index) => ({
				index: index + 1,
				title: result.title,
				url: result.url,
				snippet: result.snippet,
				content: result.content,
				fetched: result.fetched,
				error: result.error
			}))
		});
	}

	if (toolCall.function.name === 'fetch_url') {
		const url = requiredString(args, 'url');
		const page = await fetchUrl({
			...context.webSearchSettings,
			url
		});
		context.sources = uniqueSources([...context.sources, ...createWebSearchSources([page])]);
		context.statuses.push(
			createStatus(page.fetched ? 'Fetched 1 web page' : 'Failed to fetch web page', {
				urls: [url],
				items: [{ title: page.title || url, url, snippet: page.error ?? '' }]
			})
		);

		return jsonString(page);
	}

	throw new Error(`Unknown local web search tool "${toolCall.function.name}".`);
};

const markWebSearchRetrying = (context: LocalAgentRunContext, error: unknown) => {
	if (!context.webSearchRetryReported) {
		context.statuses.push(
			createStatus(
				`Web search failed, retrying from DuckDuckGo before Bing fallback: ${getErrorMessage(error)}`,
				{
					urls: [],
					items: []
				}
			)
		);
	}

	context.webSearchRetryReported = true;
};

const markWebSearchUnavailable = (context: LocalAgentRunContext, error: unknown) => {
	if (!context.webSearchUnavailable) {
		context.statuses.push(
			createStatus(
				`Web search unavailable, continuing without web context: ${getErrorMessage(error)}`,
				{
					urls: [],
					items: []
				}
			)
		);
	}

	context.webSearchUnavailable = true;
};

const runToolCall = async (
	toolCall: LocalToolCall,
	context?: LocalAgentRunContext
): Promise<LocalToolMessage> => {
	let content: string;

	try {
		const args = parseToolArguments(toolCall);
		if (
			context?.webSearchEnabled &&
			(toolCall.function.name === 'search_web' || toolCall.function.name === 'fetch_url')
		) {
			content = await runWebSearchToolCall(toolCall, args, context);
		} else {
			content = await executeLocalTool(toolCall.function.name, args, {
				agentWorkspaceId: context?.agentWorkspaceId
			});
		}
	} catch (error) {
		if (
			context?.webSearchEnabled &&
			(toolCall.function.name === 'search_web' || toolCall.function.name === 'fetch_url')
		) {
			context.forceRagFallback = true;
			markWebSearchRetrying(context, error);
		}

		content = jsonString({
			error: getErrorMessage(error),
			tool: toolCall.function.name
		});
	}

	return {
		role: 'tool',
		tool_call_id: toolCall.id,
		name: toolCall.function.name,
		content
	};
};

const buildRetryLimitTurn = (maxRetries: number): ProviderTurn => ({
	content: `Error: Local agent reached the maximum tool call retry limit (${maxRetries}).`,
	toolCalls: []
});

const buildJsonResponse = (turn: ProviderTurn) => {
	const raw = turn.raw ? structuredClone(turn.raw) : {};
	if (!Array.isArray(raw.choices) || !raw.choices[0]?.message) {
		raw.choices = [
			{
				index: 0,
				message: { role: 'assistant', content: turn.content },
				finish_reason: 'stop'
			}
		];
	} else {
		raw.choices[0].message = {
			...raw.choices[0].message,
			role: raw.choices[0].message.role ?? 'assistant',
			content: turn.content
		};
		delete raw.choices[0].message.tool_calls;
		delete raw.choices[0].message.reasoning_content;
		delete raw.choices[0].message.reasoning;
		delete raw.choices[0].message.thinking;
	}

	if (turn.usage) {
		raw.usage = turn.usage;
	}
	if (turn.sources?.length) {
		raw.sources = turn.sources;
	}
	if (turn.statuses?.length) {
		raw.statuses = turn.statuses;
	}

	return new Response(JSON.stringify(raw), {
		headers: { 'Content-Type': 'application/json' }
	});
};

const buildStreamResponse = (
	run: (callbacks?: { emitContentSnapshot: (content: string) => void }) => Promise<ProviderTurn>,
	controller: AbortController
) => {
	const encoder = new TextEncoder();

	return new Response(
		new ReadableStream({
			async start(streamController) {
				let contentSnapshotEmitted = false;
				const enqueueEvent = (data: JsonRecord) => {
					if (!controller.signal.aborted) {
						streamController.enqueue(encoder.encode(`data: ${JSON.stringify(data)}\n\n`));
					}
				};

				try {
					const turn = await run({
						emitContentSnapshot: (content: string) => {
							contentSnapshotEmitted = true;
							enqueueEvent({ content });
						}
					});
					for (const status of turn.statuses ?? []) {
						enqueueEvent({ status });
					}

					if (!controller.signal.aborted && turn.sources?.length) {
						enqueueEvent({ sources: turn.sources });
					}

					if (!controller.signal.aborted && turn.content) {
						if (contentSnapshotEmitted || turn.content.includes('<details type="tool_calls"')) {
							enqueueEvent({ content: turn.content });
						} else {
							enqueueEvent({
								choices: [{ index: 0, delta: { content: turn.content }, finish_reason: null }]
							});
						}
					}

					if (!controller.signal.aborted && turn.usage) {
						enqueueEvent({ usage: turn.usage });
					}

					if (!controller.signal.aborted) {
						streamController.enqueue(encoder.encode('data: [DONE]\n\n'));
					}
					streamController.close();
				} catch (error) {
					streamController.error(error);
				}
			},
			cancel() {
				controller.abort();
			}
		}),
		{
			headers: {
				'Content-Type': 'text/event-stream',
				'Cache-Control': 'no-cache',
				Connection: 'keep-alive'
			}
		}
	);
};

const attachLocalAgentMetadata = (
	turn: ProviderTurn,
	context: LocalAgentRunContext
): ProviderTurn => ({
	...turn,
	sources: uniqueSources([...(turn.sources ?? []), ...context.sources]),
	statuses: [...(turn.statuses ?? []), ...context.statuses]
});

const runWebSearchRagFallback = async ({
	providerBody,
	requestProvider,
	context,
	activeController,
	onActiveController,
	onPartialTurn
}: {
	providerBody: JsonRecord;
	requestProvider: LocalProviderRequest;
	context: LocalAgentRunContext;
	activeController: AbortController | null;
	onActiveController: (controller: AbortController) => void;
	onPartialTurn?: (turn: ProviderTurn) => void;
}) => {
	const originalMessages = [...(providerBody.messages ?? [])];

	const requestWithoutWebContext = async () => {
		const [res, requestController] = await requestProvider(providerBody);
		onActiveController(requestController);
		if (!res) {
			throw new Error('Local provider request failed.');
		}
		return attachLocalAgentMetadata(
			await parseProviderTurn(res, Boolean(providerBody.stream), { onPartialTurn }),
			context
		);
	};

	const query = getLastUserQuery(originalMessages);

	if (!query) {
		context.statuses.push(createStatus('No search query generated'));
		return requestWithoutWebContext();
	}

	try {
		context.statuses.push({
			action: 'web_search_queries_generated',
			queries: [query],
			done: true
		});

		const results = await searchWeb({
			...context.webSearchSettings,
			engine: 'auto',
			query
		});
		context.sources = uniqueSources([...context.sources, ...createWebSearchSources(results)]);
		context.statuses.push(getWebSearchStatus(results));

		if (results.length === 0) {
			const [res, requestController] = await requestProvider(providerBody);
			onActiveController(requestController);
			if (!res) {
				throw new Error('Local provider request failed.');
			}
			return attachLocalAgentMetadata(
				await parseProviderTurn(res, Boolean(providerBody.stream), { onPartialTurn }),
				context
			);
		}

		const webContext = createSearchContext(results);
		const fallbackMessages = [
			{
				role: 'system',
				content:
					'Use the following web search sources when they are relevant. Cite URLs in the answer when using them.\n\n' +
					webContext
			},
			...originalMessages
		];

		const [res, requestController] = await requestProvider({
			...providerBody,
			messages: fallbackMessages
		});
		onActiveController(requestController);
		if (!res) {
			throw new Error('Local provider request failed.');
		}

		return attachLocalAgentMetadata(
			await parseProviderTurn(res, Boolean(providerBody.stream), { onPartialTurn }),
			context
		);
	} catch (error) {
		markWebSearchUnavailable(context, error);

		return requestWithoutWebContext();
	} finally {
		activeController?.signal.throwIfAborted?.();
	}
};

export const requestLocalAgentChatCompletion = async ({
	body,
	providerBody,
	requestProvider,
	maxRetries = getLocalAgentMaxToolCallRetries()
}: {
	body: JsonRecord;
	providerBody: JsonRecord;
	requestProvider: LocalProviderRequest;
	maxRetries?: number;
}): Promise<[Response | null, AbortController]> => {
	const controller = new AbortController();
	let activeController: AbortController | null = null;

	const messages = [...(providerBody.messages ?? [])];
	const webSearchEnabled = shouldUseLocalWebSearchAgentLoop(body);
	const codeInterpreterEnabled = shouldUseLocalCodeInterpreterAgentLoop(body);
	const androidDeviceToolsEnabled = body?.features?.android_device_tools === true;
	const nativeFunctionCallingEnabled = body?.params?.function_calling === 'native';
	const broadLocalToolsEnabled =
		nativeFunctionCallingEnabled ||
		androidDeviceToolsEnabled ||
		(!webSearchEnabled && !codeInterpreterEnabled);
	const localSettings = webSearchEnabled || androidDeviceToolsEnabled ? await getLocalSettings() : null;
	const agentWorkspaceId =
		typeof body?.params?.agent_workspace_id === 'string' && body.params.agent_workspace_id.trim()
			? body.params.agent_workspace_id.trim()
			: null;
	const context: LocalAgentRunContext = {
		webSearchEnabled,
		webSearchSettings: normalizeLocalWebSearchSettings(localSettings?.localWebSearch ?? {}),
		sources: [],
		statuses: webSearchEnabled
			? [createStatus('Searching the web', { done: false, urls: [], items: [] })]
			: [],
		displayOutput: [],
		toolCallCount: 0,
		forceRagFallback: false,
		webSearchRetryReported: false,
		webSearchUnavailable: false,
		agentWorkspaceId
	};
	const maxModelRounds = webSearchEnabled
		? DEFAULT_LOCAL_WEB_SEARCH_MAX_MODEL_ROUNDS
		: maxRetries + 1;
	const maxToolCalls = webSearchEnabled
		? DEFAULT_LOCAL_WEB_SEARCH_MAX_TOOL_CALLS
		: Number.POSITIVE_INFINITY;
	const tools = (await getAvailableLocalTools({
		includeWebSearch: webSearchEnabled,
		includeAndroidTools: androidDeviceToolsEnabled,
		includeLocationTool: localSettings?.userLocation === true,
		agentWorkspaceId
	}))
		.filter(
			(tool) =>
				broadLocalToolsEnabled ||
				(webSearchEnabled && (tool.category === 'web' || tool.category === 'math')) ||
				(codeInterpreterEnabled && (tool.category === 'code' || tool.category === 'math')) ||
				(tool.category === 'agent' && (agentWorkspaceId || tool.requiresWorkspace === false))
		)
		.map(toProviderTool);
	const availableToolNames = new Set(
		tools.map((tool) => tool.function.name).filter((name): name is string => typeof name === 'string')
	);
	const agentBody = {
		...providerBody,
		tools,
		tool_choice: 'auto'
	};

	controller.signal.addEventListener('abort', () => {
		activeController?.abort();
	});

	const run = async (callbacks?: { emitContentSnapshot: (content: string) => void }) => {
		context.emitContentSnapshot = callbacks?.emitContentSnapshot;
		const onPartialTurn = (turn: ProviderTurn) => emitLocalPartialTurnSnapshot(context, turn);

		try {
			for (let iteration = 0; iteration < maxModelRounds; iteration += 1) {
				if (controller.signal.aborted) {
					throw new DOMException('The local agent request was aborted.', 'AbortError');
				}

				let res: Response | null;
				try {
					const [providerResponse, requestController] = await requestProvider({
						...agentBody,
						messages
					});
					res = providerResponse;
					activeController = requestController;
				} catch (error) {
					if (webSearchEnabled) {
						return finalizeLocalAgentTurn(
							await runWebSearchRagFallback({
								providerBody,
								requestProvider,
								context,
								activeController,
								onActiveController: (nextController) => {
									activeController = nextController;
								},
								onPartialTurn
							}),
							context
						);
					}
					throw error;
				}

				if (!res) {
					throw new Error('Local provider request failed.');
				}

				let turn: ProviderTurn;
				try {
					turn = await parseProviderTurn(res, Boolean(providerBody.stream), { onPartialTurn });
				} catch (error) {
					if (webSearchEnabled) {
						return finalizeLocalAgentTurn(
							await runWebSearchRagFallback({
								providerBody,
								requestProvider,
								context,
								activeController,
								onActiveController: (nextController) => {
									activeController = nextController;
								},
								onPartialTurn
							}),
							context
						);
					}
					throw error;
				}
				turn = normalizeInlineXmlToolTurn(turn, availableToolNames);

				if (turn.toolCalls.length === 0) {
					if (webSearchEnabled && iteration === 0 && context.sources.length === 0) {
						return finalizeLocalAgentTurn(
							await runWebSearchRagFallback({
								providerBody,
								requestProvider,
								context,
								activeController,
								onActiveController: (nextController) => {
									activeController = nextController;
								},
								onPartialTurn
							}),
							context
						);
					}

					if (webSearchEnabled) {
						context.statuses = context.statuses.map((status) =>
							status.description === 'Searching the web' ? { ...status, done: true } : status
						);
					}
					return finalizeLocalAgentTurn(attachLocalAgentMetadata(turn, context), context);
				}

				context.toolCallCount += turn.toolCalls.length;
				if (context.toolCallCount > maxToolCalls || iteration >= maxModelRounds - 1) {
					if (webSearchEnabled) {
						context.forceRagFallback = true;
						return finalizeLocalAgentTurn(
							await runWebSearchRagFallback({
								providerBody,
								requestProvider,
								context,
								activeController,
								onActiveController: (nextController) => {
									activeController = nextController;
								},
								onPartialTurn
							}),
							context
						);
					}
					return finalizeLocalAgentTurn(buildRetryLimitTurn(maxRetries), context);
				}

				const displayContent = formatTurnContent(turn);
				if (displayContent) {
					context.displayOutput.push({ type: 'message', content: displayContent });
				}

				for (const toolCall of turn.toolCalls) {
					context.displayOutput.push({
						type: 'function_call',
						id: toolCall.id || outputId('fc'),
						call_id: toolCall.id,
						name: toolCall.function.name,
						arguments: toolCall.function.arguments ?? '{}',
						status: 'in_progress'
					});
				}
				emitLocalDisplaySnapshot(context);

				messages.push(buildAssistantToolCallMessage(turn));

				const toolMessages = await Promise.all(
					turn.toolCalls.map((toolCall) => runToolCall(toolCall, context))
				);

				for (const toolCall of turn.toolCalls) {
					const callItem = context.displayOutput.find(
						(item) => item.type === 'function_call' && item.call_id === toolCall.id
					);
					if (callItem?.type === 'function_call') {
						callItem.status = 'completed';
					}
				}

				for (const toolMessage of toolMessages) {
					context.displayOutput.push({
						type: 'function_call_output',
						id: outputId('fco'),
						call_id: toolMessage.tool_call_id,
						output: [{ type: 'input_text', text: toolMessage.content }],
						status: 'completed'
					});
				}
				emitLocalDisplaySnapshot(context);

				messages.push(...toolMessages);

				if (context.forceRagFallback) {
					return finalizeLocalAgentTurn(
						await runWebSearchRagFallback({
							providerBody,
							requestProvider,
							context,
							activeController,
							onActiveController: (nextController) => {
								activeController = nextController;
							},
							onPartialTurn
						}),
						context
					);
				}
			}

			if (webSearchEnabled) {
				return finalizeLocalAgentTurn(
					await runWebSearchRagFallback({
						providerBody,
						requestProvider,
						context,
						activeController,
						onActiveController: (nextController) => {
							activeController = nextController;
						},
						onPartialTurn
					}),
					context
				);
			}

			return finalizeLocalAgentTurn(buildRetryLimitTurn(maxRetries), context);
		} finally {
			context.emitContentSnapshot = undefined;
		}
	};

	if (providerBody.stream) {
		return [buildStreamResponse(run, controller), controller];
	}

	return [buildJsonResponse(await run()), controller];
};
