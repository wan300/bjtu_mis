import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./db', () => ({
	getLocalSettings: vi.fn()
}));

vi.mock('./native-stream', () => ({
	supportsNativeSse: vi.fn(() => false),
	requestNativeSse: vi.fn()
}));

vi.mock('./secrets', () => ({
	resolveDirectConnectionKey: vi.fn(async (connections: any, idx: number) => connections.OPENAI_API_KEYS?.[idx] ?? '')
}));

vi.mock('./agent', () => ({
	shouldUseLocalAgentLoop: (body: Record<string, any>) => body?.params?.function_calling === 'native',
	requestLocalAgentChatCompletion: vi.fn(async () => [new Response('{}'), new AbortController()])
}));

import { requestLocalAgentChatCompletion } from './agent';
import { getLocalSettings } from './db';
import { requestLocalChatCompletion } from './providers';

describe('requestLocalChatCompletion', () => {
	let fetchMock: any;

	beforeEach(() => {
		vi.clearAllMocks();
		fetchMock = vi.fn(async () => new Response(JSON.stringify({ ok: true })));
		vi.stubGlobal('fetch', fetchMock);
		vi.mocked(getLocalSettings).mockResolvedValue({
			directConnections: {
				OPENAI_API_BASE_URLS: ['http://provider.test/v1'],
				OPENAI_API_KEYS: ['test-key'],
				OPENAI_API_CONFIGS: {
					'0': {
						provider: 'openai',
						model_ids: ['gpt-test']
					}
				}
			}
		} as any);
	});

	afterEach(() => {
		vi.unstubAllGlobals();
	});

	it('keeps default function calling on the single direct provider request path', async () => {
		await requestLocalChatCompletion({
			model: 'gpt-test',
			messages: [{ role: 'user', content: 'hello' }],
			stream: false,
			params: {
				function_calling: 'default',
				temperature: 0.2
			}
		});

		expect(requestLocalAgentChatCompletion).not.toHaveBeenCalled();
		expect(fetchMock).toHaveBeenCalledTimes(1);
		const requestInit = fetchMock.mock.calls[0][1] as RequestInit;
		const providerBody = JSON.parse(requestInit.body as string);

		expect(fetchMock.mock.calls[0][0]).toBe('http://provider.test/v1/chat/completions');
		expect(providerBody).toMatchObject({
			model: 'gpt-test',
			stream: false,
			temperature: 0.2
		});
		expect(providerBody).not.toHaveProperty('function_calling');
		expect(providerBody).not.toHaveProperty('tools');
		expect(providerBody).not.toHaveProperty('tool_choice');
	});

	it('delegates native function calling to the local-first agent loop', async () => {
		await requestLocalChatCompletion({
			model: 'gpt-test',
			messages: [{ role: 'user', content: 'hello' }],
			stream: false,
			params: {
				function_calling: 'native'
			}
		});

		expect(fetchMock).not.toHaveBeenCalled();
		expect(requestLocalAgentChatCompletion).toHaveBeenCalledTimes(1);
		const call = vi.mocked(requestLocalAgentChatCompletion).mock.calls[0][0];

		expect(call.providerBody).toMatchObject({
			model: 'gpt-test',
			stream: false,
			messages: [{ role: 'user', content: 'hello' }]
		});
		expect(call.providerBody).not.toHaveProperty('tools');
		expect(call.providerBody).not.toHaveProperty('tool_choice');
		expect(call.requestProvider).toEqual(expect.any(Function));
	});
});
