import { Capacitor, registerPlugin } from '@capacitor/core';

import {
	DEFAULT_LOCAL_WEB_SEARCH_SETTINGS,
	normalizeLocalWebSearchSettings,
	type LocalWebSearchSettings
} from './web-search-config';

export type LocalWebSearchResult = {
	title: string;
	url: string;
	snippet: string;
	content?: string;
	fetched?: boolean;
	error?: string;
};

export type LocalFetchedPage = {
	url: string;
	title?: string;
	content: string;
	fetched: boolean;
	error?: string;
};

type NativeWebSearchPlugin = {
	search(options: {
		requestId: string;
		query: string;
		engine: string;
		count: number;
		fetchPageCount: number;
		maxPageChars: number;
		timeoutMs: number;
	}): Promise<{ requestId: string; results: LocalWebSearchResult[] }>;
	fetchUrl(options: {
		requestId: string;
		url: string;
		maxPageChars: number;
		timeoutMs: number;
	}): Promise<{ requestId: string; page: LocalFetchedPage }>;
	abort(options: { requestId: string }): Promise<void>;
};

const NativeWebSearch = registerPlugin<NativeWebSearchPlugin>('NativeWebSearch');

const createRequestId = () =>
	globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;

export const supportsNativeWebSearch = () =>
	Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';

const getSourceDocument = (result: LocalWebSearchResult | LocalFetchedPage) => {
	const parts = [];
	const title = 'title' in result ? result.title : undefined;
	const snippet = 'snippet' in result ? result.snippet : undefined;

	if (title) {
		parts.push(title);
	}
	if (snippet) {
		parts.push(snippet);
	}
	if (result.content) {
		parts.push(result.content);
	}

	return parts.join('\n\n').trim();
};

export const createWebSearchSources = (results: (LocalWebSearchResult | LocalFetchedPage)[]) =>
	results
		.filter((result) => result?.url)
		.map((result) => {
			const title = ('title' in result ? result.title : undefined) || result.url;
			const document = getSourceDocument(result);

			return {
				source: {
					id: result.url,
					name: result.url,
					url: result.url
				},
				document: [document || title],
				metadata: [
					{
						source: result.url,
						name: title,
						url: result.url
					}
				],
				distances: []
			};
		});

export const getWebSearchStatus = (
	results: LocalWebSearchResult[],
	description = 'Searched {{count}} sites'
) => ({
	action: 'web_search',
	description,
	done: true,
	urls: results.map((result) => result.url),
	items: results.map((result) => ({
		title: result.title || result.url,
		url: result.url,
		snippet: result.snippet
	}))
});

export const searchWeb = async (
	options: {
		query: string;
		requestId?: string;
	} & Partial<LocalWebSearchSettings>
): Promise<LocalWebSearchResult[]> => {
	if (!supportsNativeWebSearch()) {
		throw new Error('Native web search is only available on Android local-first clients.');
	}

	const settings = normalizeLocalWebSearchSettings({
		...DEFAULT_LOCAL_WEB_SEARCH_SETTINGS,
		...options
	});
	const response = await NativeWebSearch.search({
		requestId: options.requestId ?? createRequestId(),
		query: options.query,
		engine: settings.engine,
		count: settings.resultCount,
		fetchPageCount: settings.fetchPageCount,
		maxPageChars: settings.maxPageChars,
		timeoutMs: settings.timeoutMs
	});

	return response.results ?? [];
};

export const fetchUrl = async (
	options: {
		url: string;
		requestId?: string;
	} & Partial<LocalWebSearchSettings>
): Promise<LocalFetchedPage> => {
	if (!supportsNativeWebSearch()) {
		throw new Error('Native web fetch is only available on Android local-first clients.');
	}

	const settings = normalizeLocalWebSearchSettings({
		...DEFAULT_LOCAL_WEB_SEARCH_SETTINGS,
		...options
	});
	const response = await NativeWebSearch.fetchUrl({
		requestId: options.requestId ?? createRequestId(),
		url: options.url,
		maxPageChars: settings.maxPageChars,
		timeoutMs: settings.timeoutMs
	});

	return response.page;
};

export { DEFAULT_LOCAL_WEB_SEARCH_SETTINGS, normalizeLocalWebSearchSettings };
