import {
	isSecureRef,
	keyFromSecureRef,
	secureStoreGet,
	secureStoreSet,
	supportsNativeSecureStore,
	toSecureRef
} from './secure-store';

type DirectConnectionsConfig = {
	OPENAI_API_BASE_URLS?: string[];
	OPENAI_API_KEYS?: string[];
	OPENAI_API_KEY_REFS?: string[];
	OPENAI_API_CONFIGS?: Record<string | number, Record<string, any>>;
};

const getSecretKey = (idx: number) => `direct-connections.openai.${idx}.api-key`;

export const prepareDirectConnectionsForSecureStore = async (
	directConnections: DirectConnectionsConfig = {}
) => {
	if (!supportsNativeSecureStore()) {
		return directConnections;
	}

	const keys = [...(directConnections.OPENAI_API_KEYS ?? [])];
	const refs = [...(directConnections.OPENAI_API_KEY_REFS ?? [])];
	const urls = directConnections.OPENAI_API_BASE_URLS ?? [];

	for (let idx = 0; idx < urls.length; idx += 1) {
		const value = keys[idx] ?? '';
		if (!value) {
			continue;
		}

		if (isSecureRef(value)) {
			refs[idx] = value;
			keys[idx] = '';
			continue;
		}

		const secureKey = getSecretKey(idx);
		await secureStoreSet(secureKey, value);
		refs[idx] = toSecureRef(secureKey);
		keys[idx] = '';
	}

	return {
		...directConnections,
		OPENAI_API_KEYS: keys.slice(0, urls.length),
		OPENAI_API_KEY_REFS: refs.slice(0, urls.length)
	};
};

export const resolveDirectConnectionKey = async (
	directConnections: DirectConnectionsConfig = {},
	idx: number
) => {
	const rawKey = directConnections.OPENAI_API_KEYS?.[idx] ?? '';
	if (rawKey && !isSecureRef(rawKey)) {
		return rawKey;
	}

	const ref = directConnections.OPENAI_API_KEY_REFS?.[idx] ?? (isSecureRef(rawKey) ? rawKey : '');
	if (!isSecureRef(ref)) {
		return rawKey;
	}

	return (await secureStoreGet(keyFromSecureRef(ref))) ?? '';
};

export const resolveDirectConnectionKeys = async (directConnections: DirectConnectionsConfig = {}) => {
	const urls = directConnections.OPENAI_API_BASE_URLS ?? [];
	const keys = [];

	for (let idx = 0; idx < urls.length; idx += 1) {
		keys.push(await resolveDirectConnectionKey(directConnections, idx));
	}

	return keys;
};
