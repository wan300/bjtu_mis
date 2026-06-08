<template>
  <view class="page-shell">
    <view class="tabs">
      <text class="tab" :class="{ active: role === 'buyer' }" @click="setRole('buyer')">我买到的</text>
      <text class="tab" :class="{ active: role === 'seller' }" @click="setRole('seller')">我卖出的</text>
    </view>
    <ji-empty v-if="orders.length === 0" title="暂无订单" desc="订单会按最新创建时间展示。" />
    <ji-order-card v-for="item in orders" :key="item.id" :order="item" :role="role" @click="open" />
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import JiEmpty from "@/components/ji-empty.vue";
import JiOrderCard from "@/components/ji-order-card.vue";
import { listOrders } from "@/api/order";
import type { OrderItem } from "@/types/domain";

const role = ref<"buyer" | "seller">("buyer");
const orders = ref<OrderItem[]>([]);

onLoad((query) => {
  if (query?.role === "seller") role.value = "seller";
});
onShow(load);

async function load() {
  orders.value = await listOrders(role.value);
}

function setRole(value: "buyer" | "seller") {
  role.value = value;
  load();
}

function open(order: OrderItem) {
  uni.navigateTo({ url: `/pages/order/detail?orderId=${order.id}` });
}
</script>

<style scoped>
.tabs {
  display: flex;
  gap: 16rpx;
  margin-bottom: 24rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  padding: 8rpx;
  background: var(--color-card);
  box-shadow: var(--shadow-sm);
}

.tab {
  flex: 1;
  border-radius: var(--radius-sm);
  padding: 18rpx 20rpx;
  color: var(--color-text-muted);
  text-align: center;
  font-size: 26rpx;
  font-weight: 800;
  transition: transform 0.14s ease, background 0.14s ease, color 0.14s ease;
}

.tab:active {
  transform: translate(2rpx, 2rpx);
}

.tab.active {
  color: #fff;
  font-weight: 900;
  background: var(--color-primary);
  box-shadow: var(--shadow-sm);
}

@media (max-width: 360px) {
  .tabs {
    gap: 8rpx;
  }

  .tab {
    padding: 16rpx 14rpx;
    font-size: 24rpx;
  }
}
</style>
