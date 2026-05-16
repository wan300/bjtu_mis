import type { INotification } from './types';

export const nativeNotification: INotification = {
	async requestPermission() {
		throw new Error('Native notification not yet implemented. Requires Capacitor plugin.');
	},

	notify(_title: string, _options?: NotificationOptions) {
		throw new Error('Native notification not yet implemented. Requires Capacitor plugin.');
	},

	isSupported() {
		return false;
	}
};
