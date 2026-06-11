import { resolveApiUrl } from "@/utils/env";
import { isBjtuServiceToken, waitForBjtuServiceBridge } from "@/api/bjtu-service";
import { useUserStore } from "@/store/user";
import { redirectToLogin } from "@/utils/nav";
import { toast } from "@/utils/toast";

export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
}

type Method = "GET" | "POST" | "PUT" | "DELETE";

interface RequestOptions {
  url: string;
  method?: Method;
  data?: Record<string, unknown>;
  params?: Record<string, unknown>;
  header?: Record<string, string>;
  skipAuth?: boolean;
  skipToast?: boolean;
  idempotent?: boolean;
  disableAuthRecovery?: boolean;
}

export class BizError extends Error {
  constructor(public code: number, message: string) {
    super(message);
  }
}

type NativeHttpResponse<T = unknown> = {
  statusCode: number;
  header?: Record<string, string>;
  data: ApiResult<T> | unknown;
};

type AuthRecoveryContext = {
  code: number;
  requestToken: string;
  requestMethod: Method;
  requestUrl: string;
  skipToast?: boolean;
};

type AuthRecoveryResult =
  | {
      status: "ready";
      token: string;
    }
  | {
      status: "manual_required" | "failed";
      message: string;
    };

const AUTH_EXPIRED_CODES = new Set([10001, 10002]);
const HTTP_AUTH_EXPIRED_CODES = new Set([401, 403, ...AUTH_EXPIRED_CODES]);
const SESSION_EXPIRED_MESSAGE = "会话已过期，请重新登录";
let authRecoveryInProgress = false;
let authRecoveryPromise: Promise<AuthRecoveryResult> | null = null;
let bjtuServiceSessionRecovery: Promise<AuthRecoveryResult> | null = null;

export async function request<T>(options: RequestOptions): Promise<T> {
  const method = options.method || "GET";
  const header: Record<string, string> = {
    "Content-Type": "application/json;charset=UTF-8",
    "X-Client-Version": "miniapp/1.0.0",
    ...options.header,
  };
  const requestUrl = buildUrl(resolveApiUrl(options.url), options.params);
  const originalToken = uni.getStorageSync("token") || "";
  let token = originalToken;

  if (!options.skipAuth) {
    token = await ensureRequestToken({
      code: 10001,
      requestToken: originalToken,
      requestMethod: method,
      requestUrl,
      skipToast: options.skipToast,
    }, options.disableAuthRecovery);

    if (!token) {
      finalizeAuthFailure(
        {
          code: 10001,
          requestToken: originalToken,
          requestMethod: method,
          requestUrl,
          skipToast: options.skipToast,
        },
        SESSION_EXPIRED_MESSAGE,
      );
    }

    header.Authorization = `Bearer ${token}`;
  }

  if (method !== "GET" || options.idempotent) {
    header["X-Idempotency-Key"] = createId();
  }

  try {
    const res = await sendRequest<T>(requestUrl, method, options.data, header);
    const body = res.data as ApiResult<T>;
    const bodyCode = Number(body?.code);
    const isAuthExpired = AUTH_EXPIRED_CODES.has(bodyCode) || (!options.skipAuth && (res.statusCode === 401 || res.statusCode === 403));

    if (isAuthExpired) {
      throw new BizError(resolveAuthExpiredCode(bodyCode, res.statusCode), body?.message || SESSION_EXPIRED_MESSAGE);
    }

    if (body?.code === 0) return body.data;

    const message = body?.message || "请求失败";
    if (!options.skipToast) toast(message);
    throw new BizError(body?.code || 90001, message);
  } catch (error) {
    if (error instanceof BizError) {
      if (!options.skipAuth && HTTP_AUTH_EXPIRED_CODES.has(error.code)) {
        const context = {
          code: error.code,
          requestToken: token || originalToken,
          requestMethod: method,
          requestUrl,
          skipToast: options.skipToast,
        };

        if (options.disableAuthRecovery) {
          finalizeAuthFailure(context, error.message || SESSION_EXPIRED_MESSAGE);
        }

        return retryAfterAuthRecovery<T>(options, context);
      }
      throw error;
    }

    if (!options.skipToast) {
      toast("网络异常，请稍后重试");
    }
    throw error;
  }
}

async function ensureRequestToken(context: AuthRecoveryContext, disableAuthRecovery = false) {
  if (!context.requestToken || !isBjtuServiceToken(context.requestToken)) {
    return context.requestToken;
  }

  if (disableAuthRecovery) {
    return context.requestToken;
  }

  if (!bjtuServiceSessionRecovery) {
    bjtuServiceSessionRecovery = recoverAuthentication(context, false).finally(() => {
      bjtuServiceSessionRecovery = null;
    });
  }

  const result = await bjtuServiceSessionRecovery;
  if (result.status === "ready") {
    return result.token;
  }

  finalizeAuthFailure(context, result.message, result.status === "manual_required");
}

