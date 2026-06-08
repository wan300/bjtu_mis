import { defineStore } from "pinia";
import type { ServiceItem, ServicePriceTier } from "@/types/domain";

interface OrderState {
  pendingService: ServiceItem | null;
  pendingTier: ServicePriceTier | null;
  lastOrderId: number | null;
}

export const useOrderStore = defineStore("order", {
  state: (): OrderState => ({
    pendingService: null,
    pendingTier: null,
    lastOrderId: null,
  }),
  actions: {
    setService(service: ServiceItem, tier?: ServicePriceTier | null) {
      this.pendingService = service;
      this.pendingTier = tier || null;
    },
    setLastOrder(orderId: number) {
      this.lastOrderId = orderId;
    },
  },
});
