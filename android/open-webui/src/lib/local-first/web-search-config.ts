export type LocalWebSearchEngine = 'auto' | 'duckduckgo_lite' | 'bing_html';

export type LocalWebSearchSettings = {
	engine: LocalWebSearchEngine;
	resultCount: number;
	fetchPageCount: number;
	maxPageChars: number;
	timeoutMs: number;
};

export const DEFAULT_LOCAL_WEB_SEARCH_SETTINGS: LocalWebSearchSettings = {
	engine: 'auto',
	resultCount: 5,
	fetchPageCount: 3,
	maxPageChars: 12000,
	timeoutMs: 15000
};

const clampInteger = (value: unknown, defaultValue: number, min: number, max: number) => {
	const numeric = Number(value);
	if (!Number.isFinite(numeric)) {
		return defaultValue;
	}

	return Math.max(min, Math.min(max, Math.trunc(numeric)));
};

const normalizeEngine = (value: unknown): LocalWebSearchEngine =>
	value === 'duckduckgo_lite' || value === 'bing_html' ? value : 'auto';

export const normalizeLocalWebSearchSettings = (
	settings: Partial<LocalWebSearchSettings> | Record<string, unknown> = {}
): LocalWebSearchSettings => ({
	engine: normalizeEngine(settings.engine),
	resultCount: clampInteger(
		settings.resultCount,
		DEFAULT_LOCAL_WEB_SEARCH_SETTINGS.resultCount,
		1,
		10
	),
	fetchPageCount: clampInteger(
		settings.fetchPageCount,
		DEFAULT_LOCAL_WEB_SEARCH_SETTINGS.fetchPageCount,
		0,
		5
	),
	maxPageChars: clampInteger(
		settings.maxPageChars,
		DEFAULT_LOCAL_WEB_SEARCH_SETTINGS.maxPageChars,
		1000,
		50000
	),
	timeoutMs: clampInteger(settings.timeoutMs, DEFAULT_LOCAL_WEB_SEARCH_SETTINGS.timeoutMs, 1000, 60000)
});
