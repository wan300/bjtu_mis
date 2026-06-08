<template>
  <view class="page-shell notify-page">
    <view class="hero">
      <view>
        <text class="hero-title">通知中心</text>
        <text v-if="user.isLogin" class="hero-sub">未读 {{ notification.unreadCount }}</text>
      </view>
      <button
        v-if="user.isLogin && notification.unreadCount > 0"
        class="read-all-btn"
        @click.stop="readAll"
      >
        全部已读
      </button>
    </view>

    <view v-if="user.isLogin" class="filter-bar">
      <button class="filter-btn" :class="{ active: activeFilter === 'all' }" @click="switchFilter('all')">全部</button>
      <button class="filter-btn" :class="{ active: activeFilter === 'unread' }" @click="switchFilter('unread')">未读</button>
    </view>

    <view v-if="!user.isLogin" class="surface-card login-card">
      <ji-empty title="登录后查看通知" desc="请先登录以查看订单关键通知" action-text="去登录" @action="goLogin" />
    </view>

    <view v-else-if="loading && notifications.length === 0" class="surface-card loading-card">
      <view class="loading-pill">
        <view class="loading-dot" />
        <text>加载中</text>
      </view>
    </view>

    <view v-else-if="errorMessage && notifications.length === 0" class="surface-card error-card">
      <view class="error-body">
        <text class="error-title">{{ errorMessage }}</text>
        <button class="retry-btn" @click.stop="loadFirstPage(false)">重试</button>
      </view>
    </view>

    <view v-else-if="notifications.length === 0" class="surface-card empty-card">
      <ji-empty :title="emptyTitle" desc="订单、退款、提现等通知会在这里展示" />
    </view>

    <view v-else class="notify-list">
      <view
        v-for="item in notifications"
        :key="item.id"
        class="notify-card surface-card"
        :class="{ unread: item.status === 0 }"
        @click="openNotification(item)"
      >
        <view class="notify-left">
          <view class="notify-head">
            <text class="notify-type">{{ getSourceLabel(item.sourceType) }}</text>
            <view v-if="item.status === 0" class="notify-dot" />
          </view>
          <text class="notify-title">{{ item.title }}</text>
          <text class="notify-desc">{{ item.content || getSourceLabel(item.sourceType) }}</text>
        </view>
        <view class="notify-meta">
          <text class="notify-time">{{ fmtTime(item.createTime) }}</text>
          <text class="notify-arrow">›</text>
        </view>
      </view>

      <button v-if="hasMore" class="load-more-btn" :disabled="loadingMore" @click="loadNextPage">
        {{ loadingMore ? "加载中" : "加载更多" }}
      </button>
      <text v-else class="list-end">已显示全部</text>
    </view>

    <ji-tab-bar />
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onHide, onPullDownRefresh, onReachBottom, onShow, onUnload } from "@dcloudio/uni-app";
import JiEmpty from "@/components/ji-empty.vue";
import JiTabBar from "@/components/ji-tab-bar.vue";
import {
  getNotifications,
  markAllAsRead as markAllNotificationsAsRead,
  markAsRead
} from "@/api/notification";
import type { NotificationItem } from "@/api/notification";
import { getRefundDetail } from "@/api/refund";
import { BizError } from "@/api/request";
import { useNotificationStore } from "@/store/notification";
import { useUserStore } from "@/store/user";
import { toast } from "@/utils/toast";

type NotificationFilter = "all" | "unread";

const user = useUserStore();
const notification = useNotificationStore();
const notifications = ref<NotificationItem[]>([]);
const activeFilter = ref<NotificationFilter>("all");
const errorMessage = ref("");
const loading = ref(false);
const loadingMore = ref(false);
const page = ref(1);
const total = ref(0);
const hasMore = ref(false);
const pageSize = 20;
let pagePollTimer: ReturnType<typeof setInterval> | null = null;

const emptyTitle = computed(() => activeFilter.value === "unread" ? "暂无未读通知" : "暂无通知");

function unreadOnly() {
  return activeFilter.value === "unread";
}

async function loadFirstPage(silent = false) {
  if (!user.isLogin) {
    resetLoggedOutState();
    return;
  }

  if (!silent) {
    loading.value = true;
  }
  errorMessage.value = "";

  try {
    const result = await getNotifications(1, pageSize, unreadOnly());
    page.value = 1;
    total.value = result.total || 0;
    hasMore.value = Boolean(result.hasMore);
    notifications.value = result.items || [];
    notification.setUnreadCount(result.unreadCount || 0);
  } catch (error) {
    if (!silent) {
      notifications.value = [];
      errorMessage.value = normalizeError(error);
    }
  } finally {
    loading.value = false;
  }
}

