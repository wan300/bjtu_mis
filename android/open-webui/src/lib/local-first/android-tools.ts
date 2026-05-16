import { Capacitor, registerPlugin } from '@capacitor/core';

type NativeAndroidToolsPlugin = {
	getDeviceContext(): Promise<Record<string, any>>;
	getAppInfo(): Promise<Record<string, any>>;
	requestNotificationPermission(): Promise<{ granted: boolean }>;
	showNotification(options: Record<string, any>): Promise<Record<string, any>>;
	vibrate(options: { durationMs?: number; effect?: string }): Promise<Record<string, any>>;
	getCurrentLocation(options?: { timeoutMs?: number; highAccuracy?: boolean }): Promise<Record<string, any>>;
	writeClipboard(options: { text: string; html?: string | null }): Promise<Record<string, any>>;
	readClipboardText(): Promise<{ text: string }>;
	openTextDocument(options?: { mimeType?: string; mimeTypes?: string[] }): Promise<Record<string, any>>;
	saveTextDocument(options: {
		fileName: string;
		mimeType: string;
		content: string;
	}): Promise<Record<string, any>>;
	pickMedia(options: { mimeType?: string; multiple?: boolean }): Promise<Record<string, any>>;
	capturePhoto(options: { title?: string }): Promise<Record<string, any>>;
	startAudioRecording(options: { requestId: string }): Promise<Record<string, any>>;
	stopAudioRecording(options: { requestId: string }): Promise<Record<string, any>>;
	listCalendarEvents(options: { startMs?: number; endMs?: number; limit?: number }): Promise<Record<string, any>>;
	createCalendarEvent(options: Record<string, any>): Promise<Record<string, any>>;
	updateCalendarEvent(options: Record<string, any>): Promise<Record<string, any>>;
	deleteCalendarEvent(options: { id: string }): Promise<Record<string, any>>;
	scheduleNotification(options: Record<string, any>): Promise<Record<string, any>>;
	cancelNotification(options: { id: string }): Promise<Record<string, any>>;
};

const NativeAndroidTools = registerPlugin<NativeAndroidToolsPlugin>('NativeAndroidTools');

export const supportsNativeAndroidTools = () =>
	Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';

const requireAndroidTools = () => {
	if (!supportsNativeAndroidTools()) {
		throw new Error('Android native tools are only available on Android local-first clients.');
	}
};

const confirmSensitiveAction = (message: string) => {
	if (typeof globalThis.confirm !== 'function') {
		throw new Error('user_permission_required');
	}

	if (!globalThis.confirm(message)) {
		throw new Error('user_denied');
	}
};

export const getDeviceContext = async () => {
	requireAndroidTools();
	return NativeAndroidTools.getDeviceContext();
};

export const getAppInfo = async () => {
	requireAndroidTools();
	return NativeAndroidTools.getAppInfo();
};

export const requestNotificationPermission = async () => {
	requireAndroidTools();
	return NativeAndroidTools.requestNotificationPermission();
};

export const showNotification = async (options: Record<string, any>) => {
	requireAndroidTools();
	return NativeAndroidTools.showNotification(options);
};

export const vibrate = async (options: { durationMs?: number; effect?: string } = {}) => {
	requireAndroidTools();
	return NativeAndroidTools.vibrate(options);
};

export const getCurrentLocation = async (
	options: { timeoutMs?: number; highAccuracy?: boolean } = {}
) => {
	requireAndroidTools();
	return NativeAndroidTools.getCurrentLocation(options);
};

export const writeClipboard = async (options: { text: string; html?: string | null }) => {
	requireAndroidTools();
	return NativeAndroidTools.writeClipboard(options);
};

export const readClipboardText = async () => {
	requireAndroidTools();
	return NativeAndroidTools.readClipboardText();
};

export const openTextDocument = async (
	options: { mimeType?: string; mimeTypes?: string[] } = {}
) => {
	requireAndroidTools();
	return NativeAndroidTools.openTextDocument(options);
};

export const saveTextDocument = async (options: {
	fileName: string;
	mimeType: string;
	content: string;
}) => {
	requireAndroidTools();
	return NativeAndroidTools.saveTextDocument(options);
};

export const pickMedia = async (options: { mimeType?: string; multiple?: boolean } = {}) => {
	requireAndroidTools();
	confirmSensitiveAction('Allow the assistant to open the Android file or media picker?');
	return NativeAndroidTools.pickMedia(options);
};

export const capturePhoto = async (options: { title?: string } = {}) => {
	requireAndroidTools();
	confirmSensitiveAction('Allow the assistant to open the camera to capture a photo?');
	return NativeAndroidTools.capturePhoto(options);
};

export const startAudioRecording = async ({ requestId }: { requestId: string }) => {
	requireAndroidTools();
	confirmSensitiveAction('Allow the assistant to start audio recording?');
	return NativeAndroidTools.startAudioRecording({ requestId });
};

export const stopAudioRecording = async ({ requestId }: { requestId: string }) => {
	requireAndroidTools();
	return NativeAndroidTools.stopAudioRecording({ requestId });
};

export const listCalendarEvents = async (
	options: { startMs?: number; endMs?: number; limit?: number } = {}
) => {
	requireAndroidTools();
	confirmSensitiveAction('Allow the assistant to read Android calendar events?');
	return NativeAndroidTools.listCalendarEvents(options);
};

export const createCalendarEvent = async (options: Record<string, any>) => {
	requireAndroidTools();
	confirmSensitiveAction('Allow the assistant to create an Android calendar event?');
	return NativeAndroidTools.createCalendarEvent(options);
};

export const updateCalendarEvent = async (options: Record<string, any>) => {
	requireAndroidTools();
	confirmSensitiveAction('Allow the assistant to update an Android calendar event?');
	return NativeAndroidTools.updateCalendarEvent(options);
};

export const deleteCalendarEvent = async (options: { id: string }) => {
	requireAndroidTools();
	confirmSensitiveAction('Allow the assistant to delete an Android calendar event?');
	return NativeAndroidTools.deleteCalendarEvent(options);
};

export const scheduleNotification = async (options: Record<string, any>) => {
	requireAndroidTools();
	confirmSensitiveAction('Allow the assistant to schedule a local Android notification?');
	return NativeAndroidTools.scheduleNotification(options);
};

export const cancelNotification = async (options: { id: string }) => {
	requireAndroidTools();
	confirmSensitiveAction('Allow the assistant to cancel a local Android notification?');
	return NativeAndroidTools.cancelNotification(options);
};
