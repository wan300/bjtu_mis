import { request } from "@/api/request";
import type { DemandBid, DemandItem } from "@/types/domain";

export interface DemandListParams {
  campusId?: number;
  categoryId?: number;
  keyword?: string;
}

export interface PublishDemandPayload {
  categoryId: number;
  title: string;
  description: string;
  budgetAmount: number;
  expectedTime?: string;
}

export interface BidDemandPayload {
  demandId: number;
  price: number;
  proposal: string;
  serviceTime?: string;
}

export function listDemands(params: DemandListParams) {
  return request<DemandItem[]>({
    url: "/api/demand/list",
    params: params as Record<string, unknown>,
    skipAuth: true,
  });
}

export function getDemandDetail(id: number) {
  return request<DemandItem>({
    url: "/api/demand/detail",
    params: { id },
    skipAuth: true,
  });
}

export function publishDemand(data: PublishDemandPayload) {
  return request<{ demandId: number; status: number; message: string }>({
    url: "/api/demand/publish",
    method: "POST",
    data: data as unknown as Record<string, unknown>,
  });
}

export function bidDemand(data: BidDemandPayload) {
  return request<{ bidId: number; demandId: number; status: number; message: string }>({
    url: "/api/demand/bid",
    method: "POST",
    data: data as unknown as Record<string, unknown>,
  });
}

export function listDemandBids(demandId: number) {
  return request<DemandBid[]>({ url: "/api/demand/bids", params: { demandId } });
}

export function getMyDemandBid(demandId: number) {
  return request<DemandBid | null>({ url: "/api/demand/my-bid", params: { demandId }, skipToast: true });
}
