import type { IFilePicker, IShare, INotification, PlatformAdapters } from './types';
import { webFilePicker } from './file-picker.web';
import { nativeFilePicker } from './file-picker.native';
import { webShare } from './share.web';
import { nativeShare } from './share.native';
import { webNotification } from './notification.web';
import { nativeNotification } from './notification.native';
import { ENABLE_MOBILE_NATIVE_FEATURES } from '$lib/mobile/feature-flags';

let _filePicker: IFilePicker | null = null;
let _share: IShare | null = null;
let _notification: INotification | null = null;

export function getFilePicker(): IFilePicker {
	if (!_filePicker) {
		_filePicker = ENABLE_MOBILE_NATIVE_FEATURES ? nativeFilePicker : webFilePicker;
	}
	return _filePicker;
}

export function getShare(): IShare {
	if (!_share) {
		_share = ENABLE_MOBILE_NATIVE_FEATURES ? nativeShare : webShare;
	}
	return _share;
}

export function getNotification(): INotification {
	if (!_notification) {
		_notification = ENABLE_MOBILE_NATIVE_FEATURES ? nativeNotification : webNotification;
	}
	return _notification;
}

export function getPlatformAdapters(): PlatformAdapters {
	return {
		filePicker: getFilePicker(),
		share: getShare(),
		notification: getNotification()
	};
}

export type { IFilePicker, IShare, INotification, PlatformAdapters };
