import { request } from "@/api/request";
import type { MessageItem } from "@/types/domain";

export interface ConversationItem {
  orderId: number;
  orderNo: string;
  status: number;
  serviceTitle?: string;
  serviceCoverUrl?: string;
  counterpartyId: number;
  counterpartyName: string;
  counterpartyAvatar?: string;
  counterpartyRole: "buyer" | "seller";
  lastContent: string;
  lastTime: string;
  unreadCount: number;
}

export function sendMessage(orderId: number, content: string) {
  return request<{ messageId: number }>({
    url: "/api/message/send",
    method: "POST",
    data: { orderId, content },
  });
}

export function listMessages(orderId: number) {
  return request<MessageItem[]>({ url: "/api/message/list", params: { orderId } });
}

export function getConversations() {
  return request<ConversationItem[]>({ url: "/api/message/conversations" });
}
