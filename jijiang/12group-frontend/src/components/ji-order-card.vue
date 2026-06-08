<template>
  <view class="ji-order-card surface-card" @click="$emit('click', order)">
    <view class="card-head">
      <text class="order-no">{{ order.orderNo }}</text>
      <ji-status-pill :text="orderStatusText(order.status)" :tone="ORDER_STATUS[order.status]?.tone || 'idle'" />
    </view>
    <view class="card-body">
      <image v-if="coverUrl" :src="coverUrl" class="cover-img" mode="aspectFill" />
      <view v-else class="cover-placeholder">课</view>
      <view class="info">
        <text class="service-title">{{ order.serviceTitle || `服务 #${order.serviceId}` }}</text>
        <text class="counterparty">
          {{ role === "seller" ? `买家: ${order.buyerName || `用户 #${order.buyerId}`}` : `讲师: ${order.sellerName || `用户 #${order.sellerId}`}` }}
        </text>
      </view>
      <view class="amount-col">
        <text class="amount">¥{{ money(order.amount) }}</text>
        <text class="time">{{ formatTime(order.createTime) }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from "vue";
import JiStatusPill from "@/components/ji-status-pill.vue";
import type { OrderItem } from "@/types/domain";
import { money } from "@/utils/money";
import { ORDER_STATUS, orderStatusText } from "@/utils/status";
import { normalizeImageUrl } from "@/utils/image";

const props = withDefaults(defineProps<{ order: OrderItem; role?: "buyer" | "seller" }>(), { role: "buyer" });
defineEmits<{ click: [order: OrderItem] }>();

const coverUrl = computed(() => normalizeImageUrl(props.order.serviceCoverUrl));

function formatTime(time?: string) {
  if (!time) return "";
  const d = time.replace("T", " ").substring(0, 16);
  const now = new Date();
  const dt = new Date(time);
  const diff = now.getTime() - dt.getTime();
  if (diff < 60 * 1000) return "刚刚";
  if (diff < 60 * 60 * 1000) return Math.floor(diff / 60000) + "分钟前";
  if (diff < 24 * 60 * 60 * 1000) return Math.floor(diff / 3600000) + "小时前";
  if (diff < 7 * 24 * 60 * 60 * 1000) return Math.floor(diff / 86400000) + "天前";
  return d;
}
</script>

<style scoped>
.ji-order-card {
  display: block;
  margin-bottom: 24rpx;
  padding: 24rpx;
  border: var(--stroke);
  border-radius: var(--radius-lg);
  background: var(--color-card);
  box-shadow: var(--shadow-md);
  transition: transform 0.14s ease, box-shadow 0.14s ease;
}

.ji-order-card:active {
  transform: translate(2rpx, 2rpx);
  box-shadow: var(--shadow-sm);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.order-no {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text-muted);
  font-size: 23rpx;
  font-weight: 800;
}

.card-body {
  display: flex;
  align-items: center;
  gap: 18rpx;
  margin-top: 18rpx;
}

.cover-img,
.cover-placeholder {
  width: 112rpx;
  height: 112rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  flex-shrink: 0;
}

.cover-img {
  background: var(--color-card-muted);
}

.cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 40rpx;
  font-weight: 900;
  background: var(--color-primary);
}

.info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  min-width: 0;
}

.service-title {
  color: var(--color-dark);
  font-size: 29rpx;
  font-weight: 900;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.counterparty {
  color: var(--color-text-muted);
  font-size: 24rpx;
}

.amount-col {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6rpx;
  flex-shrink: 0;
}

.amount {
  padding: 4rpx 12rpx;
  border-radius: 6rpx;
  color: #0F766E;
  font-size: 28rpx;
  font-weight: 800;
  background: #E8F8F5;
  white-space: nowrap;
}

.time {
  color: var(--color-text-faint);
  font-size: 20rpx;
  white-space: nowrap;
}

@media (max-width: 360px) {
  .ji-order-card {
    padding: 20rpx;
  }

  .card-body {
    gap: 14rpx;
  }

  .cover-img,
  .cover-placeholder {
    width: 96rpx;
    height: 96rpx;
  }

  .service-title {
    font-size: 26rpx;
  }

  .amount {
    font-size: 28rpx;
  }
}
</style>
