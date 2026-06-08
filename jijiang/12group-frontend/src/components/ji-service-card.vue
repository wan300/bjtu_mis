<template>
  <view class="ji-service-card surface-card" @click="$emit('click', service)">
    <view class="ji-service-cover">
      <image v-if="coverUrl" :src="coverUrl" class="ji-service-cover-img" mode="aspectFill" @error="handleCoverError" />
      <text v-if="!coverUrl">技匠</text>
    </view>
    <view class="ji-service-content">
      <view class="ji-service-title-row">
        <text class="ji-service-title">{{ service.title }}</text>
        <text class="ji-service-price">¥{{ money(service.price) }}</text>
      </view>
      <text class="ji-service-desc">{{ service.description || "这位讲师暂未填写描述" }}</text>
      <view class="ji-service-meta">
        <text class="ji-service-meta-item">{{ service.sellerName || `讲师 #${service.sellerId}` }}</text>
        <text class="ji-service-meta-item">评分 {{ Number(service.scoreAvg || 5).toFixed(1) }}</text>
        <text class="ji-service-meta-item">成交 {{ service.salesCount || 0 }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { money } from "@/utils/money";
import { normalizeImageUrl } from "@/utils/image";
import type { ServiceItem } from "@/types/domain";

const props = defineProps<{ service: ServiceItem }>();
defineEmits<{ click: [service: ServiceItem] }>();

const failedCoverUrl = ref("");
const normalizedCoverUrl = computed(() => normalizeImageUrl(props.service.coverUrl));
const coverUrl = computed(() => {
  const url = normalizedCoverUrl.value;
  return url && url !== failedCoverUrl.value ? url : "";
});

watch(normalizedCoverUrl, () => {
  failedCoverUrl.value = "";
});

function handleCoverError() {
  if (coverUrl.value) failedCoverUrl.value = coverUrl.value;
}
</script>

<style>
.ji-service-card {
  display: flex;
  gap: 24rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
  border: var(--stroke);
  border-radius: var(--radius-lg);
  background: var(--color-card);
  box-shadow: var(--shadow-md);
  transition: transform 0.14s ease, box-shadow 0.14s ease;
}

.ji-service-card:active {
  transform: translate(2rpx, 2rpx);
  box-shadow: var(--shadow-sm);
}

.ji-service-cover {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 176rpx;
  height: 176rpx;
  border: var(--stroke);
  border-radius: var(--radius-lg);
  color: #fff;
  font-size: 34rpx;
  font-weight: 900;
  background: var(--color-primary);
  background-size: cover;
  background-position: center;
  overflow: hidden;
  position: relative;
  z-index: 0;
}

.ji-service-cover text {
  position: relative;
  z-index: 1;
}

.ji-service-cover-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.ji-service-content {
  min-width: 0;
  flex: 1;
}

.ji-service-title-row {
  display: flex;
  gap: 16rpx;
  align-items: flex-start;
  justify-content: space-between;
}

.ji-service-title {
  flex: 1;
  color: var(--color-dark);
  font-size: 31rpx;
  font-weight: 900;
  line-height: 1.35;
  word-break: keep-all;
  overflow-wrap: break-word;
}

.ji-service-price {
  padding: 4rpx 12rpx;
  border-radius: 6rpx;
  color: #0F766E;
  font-size: 28rpx;
  font-weight: 800;
  background: #E8F8F5;
  flex-shrink: 0;
  white-space: nowrap;
  max-width: 50%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ji-service-desc {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 12rpx;
  color: var(--color-text-muted);
  font-size: 24rpx;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.ji-service-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 18rpx;
}

.ji-service-meta-item {
  border: none;
  border-radius: var(--radius-sm);
  padding: 8rpx 14rpx;
  font-size: 22rpx;
  font-weight: 700;
  background: #EBF3FF;
  color: #2F5BFF;
}

@media (max-width: 360px) {
  .ji-service-card {
    gap: 18rpx;
    padding: 20rpx;
  }

  .ji-service-cover {
    flex-basis: 148rpx;
    height: 148rpx;
    font-size: 30rpx;
  }

  .ji-service-title {
    font-size: 28rpx;
  }

  .ji-service-price {
    font-size: 28rpx;
  }

  .ji-service-meta-item {
    padding: 6rpx 10rpx;
    font-size: 20rpx;
  }
}
</style>
