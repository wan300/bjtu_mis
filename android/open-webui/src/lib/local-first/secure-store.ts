import { Capacitor, registerPlugin } from '@capacitor/core';

type NativeSecureStorePlugin = {
	set(options: { key: string; value: string }): Promise<{ key: string }>;
	get(options: { key: string }): Promise<{ key: string; value?: string | null }>;
	remove(options: { key: string }): Promise<{ key: string }>;
	keys(): Promise<{ keys: string[] }>;
};

const NativeSecureStore = registerPlugin<NativeSecureStorePlugin>('NativeSecureStore');

export const SECURE_REF_PREFIX = 'secure-ref:';

export const supportsNativeSecureStore = () =>
	Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';

export const toSecureRef = (key: string) => `${SECURE_REF_PREFIX}${key}`;

export const isSecureRef = (value: unknown): value is string =>
	typeof value === 'string' && value.startsWith(SECURE_REF_PREFIX);

export const keyFromSecureRef = (value: string) => value.slice(SECURE_REF_PREFIX.length);

export const secureStoreSet = async (key: string, value: string) => {
	if (!supportsNativeSecureStore()) {
		return;
	}
	await NativeSecureStore.set({ key, value });
};

export const secureStoreGet = async (key: string) => {
	if (!supportsNativeSecureStore()) {
		return null;
	}
	const response = await NativeSecureStore.get({ key });
	return response.value ?? null;
};

export const secureStoreRemove = async (key: string) => {
	if (!supportsNativeSecureStore()) {
		return;
	}
	await NativeSecureStore.remove({ key });
};

export const secureStoreKeys = async () => {
	if (!supportsNativeSecureStore()) {
		return [];
	}
	const response = await NativeSecureStore.keys();
	return response.keys ?? [];
};
