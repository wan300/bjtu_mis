<template>
  <view class="page-shell">
    <view class="surface-card card">
      <text class="title">第三方服务身份</text>
      <text class="desc">
        技匠在 BJTU MIS App 内使用已授权的 BJTU 身份，不提供单独退出登录。需要停止使用时，请返回服务列表管理第三方服务授权。
      </text>
      <button class="primary-btn" @click="closeService">返回服务列表</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { closeBjtuService } from "@/api/bjtu-service";
import { toast } from "@/utils/toast";

async function closeService() {
  try {
    await closeBjtuService();
  } catch {
    toast("无法返回服务列表");
  }
}
</script>

<style scoped>
.card {
  padding: 36rpx;
}

.title {
  color: var(--color-dark);
  font-size: 38rpx;
  font-weight: 900;
}

.desc {
  display: block;
  margin: 18rpx 0 34rpx;
  color: #748198;
  font-size: 26rpx;
  line-height: 1.7;
}
</style>
