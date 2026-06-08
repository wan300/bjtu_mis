<template>
  <view class="page-shell login">
    <view class="brand hero-card">
      <text class="eyebrow">Campus Craft Market</text>
      <text class="logo">技匠</text>
      <text class="slogan">使用 BJTU App 身份进入校园技能服务。</text>
    </view>

    <view class="surface-card panel">
      <text class="title">BJTU 身份登录</text>
      <text class="desc">{{ panelDesc }}</text>

      <view v-if="user.userInfo" class="identity-card">
        <text class="identity-name">{{ user.userInfo.nickname }}</text>
        <text class="identity-meta">{{ user.userInfo.campusName || "北京交通大学" }}</text>
      </view>

      <button class="primary-btn" :loading="loading" :disabled="loading" @click="loginWithBjtu(false)">
        {{ user.isLogin ? "进入技匠" : "使用 BJTU 身份进入" }}
      </button>
      <button class="ghost-btn" :disabled="loading" @click="closeService">返回服务列表</button>

      <text v-if="errorMessage" class="error">{{ errorMessage }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { closeBjtuService } from "@/api/bjtu-service";
import { useUserStore } from "@/store/user";
import { toast } from "@/utils/toast";

const user = useUserStore();
const loading = ref(false);
const redirect = ref("/pages/tabbar/home/index");
const errorMessage = ref("");

const panelDesc = computed(() => {
  if (errorMessage.value) return "请确认已在 BJTU MIS App 内打开，并已授权身份读取权限。";
  if (user.userInfo) return "已读取 BJTU App 授权身份，不需要微信或 MIS 密码。";
  return "正在等待 BJTU App 提供授权身份。";
});

onLoad((query) => {
  if (query?.redirect) redirect.value = decodeURIComponent(String(query.redirect));
  void loginWithBjtu(true);
});

async function loginWithBjtu(silent = false) {
  if (loading.value) return;
  loading.value = true;
  errorMessage.value = "";
  try {
    await user.loginWithBjtuServiceIdentity();
    if (!silent) toast("欢迎来到技匠", "success");
    await finishLogin();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "BJTU 身份登录失败";
  } finally {
    loading.value = false;
  }
}

async function finishLogin() {
  await new Promise<void>((resolve, reject) => {
    const options = { url: redirect.value, success: () => resolve(), fail: reject };
    if (redirect.value.startsWith("/pages/tabbar/")) uni.switchTab(options);
    else uni.redirectTo(options);
  });
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

.identity-card {
  margin-bottom: 28rpx;
  padding: 24rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  background: #ffffff;
  box-shadow: var(--shadow-sm);
}

.identity-name,
.identity-meta {
  display: block;
}

.identity-name {
  color: var(--color-dark);
  font-size: 34rpx;
  font-weight: 900;
}

.identity-meta {
  margin-top: 8rpx;
  color: var(--color-text-muted);
  font-size: 24rpx;
}

.ghost-btn {
  margin-top: 20rpx;
}

.error {
  display: block;
  margin-top: 22rpx;
  color: #dc2626;
  font-size: 24rpx;
  line-height: 1.6;
}
</style>
