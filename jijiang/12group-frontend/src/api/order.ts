import { request } from "@/api/request";
import type { OrderCreateResult, OrderItem } from "@/types/domain";

export function createOrder(data: { serviceId: number; tierKey?: string; remark?: string }) {
  return request<OrderCreateResult>({
    url: "/api/order/create",
    method: "POST",
    data: data as Record<string, unknown>,
    idempotent: true,
  });
}

export function listOrders(role: "buyer" | "seller" = "buyer") {
  return request<OrderItem[]>({ url: "/api/order/list", params: { role } });
}

export function getOrderDetail(orderId: number) {
  return request<OrderItem>({ url: "/api/order/detail", params: { orderId } });
}

export function acceptOrder(orderId: number) {
  return request<void>({ url: "/api/order/accept", method: "POST", data: { orderId } });
}

export function deliverOrder(orderId: number, deliverText: string) {
  return request<void>({ url: "/api/order/deliver", method: "POST", data: { orderId, deliverText } });
}

export function confirmOrder(orderId: number) {
  return request<void>({ url: "/api/order/confirm", method: "POST", data: { orderId } });
}
