type GeneratedFileLike = {
	displayName?: string | null;
	relativePath?: string | null;
	mimeType?: string | null;
	sizeBytes?: number | null;
	role?: string | null;
};

export type GeneratedFileSaveErrorMessage = {
	key: string;
	params?: Record<string, string>;
};

const WORKSPACE_PATH_ROOTS = new Set(['output', 'work', 'logs']);
const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const TEXT_MIME_TYPES = new Set([
	'application/json',
	'application/xml',
	'application/yaml',
	'application/x-yaml',
	'application/javascript'
]);
const TEXT_EXTENSIONS = new Set([
	'txt',
	'log',
	'json',
	'jsonl',
	'jsonc',
	'csv',
	'tsv',
	'html',
	'htm',
	'xml',
	'yaml',
	'yml'
]);
const CODE_EXTENSIONS = new Set([
	'js',
	'mjs',
	'cjs',
	'ts',
	'tsx',
	'jsx',
	'py',
	'java',
	'kt',
	'kts',
	'c',
	'cpp',
	'h',
	'hpp',
	'cs',
	'go',
	'rs',
	'rb',
	'php',
	'sh',
	'bash',
	'sql',
	'css',
	'scss'
]);

export type NativeAgentGeneratedFilePreviewKind = 'pdf' | 'docx' | 'markdown' | 'code' | 'text';

export const normalizeNativeAgentWorkspacePath = (value: unknown): string | null => {
	if (typeof value !== 'string') return null;

	let path = value.trim();
	if (!path) return null;

	try {
		path = decodeURIComponent(path);
	} catch {
		// Keep the original value if it is not URI encoded.
	}

	path = path
		.replace(/\\/g, '/')
		.replace(/^\.\/+/, '')
		.replace(/^[`'"]+|[`'"]+$/g, '')
		.split(/[?#]/)[0]
		.trim();

	if (!path || path.includes('://') || path.startsWith('/') || /^[A-Za-z]:/.test(path)) {
		return null;
	}
	if (/[\r\n\t]/.test(path)) return null;

	const parts = path.split('/').filter(Boolean);
	if (parts.length === 0 || parts.some((part) => part === '.' || part === '..')) {
		return null;
	}

	if (path === 'results.zip') return path;
	if (!WORKSPACE_PATH_ROOTS.has(parts[0])) return null;
	if (parts.length < 2) return null;

	return parts.join('/');
};

export const getNativeAgentGeneratedFilePreviewKind = (
	path: string,
	mimeType: string | null | undefined = ''
): NativeAgentGeneratedFilePreviewKind | null => {
	const normalizedPath = normalizeNativeAgentWorkspacePath(path) ?? path;
	const normalizedMimeType = (mimeType ?? '').toLowerCase();
	const extension = normalizedPath.split('.').pop()?.toLowerCase() ?? '';

	if (normalizedMimeType === 'application/pdf' || extension === 'pdf') return 'pdf';
	if (normalizedMimeType === DOCX_MIME || extension === 'docx') return 'docx';
	if (normalizedMimeType === 'text/markdown' || extension === 'md' || extension === 'markdown') {
		return 'markdown';
	}
	if (CODE_EXTENSIONS.has(extension)) return 'code';
	if (
		normalizedMimeType.startsWith('text/') ||
		TEXT_MIME_TYPES.has(normalizedMimeType) ||
		TEXT_EXTENSIONS.has(extension)
	) {
		return 'text';
	}

	return null;
};

export const isNativeAgentGeneratedFilePreviewable = (
	path: string,
	mimeType: string | null | undefined = ''
) => getNativeAgentGeneratedFilePreviewKind(path, mimeType) !== null;

export const shouldShowNativeAgentOutputEntry = (workspaceId?: string | null) =>
	typeof workspaceId === 'string' && workspaceId.trim() !== '';

export const dedupeNativeAgentGeneratedFiles = <T extends GeneratedFileLike>(files: T[] = []): T[] => {
	const seen = new Set<string>();
	const result: T[] = [];

	for (const file of files) {
		const path = normalizeNativeAgentWorkspacePath(file?.relativePath ?? '');
		if (!path || seen.has(path)) continue;
		seen.add(path);
		result.push({ ...file, relativePath: path } as T);
	}

	return result;
};

export const findNativeAgentGeneratedFile = <T extends GeneratedFileLike>(
	files: T[] = [],
	value: unknown
): T | null => {
	const path = normalizeNativeAgentWorkspacePath(value);
	if (!path) return null;
	return dedupeNativeAgentGeneratedFiles(files).find((file) => file.relativePath === path) ?? null;
};

const getErrorMessageText = (error: unknown) => {
	if (error instanceof Error) return error.message;
	if (typeof error === 'string') return error;
	if (error && typeof error === 'object' && 'message' in error) {
		return String((error as { message?: unknown }).message ?? '');
	}
	return '';
};

export const getGeneratedFileSaveErrorMessage = (
	error: unknown,
	relativePath?: string | null
): GeneratedFileSaveErrorMessage => {
	const message = getErrorMessageText(error).trim();
	const path = normalizeNativeAgentWorkspacePath(relativePath ?? '') ?? relativePath ?? '';

	if (/file not found|not_found|no such file/i.test(message)) {
		return {
			key: 'Generated output file was not found.',
			params: path ? { path } : undefined
		};
	}

	if (/downloads|mediastore|output document|openoutputstream|permission|eacces|denied/i.test(message)) {
		return {
			key: 'Android could not save this file to Downloads.'
		};
	}

	if (message) {
		return {
			key: 'Failed to save generated file: {{message}}',
			params: { message }
		};
	}

	return { key: 'Failed to save generated file.' };
};
