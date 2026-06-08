import { request } from "@/api/request";
import type { PaymentCreateResult, PaymentSyncResult } from "@/types/domain";

export function payOrder(orderId: number) {
  return request<PaymentCreateResult>({
    url: "/api/payment/create",
    method: "POST",
    data: { orderId },
  });
}

export function syncPaymentStatus(orderId: number, options?: { silent?: boolean }) {
  return request<PaymentSyncResult>({
    url: "/api/payment/sync",
    method: "POST",
    data: { orderId },
    skipToast: options?.silent,
  });
}
