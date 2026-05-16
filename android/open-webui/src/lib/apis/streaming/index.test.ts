import { describe, expect, it } from 'vitest';

import { createOpenAITextStream } from './index';

const streamBody = (events: Record<string, any>[]) => {
	const encoder = new TextEncoder();

	return new ReadableStream<Uint8Array>({
		start(controller) {
			for (const event of events) {
				controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
			}
			controller.enqueue(encoder.encode('data: [DONE]\n\n'));
			controller.close();
		}
	});
};

describe('createOpenAITextStream', () => {
	it('emits top-level content replacement snapshots without chunking them', async () => {
		const updates: any[] = [];
		const stream = await createOpenAITextStream(
			streamBody([{ content: '<details type="tool_calls" done="false"></details>' }]),
			false
		);

		for await (const update of stream) {
			updates.push(update);
		}

		expect(updates[0]).toMatchObject({
			done: false,
			value: '',
			content: '<details type="tool_calls" done="false"></details>'
		});
		expect(updates.at(-1)).toMatchObject({ done: true, value: '' });
	});

	it('continues to emit normal OpenAI delta content', async () => {
		const updates: any[] = [];
		const stream = await createOpenAITextStream(
			streamBody([{ choices: [{ index: 0, delta: { content: 'hello' } }] }]),
			false
		);

		for await (const update of stream) {
			updates.push(update);
		}

		expect(updates[0]).toMatchObject({ done: false, value: 'hello' });
		expect(updates.at(-1)).toMatchObject({ done: true, value: '' });
	});
});
