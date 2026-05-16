import type { IFilePicker } from './types';

export const nativeFilePicker: IFilePicker = {
	async pickImage(_source?: 'camera' | 'gallery') {
		throw new Error('Native file picker not yet implemented. Requires Capacitor plugin.');
	},

	async pickFiles(_accept?: string) {
		throw new Error('Native file picker not yet implemented. Requires Capacitor plugin.');
	},

	isSupported() {
		return false;
	}
};
