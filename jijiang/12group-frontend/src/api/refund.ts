import { request } from "@/api/request";
import type { RefundRequestItem, RefundSubmitRequest } from "@/types/domain";

export function submitRefund(data: RefundSubmitRequest) {
  return request<RefundRequestItem>({
    url: "/api/refund/submit",
    method: "POST",
    data: data as unknown as Record<string, unknown>,
    idempotent: true,
  });
}

export function listMyRefunds() {
  return request<RefundRequestItem[]>({ url: "/api/refund/list" });
}

export function getRefundDetail(refundId: number) {
  return request<RefundRequestItem>({ url: "/api/refund/detail", params: { refundId } });
}
