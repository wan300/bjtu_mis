import { request } from "@/api/request";
import type { UserInfo, VerifyResult } from "@/types/domain";

export interface VerifySubmitPayload {
  campusId: number;
  certType: number;
  certImageUrl: string;
  realName: string;
  studentNo: string;
}

export interface ProfileUpdatePayload {
  nickname?: string;
  avatarUrl?: string;
}

export function getMe() {
  return request<UserInfo>({ url: "/api/user/me" });
}

export function updateProfile(data: ProfileUpdatePayload) {
  return request<UserInfo>({
    url: "/api/user/profile",
    method: "POST",
    data: data as unknown as Record<string, unknown>,
  });
}

export function submitVerify(data: VerifySubmitPayload) {
  return request<VerifyResult>({ url: "/api/user/verify/submit", method: "POST", data: data as unknown as Record<string, unknown> });
}

export function getVerifyStatus() {
  return request<{ status: number; isSellerVerified: number }>({ url: "/api/user/verify/status" });
}
