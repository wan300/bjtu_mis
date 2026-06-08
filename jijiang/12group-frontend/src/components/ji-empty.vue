<template>
  <view class="ji-empty">
    <view class="ji-empty-orb">匠</view>
    <text class="ji-empty-title">{{ title }}</text>
    <text class="ji-empty-desc">{{ desc }}</text>
    <button v-if="actionText" class="ghost-btn ji-empty-action" @click="$emit('action')">{{ actionText }}</button>
  </view>
</template>

<script setup lang="ts">
defineProps<{
  title?: string;
  desc?: string;
  actionText?: string;
}>();
defineEmits<{ action: [] }>();
</script>

<style>
.ji-empty {
  padding: 76rpx 36rpx;
  text-align: center;
}

.ji-empty-orb {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 132rpx;
  height: 132rpx;
  margin: 0 auto 24rpx;
  border: var(--stroke);
  border-radius: var(--radius-lg);
  color: var(--color-primary);
  font-size: 52rpx;
  font-weight: 900;
  background: var(--color-card);
  box-shadow: var(--shadow-md);
}

.ji-empty-title {
  display: block;
  color: var(--color-dark);
  font-size: 31rpx;
  font-weight: 900;
}

.ji-empty-desc {
  display: block;
  margin-top: 12rpx;
  color: var(--color-text-muted);
  font-size: 24rpx;
  line-height: 1.7;
}

.ji-empty-action {
  min-width: 260rpx;
  width: fit-content;
  margin: 30rpx auto 0;
  padding: 0 34rpx;
}
</style>
