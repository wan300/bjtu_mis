import { defineStore } from "pinia";
import { fetchMisCaptcha, misAutoLogin, misManualLogin, refreshToken, switchRole } from "@/api/auth";
import { getBjtuServiceCredentials, isBjtuServiceToken } from "@/api/bjtu-service";
import { getMe, updateProfile as updateUserProfile } from "@/api/user";
import { useNotificationStore } from "@/store/notification";
import type { LoginResult, MisCaptcha, MisLoginResult, Role, UserInfo } from "@/types/domain";

interface PendingMisLogin {
  loginName: string;
  password: string;
  captcha: MisCaptcha | null;
  message: string;
}

interface StartupBootstrapResult {
  status: "ready" | "manual_required" | "failed";
  message?: string;
}

interface BjtuServiceLoginOptions {
  allowManualFallback?: boolean;
}

interface UserState {
  token: string;
  refreshTokenValue: string;
  userInfo: UserInfo | null;
  currentRole: Role;
  pendingMisLogin: PendingMisLogin | null;
}

type BjtuServiceCredentialsPayload = Awaited<ReturnType<typeof getBjtuServiceCredentials>>;

const MANUAL_REQUIRED_MESSAGE = "需要验证码，请在当前页面继续完成登录。";
const AUTO_LOGIN_FAILED_MESSAGE = "自动登录失败，请手动登录。";
const REAUTHORIZE_MESSAGE = "BJTU App 身份不可用，请重新打开服务并完成授权。";

function normalizeLoginName(credentials: BjtuServiceCredentialsPayload) {
  return (
    credentials.login_name ||
    credentials.loginName ||
    credentials.student_id ||
    credentials.studentId ||
    credentials.account ||
    ""
  ).trim();
}

