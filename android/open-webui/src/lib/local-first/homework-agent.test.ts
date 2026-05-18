import { describe, expect, it } from 'vitest';

import {
	getNativeAgentAttachmentExtractionStatus,
	shouldBlockHomeworkDraftSubmit
} from './homework-agent';

describe('homework agent helpers', () => {
	it('blocks submit while homework draft attachments are preparing', () => {
		expect(shouldBlockHomeworkDraftSubmit(true)).toBe(true);
		expect(shouldBlockHomeworkDraftSubmit(false)).toBe(false);
	});

	it('does not assume imported attachments have already been extracted', () => {
		expect(
			getNativeAgentAttachmentExtractionStatus({
				displayName: 'homework.zip',
				relativePath: 'inbox/homework.zip'
			})
		).toEqual({ kind: 'none' });
	});

	it('reports extracted archive file count before falling back to errors', () => {
		expect(
			getNativeAgentAttachmentExtractionStatus({
				displayName: 'homework.zip',
				relativePath: 'inbox/homework.zip',
				extractedFiles: ['work/attachments/homework/a.txt', 'work/attachments/homework/b.pdf'],
				extractionError: 'ignored'
			})
		).toEqual({ kind: 'extracted', count: 2 });
	});

	it('reports archive extraction failure messages', () => {
		expect(
			getNativeAgentAttachmentExtractionStatus({
				displayName: 'bad.zip',
				relativePath: 'inbox/bad.zip',
				extractionError: 'Archive entry contains path traversal'
			})
		).toEqual({ kind: 'failed', message: 'Archive entry contains path traversal' });
	});
});
