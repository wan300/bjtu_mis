type BjtuServiceError = {
  code?: string;
  message?: string;
};

type BjtuServiceResult<T> =
  | {
      ok: true;
      data: T;
      error?: never;
    }
  | {
      ok: false;
      data?: never;
      error?: BjtuServiceError;
    };

type BjtuServiceBridge = {
  invoke<T = unknown>(method: string, params?: Record<string, unknown>): Promise<BjtuServiceResult<T>>;
  __resolve?: (id: string, payload: BjtuServiceResult<unknown>) => void;
};

type BjtuServiceNativeBridge = {
  invoke(requestJson: string): void;
};

export type BjtuServiceProfile = Record<string, unknown>;
export const BJTU_SERVICE_TOKEN_PREFIX = "bjtu-service-local:";

type BjtuServiceProfileData = {
  name?: string;
  student_id?: string;
  studentId?: string;
  account?: string;
  campus?: string;
  college?: string;
  avatar_url?: string;
  avatarUrl?: string;
};

export type BjtuServiceCredentials = {
  login_name?: string;
  loginName?: string;
  student_id?: string;
  studentId?: string;
  account?: string;
  password?: string;
};

type BjtuServiceProfileEnvelope = {
  data?: BjtuServiceProfileData;
};

declare global {
  interface Window {
    BjtuService?: BjtuServiceBridge;
    BjtuServiceNative?: BjtuServiceNativeBridge;
  }
}

export function hasBjtuServiceBridge() {
  ensureBjtuServiceBridgeShim();
  return typeof window !== "undefined" && Boolean(window.BjtuService?.invoke);
}

export async function waitForBjtuServiceBridge(timeoutMs = 3000) {
  if (typeof window === "undefined") return null;
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    ensureBjtuServiceBridgeShim();
    if (window.BjtuService?.invoke) return window.BjtuService;
    await delay(100);
  }
  ensureBjtuServiceBridgeShim();
  return window.BjtuService || null;
}

export async function invokeBjtuService<T>(method: string, params: Record<string, unknown> = {}) {
  const bridge = await waitForBjtuServiceBridge();
  if (!bridge) {
    throw new Error("当前环境未提供 BJTU App 第三方服务接口");
  }
  const result = await bridge.invoke<T>(method, params);
  if (result.ok) return result.data;
  throw new Error(result.error?.message || result.error?.code || "BJTU App 接口调用失败");
}

export async function syncBjtuServiceProfile() {
  const bridge = await waitForBjtuServiceBridge();
  if (!bridge) return null;
  const result = await bridge.invoke<BjtuServiceProfile>("identity.get_profile", {});
  if (!result.ok) return null;
  uni.setStorageSync("bjtuServiceProfile", result.data);
  return result.data;
}

export async function getBjtuServiceCredentials() {
  return invokeBjtuService<BjtuServiceCredentials>("identity.get_credentials", {});
}

export async function createBjtuServiceUserInfo(credentials?: BjtuServiceCredentials | null) {
  const profile = await invokeBjtuService<BjtuServiceProfileEnvelope | BjtuServiceProfileData>("identity.get_profile", {});
  uni.setStorageSync("bjtuServiceProfile", profile);
  const data = extractProfileData(profile);
  const resolvedCredentials = credentials || (await getBjtuServiceCredentials());
  const studentId = text(
    data.student_id ||
      data.studentId ||
      data.account ||
      resolvedCredentials.student_id ||
      resolvedCredentials.studentId ||
      resolvedCredentials.login_name ||
      resolvedCredentials.loginName ||
      resolvedCredentials.account
  );
  if (!studentId) {
    throw new Error("BJTU 身份缺少学号，无法进入技匠");
  }
  const name = text(data.name) || `BJTU ${studentId}`;
  const campusName = text(data.campus) || "北京交通大学";
  return {
    id: stableNumericId(studentId),
    nickname: name,
    avatarUrl: text(data.avatar_url || data.avatarUrl) || undefined,
    verifyStatus: 2,
    currentRole: 1 as const,
    campusId: 1,
    campusName,
    creditScore: 100,
    isSellerVerified: 1,
    depositPaid: 0,
    hasWechatIdentity: false,
    hasMisIdentity: true
  };
}

export async function closeBjtuService() {
  const bridge = await waitForBjtuServiceBridge(1000);
  if (!bridge) {
    uni.switchTab({ url: "/pages/tabbar/home/index" });
    return;
  }
  const result = await bridge.invoke("app.close_service", {});
  if (!result.ok) {
    throw new Error(result.error?.message || "无法退出第三方服务");
  }
}

export function isBjtuServiceToken(token?: string | null) {
  return Boolean(token?.startsWith(BJTU_SERVICE_TOKEN_PREFIX));
}

export function bjtuServiceTokenFor(userId: number) {
  return `${BJTU_SERVICE_TOKEN_PREFIX}${userId}`;
}

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

const pendingBridgeCallbacks: Record<string, (payload: BjtuServiceResult<unknown>) => void> = {};

function ensureBjtuServiceBridgeShim() {
  if (typeof window === "undefined") return;
  if (window.BjtuService?.invoke) return;
  const native = window.BjtuServiceNative;
  if (!native?.invoke) return;

  window.BjtuService = {
    invoke<T = unknown>(method: string, params: Record<string, unknown> = {}) {
      return new Promise<BjtuServiceResult<T>>((resolve) => {
        const id = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
        pendingBridgeCallbacks[id] = resolve as (payload: BjtuServiceResult<unknown>) => void;
        native.invoke(JSON.stringify({ id, method, params }));
      });
    },
    __resolve(id: string, payload: BjtuServiceResult<unknown>) {
      const callback = pendingBridgeCallbacks[id];
      if (!callback) return;
      delete pendingBridgeCallbacks[id];
      callback(payload);
    }
  };
}

function extractProfileData(profile: BjtuServiceProfileEnvelope | BjtuServiceProfileData): BjtuServiceProfileData {
  const envelope = profile as BjtuServiceProfileEnvelope;
  return envelope.data || (profile as BjtuServiceProfileData);
}

function text(value: unknown) {
  return String(value || "").trim();
}

function stableNumericId(value: string) {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) | 0;
  }
  return Math.abs(hash) || 1;
}
