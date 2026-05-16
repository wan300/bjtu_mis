import type { INotification } from './types';

export const webNotification: INotification = {
	async requestPermission() {
		if (!('Notification' in window)) {
			return 'denied' as NotificationPermission;
		}
		return Notification.requestPermission();
	},

	notify(title: string, options?: NotificationOptions) {
		if (this.isSupported() && Notification.permission === 'granted') {
			new Notification(title, options);
		}
	},

	isSupported() {
		return 'Notification' in window;
	}
};
