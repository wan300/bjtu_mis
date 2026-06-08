import { request } from "@/api/request";
import type { ReviewItem, ReviewStats } from "@/types/domain";

export function submitReview(data: {
  orderId: number;
  score: number;
  content: string;
  tags?: string[];
  isAnonymous?: boolean;
  images?: string[];
}) {
  return request<{ reviewId: number }>({
    url: "/api/review/submit",
    method: "POST",
    data: data as Record<string, unknown>,
  });
}

export function getReviewList(serviceId: number, page: number, pageSize: number, opts?: { score?: number; tag?: string }) {
  return request<{ items: ReviewItem[]; total: number; page: number; pageSize: number; stats: ReviewStats | null }>({
    url: "/api/review/list",
    method: "GET",
    params: { serviceId, page, pageSize, ...opts },
  });
}

export function getReviewStats(serviceId: number) {
  return request<ReviewStats>({
    url: "/api/review/stats",
    method: "GET",
    params: { serviceId },
  });
}

export function getReviewDetail(reviewId: number) {
  return request<ReviewItem>({
    url: "/api/review/detail",
    method: "GET",
    params: { reviewId },
  });
}

export function getReviewByOrder(orderId: number) {
  return request<ReviewItem | null>({
    url: "/api/review/by-order",
    method: "GET",
    params: { orderId },
  });
}

export function submitFollowUp(data: {
  orderId: number;
  content: string;
  images?: string[];
}) {
  return request<void>({
    url: "/api/review/follow-up",
    method: "POST",
    data: data as Record<string, unknown>,
  });
}

export function replyReview(reviewId: number, content: string, target?: "review" | "followUp") {
  return request<void>({
    url: "/api/review/reply",
    method: "POST",
    data: { reviewId, content, target: target || "review" } as Record<string, unknown>,
  });
}
