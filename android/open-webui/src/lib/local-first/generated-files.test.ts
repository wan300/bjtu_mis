import { describe, expect, it } from 'vitest';

import {
	dedupeNativeAgentGeneratedFiles,
	findNativeAgentGeneratedFile,
	getGeneratedFileSaveErrorMessage,
	getNativeAgentGeneratedFilePreviewKind,
	isNativeAgentGeneratedFilePreviewable,
	normalizeNativeAgentWorkspacePath,
	shouldShowNativeAgentOutputEntry
} from './generated-files';

describe('native agent generated file helpers', () => {
	it('recognizes generated workspace paths', () => {
		expect(normalizeNativeAgentWorkspacePath('output/a.pdf')).toBe('output/a.pdf');
		expect(normalizeNativeAgentWorkspacePath('output\\a.docx')).toBe('output/a.docx');
		expect(normalizeNativeAgentWorkspacePath('output/Final Report.pdf')).toBe(
			'output/Final Report.pdf'
		);
		expect(normalizeNativeAgentWorkspacePath('output/作业4_分析报告.md')).toBe(
			'output/作业4_分析报告.md'
		);
		expect(normalizeNativeAgentWorkspacePath('results.zip')).toBe('results.zip');
		expect(normalizeNativeAgentWorkspacePath('work/tmp/final.md')).toBe('work/tmp/final.md');
	});

	it('rejects unsafe or unrelated paths', () => {
		expect(normalizeNativeAgentWorkspacePath('https://example.com/output/a.pdf')).toBeNull();
		expect(normalizeNativeAgentWorkspacePath('/output/a.pdf')).toBeNull();
		expect(normalizeNativeAgentWorkspacePath('C:/Users/a.pdf')).toBeNull();
		expect(normalizeNativeAgentWorkspacePath('output/../a.pdf')).toBeNull();
		expect(normalizeNativeAgentWorkspacePath('output/a\tb.pdf')).toBeNull();
		expect(normalizeNativeAgentWorkspacePath('make -C ./proj1')).toBeNull();
		expect(normalizeNativeAgentWorkspacePath('inbox/source.pdf')).toBeNull();
	});

	it('detects previewable file types', () => {
		expect(getNativeAgentGeneratedFilePreviewKind('output/a.pdf', 'application/pdf')).toBe('pdf');
		expect(
			getNativeAgentGeneratedFilePreviewKind(
				'output/a.docx',
				'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
			)
		).toBe('docx');
		expect(getNativeAgentGeneratedFilePreviewKind('output/a.md', 'text/markdown')).toBe(
			'markdown'
		);
		expect(getNativeAgentGeneratedFilePreviewKind('output/a.py', 'text/plain')).toBe('code');
		expect(getNativeAgentGeneratedFilePreviewKind('output/a.txt', 'text/plain')).toBe('text');
		expect(isNativeAgentGeneratedFilePreviewable('results.zip', 'application/zip')).toBe(false);
	});

	it('dedupes generated files by normalized path while preserving first-seen order', () => {
		expect(
			dedupeNativeAgentGeneratedFiles([
				{ displayName: 'a', relativePath: 'output/a.pdf' },
				{ displayName: 'duplicate', relativePath: 'output\\a.pdf' },
				{ displayName: 'b', relativePath: 'results.zip' },
				{ displayName: 'unsafe', relativePath: '../escape.txt' }
			])
		).toEqual([
			{ displayName: 'a', relativePath: 'output/a.pdf' },
			{ displayName: 'b', relativePath: 'results.zip' }
		]);
	});

	it('shows the output entry whenever a native workspace is available', () => {
		expect(shouldShowNativeAgentOutputEntry('workspace-1')).toBe(true);
		expect(shouldShowNativeAgentOutputEntry('  workspace-1  ')).toBe(true);
		expect(shouldShowNativeAgentOutputEntry('')).toBe(false);
		expect(shouldShowNativeAgentOutputEntry(null)).toBe(false);
	});

	it('finds generated files by normalized path before saving', () => {
		const files = [
			{ displayName: '报告.md', relativePath: 'output/作业4_分析报告.md' },
			{ displayName: 'bundle', relativePath: 'results.zip' }
		];

		expect(findNativeAgentGeneratedFile(files, 'output\\作业4_分析报告.md')?.displayName).toBe(
			'报告.md'
		);
		expect(findNativeAgentGeneratedFile(files, 'output/missing.md')).toBeNull();
	});

	it('maps native save failures to user-facing messages', () => {
		expect(getGeneratedFileSaveErrorMessage(new Error('File not found: output/missing.md'))).toEqual(
			{
				key: 'Generated output file was not found.'
			}
		);
		expect(getGeneratedFileSaveErrorMessage(new Error('Failed to create Downloads entry'))).toEqual(
			{
				key: 'Android could not save this file to Downloads.'
			}
		);
		expect(getGeneratedFileSaveErrorMessage(new Error('boom'))).toEqual({
			key: 'Failed to save generated file: {{message}}',
			params: { message: 'boom' }
		});
	});
});
