import { describe, expect, it } from 'vitest';

import {
	createNativeHomeworkDraftState,
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

	it('ignores handoff events without pending homework draft', () => {
		expect(createNativeHomeworkDraftState({ hasPending: false })).toBeNull();
		expect(createNativeHomeworkDraftState(null)).toBeNull();
	});

	it('creates a new homework draft state with workspace and attachments', () => {
		expect(
			createNativeHomeworkDraftState({
				hasPending: true,
				workspaceId: ' workspace-1 ',
				draft: 'homework draft',
				attachments: [
					{
						displayName: 'homework.pdf',
						relativePath: 'inbox/homework.pdf',
						sizeBytes: 1024
					}
				],
				failedAttachments: [{ filename: 'missing.zip', message: 'download failed' }]
			})
		).toEqual({
			prompt: 'homework draft',
			workspaceId: 'workspace-1',
			attachments: [
				{
					displayName: 'homework.pdf',
					relativePath: 'inbox/homework.pdf',
					sizeBytes: 1024
				}
			],
			failures: [{ filename: 'missing.zip', message: 'download failed' }],
			params: { agent_workspace_id: 'workspace-1' }
		});
	});
});