async function retryAfterAuthRecovery<T>(options: RequestOptions, context: AuthRecoveryContext) {
  if (!authRecoveryPromise) {
    authRecoveryPromise = recoverAuthentication(context, !isBjtuServiceToken(context.requestToken)).finally(() => {
      authRecoveryPromise = null;
    });
  }

  const result = await authRecoveryPromise;
  if (result.status === "ready") {
    return request<T>({
      ...options,
      disableAuthRecovery: true,
    });
  }

  finalizeAuthFailure(context, result.message, result.status === "manual_required");
}

async function recoverAuthentication(
  context: AuthRecoveryContext,
  preferRefresh: boolean,
): Promise<AuthRecoveryResult> {
  const user = useUserStore();
  const isPseudoSession = isBjtuServiceToken(context.requestToken);
  const tokenType = isPseudoSession ? "pseudo_bjtu_service_token" : "service_session";

  console.warn(
    "auth_recovery_start",
    JSON.stringify({
      code: context.code,
      requestMethod: context.requestMethod,
      requestUrl: context.requestUrl,
      tokenType,
      preferRefresh,
    }),
  );

  if (!isPseudoSession && preferRefresh && user.refreshTokenValue) {
    try {
      await user.refreshBusinessSession();
      const token = uni.getStorageSync("token") || user.token;
      if (token) {
        return { status: "ready", token };
      }
    } catch (error) {
      console.warn(
        "auth_refresh_failed",
        JSON.stringify({
          requestMethod: context.requestMethod,
          requestUrl: context.requestUrl,
          message: error instanceof Error ? error.message : "unknown",
        }),
      );
    }
  }

  try {
    const result = await user.recoverSessionAfterAuthExpired();
    if (result.status === "ready") {
      const token = uni.getStorageSync("token") || user.token;
      if (token) {
        return { status: "ready", token };
      }
      return { status: "failed", message: SESSION_EXPIRED_MESSAGE };
    }

    return {
      status: result.status === "manual_required" ? "manual_required" : "failed",
      message: result.message || SESSION_EXPIRED_MESSAGE,
    };
  } catch (error) {
    return {
      status: "failed",
      message: error instanceof Error ? error.message : SESSION_EXPIRED_MESSAGE,
    };
  }
}

function finalizeAuthFailure(
  context: AuthRecoveryContext,
  message: string,
  preservePendingMisLogin = false,
): never {
  if (!context.skipToast) {
    toast(message);
  }

  const user = useUserStore();
  user.clearSession({ preservePendingMisLogin });

  console.warn(
    "auth_expired",
    JSON.stringify({
      code: context.code,
      requestMethod: context.requestMethod,
      requestUrl: context.requestUrl,
      tokenType: isBjtuServiceToken(context.requestToken) ? "pseudo_bjtu_service_token" : "service_session",
      preservePendingMisLogin,
    }),
  );

  if (!authRecoveryInProgress) {
    authRecoveryInProgress = true;
    redirectToLogin(undefined, message);
    setTimeout(() => {
      authRecoveryInProgress = false;
    }, 1200);
  }

  throw new BizError(context.code, message);
}

function resolveAuthExpiredCode(bodyCode: unknown, httpStatus: number) {
  const authCode = Number(bodyCode);
  if (AUTH_EXPIRED_CODES.has(authCode)) return authCode;
  if (httpStatus === 401 || httpStatus === 403) return httpStatus;
  return 10001;
}

async function sendRequest<T>(
  url: string,
  method: Method,
  data: Record<string, unknown> | undefined,
  header: Record<string, string>,
): Promise<UniApp.RequestSuccessCallbackResult> {
  const bridgeTimeoutMs = isBjtuServiceSandboxRuntime() ? 5000 : 1200;
  const bridge = isAbsoluteHttpUrl(url) ? await waitForBjtuServiceBridge(bridgeTimeoutMs) : null;

  if (bridge?.invoke) {
    const result = await bridge.invoke<NativeHttpResponse<T>>("app.http_request", {
      url,
      method,
      data,
      headers: header,
    });
    if (!result.ok) {
      throw new Error(result.error?.message || result.error?.code || "BJTU App HTTP 请求失败");
    }
    return {
      data: result.data.data,
      statusCode: result.data.statusCode,
      header: result.data.header || {},
      cookies: [],
    } as UniApp.RequestSuccessCallbackResult;
  }

  if (isBjtuServiceSandboxRuntime() && isAbsoluteHttpUrl(url)) {
    throw new Error("BJTU App 第三方服务 HTTP 桥接尚未就绪");
  }

  return (await uni.request({
    url,
    method,
    data,
    header,
    timeout: 15000,
  })) as UniApp.RequestSuccessCallbackResult;
}

function buildUrl(base: string, params?: Record<string, unknown>) {
  if (!params) return base;
  const query = Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== null && value !== "")
    .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
    .join("&");
  if (!query) return base;
  return `${base}${base.includes("?") ? "&" : "?"}${query}`;
}

function createId() {
  return `jj-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function isAbsoluteHttpUrl(value: string) {
  return /^https?:\/\//i.test(value);
}

function isBjtuServiceSandboxRuntime() {
  return typeof window !== "undefined" && /\.third-party\.bjtu-mis\.local$/i.test(window.location.hostname);
}