async function refreshFirstPageForPolling() {
  if (!user.isLogin || loading.value || loadingMore.value) return;
  try {
    const result = await getNotifications(1, pageSize, unreadOnly());
    page.value = Math.max(page.value, 1);
    total.value = result.total || 0;
    hasMore.value = Boolean(result.hasMore);
    notification.setUnreadCount(result.unreadCount || 0);
    const firstPage = result.items || [];
    if (page.value === 1 || notifications.value.length <= pageSize) {
      notifications.value = firstPage;
      return;
    }
    const firstPageIds = new Set(firstPage.map((item) => item.id));
    notifications.value = [
      ...firstPage,
      ...notifications.value.filter((item) => !firstPageIds.has(item.id)),
    ];
  } catch {
    // Keep the current list during background refresh failures.
  }
}

async function loadNextPage() {
  if (!user.isLogin || !hasMore.value || loading.value || loadingMore.value) return;
  loadingMore.value = true;
  try {
    const nextPage = page.value + 1;
    const result = await getNotifications(nextPage, pageSize, unreadOnly());
    page.value = result.page || nextPage;
    total.value = result.total || total.value;
    hasMore.value = Boolean(result.hasMore);
    notification.setUnreadCount(result.unreadCount || 0);
    const existingIds = new Set(notifications.value.map((item) => item.id));
    notifications.value = [
      ...notifications.value,
      ...(result.items || []).filter((item) => !existingIds.has(item.id)),
    ];
  } catch (error) {
    toast(normalizeError(error), "none");
  } finally {
    loadingMore.value = false;
  }
}

function switchFilter(filter: NotificationFilter) {
  if (activeFilter.value === filter) return;
  activeFilter.value = filter;
  loadFirstPage(false);
}

async function readAll() {
  if (!user.isLogin || notification.unreadCount <= 0) return;
  try {
    await markAllNotificationsAsRead();
    notification.setUnreadCount(0);
    if (unreadOnly()) {
      notifications.value = [];
      total.value = 0;
      hasMore.value = false;
    } else {
      notifications.value = notifications.value.map((item) => ({ ...item, status: 1 }));
    }
    toast("已全部标记为已读", "success");
  } catch (error) {
    toast(normalizeError(error), "none");
  }
}

function goLogin() {
  uni.navigateTo({ url: "/pages/login/index" });
}

function getSourceLabel(type: string) {
  switch (type) {
    case "ORDER":
      return "订单通知";
    case "REFUND":
      return "退款通知";
    case "WITHDRAWAL":
      return "提现通知";
    default:
      return "系统通知";
  }
}

async function openNotification(item: NotificationItem) {
  if (item.status === 0) {
    try {
      await markAsRead(item.id);
      markLocalRead(item.id);
    } catch {
      toast("标记已读失败", "none");
    }
  }

  if (!item.sourceId) {
    return;
  }

  if (item.sourceType === "ORDER") {
    uni.navigateTo({ url: `/pages/order/detail?orderId=${item.sourceId}` });
    return;
  }

  if (item.sourceType === "REFUND") {
    try {
      const refund = await getRefundDetail(item.sourceId);
      if (refund.orderId) {
        uni.navigateTo({ url: `/pages/order/detail?orderId=${refund.orderId}` });
      }
    } catch {
      toast("退款详情加载失败", "none");
    }
    return;
  }

  if (item.sourceType === "WITHDRAWAL") {
    uni.navigateTo({ url: "/pages/seller/income" });
  }
}

function markLocalRead(notificationId: number) {
  notification.decrementUnreadCount();
  if (unreadOnly()) {
    notifications.value = notifications.value.filter((item) => item.id !== notificationId);
    total.value = Math.max(0, total.value - 1);
    hasMore.value = notifications.value.length < total.value;
    return;
  }
  notifications.value = notifications.value.map((item) =>
    item.id === notificationId ? { ...item, status: 1 } : item
  );
}

