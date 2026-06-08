<template>
  <view class="page-shell page-with-tab">
    <view class="hero-card">
      <text class="hello">讲师工作台</text>
      <text class="headline">{{ user.userInfo?.nickname || "同学" }}，今天也把技能变成价值</text>
      <view class="quick">
        <button @click="publish">发布服务</button>
        <button @click="bounties">去竞标</button>
        <button @click="orders">处理订单</button>
      </view>
    </view>

    <view class="stats">
      <view class="surface-card stat"><text class="num">{{ sellerOrders.length }}</text><text>卖家订单</text></view>
      <view class="surface-card stat"><text class="num">{{ waiting }}</text><text>待处理</text></view>
      <view class="surface-card stat"><text class="num">{{ user.userInfo?.creditScore || 100 }}</text><text>信誉分</text></view>
    </view>

    <view class="surface-card checklist">
      <text class="title">开通检查</text>
      <view class="row"><text>实名认证</text><text>{{ user.isVerified ? "已完成" : "待完成" }}</text></view>
      <view class="row"><text>讲师资格</text><text>{{ user.isSellerVerified ? "已通过" : "待审核" }}</text></view>
      <view class="row"><text>保证金</text><text>{{ user.hasDeposit ? "已缴纳" : "未缴纳" }}</text></view>
    </view>
    <ji-tab-bar />
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import JiTabBar from "@/components/ji-tab-bar.vue";
import { listOrders } from "@/api/order";
import { useUserStore } from "@/store/user";
import type { OrderItem } from "@/types/domain";

const user = useUserStore();
const sellerOrders = ref<OrderItem[]>([]);
const waiting = computed(() => sellerOrders.value.filter((item) => item.status === 20 || item.status === 30).length);

onShow(async () => {
  if (user.isLogin) sellerOrders.value = await listOrders("seller");
});

function publish() {
  uni.navigateTo({ url: "/pages/service/publish" });
}

function bounties() {
  uni.navigateTo({ url: "/pages/demand/list" });
}

function orders() {
  uni.reLaunch({ url: "/pages/tabbar/seller-order/index" });
}
</script>

<style scoped>
.hello {
  font-size: 24rpx;
  color: var(--color-text-muted);
}

.headline {
  display: block;
  margin-top: 30rpx;
  font-size: 42rpx;
  font-weight: 900;
  line-height: 1.35;
  color: var(--color-dark);
}

/* ── 快捷按钮：冰川蓝黑边硬阴影 ── */
.quick {
  display: flex;
  gap: 18rpx;
  margin-top: 36rpx;
}

.quick button {
  flex: 1;
  height: 76rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  color: var(--color-primary);
  font-weight: 900;
  background: var(--color-card);
  box-shadow: var(--shadow-sm);
  transition: transform 0.14s ease, box-shadow 0.14s ease;
}

.quick button:active {
  transform: translate(2rpx, 2rpx);
  box-shadow: none;
}

/* ── 资产看板：非对称双卡片 ── */
.stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16rpx;
  margin-top: 24rpx;
}

.stat {
  padding: 28rpx 16rpx;
  text-align: center;
  border: var(--stroke);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  font-size: 22rpx;
  transition: transform 0.14s ease, box-shadow 0.14s ease;
}

.stat:first-child {
  grid-column: span 2;
  background: var(--color-accent-1);
}

.stat:nth-child(2) {
  background: var(--color-bg-soft);
  /* 斑马斜线条纹 */
  background-image: repeating-linear-gradient(
    45deg,
    transparent,
    transparent 4rpx,
    rgba(0,0,0,0.04) 4rpx,
    rgba(0,0,0,0.04) 8rpx
  );
}

.stat:nth-child(3) {
  background: var(--color-card);
}

.stat:active {
  transform: translate(2rpx, 2rpx);
  box-shadow: var(--shadow-sm);
}

.num {
  display: block;
  font-size: 36rpx;
  font-weight: 900;
}

.stat:first-child .num {
  color: var(--color-dark);
  font-size: 48rpx;
}

.stat:nth-child(2) .num {
  color: var(--color-dark);
}

/* 信誉分：巨大墨黑数字 */
.stat:nth-child(3) .num {
  color: var(--color-dark);
  font-size: 42rpx;
}

.stat:first-child,
.stat:nth-child(2) {
  color: var(--color-text-secondary);
}

.stat:nth-child(3) {
  color: var(--color-text-muted);
}

/* ── 开通检查 ── */
.checklist {
  margin-top: 24rpx;
  padding: 30rpx;
}

.title {
  display: block;
  margin-bottom: 14rpx;
  color: var(--color-dark);
  font-size: 32rpx;
  font-weight: 900;
}

.row {
  display: flex;
  justify-content: space-between;
  padding: 22rpx 0;
  border-bottom: var(--stroke);
  color: var(--color-text-secondary);
}
</style>
