import { request } from "@/api/request";
import type { DeletionStatus } from "@/types/domain";

export function requestDeletion() {
  return request<{ message: string; coolingUntil?: string }>({
    url: "/api/user/deletion/request",
    method: "POST",
  });
}

export function cancelDeletion() {
  return request<{ message: string }>({
    url: "/api/user/deletion/cancel",
    method: "POST",
  });
}

export function getDeletionStatus() {
  return request<DeletionStatus>({
    url: "/api/user/deletion/status",
  });
}