function fmtTime(value?: string) {
  if (!value) return "";
  const d = new Date(value.replace("T", " "));
  const diff = Date.now() - d.getTime();
  if (diff < 60000) return "刚刚";
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}小时前`;
  if (diff < 172800000) return "昨天";
  return value.substring(0, 10);
}

function normalizeError(error: unknown) {
  if (error instanceof BizError) return error.message;
  if (error instanceof Error) return error.message;
  return "加载失败，请稍后重试";
}

function resetLoggedOutState() {
  notifications.value = [];
  errorMessage.value = "";
  loading.value = false;
  loadingMore.value = false;
  page.value = 1;
  total.value = 0;
  hasMore.value = false;
}

function startPagePolling() {
  stopPagePolling();
  if (!user.isLogin) return;
  pagePollTimer = setInterval(() => {
    refreshFirstPageForPolling();
  }, 30000);
}

function stopPagePolling() {
  if (pagePollTimer) {
    clearInterval(pagePollTimer);
    pagePollTimer = null;
  }
}

onShow(() => {
  loadFirstPage(false);
  startPagePolling();
});

onHide(stopPagePolling);
onUnload(stopPagePolling);

onPullDownRefresh(async () => {
  await loadFirstPage(false);
  uni.stopPullDownRefresh();
});

onReachBottom(loadNextPage);
</script>

<style scoped>
.notify-page { padding-bottom: 150rpx; }
.hero { display: flex; align-items: center; justify-content: space-between; gap: 20rpx; padding: 36rpx 6rpx 18rpx; }
.hero-title { display: block; color: #152033; font-size: 44rpx; font-weight: 900; }
.hero-sub { display: block; margin-top: 8rpx; color: var(--color-text-muted); font-size: 24rpx; font-weight: 700; }
.read-all-btn { height: 64rpx; min-width: 156rpx; border: var(--stroke); border-radius: var(--radius-sm); color: #fff; font-size: 24rpx; font-weight: 900; line-height: 64rpx; background: var(--color-primary); box-shadow: var(--shadow-sm); }
.filter-bar { display: flex; gap: 14rpx; margin: 0 0 24rpx; }
.filter-btn { flex: 0 0 138rpx; height: 64rpx; border: var(--stroke); border-radius: var(--radius-sm); color: var(--color-text); font-size: 24rpx; font-weight: 900; line-height: 64rpx; background: #fff; box-shadow: var(--shadow-sm); }
.filter-btn.active { color: #fff; background: var(--color-dark); }
.login-card, .empty-card, .error-card, .loading-card { margin-top: 24rpx; }
.loading-card { display: flex; align-items: center; justify-content: center; min-height: 220rpx; }
.notify-list { padding: 0; }
.notify-card { display: flex; justify-content: space-between; gap: 20rpx; padding: 26rpx 24rpx; margin-bottom: 16rpx; border-radius: var(--radius-lg); transition: background 0.2s ease; }
.notify-card.unread { background: #eef7ff; }
.notify-left { flex: 1; min-width: 0; }
.notify-head { display: flex; align-items: center; gap: 10rpx; margin-bottom: 8rpx; }
.notify-type { color: var(--color-primary); font-size: 21rpx; font-weight: 900; }
.notify-dot { width: 14rpx; height: 14rpx; border-radius: 50%; background: var(--color-accent-3); }
.notify-title { display: block; color: #152033; font-size: 28rpx; font-weight: 900; margin-bottom: 8rpx; }
.notify-desc { display: block; color: #667085; font-size: 24rpx; line-height: 34rpx; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.notify-meta { display: flex; flex-direction: column; align-items: flex-end; justify-content: space-between; gap: 12rpx; flex-shrink: 0; }
.notify-time { color: #9aa6b8; font-size: 20rpx; white-space: nowrap; }
.notify-arrow { color: var(--color-text-faint); font-size: 34rpx; font-weight: 300; line-height: 1; }
.load-more-btn { width: 100%; height: 76rpx; margin-top: 8rpx; border: var(--stroke); border-radius: var(--radius-md); color: var(--color-primary); font-size: 26rpx; font-weight: 900; line-height: 76rpx; background: #fff; box-shadow: var(--shadow-sm); }
.list-end { display: block; padding: 20rpx 0 8rpx; color: var(--color-text-muted); font-size: 22rpx; text-align: center; }
.error-body { display: flex; flex-direction: column; align-items: center; gap: 24rpx; padding: 56rpx 24rpx; text-align: center; }
.error-title { color: #1f4fd8; font-size: 28rpx; line-height: 40rpx; }
.retry-btn { width: 220rpx; height: 64rpx; border-radius: 32rpx; color: #fff; line-height: 64rpx; background: #1f4fd8; }
</style>
