<template>
  <view class="page-shell">
    <view class="hero-card credit">
      <text class="score">{{ user.userInfo?.creditScore || 100 }}</text>
      <text class="level">{{ creditLevel(user.userInfo?.creditScore) }}</text>
      <text class="desc">信誉分由订单评价、履约状态和争议结果共同影响。</text>
    </view>
    <view class="surface-card rule">
      <text class="title">信誉规则</text>
      <text>5 星 +2，4 星 +1，2 星 -3，1 星 -5；仲裁败诉、超时取消会额外扣分。</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { useUserStore } from "@/store/user";
import { creditLevel } from "@/utils/status";

const user = useUserStore();
</script>

<style scoped>
.credit {
  text-align: center;
}

.score {
  display: block;
  font-size: 96rpx;
  font-weight: 900;
}

.level,
.desc {
  display: block;
}

.level {
  font-size: 32rpx;
  font-weight: 900;
}

.desc {
  margin-top: 22rpx;
  opacity: 0.82;
  line-height: 1.6;
}

.rule {
  margin-top: 24rpx;
  padding: 34rpx;
  color: var(--color-text-muted);
  line-height: 1.7;
}

.title {
  display: block;
  margin-bottom: 18rpx;
  color: var(--color-dark);
  font-size: 32rpx;
  font-weight: 900;
}
</style>