export const useUserStore = defineStore("user", {
  state: (): UserState => ({
    token: uni.getStorageSync("token") || "",
    refreshTokenValue: uni.getStorageSync("refreshToken") || "",
    userInfo: uni.getStorageSync("userInfo") || null,
    currentRole: (uni.getStorageSync("currentRole") || 1) as Role,
    pendingMisLogin: null,
  }),
  getters: {
    isLogin: (state) => Boolean(state.token) && !isBjtuServiceToken(state.token),
    isVerified: (state) => Number(state.userInfo?.verifyStatus) === 2,
    isSellerVerified: (state) => Number(state.userInfo?.isSellerVerified) === 1,
    hasDeposit: (state) => Number(state.userInfo?.depositPaid) === 1,
    campusId: (state) => Number(state.userInfo?.campusId || 1),
    isBjtuServiceSession: (state) => isBjtuServiceToken(state.token),
  },
  actions: {
    async bootstrapStartupSession(): Promise<StartupBootstrapResult> {
      this.clearPendingMisLogin();

      if (this.token && !isBjtuServiceToken(this.token)) {
        return { status: "ready" };
      }

      if (isBjtuServiceToken(this.token)) {
        this.clearSession();
      }

      const maxAttempts = 2;
      let lastMessage = "";

      for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
        try {
          const result = await this.loginWithBjtuServiceIdentity({ allowManualFallback: true });
          if (result.status === "ready") {
            return { status: "ready", message: result.message };
          }
          if (result.status === "manual_required") {
            return {
              status: "manual_required",
              message: result.message || MANUAL_REQUIRED_MESSAGE,
            };
          }
        } catch (error) {
          lastMessage = error instanceof Error ? error.message : AUTO_LOGIN_FAILED_MESSAGE;
          if (attempt < maxAttempts - 1) {
            await new Promise((resolve) => setTimeout(resolve, 300));
            continue;
          }
        }
      }

      return {
        status: "failed",
        message: lastMessage || AUTO_LOGIN_FAILED_MESSAGE,
      };
    },

    async loginWithBjtuServiceIdentity(options: BjtuServiceLoginOptions = {}): Promise<MisLoginResult> {
      const { allowManualFallback = false } = options;
      const credentials = await this.loadBjtuServiceCredentials();
      const result = await misAutoLogin(credentials.loginName, credentials.password);
      this.captureMisLoginResult(result, credentials);

      if (result.status === "ready") {
        this.applyMisLogin(result);
        return result;
      }

      if (allowManualFallback && result.status === "manual_required") {
        return result;
      }

      throw new Error(result.message || AUTO_LOGIN_FAILED_MESSAGE);
    },

    async recoverSessionAfterAuthExpired() {
      return this.loginWithBjtuServiceIdentity({ allowManualFallback: true });
    },

    async refreshBusinessSession(): Promise<LoginResult> {
      const result = await refreshToken();
      this.applyLogin(result.accessToken, result.refreshToken, result.userInfo);
      return result;
    },

    async loginByMis(loginName: string, password: string) {
      const result = await misAutoLogin(loginName, password);
      this.captureMisLoginResult(result, {
        loginName: loginName.trim(),
        password,
      });
      this.applyMisLogin(result);
      return result;
    },

    async loginByMisCaptcha(challengeId: string, loginName: string, password: string, captcha: string) {
      const result = await misManualLogin(challengeId, loginName, password, captcha);
      this.captureMisLoginResult(result, {
        loginName: loginName.trim(),
        password,
      });
      this.applyMisLogin(result);
      return result;
    },

    async fetchMisCaptcha(): Promise<MisCaptcha> {
      const result = await fetchMisCaptcha();
      return result.captcha;
    },

    async loadBjtuServiceCredentials() {
      let credentials: BjtuServiceCredentialsPayload;
      try {
        credentials = await getBjtuServiceCredentials();
      } catch {
        throw new Error(REAUTHORIZE_MESSAGE);
      }

      const loginName = normalizeLoginName(credentials);
      const password = credentials.password || "";
      if (!loginName || !password) {
        throw new Error(REAUTHORIZE_MESSAGE);
      }

      return {
        loginName,
        password,
      };
    },

    setPendingMisLogin(payload: PendingMisLogin) {
      this.pendingMisLogin = payload;
    },

    clearPendingMisLogin() {
      this.pendingMisLogin = null;
    },

    async refreshUserInfo() {
      if (this.isBjtuServiceSession) {
        const result = await this.recoverSessionAfterAuthExpired();
        if (result.status !== "ready") {
          throw new Error(result.message || MANUAL_REQUIRED_MESSAGE);
        }
        return;
      }

      const user = await getMe();
      this.applyUserInfo(user);
    },

    async switchIdentity(role: Role) {
      if (isBjtuServiceToken(this.token)) {
        this.currentRole = role;
        if (this.userInfo) {
          this.applyUserInfo({ ...this.userInfo, currentRole: role });
        }
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
      this.clearPendingMisLogin();
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

    clearSession(options: { preservePendingMisLogin?: boolean } = {}) {
      const { preservePendingMisLogin = false } = options;
      this.token = "";
      this.refreshTokenValue = "";
      this.userInfo = null;
      this.currentRole = 1;
      if (!preservePendingMisLogin) {
        this.clearPendingMisLogin();
      }
      uni.removeStorageSync("token");
      uni.removeStorageSync("refreshToken");
      uni.removeStorageSync("userInfo");
      uni.removeStorageSync("bjtuServiceProfile");
      uni.setStorageSync("currentRole", 1);
      useNotificationStore().stopPolling(true);
    },

    updateDepositPaid(paid: boolean) {
      if (this.userInfo) {
        this.userInfo = { ...this.userInfo, depositPaid: paid ? 1 : 0 };
        uni.setStorageSync("userInfo", this.userInfo);
      }
    },

    updateVerifyStatus(status: number) {
      if (this.userInfo) {
        this.userInfo = {
          ...this.userInfo,
          verifyStatus: status,
          isSellerVerified: status === 2 ? 1 : 0,
        };
        uni.setStorageSync("userInfo", this.userInfo);
      }
    },

    captureMisLoginResult(
      result: MisLoginResult,
      credentials: {
        loginName: string;
        password: string;
      },
    ) {
      if (result.status === "manual_required") {
        this.setPendingMisLogin({
          loginName: credentials.loginName,
          password: credentials.password,
          captcha: result.captcha || null,
          message: result.message || MANUAL_REQUIRED_MESSAGE,
        });
        return;
      }

      this.clearPendingMisLogin();
    },
  },
});
