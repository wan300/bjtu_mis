import type { IShare } from './types';

export const nativeShare: IShare = {
	async share(_data: { title: string; text?: string; url?: string }) {
		throw new Error('Native share not yet implemented. Requires Capacitor plugin.');
	},

	isSupported() {
		return false;
	}
};
