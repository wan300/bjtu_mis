import { beforeEach, describe, expect, it, vi } from 'vitest';

const nativePlatform = vi.hoisted(() => ({
	isNative: false,
	platform: 'web',
	search: vi.fn(),
	fetchUrl: vi.fn()
}));

vi.mock('@capacitor/core', () => ({
	Capacitor: {
		isNativePlatform: () => nativePlatform.isNative,
		getPlatform: () => nativePlatform.platform
	},
	registerPlugin: () => ({
		search: nativePlatform.search,
		fetchUrl: nativePlatform.fetchUrl,
		abort: vi.fn()
	})
}));

import {
	createWebSearchSources,
	fetchUrl,
	normalizeLocalWebSearchSettings,
	searchWeb,
	supportsNativeWebSearch
} from './web-search';

beforeEach(() => {
	vi.clearAllMocks();
	nativePlatform.isNative = false;
	nativePlatform.platform = 'web';
});

describe('local-first native web search bridge', () => {
	it('only reports support for Android native clients', () => {
		expect(supportsNativeWebSearch()).toBe(false);

		nativePlatform.isNative = true;
		nativePlatform.platform = 'android';

		expect(supportsNativeWebSearch()).toBe(true);
	});

	it('normalizes settings to safe local web search bounds', () => {
		expect(
			normalizeLocalWebSearchSettings({
				resultCount: 99,
				fetchPageCount: -1,
				maxPageChars: 100,
				timeoutMs: 999999
			})
		).toMatchObject({
			engine: 'auto',
			resultCount: 10,
			fetchPageCount: 0,
			maxPageChars: 1000,
			timeoutMs: 60000
		});

		expect(normalizeLocalWebSearchSettings({ engine: 'bing_html' }).engine).toBe('bing_html');
		expect(normalizeLocalWebSearchSettings({ engine: 'unsupported' }).engine).toBe('auto');
	});

	it('calls the Android plugin with normalized search options', async () => {
		nativePlatform.isNative = true;
		nativePlatform.platform = 'android';
		nativePlatform.search.mockResolvedValue({
			requestId: 'request-1',
			results: [{ title: 'Result', url: 'https://example.com', snippet: 'Snippet' }]
		});

		const results = await searchWeb({
			requestId: 'request-1',
			query: 'current news',
			engine: 'bing_html',
			resultCount: 20,
			fetchPageCount: 4
		});

		expect(nativePlatform.search).toHaveBeenCalledWith(
			expect.objectContaining({
				requestId: 'request-1',
				query: 'current news',
				engine: 'bing_html',
				count: 10,
				fetchPageCount: 4,
				maxPageChars: 12000,
				timeoutMs: 15000
			})
		);
		expect(results[0].url).toBe('https://example.com');
	});

	it('rejects fetches outside Android native clients', async () => {
		await expect(fetchUrl({ url: 'https://example.com' })).rejects.toThrow('Android local-first');
	});

	it('creates citation-compatible sources from native search results', () => {
		const sources = createWebSearchSources([
			{
				title: 'Example',
				url: 'https://example.com',
				snippet: 'Snippet',
				content: 'Content',
				fetched: true
			}
		]);

		expect(sources[0]).toMatchObject({
			source: {
				id: 'https://example.com',
				name: 'https://example.com',
				url: 'https://example.com'
			},
			document: ['Example\n\nSnippet\n\nContent'],
			metadata: [{ source: 'https://example.com', name: 'Example', url: 'https://example.com' }]
		});
	});
});
