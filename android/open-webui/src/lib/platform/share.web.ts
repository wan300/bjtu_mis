import type { IShare } from './types';

export const webShare: IShare = {
	async share(data: { title: string; text?: string; url?: string }) {
		if (!navigator.share) {
			throw new Error('Web Share API not supported');
		}
		return navigator.share(data);
	},

	isSupported() {
		return !!navigator.share;
	}
};
