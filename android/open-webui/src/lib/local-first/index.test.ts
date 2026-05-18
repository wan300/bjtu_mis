import { describe, expect, it } from 'vitest';

import {
	getDefaultLocalSettings,
	getLocalBackendConfig,
	getLocalUser,
	resolveLocalUserName,
	shouldUseLocalFirstClient
} from './index';

describe('shouldUseLocalFirstClient', () => {
	it('uses local-first mode by default on Android native clients', () => {
		expect(shouldUseLocalFirstClient({}, true, 'android')).toBe(true);
	});

	it('does not force local-first mode on non-Android native clients', () => {
		expect(shouldUseLocalFirstClient({}, true, 'ios')).toBe(false);
	});

	it('allows local-first mode to be enabled globally', () => {
		expect(shouldUseLocalFirstClient({ VITE_ENABLE_LOCAL_FIRST_CLIENT: 'true' }, false)).toBe(true);
		expect(shouldUseLocalFirstClient({ VITE_LOCAL_FIRST_CLIENT: '1' }, false)).toBe(true);
	});

	it('allows local-first mode to be enabled only for native clients', () => {
		const env = { VITE_ENABLE_NATIVE_LOCAL_FIRST_CLIENT: 'true' };

		expect(shouldUseLocalFirstClient(env, true)).toBe(true);
		expect(shouldUseLocalFirstClient(env, false)).toBe(false);
	});

	it('allows Android native local-first mode to be disabled explicitly', () => {
		const env = { VITE_DISABLE_ANDROID_LOCAL_FIRST_CLIENT: 'true' };

		expect(shouldUseLocalFirstClient(env, true, 'android')).toBe(false);
	});

	it('enables local web search capability in local-first config and user permissions', () => {
		expect(getLocalBackendConfig().features.enable_web_search).toBe(true);
		expect(getLocalUser().permissions.features.web_search).toBe(true);
		expect(getDefaultLocalSettings().localWebSearch).toMatchObject({
			engine: 'auto',
			resultCount: 5,
			fetchPageCount: 3,
			maxPageChars: 12000,
			timeoutMs: 15000
		});
	});

	it('uses the native student name as the local user name when available', () => {
		expect(resolveLocalUserName({ getStudentName: () => ' 张三 ' })).toBe('张三');
	});

	it('falls back to the default local user name when the native student name is unavailable', () => {
		expect(resolveLocalUserName()).toBe('Local User');
		expect(resolveLocalUserName({ getStudentName: () => '   ' })).toBe('Local User');
		expect(
			resolveLocalUserName({
				getStudentName: () => {
					throw new Error('native bridge failed');
				}
			})
		).toBe('Local User');
	});
});
