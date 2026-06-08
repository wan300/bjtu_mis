import { resolveApiUrl } from "@/utils/env";
import { isBjtuServiceToken, waitForBjtuServiceBridge } from "@/api/bjtu-service";
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

export async function request<T>(options: RequestOptions): Promise<T> {
  const method = options.method || "GET";
  const header: Record<string, string> = {
    "Content-Type": "application/json;charset=UTF-8",
    "X-Client-Version": "miniapp/1.0.0",
    ...options.header,
  };
  const token = uni.getStorageSync("token");
  if (!options.skipAuth && token) header.Authorization = `Bearer ${token}`;
  if (method !== "GET" || options.idempotent) header["X-Idempotency-Key"] = createId();

  const url = buildUrl(resolveApiUrl(options.url), options.params);
  try {
    const res = await sendRequest<T>(url, method, options.data, header);
    const body = res.data as ApiResult<T>;
    if (body?.code === 0) return body.data;
    if (body?.code === 10001 || body?.code === 10002) {
      if (isBjtuServiceToken(token)) {
        const message = body?.message || "该操作需要技匠后端接入 BJTU 身份直登";
        if (!options.skipToast) toast(message);
        throw new BizError(body?.code || 10001, message);
      }
      uni.removeStorageSync("token");
      uni.removeStorageSync("refreshToken");
      uni.removeStorageSync("userInfo");
      uni.navigateTo({ url: "/pages/login/index" });
    }
    const message = body?.message || "请求失败";
    if (!options.skipToast) toast(message);
    throw new BizError(body?.code || 90001, message);
  } catch (error) {
    if (!(error instanceof BizError) && !options.skipToast) {
      toast("网络异常，请稍后重试");
    }
    throw error;
  }
}

async function sendRequest<T>(
  url: string,
  method: Method,
  data: Record<string, unknown> | undefined,
  header: Record<string, string>
): Promise<UniApp.RequestSuccessCallbackResult> {
  const bridgeTimeoutMs = isBjtuServiceSandboxRuntime() ? 5000 : 1200;
  const bridge = isAbsoluteHttpUrl(url) ? await waitForBjtuServiceBridge(bridgeTimeoutMs) : null;
  if (bridge?.invoke) {
    const result = await bridge.invoke<NativeHttpResponse<T>>("app.http_request", {
      url,
      method,
      data,
      headers: header
    });
    if (!result.ok) {
      throw new Error(result.error?.message || result.error?.code || "BJTU App HTTP 请求失败");
    }
    return {
      data: result.data.data,
      statusCode: result.data.statusCode,
      header: result.data.header || {},
      cookies: []
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
