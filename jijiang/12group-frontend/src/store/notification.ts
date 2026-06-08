import { defineStore } from "pinia";
import { getUnreadCount } from "@/api/notification";

const POLL_INTERVAL_MS = 30000;

interface NotificationState {
  unreadCount: number;
  pollingTimer: ReturnType<typeof setInterval> | null;
}

export const useNotificationStore = defineStore("notification", {
  state: (): NotificationState => ({
    unreadCount: 0,
    pollingTimer: null,
  }),
  actions: {
    async refreshUnreadCount() {
      if (!uni.getStorageSync("token")) {
        this.setUnreadCount(0);
        return 0;
      }
      const result = await getUnreadCount();
      this.setUnreadCount(result.unreadCount || 0);
      return this.unreadCount;
    },
    startPolling() {
      if (!uni.getStorageSync("token")) {
        this.stopPolling(true);
        return;
      }
      if (!this.pollingTimer) {
        this.pollingTimer = setInterval(() => {
          this.refreshUnreadCount().catch(() => {
            // Keep polling; transient auth/network failures are handled by request.ts.
          });
        }, POLL_INTERVAL_MS);
      }
      this.refreshUnreadCount().catch(() => {
        // The first foreground refresh should not break page rendering.
      });
    },
    stopPolling(clear = false) {
      if (this.pollingTimer) {
        clearInterval(this.pollingTimer);
        this.pollingTimer = null;
      }
      if (clear) {
        this.setUnreadCount(0);
      }
    },
    setUnreadCount(count: number) {
      this.unreadCount = Math.max(0, Number(count) || 0);
      uni.setStorageSync("notificationUnreadCount", this.unreadCount);
      this.syncNativeTabBadge();
    },
    decrementUnreadCount(step = 1) {
      this.setUnreadCount(this.unreadCount - step);
    },
    syncNativeTabBadge() {
      const pages = getCurrentPages();
      const currentPage = pages[pages.length - 1] as {
        getTabBar?: () => { setData?: (data: { notificationUnreadCount: number }) => void } | null;
      } | undefined;
      currentPage?.getTabBar?.()?.setData?.({ notificationUnreadCount: this.unreadCount });

      const text = this.unreadCount > 99 ? "99+" : String(this.unreadCount);
      if (this.unreadCount > 0) {
        uni.setTabBarBadge({ index: 3, text, fail: () => undefined });
      } else {
        uni.removeTabBarBadge({ index: 3, fail: () => undefined });
      }
    },
  },
});
