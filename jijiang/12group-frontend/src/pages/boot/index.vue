<template>
  <view class="page-shell boot-page">
    <view class="hero-card boot-card">
      <text class="eyebrow">Campus Craft Market</text>
      <text class="logo">技匠</text>
      <text class="title">正在自动登录</text>
      <text class="desc">{{ loadingMessage }}</text>
      <view class="loading-pill boot-loading">
        <view class="loading-dot" />
        <text>请稍候</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { HOME_ROUTE, navigateAfterLogin, redirectToLogin } from "@/utils/nav";
import { useUserStore } from "@/store/user";

const user = useUserStore();
const loadingMessage = ref("正在验证 BJTU 身份并连接技匠服务...");

onLoad(async (query) => {
  const redirect = query?.redirect ? decodeURIComponent(String(query.redirect)) : HOME_ROUTE;

  try {
    const result = await user.bootstrapStartupSession();
    if (result.status === "ready") {
      loadingMessage.value = "登录成功，正在进入首页...";
      await navigateAfterLogin(redirect);
      return;
    }

    redirectToLogin(redirect, result.message || "自动登录失败，请完成验证码登录");
  } catch (error) {
    const message = error instanceof Error ? error.message : "自动登录失败，请手动登录";
    redirectToLogin(redirect, message);
  }
});
</script>

<style scoped>
.boot-page {
  display: flex;
  align-items: center;
  justify-content: center;
}

.boot-card {
  display: flex;
  flex-direction: column;
  gap: 18rpx;
  width: 100%;
  min-height: 420rpx;
  justify-content: center;
}

.eyebrow {
  font-size: 22rpx;
  letter-spacing: 3rpx;
  color: var(--color-text-secondary);
}

.logo {
  color: var(--color-dark);
  font-size: 88rpx;
  font-weight: 900;
}

.title {
  color: var(--color-dark);
  font-size: 40rpx;
  font-weight: 900;
}

.desc {
  color: var(--color-text-muted);
  font-size: 28rpx;
  line-height: 1.7;
}

.boot-loading {
  margin-top: 8rpx;
}
</style>
