import { afterEach, describe, expect, it, vi } from 'vitest';

import { buildLocalProviderBody } from './params';

afterEach(() => {
	vi.restoreAllMocks();
});

const baseBody = (params: Record<string, any>, extra: Record<string, any> = {}) => ({
	model: 'ignored',
	messages: [{ role: 'user', content: 'hello' }],
	stream: false,
	params,
	...extra
});

describe('buildLocalProviderBody', () => {
	it('removes OpenWebUI-only params from local provider requests', () => {
		const body = buildLocalProviderBody(
			baseBody({
				temperature: 0.2,
				stream_response: false,
				stream_delta_chunk_size: 6,
				function_calling: 'native',
				local_agent_review: true,
				reasoning_tags: ['think'],
				system: 'hidden'
			}),
			'gpt-test',
			{ provider: 'openai' }
		);

		expect(body).toMatchObject({
			model: 'gpt-test',
			temperature: 0.2
		});
		expect(body).not.toHaveProperty('stream_response');
		expect(body).not.toHaveProperty('stream_delta_chunk_size');
		expect(body).not.toHaveProperty('function_calling');
		expect(body).not.toHaveProperty('local_agent_review');
		expect(body).not.toHaveProperty('reasoning_tags');
		expect(body).not.toHaveProperty('system');
	});

	it('applies the extended OpenAI-compatible allowlist for supported providers', () => {
		const body = buildLocalProviderBody(
			baseBody({
				temperature: '0.3',
				top_p: '0.8',
				frequency_penalty: '0.1',
				presence_penalty: '0.2',
				max_tokens: '128',
				seed: 42,
				reasoning_effort: 'high',
				logit_bias: { '1': 5 },
				top_k: 40
			}),
			'gpt-test',
			{ provider: 'openrouter' }
		);

		expect(body).toMatchObject({
			temperature: 0.3,
			top_p: 0.8,
			frequency_penalty: 0.1,
			presence_penalty: 0.2,
			max_tokens: 128,
			seed: 42,
			reasoning_effort: 'high',
			logit_bias: { '1': 5 }
		});
		expect(body).not.toHaveProperty('top_k');
	});

	it('deep merges parsed custom params as an explicit provider escape hatch', () => {
		const body = buildLocalProviderBody(
			baseBody({
				response_format: { type: 'json_object' },
				custom_params: {
					response_format: '{"json_schema":{"name":"answer"}}',
					vendor_flag: 'true',
					nested: '{"enabled":true}',
					stream_response: false
				}
			}),
			'gpt-test',
			{ provider: 'openai' }
		);

		expect(body.response_format).toEqual({
			type: 'json_object',
			json_schema: { name: 'answer' }
		});
		expect(body.vendor_flag).toBe(true);
		expect(body.nested).toEqual({ enabled: true });
		expect(body).not.toHaveProperty('stream_response');
	});

	it('normalizes logit_bias objects, JSON strings, and token pairs', () => {
		expect(
			buildLocalProviderBody(baseBody({ logit_bias: { '10': -5 } }), 'gpt-test', {
				provider: 'openai'
			}).logit_bias
		).toEqual({ '10': -5 });

		expect(
			buildLocalProviderBody(baseBody({ logit_bias: '{"11": 7}' }), 'gpt-test', {
				provider: 'openai'
			}).logit_bias
		).toEqual({ '11': 7 });

		expect(
			buildLocalProviderBody(baseBody({ logit_bias: '12:200, 13:-150' }), 'gpt-test', {
				provider: 'openai'
			}).logit_bias
		).toEqual({ '12': 100, '13': -100 });
	});

	it('normalizes stop arrays and comma-separated strings', () => {
		expect(
			buildLocalProviderBody(baseBody({ stop: ['END', ' DONE '] }), 'gpt-test', {
				provider: 'openai'
			}).stop
		).toEqual(['END', 'DONE']);

		expect(
			buildLocalProviderBody(baseBody({ stop: 'END,\\n' }), 'gpt-test', {
				provider: 'openai'
			}).stop
		).toEqual(['END', '\n']);
	});

	it('keeps valid response_format values and drops invalid strings', () => {
		expect(
			buildLocalProviderBody(baseBody({ response_format: '{"type":"json_object"}' }), 'gpt-test', {
				provider: 'openai'
			}).response_format
		).toEqual({ type: 'json_object' });

		const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
		const invalid = buildLocalProviderBody(baseBody({ response_format: 'json' }), 'gpt-test', {
			provider: 'openai'
		});

		expect(invalid).not.toHaveProperty('response_format');
		expect(warn).toHaveBeenCalled();
	});

	it('uses conservative provider filtering while preserving custom params', () => {
		const body = buildLocalProviderBody(
			baseBody({
				temperature: 0.4,
				frequency_penalty: 0.5,
				reasoning_effort: 'high',
				logit_bias: '1:10',
				custom_params: {
					reasoning_effort: 'medium',
					logit_bias: '{"2": -10}',
					vendor_private: '{"enabled":true}'
				}
			}),
			'gemini-test',
			{ provider: 'gemini-openai' }
		);

		expect(body.temperature).toBe(0.4);
		expect(body).not.toHaveProperty('frequency_penalty');
		expect(body.reasoning_effort).toBe('medium');
		expect(body.logit_bias).toEqual({ '2': -10 });
		expect(body.vendor_private).toEqual({ enabled: true });
	});
});
