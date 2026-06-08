import { request } from "@/api/request";
import type { Category, ServiceItem } from "@/types/domain";
import { normalizeImageUrl } from "@/utils/image";

export interface SearchServiceParams {
  campusId?: number;
  keyword?: string;
  categoryId?: number;
}

export interface PublishServicePayload {
  categoryId: number;
  title: string;
  description: string;
  price: number;
  priceConfig: string;
  coverUrl: string;
  stock: number;
}

export function getCategories() {
  return request<Category[]>({ url: "/api/service/categories", skipAuth: true });
}

export async function searchServices(params: SearchServiceParams) {
  const items = await request<ServiceItem[]>({
    url: "/api/service/search",
    params: params as Record<string, unknown>,
    skipAuth: true,
  });
  return items.map(normalizeServiceItem);
}

export async function getServiceDetail(id: number) {
  const item = await request<ServiceItem>({ url: "/api/service/detail", params: { id }, skipAuth: true });
  return normalizeServiceItem(item);
}

export function publishService(data: PublishServicePayload) {
  return request<{ serviceId: number; status: number; message: string }>({
    url: "/api/service/publish",
    method: "POST",
    data: data as unknown as Record<string, unknown>,
  });
}

export async function getMyServices() {
  const items = await request<ServiceItem[]>({ url: "/api/service/my-list" });
  return items.map(normalizeServiceItem);
}

export function editService(data: {
  serviceId: number;
  categoryId?: number;
  title: string;
  description: string;
  price: number;
  priceConfig?: string;
  coverUrl?: string;
  stock?: number;
}) {
  return request<void>({
    url: "/api/service/edit",
    method: "POST",
    data: data as unknown as Record<string, unknown>,
  });
}

export function offlineService(serviceId: number) {
  return request<void>({
    url: "/api/service/offline",
    method: "POST",
    data: { serviceId },
  });
}

export function resubmitService(serviceId: number) {
  return request<void>({
    url: "/api/service/resubmit",
    method: "POST",
    data: { serviceId },
  });
}

function normalizeServiceItem(item: ServiceItem): ServiceItem {
  return {
    ...item,
    coverUrl: normalizeImageUrl(item.coverUrl) || undefined,
  };
}
