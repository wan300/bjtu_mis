<template>
  <web-view v-if="url" :src="url" />
  <view v-else class="page-shell">
    <view class="surface-card doc">
      <text class="title">{{ title }}</text>
      <text class="p">欢迎使用技匠。平台提供实名认证、担保交易、站内沟通、评价信誉与争议处理能力。</text>
      <text class="p">请勿私下交易、交换联系方式或发布代写作弊等违规服务。</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";

const title = ref("技匠服务协议");
const url = ref("");

onLoad((query) => {
  if (query?.title) title.value = decodeURIComponent(String(query.title));
  if (query?.url) url.value = decodeURIComponent(String(query.url));
});
</script>

<style scoped>
.doc {
  padding: 36rpx;
}

.title {
  display: block;
  color: var(--color-dark);
  font-size: 38rpx;
  font-weight: 900;
}

.p {
  display: block;
  margin-top: 24rpx;
  color: var(--color-text-secondary);
  font-size: 28rpx;
  line-height: 1.8;
}
</style>
