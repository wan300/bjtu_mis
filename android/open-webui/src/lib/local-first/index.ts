import { browser } from '$app/environment';

import { DEFAULT_LOCAL_WEB_SEARCH_SETTINGS } from './web-search-config';

export const LOCAL_FIRST_TOKEN = 'local-first-token';
export const LOCAL_FIRST_USER_ID = 'local-user';
export const LOCAL_FIRST_APP_NAME = 'Open WebUI';
export const DEFAULT_LOCAL_USER_NAME = 'Local User';

type NativeStudentProfileBridge = {
	getStudentName?: () => unknown;
	getPreferredTheme?: () => unknown;
};

const getNativeStudentProfileBridge = (): NativeStudentProfileBridge | undefined => {
	if (!browser) {
		return undefined;
	}

	return (
		window as typeof window & {
			BjtuMisNative?: NativeStudentProfileBridge;
		}
	).BjtuMisNative;
};

export const resolveLocalUserName = (
	nativeBridge: NativeStudentProfileBridge | undefined = getNativeStudentProfileBridge()
) => {
	try {
		const studentName = nativeBridge?.getStudentName?.();
		return typeof studentName === 'string' && studentName.trim()
			? studentName.trim()
			: DEFAULT_LOCAL_USER_NAME;
	} catch {
		return DEFAULT_LOCAL_USER_NAME;
	}
};

const DEFAULT_DIRECT_CONNECTIONS = {
	OPENAI_API_BASE_URLS: [],
	OPENAI_API_KEYS: [],
	OPENAI_API_KEY_REFS: [],
	OPENAI_API_CONFIGS: {}
};

const isEnabled = (value: unknown) => value === true || value === 'true' || value === '1';

export const shouldUseLocalFirstClient = (
	env: Record<string, unknown> = import.meta.env,
	isNativePlatform = false,
	platform = ''
) => {
	const explicitLocalFirst =
		isEnabled(env.VITE_ENABLE_LOCAL_FIRST_CLIENT) || isEnabled(env.VITE_LOCAL_FIRST_CLIENT);

	const nativeLocalFirst =
		isEnabled(env.VITE_ENABLE_NATIVE_LOCAL_FIRST_CLIENT) ||
		isEnabled(env.VITE_NATIVE_LOCAL_FIRST_CLIENT);

	const nativeLocalFirstDisabled =
		isEnabled(env.VITE_DISABLE_NATIVE_LOCAL_FIRST_CLIENT) ||
		isEnabled(env.VITE_DISABLE_ANDROID_LOCAL_FIRST_CLIENT);

	const androidLocalFirst = isNativePlatform && platform === 'android';

	return (
		explicitLocalFirst ||
		(!nativeLocalFirstDisabled && (androidLocalFirst || (nativeLocalFirst && isNativePlatform)))
	);
};

export const isLocalFirstClient = () => {
	if (!browser) {
		return false;
	}

	const win = window as typeof window & {
		Capacitor?: {
			getPlatform?: () => string;
			isNativePlatform?: () => boolean;
		};
	};

	return shouldUseLocalFirstClient(
		import.meta.env,
		win.Capacitor?.isNativePlatform?.() === true,
		win.Capacitor?.getPlatform?.() ?? ''
	);
};

export const getLocalBackendConfig = () => ({
	status: true,
	name: LOCAL_FIRST_APP_NAME,
	version: 'local-first',
	default_locale: 'zh-CN',
	oauth: {
		providers: {}
	},
	features: {
		auth: false,
		enable_signup: false,
		enable_login_form: false,
		enable_ldap: false,
		enable_oauth_signup: false,
		enable_websocket: false,
		enable_direct_connections: true,
		enable_folders: false,
		enable_channels: false,
		enable_notes: false,
		enable_calendar: true,
		enable_automations: false,
		enable_community_sharing: false,
		enable_public_active_users_count: false,
		enable_memories: true,
		enable_web_search: true,
		enable_image_generation: false,
		enable_code_interpreter: true,
		enable_local_knowledge: true,
		enable_android_device_tools: true,
		enable_local_memory: true,
		enable_local_tasks: true,
		enable_version_update_check: false,
		enable_admin_export: false,
		enable_admin_chat_access: false
	},
	file: {
		max_count: 10,
		max_size: 25 * 1024 * 1024
	},
	audio: {
		stt: { engine: '' },
		tts: { engine: '' }
	},
	model_config: {},
	default_models: null
});

export const getLocalUser = () => ({
	id: LOCAL_FIRST_USER_ID,
	name: resolveLocalUserName(),
	email: 'local@device',
	role: 'user',
	profile_image_url: '/user.png',
	is_active: true,
	permissions: {
		chat: {
			controls: true,
			file_upload: true,
			delete: true,
			edit: true,
			tts: false,
			rate_response: false,
			temporary: true,
			export: true
		},
		features: {
			web_search: true,
			image_generation: false,
			code_interpreter: true,
			direct_tool_servers: false,
			notes: true,
			calendar: true,
			automations: false,
			memories: true,
			local_knowledge: true,
			android_device_tools: true,
			local_tasks: true
		},
		workspace: {
			models: false,
			knowledge: true,
			prompts: false,
			tools: false
		},
		settings: {
			interface: true
		}
	}
});

export const getDefaultLocalSettings = () => ({
	directConnections: structuredClone(DEFAULT_DIRECT_CONNECTIONS),
	localWebSearch: structuredClone(DEFAULT_LOCAL_WEB_SEARCH_SETTINGS),
	pinnedMenuItems: [],
	title: {
		auto: false
	},
	autoTags: false,
	autoFollowUps: false,
	params: {
		stream_response: true
	},
	showChangelog: false,
	showUpdateToast: false,
	notificationEnabled: false,
	richTextInput: true,
	imageCompression: true,
	imageCompressionInChannels: false
});

export const mergeLocalSettings = (settings: Record<string, unknown> = {}) => ({
	...getDefaultLocalSettings(),
	...settings,
	directConnections: {
		...DEFAULT_DIRECT_CONNECTIONS,
		...(settings?.directConnections as Record<string, unknown> | undefined)
	},
	localWebSearch: {
		...DEFAULT_LOCAL_WEB_SEARCH_SETTINGS,
		...(settings?.localWebSearch as Record<string, unknown> | undefined)
	},
	pinnedMenuItems: Array.isArray(settings?.pinnedMenuItems) ? settings.pinnedMenuItems : []
});

export const ensureLocalSession = () => {
	if (!browser) {
		return getLocalUser();
	}

	localStorage.setItem('token', LOCAL_FIRST_TOKEN);
	return getLocalUser();
};
