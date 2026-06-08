import { request } from "@/api/request";
import type { DepositCreateResult, DepositStatus, DepositSyncResult } from "@/types/domain";

export function createDeposit() {
  return request<DepositCreateResult>({
    url: "/api/deposit/create",
    method: "POST",
  });
}

export function getDepositStatus() {
  return request<DepositStatus>({
    url: "/api/deposit/status",
  });
}

export function syncDeposit(outTradeNo: string) {
  return request<DepositSyncResult>({
    url: "/api/deposit/sync",
    method: "POST",
    data: { outTradeNo },
  });
}
