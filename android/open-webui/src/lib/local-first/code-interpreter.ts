import { get } from 'svelte/store';

import { getFileContentById } from '$lib/apis/files';
import { pyodideWorker } from '$lib/stores';
import PyodideWorker from '$lib/workers/pyodide.worker?worker';

type PythonFileReference = {
	id?: string;
	name?: string;
	filename?: string;
	data?: ArrayBuffer | string;
};

export type LocalPythonExecutionResult = {
	stdout: string | null;
	stderr: string | null;
	result: any;
	images: string[];
	files: unknown[];
	elapsedMs: number;
	truncated: boolean;
};

const OUTPUT_LIMIT = 120000;
const DEFAULT_TIMEOUT_MS = 60000;

export const getPyodidePackagesForCode = (code: string) =>
	[
		/\bimport\s+requests\b|\bfrom\s+requests\b/.test(code) ? 'requests' : null,
		/\bimport\s+bs4\b|\bfrom\s+bs4\b/.test(code) ? 'beautifulsoup4' : null,
		/\bimport\s+numpy\b|\bfrom\s+numpy\b/.test(code) ? 'numpy' : null,
		/\bimport\s+pandas\b|\bfrom\s+pandas\b/.test(code) ? 'pandas' : null,
		/\bimport\s+matplotlib\b|\bfrom\s+matplotlib\b/.test(code) ? 'matplotlib' : null,
		/\bimport\s+seaborn\b|\bfrom\s+seaborn\b/.test(code) ? 'seaborn' : null,
		/\bimport\s+sklearn\b|\bfrom\s+sklearn\b/.test(code) ? 'scikit-learn' : null,
		/\bimport\s+scipy\b|\bfrom\s+scipy\b/.test(code) ? 'scipy' : null,
		/\bimport\s+re\b|\bfrom\s+re\b/.test(code) ? 'regex' : null,
		/\bimport\s+sympy\b|\bfrom\s+sympy\b/.test(code) ? 'sympy' : null,
		/\bimport\s+tiktoken\b|\bfrom\s+tiktoken\b/.test(code) ? 'tiktoken' : null,
		/\bimport\s+pytz\b|\bfrom\s+pytz\b/.test(code) ? 'pytz' : null
	].filter(Boolean) as string[];

export const getOrCreatePyodideWorker = () => {
	let worker = get(pyodideWorker);
	if (!worker) {
		worker = new PyodideWorker();
		pyodideWorker.set(worker);
	}
	return worker;
};

const arrayBufferFromString = (value: string) => new TextEncoder().encode(value).buffer;

const resolvePythonFiles = async (files: PythonFileReference[] = []) => {
	const payloads = [];
	for (const file of files) {
		const name = file?.filename || file?.name || 'file';

		if (file.data instanceof ArrayBuffer) {
			payloads.push({ name, data: file.data });
			continue;
		}

		if (typeof file.data === 'string') {
			payloads.push({ name, data: arrayBufferFromString(file.data) });
			continue;
		}

		if (file.id) {
			const content = await getFileContentById(file.id);
			if (content instanceof ArrayBuffer) {
				payloads.push({ name, data: content });
			} else if (typeof content === 'string') {
				payloads.push({ name, data: arrayBufferFromString(content) });
			}
		}
	}

	return payloads;
};

const truncate = (value: string | null | undefined) => {
	if (!value || value.length <= OUTPUT_LIMIT) {
		return { value: value ?? null, truncated: false };
	}
	return { value: value.slice(0, OUTPUT_LIMIT), truncated: true };
};

const extractImages = (stdout: string | null) =>
	(stdout ?? '')
		.split(/\r?\n/)
		.map((line) => line.trim())
		.filter((line) => line.startsWith('data:image/png;base64,'));

export const executePythonWithWorker = async ({
	id = crypto.randomUUID(),
	code,
	files = [],
	timeoutMs = DEFAULT_TIMEOUT_MS
}: {
	id?: string;
	code: string;
	files?: PythonFileReference[];
	timeoutMs?: number;
}): Promise<LocalPythonExecutionResult> => {
	const startedAt = Date.now();
	const worker = getOrCreatePyodideWorker();
	const filePayloads = await resolvePythonFiles(files);
	const packages = getPyodidePackagesForCode(code);

	return await new Promise((resolve) => {
		let settled = false;
		const finish = (data: { stdout?: string | null; stderr?: string | null; result?: any }) => {
			if (settled) return;
			settled = true;
			clearTimeout(timeoutId);
			worker.removeEventListener('message', onMessage);
			worker.removeEventListener('error', onError);

			const stdout = truncate(data.stdout ?? null);
			const stderr = truncate(data.stderr ?? null);
			resolve({
				stdout: stdout.value,
				stderr: stderr.value,
				result: data.result ?? null,
				images: extractImages(stdout.value),
				files: [],
				elapsedMs: Date.now() - startedAt,
				truncated: stdout.truncated || stderr.truncated
			});
		};

		const timeoutId = setTimeout(() => {
			worker.terminate();
			pyodideWorker.set(null);
			finish({
				stdout: null,
				stderr: 'Execution Time Limit Exceeded',
				result: null
			});
		}, Math.max(1000, Math.min(300000, Math.trunc(timeoutMs))));

		const onMessage = (event: MessageEvent) => {
			const { id: eventId, ...data } = event.data ?? {};
			if (eventId !== id || data?.type?.startsWith?.('fs:')) return;
			finish(data);
		};

		const onError = (event: ErrorEvent) => {
			finish({
				stdout: null,
				stderr: event.message || 'Python worker failed',
				result: null
			});
		};

		worker.addEventListener('message', onMessage);
		worker.addEventListener('error', onError);
		worker.postMessage({
			type: 'execute',
			id,
			code,
			packages,
			files: filePayloads.length > 0 ? filePayloads : undefined
		});
	});
};
