import { browser } from '$app/environment';

type CapacitorWindow = Window & {
	Capacitor?: {
		getPlatform?: () => string;
		isNativePlatform?: () => boolean;
	};
};

export const isNativeAndroid = () => {
	if (!browser) {
		return false;
	}

	const capacitor = (window as CapacitorWindow).Capacitor;
	return capacitor?.isNativePlatform?.() === true && capacitor?.getPlatform?.() === 'android';
};

export const isAndroid = () => {
	if (!browser) {
		return false;
	}

	const platform = (window as CapacitorWindow).Capacitor?.getPlatform?.();
	return platform === 'android' || /Android/i.test(navigator.userAgent);
};
