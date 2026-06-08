import { defineStore } from "pinia";
import { fetchMisCaptcha, misAutoLogin, misManualLogin, refreshToken, switchRole } from "@/api/auth";
import { bjtuServiceTokenFor, createBjtuServiceUserInfo, isBjtuServiceToken } from "@/api/bjtu-service";
import { getMe, updateProfile as updateUserProfile } from "@/api/user";
import { useNotificationStore } from "@/store/notification";
import type { MisCaptcha, MisLoginResult, Role, UserInfo } from "@/types/domain";

interface UserState {
  token: string;
  refreshTokenValue: string;
  userInfo: UserInfo | null;
  currentRole: Role;
}

export const useUserStore = defineStore("user", {
  state: (): UserState => ({
    token: uni.getStorageSync("token") || "",
    refreshTokenValue: uni.getStorageSync("refreshToken") || "",
    userInfo: uni.getStorageSync("userInfo") || null,
    currentRole: (uni.getStorageSync("currentRole") || 1) as Role,
  }),
  getters: {
    isLogin: (state) => Boolean(state.token),
    isVerified: (state) => Number(state.userInfo?.verifyStatus) === 2,
    isSellerVerified: (state) => Number(state.userInfo?.isSellerVerified) === 1,
    hasDeposit: (state) => Number(state.userInfo?.depositPaid) === 1,
    campusId: (state) => Number(state.userInfo?.campusId || 1),
    isBjtuServiceSession: (state) => isBjtuServiceToken(state.token),
  },
  actions: {
    async loginWithBjtuServiceIdentity() {
      const userInfo = await createBjtuServiceUserInfo();
      const token = bjtuServiceTokenFor(userInfo.id);
      this.applyLogin(token, token, userInfo);
      return userInfo;
    },
    async loginByMis(loginName: string, password: string) {
      const result = await misAutoLogin(loginName, password);
      this.applyMisLogin(result);
      return result;
    },
    async loginByMisCaptcha(challengeId: string, loginName: string, password: string, captcha: string) {
      const result = await misManualLogin(challengeId, loginName, password, captcha);
      this.applyMisLogin(result);
      return result;
    },
    async fetchMisCaptcha(): Promise<MisCaptcha> {
      const result = await fetchMisCaptcha();
      return result.captcha;
    },
    async refreshUserInfo() {
      if (isBjtuServiceToken(this.token)) {
        await this.loginWithBjtuServiceIdentity();
        return;
      }
      try {
        const result = await refreshToken();
        this.applyLogin(result.accessToken, result.refreshToken, result.userInfo);
      } catch {
        const user = await getMe();
        this.userInfo = user;
        uni.setStorageSync("userInfo", user);
      }
    },
    async switchIdentity(role: Role) {
      if (isBjtuServiceToken(this.token)) {
        this.currentRole = role;
        if (this.userInfo) this.applyUserInfo({ ...this.userInfo, currentRole: role });
        uni.setStorageSync("currentRole", role);
        return;
      }
      const result = await switchRole(role);
      this.applyLogin(result.accessToken, result.refreshToken, result.userInfo);
    },
    async updateProfile(nickname?: string, avatarUrl?: string) {
      if (isBjtuServiceToken(this.token) && this.userInfo) {
        const updated = { ...this.userInfo, nickname: nickname || this.userInfo.nickname, avatarUrl };
        this.applyUserInfo(updated);
        return updated;
      }
      const result = await updateUserProfile({ nickname, avatarUrl });
      this.applyUserInfo(result);
      return result;
    },
    applyLogin(token: string, refresh: string, info: UserInfo) {
      this.token = token;
      this.refreshTokenValue = refresh;
      this.applyUserInfo(info);
      this.currentRole = info.currentRole || this.currentRole;
      uni.setStorageSync("token", token);
      uni.setStorageSync("refreshToken", refresh);
      uni.setStorageSync("currentRole", this.currentRole);
      if (!isBjtuServiceToken(token)) {
        useNotificationStore().startPolling();
      }
    },
    applyUserInfo(info: UserInfo) {
      this.userInfo = info;
      uni.setStorageSync("userInfo", info);
    },
    applyMisLogin(result: MisLoginResult) {
      if (result.status !== "ready") return;
      if (!result.accessToken || !result.refreshToken || !result.userInfo) return;
      this.applyLogin(result.accessToken, result.refreshToken, result.userInfo);
    },
    updateDepositPaid(paid: boolean) {
      if (this.userInfo) {
        this.userInfo = { ...this.userInfo, depositPaid: paid ? 1 : 0 };
        uni.setStorageSync("userInfo", this.userInfo);
      }
    },
    updateVerifyStatus(status: number) {
      if (this.userInfo) {
        this.userInfo = { ...this.userInfo, verifyStatus: status, isSellerVerified: status === 2 ? 1 : 0 };
        uni.setStorageSync("userInfo", this.userInfo);
      }
    },
  },
});
