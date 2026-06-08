<template>
  <view class="page-shell page-with-tab">
    <view class="hero-card profile">
      <image v-if="user.userInfo?.avatarUrl" class="avatar avatar-img" :src="user.userInfo.avatarUrl" mode="aspectFill" />
      <view v-else class="avatar">{{ (user.userInfo?.nickname || "技").slice(0, 1) }}</view>
      <view class="info">
        <text class="name">{{ user.userInfo?.nickname || "未登录同学" }}</text>
        <text class="sub">{{ user.userInfo?.campusName || "登录后同步校区与信誉" }}</text>
      </view>
      <button v-if="!user.isLogin" class="login-btn" @click="login">登录</button>
    </view>

    <view class="stats surface-card">
      <view class="stat-col">
        <text class="num">{{ user.userInfo?.creditScore || 100 }}</text>
        <text class="label">信誉分</text>
      </view>
      <view class="stat-divider" />
      <view class="stat-col">
        <text class="num">{{ verifyText }}</text>
        <text class="label">实名状态</text>
      </view>
      <view class="stat-divider" />
      <view class="stat-col">
        <text class="num">{{ user.currentRole === 2 ? "讲师" : "买家" }}</text>
        <text class="label">当前身份</text>
      </view>
    </view>

    <view class="menu surface-card">
      <view v-for="item in menus" :key="item.text" class="row" @click="open(item.url)">
        <view class="row-left">
          <view class="row-icon" :style="{ background: item.bg }">
            <text class="row-shape">{{ item.shape }}</text>
          </view>
          <view class="row-label">
            <text>{{ item.text }}</text>
            <view v-if="item.text === '通知中心' && unreadBadge" class="row-badge">{{ unreadBadge }}</view>
          </view>
        </view>
        <text class="arrow">›</text>
      </view>
      <view class="row" @click="switchMode">
        <view class="row-left">
          <view class="row-icon switch-icon">
            <text class="row-shape">⇄</text>
          </view>
          <text>{{ user.currentRole === 2 ? "切回买家模式" : "进入讲师模式" }}</text>
        </view>
        <text class="arrow">›</text>
      </view>
    </view>
    <ji-tab-bar />
  </view>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { closeBjtuService } from "@/api/bjtu-service";
import JiTabBar from "@/components/ji-tab-bar.vue";
import { useNotificationStore } from "@/store/notification";
import { useUserStore } from "@/store/user";

const user = useUserStore();
const notification = useNotificationStore();
const verifyText = computed(() => (user.isVerified ? "已实名" : user.userInfo?.verifyStatus === 1 ? "审核中" : "未实名"));
const unreadBadge = computed(() => {
  if (!user.isLogin || notification.unreadCount <= 0) return "";
  return notification.unreadCount > 99 ? "99+" : String(notification.unreadCount);
});
const menus = [
  { text: "我的订单", url: "/pages/order/list", shape: "■", bg: "#5A9AFC" },
  { text: "通知中心", url: "/pages/notification/index", shape: "🔔", bg: "#F78DA7" },
  { text: "实名认证", url: "/pages/user/verify", shape: "◆", bg: "#74D6C1" },
  { text: "个人资料", url: "/pages/user/profile", shape: "●", bg: "#5A9AFC" },
  { text: "我的信誉", url: "/pages/user/my-credit", shape: "★", bg: "#F9E58A" },
  { text: "我的服务", url: "/pages/user/my-service", shape: "⬢", bg: "#74D6C1" },
  { text: "收益中心", url: "/pages/seller/income", shape: "▲", bg: "#F9E58A" },
  { text: "保证金", url: "/pages/seller/deposit", shape: "⬖", bg: "#F78DA7" },
  { text: "退出第三方服务", url: "__closeThirdPartyService", shape: "✕", bg: "#9CA3AF" },
];

function login() {
  uni.navigateTo({ url: "/pages/login/index" });
}

function open(url: string) {
  if (url === "__closeThirdPartyService") {
    void closeBjtuService();
    return;
  }
  if (!user.isLogin && !url.includes("profile")) {
    login();
    return;
  }
  uni.navigateTo({ url });
}

async function switchMode() {
  if (!user.isLogin) return login();
  const targetRole = user.currentRole === 2 ? 1 : 2;
  try {
    await user.switchIdentity(targetRole);
  } catch {
    return;
  }
  if (targetRole === 1) {
    uni.switchTab({ url: "/pages/tabbar/home/index" });
    return;
  }
  uni.reLaunch({ url: "/pages/tabbar/seller-desk/index" });
}

onShow(() => {
  if (user.isLogin && !user.isBjtuServiceSession) {
    notification.refreshUnreadCount().catch(() => undefined);
  }
});
</script>

<style scoped>
.profile {
  display: flex;
  align-items: center;
  gap: 24rpx;
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 116rpx;
  height: 116rpx;
  border: var(--stroke);
  border-radius: var(--radius-lg);
  color: var(--color-primary);
  font-size: 44rpx;
  font-weight: 900;
  background: var(--color-card);
}

.avatar-img {
  display: block;
}

.info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10rpx;
}

.name {
  font-size: 34rpx;
  font-weight: 900;
  color: var(--color-dark);
}

.sub {
  font-size: 24rpx;
  color: var(--color-text-secondary);
}

.login-btn {
  border: var(--stroke);
  border-radius: var(--radius-sm);
  padding: 16rpx 28rpx;
  color: var(--color-primary);
  font-weight: 900;
  background: var(--color-card);
  box-shadow: var(--shadow-sm);
}

.stats {
  display: flex;
  justify-content: space-around;
  align-items: center;
  margin-top: 24rpx;
  padding: 30rpx 12rpx;
  text-align: center;
}

.stat-col {
  flex: 1;
}

.stat-divider {
  width: 0;
  height: 56rpx;
  border-left: 2px dashed #CBD5E1;
  flex-shrink: 0;
}

.num,
.label {
  display: block;
}

.num {
  color: var(--color-dark);
  font-size: 30rpx;
  font-weight: 900;
}

.label {
  margin-top: 8rpx;
  color: var(--color-text-muted);
  font-size: 22rpx;
}

.menu {
  margin-top: 24rpx;
  padding: 6rpx 24rpx;
}

.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 26rpx 8rpx;
  border-bottom: 1px solid #E2E8F0;
  color: var(--color-dark);
  font-size: 28rpx;
  font-weight: 700;
}

.row:last-child {
  border-bottom: 0;
}

.row-left {
  display: flex;
  align-items: center;
  gap: 18rpx;
}

.row-label {
  display: flex;
  align-items: center;
  gap: 14rpx;
  min-width: 0;
}

.row-badge {
  min-width: 34rpx;
  height: 34rpx;
  padding: 0 10rpx;
  border-radius: 999rpx;
  color: #fff;
  font-size: 20rpx;
  font-weight: 900;
  line-height: 34rpx;
  text-align: center;
  background: var(--color-accent-3);
}

.row-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48rpx;
  height: 48rpx;
  border: 2px solid #1A1A1A;
  border-radius: 50%;
  flex-shrink: 0;
  box-shadow: 3rpx 3rpx 0 0 #1A1A1A;
}

.row-shape {
  font-size: 28rpx;
  color: #fff;
  font-weight: 900;
  line-height: 1;
}

.switch-icon {
  background: #5A9AFC;
}

.arrow {
  color: var(--color-text-faint);
  font-size: 30rpx;
  font-weight: 300;
}
</style>
