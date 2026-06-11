<template>
  <view class="page-shell">
    <view v-if="service" class="surface-card card">
      <text class="title">{{ service.title }}</text>
      <text class="muted">讲师 #{{ service.sellerId }}</text>
      <view class="amount">¥{{ money(selectedTier?.price || service.price) }}</view>
      <view v-if="selectedTier" class="tier-summary">
        <text>{{ selectedTier.name }}</text>
        <text>{{ selectedTier.qty }}{{ selectedTier.unit }}</text>
      </view>
    </view>

    <view class="surface-card card">
      <view class="field">
        <view class="field-label">学习需求备注</view>
        <textarea v-model="remark" class="field-textarea" placeholder="写下希望上课的时间、目标和基础情况" />
      </view>
      <view class="agreement" @click="agreed = !agreed">
        <text class="check">{{ agreed ? "✓" : "" }}</text>
        <text>我同意平台担保交易规则，支付前不交换联系方式。</text>
      </view>
    </view>

    <button class="primary-btn submit" :disabled="!agreed || loading" :loading="loading" @click="submit">
      提交订单并支付
    </button>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { createOrder } from "@/api/order";
import { payOrder } from "@/api/payment";
import { getServiceDetail } from "@/api/service";
import { useOrderStore } from "@/store/order";
import { useUserStore } from "@/store/user";
import { money } from "@/utils/money";
import { parseServicePriceTiers } from "@/utils/price-tiers";
import { toast } from "@/utils/toast";
import type { ServiceItem } from "@/types/domain";

const orderStore = useOrderStore();
const user = useUserStore();
const service = ref<ServiceItem | null>(null);
const serviceId = ref(0);
const selectedTierKey = ref("");
const remark = ref("");
const agreed = ref(true);
const loading = ref(false);
const priceTiers = computed(() => parseServicePriceTiers(service.value));
const selectedTier = computed(() => priceTiers.value.find((tier) => tier.key === selectedTierKey.value) || priceTiers.value[0]);

onLoad(async (query) => {
  if (!user.isLogin) {
    goLogin();
    return;
  }
  serviceId.value = Number(query?.serviceId || 0);
  selectedTierKey.value = String(query?.tierKey || orderStore.pendingTier?.key || "");
  service.value = orderStore.pendingService?.id === serviceId.value ? orderStore.pendingService : null;
  if (!service.value && serviceId.value) service.value = await getServiceDetail(serviceId.value);
  if (!priceTiers.value.some((tier) => tier.key === selectedTierKey.value)) {
    selectedTierKey.value = priceTiers.value[0]?.key || "";
  }
});

async function submit() {
  if (!serviceId.value) return;
  if (!user.isLogin) {
    goLogin();
    return;
  }
  loading.value = true;
  try {
    if (!selectedTier.value) {
      toast("请选择套餐");
      return;
    }
    const order = await createOrder({ serviceId: serviceId.value, tierKey: selectedTier.value.key, remark: remark.value });
    orderStore.setLastOrder(order.orderId);
    const cashier = await payOrder(order.orderId);
    toast("支付单已生成", "success");
    uni.redirectTo({
      url: `/pages/order/pay-result?orderId=${order.orderId}&payUrl=${encodeURIComponent(cashier.payUrl)}&qrCodeUrl=${encodeURIComponent(cashier.qrCodeUrl || "")}`,
    });
  } finally {
    loading.value = false;
  }
}

function goLogin() {
  uni.navigateTo({ url: "/pages/login/index" });
}
</script>

<style scoped>
.card {
  padding: 32rpx;
  margin-bottom: 24rpx;
}

.title {
  display: block;
  color: var(--color-dark);
  font-size: 34rpx;
  font-weight: 900;
}

.amount {
  margin-top: 24rpx;
  padding: 4rpx 16rpx;
  border-radius: 8rpx;
  color: #0F766E;
  font-size: 46rpx;
  font-weight: 800;
  background: #E8F8F5;
}

.tier-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-top: 18rpx;
  padding-top: 18rpx;
  border-top: 1rpx solid #eef2f7;
  color: var(--color-text-secondary);
  font-size: 26rpx;
}

.agreement {
  display: flex;
  align-items: flex-start;
  gap: 16rpx;
  color: var(--color-text-muted);
  font-size: 24rpx;
  line-height: 1.6;
}

.check {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34rpx;
  height: 34rpx;
  border: var(--stroke);
  border-radius: var(--radius-sm);
  color: #fff;
  background: var(--color-primary);
}

.submit {
  margin-top: 34rpx;
}
</style>
