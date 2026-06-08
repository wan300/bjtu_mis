import { request } from "@/api/request";
import type { LoginResult, MisCaptcha, MisLoginResult } from "@/types/domain";

export function misAutoLogin(loginName: string, password: string) {
  return request<MisLoginResult>({
    url: "/api/auth/mis/auto-login",
    method: "POST",
    data: { loginName, password },
    skipAuth: true,
  });
}

export function misManualLogin(challengeId: string, loginName: string, password: string, captcha: string) {
  return request<MisLoginResult>({
    url: "/api/auth/mis/manual-login",
    method: "POST",
    data: { challengeId, loginName, password, captcha },
    skipAuth: true,
  });
}

export function fetchMisCaptcha() {
  return request<{ captcha: MisCaptcha }>({
    url: "/api/auth/mis/captcha",
    method: "GET",
    skipAuth: true,
  });
}

export function refreshToken() {
  return request<LoginResult>({
    url: "/api/auth/refresh",
    method: "POST",
  });
}

export function switchRole(targetRole: 1 | 2) {
  return request<LoginResult>({
    url: "/api/auth/switch-role",
    method: "POST",
    data: { targetRole },
  });
}
