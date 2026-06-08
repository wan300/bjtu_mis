<template>
  <view class="page-shell empty-page">
    <ji-empty title="这里暂时没有内容" desc="等后端接口与运营内容接入后会自动展示。" action-text="回首页" @action="home" />
  </view>
</template>

<script setup lang="ts">
import JiEmpty from "@/components/ji-empty.vue";

function home() {
  uni.switchTab({ url: "/pages/tabbar/home/index" });
}
</script>

<style scoped>
.empty-page {
  min-height: 100vh;
}
</style>
