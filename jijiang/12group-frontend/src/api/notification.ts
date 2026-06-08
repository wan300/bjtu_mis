import { request } from "@/api/request";

export interface NotificationItem {
  id: number;
  title: string;
  content?: string;
  sourceType: string;
  sourceId?: number;
  status: number;
  createTime: string;
  updateTime: string;
}

export interface NotificationListResult {
  items: NotificationItem[];
  page: number;
  pageSize: number;
  total: number;
  hasMore: boolean;
  unreadCount: number;
}

export interface NotificationUnreadResult {
  unreadCount: number;
}

export function getNotifications(page = 1, pageSize = 20, unreadOnly = false) {
  return request<NotificationListResult>({
    url: "/api/notifications",
    params: { page, pageSize, unreadOnly },
    skipToast: true,
  });
}

export function getUnreadCount() {
  return request<NotificationUnreadResult>({
    url: "/api/notifications/unread-count",
    skipToast: true,
  });
}

export function markAsRead(notificationId: number) {
  return request<void>({
    url: `/api/notifications/${notificationId}/read`,
    method: "POST",
  });
}

export function markAllAsRead() {
  return request<void>({
    url: "/api/notifications/read-all",
    method: "POST",
  });
}
