import type { ServiceItem, ServicePriceTier } from "@/types/domain";

export function parseServicePriceTiers(service?: ServiceItem | null): ServicePriceTier[] {
  if (!service) return [];
  const fallback = fallbackTier(service.price);
  if (!service.priceConfig) return [fallback];
  try {
    const parsed = JSON.parse(service.priceConfig);
    if (!Array.isArray(parsed)) return [fallback];
    const tiers = parsed
      .map((item, index): ServicePriceTier | null => {
        const price = Number(item?.price);
        const qty = Number(item?.qty || 1);
        const name = String(item?.name || "").trim();
        const unit = String(item?.unit || "次").trim();
        const key = String(item?.key || `tier-${index}`).trim();
        if (!key || !name || !Number.isFinite(price) || price <= 0 || !Number.isFinite(qty) || qty <= 0) {
          return null;
        }
        return { key, name, price, unit: unit || "次", qty };
      })
      .filter((item): item is ServicePriceTier => !!item);
    return tiers.length > 0 ? tiers : [fallback];
  } catch {
    return [fallback];
  }
}

function fallbackTier(price: number): ServicePriceTier {
  return {
    key: "single",
    name: "单次体验",
    price: Number(price || 0),
    unit: "次",
    qty: 1,
  };
}
