export interface IFilePicker {
	pickImage(source?: 'camera' | 'gallery'): Promise<File[]>;
	pickFiles(accept?: string): Promise<File[]>;
	isSupported(): boolean;
}

export interface IShare {
	share(data: { title: string; text?: string; url?: string }): Promise<void>;
	isSupported(): boolean;
}

export interface INotification {
	requestPermission(): Promise<NotificationPermission>;
	notify(title: string, options?: NotificationOptions): void;
	isSupported(): boolean;
}

export type PlatformAdapters = {
	filePicker: IFilePicker;
	share: IShare;
	notification: INotification;
};
