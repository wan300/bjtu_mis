<template>
  <view class="page-shell page-with-tab">
    <view class="section-title"><text>卖家订单</text><text class="publish" @click="load">刷新</text></view>
    <ji-empty v-if="orders.length === 0" title="暂无卖家订单" desc="发布服务并通过审核后，买家订单会显示在这里。" />
    <ji-order-card v-for="item in orders" :key="item.id" :order="item" role="seller" @click="open" />
    <ji-tab-bar />
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import JiEmpty from "@/components/ji-empty.vue";
import JiOrderCard from "@/components/ji-order-card.vue";
import JiTabBar from "@/components/ji-tab-bar.vue";
import { listOrders } from "@/api/order";
import type { OrderItem } from "@/types/domain";

const orders = ref<OrderItem[]>([]);
onShow(load);

async function load() {
  orders.value = await listOrders("seller");
}

function open(order: OrderItem) {
  uni.navigateTo({ url: `/pages/order/detail?orderId=${order.id}` });
}
</script>

<style scoped>
.publish {
  color: var(--color-primary);
  font-size: 24rpx;
}
</style>
