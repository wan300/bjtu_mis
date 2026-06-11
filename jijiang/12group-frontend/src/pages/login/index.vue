<template>
  <view class="page-shell login">
    <view class="brand hero-card">
      <text class="eyebrow">Campus Craft Market</text>
      <text class="logo">技匠</text>
      <text class="slogan">使用 BJTU MIS 身份进入校园技能服务。</text>
    </view>

    <view class="surface-card panel">
      <text class="title">BJTU MIS 登录</text>
      <text class="desc">{{ panelDesc }}</text>

      <view v-if="manualMode" class="form">
        <input v-model="loginName" class="field-input" placeholder="账号 / 学号" />
        <input v-model="password" class="field-input" password placeholder="MIS 密码" />
        <view v-if="manualRequired" class="captcha-row">
          <input
            v-model="captchaText"
            class="field-input captcha-input"
            placeholder="请输入验证码"
            maxlength="6"
          />
          <image
            v-if="captcha?.imageDataUrl"
            class="captcha-img"
            :src="captcha.imageDataUrl"
            mode="aspectFit"
            @click="reloadCaptcha"
          />
          <button v-else class="ghost-btn mini" :loading="captchaLoading" @click="reloadCaptcha">
            获取验证码
          </button>
        </view>
      </view>

      <button
        v-if="!manualMode"
        class="primary-btn"
        :loading="loading"
        :disabled="loading"
        @click="startAutoLogin"
      >
        自动登录（推荐）
      </button>

      <button
        v-else
        class="primary-btn"
        :loading="loading"
        :disabled="!canSubmitManual"
        @click="loginWithCredentials"
      >
        {{ manualRequired ? "提交验证码并登录" : "使用 MIS 账号密码登录" }}
      </button>

      <button v-if="manualMode" class="ghost-btn mini" :loading="captchaLoading" @click="reloadCaptcha">
        {{ manualRequired ? "更换验证码" : "获取验证码" }}
      </button>
      <button v-else class="ghost-btn" :disabled="loading" @click="enterManualMode">
        账号密码手动登录
      </button>

      <button class="ghost-btn" :disabled="loading" @click="closeService">返回服务列表</button>

      <text v-if="errorMessage" class="error">{{ errorMessage }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { closeBjtuService, getBjtuServiceCredentials } from "@/api/bjtu-service";
import { useUserStore } from "@/store/user";
import { navigateAfterLogin } from "@/utils/nav";
import type { MisCaptcha, MisLoginResult } from "@/types/domain";
import { toast } from "@/utils/toast";

const user = useUserStore();
const redirect = ref("/pages/tabbar/home/index");
const loading = ref(false);
const captchaLoading = ref(false);
const manualMode = ref(false);
const manualRequired = ref(false);
const errorMessage = ref("");
const loginName = ref("");
const password = ref("");
const captchaText = ref("");
const captcha = ref<MisCaptcha | null>(null);

const panelDesc = computed(() => {
  if (manualRequired.value) return "请输入验证码以继续完成登录。";
  if (manualMode.value) return "请填写 MIS 账号密码；如果需要验证码，会在当前页面继续完成。";
  return "启动自动登录失败后，可切换到手动输入验证码继续登录。";
});

const canSubmitManual = computed(() => {
  if (!loginName.value.trim() || !password.value) return false;
  if (manualRequired.value) {
    return Boolean(captcha.value?.challengeId && captchaText.value.trim());
  }
  return true;
});

onLoad(async (query) => {
  if (query?.redirect) {
    redirect.value = decodeURIComponent(String(query.redirect));
  }
  if (query?.message) {
    errorMessage.value = decodeURIComponent(String(query.message));
  }

  if (user.isLogin) {
    await finishLogin();
    return;
  }

  hydrateFromPendingState();
});

async function startAutoLogin() {
  if (loading.value) return;
  loading.value = true;
  errorMessage.value = "";
  manualMode.value = false;
  manualRequired.value = false;
  captcha.value = null;
  captchaText.value = "";

  try {
    const result = await user.loginWithBjtuServiceIdentity({ allowManualFallback: true });
    if (result.status === "ready") {
      toast("自动登录成功", "success");
      await finishLogin();
      return;
    }

    if (result.status === "manual_required") {
      await enterManualModeFromResult(result);
      return;
    }

    errorMessage.value = result.message || "自动登录失败，请改用手动登录。";
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "自动登录失败，请稍后重试。";
  } finally {
    loading.value = false;
  }
}

async function loginWithCredentials() {
  if (loading.value || !canSubmitManual.value) return;
  loading.value = true;
  errorMessage.value = "";

  try {
    const result = manualRequired.value && captcha.value?.challengeId
      ? await user.loginByMisCaptcha(
          captcha.value.challengeId,
          loginName.value.trim(),
          password.value,
          captchaText.value.trim(),
        )
      : await user.loginByMis(loginName.value.trim(), password.value);
    await handleMisResult(result);
  } catch {
    // request layer already provides the failure toast.
  } finally {
    loading.value = false;
  }
}

async function reloadCaptcha() {
  if (captchaLoading.value) return;
  captchaLoading.value = true;
  errorMessage.value = "";

  try {
    captcha.value = await user.fetchMisCaptcha();
    captchaText.value = "";
    manualMode.value = true;
    manualRequired.value = true;
  } catch {
    errorMessage.value = "获取验证码失败，请重试。";
  } finally {
    captchaLoading.value = false;
  }
}

async function enterManualMode() {
  manualMode.value = true;
  if (!user.pendingMisLogin) {
    manualRequired.value = false;
  }
  await fillCredentialsFromService();
  hydrateFromPendingState();
}

async function enterManualModeFromResult(result: MisLoginResult) {
  if (result.message) {
    errorMessage.value = result.message;
    toast(result.message);
  }
  manualMode.value = true;
  manualRequired.value = true;
  hydrateFromPendingState();
  await fillCredentialsFromService();
  if (!captcha.value?.imageDataUrl) {
    void reloadCaptcha();
  }
}

async function handleMisResult(result: MisLoginResult) {
  if (result.status === "ready") {
    toast("登录成功", "success");
    await finishLogin();
    return;
  }

  if (result.status === "manual_required") {
    await enterManualModeFromResult(result);
    return;
  }

  const message = result.message || "MIS 登录失败，请核对账号信息。";
  errorMessage.value = message;
  toast(message);
}

function hydrateFromPendingState() {
  const pending = user.pendingMisLogin;
  if (!pending) return;

  manualMode.value = true;
  manualRequired.value = true;
  loginName.value = pending.loginName || loginName.value;
  password.value = pending.password || password.value;
  captcha.value = pending.captcha || captcha.value;
  captchaText.value = "";
  if (pending.message) {
    errorMessage.value = pending.message;
  }
  if (!captcha.value?.imageDataUrl) {
    void reloadCaptcha();
  }
}

async function fillCredentialsFromService() {
  try {
    const credentials = await getBjtuServiceCredentials();
    const account =
      credentials.login_name ||
      credentials.loginName ||
      credentials.student_id ||
      credentials.studentId ||
      credentials.account ||
      "";
    if (!loginName.value && account) {
      loginName.value = account;
    }
    if (!password.value && credentials.password) {
      password.value = credentials.password;
    }
  } catch {
    // Keep current input values and allow manual entry.
  }
}

async function finishLogin() {
  await navigateAfterLogin(redirect.value);
}

async function closeService() {
  try {
    await closeBjtuService();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "无法返回服务列表";
  }
}
</script>

<style scoped>
.login {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 34rpx;
}

.brand {
  min-height: 430rpx;
}

.eyebrow {
  font-size: 22rpx;
  letter-spacing: 3rpx;
  color: var(--color-text-secondary);
}

.logo {
  display: block;
  margin-top: 58rpx;
  font-size: 86rpx;
  font-weight: 900;
  color: var(--color-dark);
}

.slogan {
  display: block;
  width: 520rpx;
  margin-top: 28rpx;
  font-size: 32rpx;
  font-weight: 700;
  line-height: 1.55;
  color: var(--color-text);
}

.panel {
  padding: 38rpx;
}

.title {
  display: block;
  color: var(--color-dark);
  font-size: 38rpx;
  font-weight: 900;
}

.desc {
  display: block;
  margin: 16rpx 0 34rpx;
  color: var(--color-text-muted);
  font-size: 26rpx;
  line-height: 1.7;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
  margin-bottom: 20rpx;
}

.field-input {
  width: 100%;
  box-sizing: border-box;
}

.captcha-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.captcha-input {
  flex: 1;
}

.captcha-img {
  width: 210rpx;
  height: 86rpx;
  border: var(--stroke);
  border-radius: var(--radius-sm);
  background: var(--color-card-muted);
}

.ghost-btn {
  margin-top: 20rpx;
}

.mini {
  margin-top: 0;
}

.error {
  display: block;
  margin-top: 22rpx;
  color: #dc2626;
  font-size: 24rpx;
  line-height: 1.6;
}
</style>
