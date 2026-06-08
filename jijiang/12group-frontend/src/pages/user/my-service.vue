<template>
  <view class="page-shell">
    <view class="hero-card">
      <text class="title">我的服务</text>
      <text class="desc">当前可发布服务并等待后台审核，服务列表接口待后端补齐。</text>
    </view>
    <ji-empty title="暂无可展示服务" desc="/api/service/my-list 尚未实现，发布记录会在后端补齐后展示。" action-text="发布服务" @action="publish" />
  </view>
</template>

<script setup lang="ts">
import JiEmpty from "@/components/ji-empty.vue";

function publish() {
  uni.navigateTo({ url: "/pages/service/publish" });
}
</script>

<style scoped>
.title,
.desc {
  display: block;
}

.title {
  font-size: 42rpx;
  font-weight: 900;
}

.desc {
  margin-top: 18rpx;
  opacity: 0.82;
  line-height: 1.6;
}
</style>
