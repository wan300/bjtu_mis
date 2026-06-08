<template>
  <view class="ji-tab-bar">
    <view
      v-for="item in tabs"
      :key="item.url"
      class="ji-tab-item"
      :class="{ 'ji-tab-active': current === item.url }"
      @click="jump(item.url)"
    >
      <image class="ji-tab-icon" :src="current === item.url ? item.activeIcon : item.icon" mode="aspectFit" />
      <view v-if="item.text === '我的' && unreadBadge" class="ji-tab-badge">{{ unreadBadge }}</view>
      <text class="ji-tab-text">{{ item.text }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useNotificationStore } from "@/store/notification";
import { useUserStore } from "@/store/user";

const user = useUserStore();
const notification = useNotificationStore();
const buyerTabs = [
  { text: "首页", icon: "/static/tabbar/home.png", activeIcon: "/static/tabbar/home-active.png", url: "/pages/tabbar/home/index" },
  { text: "发现", icon: "/static/tabbar/discover.png", activeIcon: "/static/tabbar/discover-active.png", url: "/pages/tabbar/discover/index" },
  { text: "消息", icon: "/static/tabbar/message.png", activeIcon: "/static/tabbar/message-active.png", url: "/pages/tabbar/message/index" },
  { text: "我的", icon: "/static/tabbar/mine.png", activeIcon: "/static/tabbar/mine-active.png", url: "/pages/tabbar/mine/index" },
];
const sellerTabs = [
  { text: "工作台", icon: "/static/tabbar/workbench.png", activeIcon: "/static/tabbar/workbench-active.png", url: "/pages/tabbar/seller-desk/index" },
  { text: "订单", icon: "/static/tabbar/order.png", activeIcon: "/static/tabbar/order-active.png", url: "/pages/tabbar/seller-order/index" },
  { text: "服务", icon: "/static/tabbar/service.png", activeIcon: "/static/tabbar/service-active.png", url: "/pages/tabbar/seller-service/index" },
  { text: "我的", icon: "/static/tabbar/mine.png", activeIcon: "/static/tabbar/mine-active.png", url: "/pages/tabbar/mine/index" },
];

const tabs = computed(() => (user.currentRole === 2 ? sellerTabs : buyerTabs));
const unreadBadge = computed(() => {
  if (!user.isLogin || notification.unreadCount <= 0) return "";
  return notification.unreadCount > 99 ? "99+" : String(notification.unreadCount);
});
const current = ref("");

type NativeTabBarPage = {
  getTabBar?: () => { setData?: (data: { selected: number }) => void } | null;
};

function syncNativeTabBar(path: string) {
  const selected = buyerTabs.findIndex((item) => item.url === path);
  if (selected < 0) return;
  const pages = getCurrentPages();
  const page = pages[pages.length - 1] as NativeTabBarPage | undefined;
  const tabBar = page?.getTabBar?.();
  tabBar?.setData?.({ selected });
}

function syncCurrent() {
  const pages = getCurrentPages();
  const route = pages[pages.length - 1]?.route || "";
  current.value = route ? `/${route}` : "";
  syncNativeTabBar(current.value);
}

function jump(url: string) {
  if (current.value === url) return;
  current.value = url;
  syncNativeTabBar(url);
  const nativeTab = buyerTabs.some((item) => item.url === url);
  const options = { url, fail: syncCurrent };
  if (nativeTab) uni.switchTab(options);
  else uni.reLaunch(options);
}

function hideSystemTabBar() {
  // #ifdef H5
  uni.hideTabBar({ animation: false });
  // #endif
}

function handleShow() {
  syncCurrent();
  hideSystemTabBar();
  if (user.isLogin) {
    notification.startPolling();
  }
}

onMounted(handleShow);
onShow(handleShow);
</script>

<style>
.ji-tab-bar {
  position: fixed;
  right: 24rpx;
  bottom: 22rpx;
  left: 24rpx;
  z-index: 90;
  display: flex;
  justify-content: space-around;
  height: 108rpx;
  border: var(--stroke);
  border-radius: var(--radius-xl);
  background: var(--color-glass-bg);
  backdrop-filter: blur(var(--glass-blur));
  -webkit-backdrop-filter: blur(var(--glass-blur));
  box-shadow: var(--shadow-md);
}

@supports not (backdrop-filter: blur(1px)) {
  .ji-tab-bar {
    background: rgba(255, 255, 255, 0.95);
  }
}

.ji-tab-item {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  flex-direction: column;
  gap: 6rpx;
  color: #4A5568;
  font-size: 21rpx;
  font-weight: 700;
  transition: color 0.14s ease, transform 0.14s ease;
}

.ji-tab-item:active {
  transform: translate(2rpx, 2rpx);
}

.ji-tab-icon {
  width: 38rpx;
  height: 38rpx;
  display: block;
}

.ji-tab-badge {
  position: absolute;
  top: 12rpx;
  right: calc(50% - 42rpx);
  min-width: 30rpx;
  height: 30rpx;
  padding: 0 8rpx;
  border: 2rpx solid #fff;
  border-radius: 999rpx;
  color: #fff;
  font-size: 18rpx;
  font-weight: 900;
  line-height: 30rpx;
  text-align: center;
  background: var(--color-accent-3);
  box-shadow: 0 4rpx 10rpx rgba(247, 141, 167, 0.32);
}

.ji-tab-text {
  line-height: 1.2;
}

.ji-tab-active {
  color: var(--color-primary);
  font-weight: 900;
}

.ji-tab-active .ji-tab-icon {
  transform: translateY(-2rpx);
}

@media (max-width: 360px) {
  .ji-tab-bar {
    right: 18rpx;
    left: 18rpx;
  }

  .ji-tab-item {
    font-size: 20rpx;
  }
}
</style>
