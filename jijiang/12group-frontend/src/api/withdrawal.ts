import { request } from "@/api/request";
import type {
  WithdrawApplyRequest,
  WithdrawApplyResult,
  WithdrawalBalance,
  WithdrawalRecord,
} from "@/types/domain";

export interface WithdrawalListParams {
  page: number;
  pageSize: number;
  status?: number;
}

export interface WithdrawalPageResult {
  items: WithdrawalRecord[];
  total: number;
  page: number;
  pageSize: number;
}

export function checkBalance() {
  return request<WithdrawalBalance>({
    url: "/api/withdrawals/check",
  });
}

export function applyWithdraw(data: WithdrawApplyRequest) {
  return request<WithdrawApplyResult>({
    url: "/api/withdrawals/apply",
    method: "POST",
    data: data as unknown as Record<string, unknown>,
  });
}

export function listWithdrawals(params: WithdrawalListParams) {
  return request<WithdrawalPageResult>({
    url: "/api/withdrawals",
    params: params as unknown as Record<string, unknown>,
  });
}

export function getWithdrawalDetail(id: number) {
  return request<WithdrawalRecord>({
    url: `/api/withdrawals/${id}`,
  });
}
