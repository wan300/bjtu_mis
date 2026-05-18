import { describe, expect, it } from 'vitest';

import {
	dedupeNativeAgentGeneratedFiles,
	getNativeAgentGeneratedFilePreviewKind,
	isNativeAgentGeneratedFilePreviewable,
	normalizeNativeAgentWorkspacePath
} from './generated-files';

describe('native agent generated file helpers', () => {
	it('recognizes generated workspace paths', () => {
		expect(normalizeNativeAgentWorkspacePath('output/a.pdf')).toBe('output/a.pdf');
		expect(normalizeNativeAgentWorkspacePath('output\\a.docx')).toBe('output/a.docx');
		expect(normalizeNativeAgentWorkspacePath('output/Final Report.pdf')).toBe(
			'output/Final Report.pdf'
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
});
