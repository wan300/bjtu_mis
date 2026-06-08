<template>
  <view class="page-shell">
    <view class="hero-card income">
      <text class="label">可提现余额</text>
      <text class="amount">¥{{ formatMoney(balance) }}</text>
      <text class="desc">累计收入 ¥{{ formatMoney(totalIncome) }}，平台按 90% 结算</text>
    </view>
    <view class="surface-card card">
      <view class="row"><text>冻结中</text><text>¥{{ formatMoney(frozenBalance) }}</text></view>
      <view class="row"><text>提现中</text><text>¥{{ formatMoney(pendingWithdraw) }}</text></view>
      <view class="row"><text>累计提现</text><text>¥{{ formatMoney(withdrawnTotal) }}</text></view>
      <view class="row"><text>平台佣金</text><text>订单完成后按规则结算</text></view>
    </view>
    <button class="primary-btn" :loading="loading" @click="withdraw">申请提现</button>
    <view class="surface-card records">
      <view class="records-head">
        <text class="section-title">提现记录</text>
        <text class="more" @click="withdraw">查看全部</text>
      </view>
      <view v-if="records.length === 0" class="empty">暂无提现记录</view>
      <view v-for="item in records" :key="item.id" class="record-row">
        <view>
          <text class="record-amount">¥{{ formatMoney(item.amount) }}</text>
          <text class="record-time">{{ formatDate(item.createTime) }}</text>
        </view>
        <ji-status-pill :text="withdrawStatusText(item.status)" :tone="withdrawStatusTone(item.status)" />
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import JiStatusPill from "@/components/ji-status-pill.vue";
import { checkBalance, listWithdrawals } from "@/api/withdrawal";
import { useUserStore } from "@/store/user";
import type { WithdrawalRecord } from "@/types/domain";
import { withdrawStatusText, withdrawStatusTone } from "@/utils/status";
import { toast } from "@/utils/toast";

const user = useUserStore();
const loading = ref(false);
const balance = ref(0);
const frozenBalance = ref(0);
const pendingWithdraw = ref(0);
const totalIncome = ref(0);
const withdrawnTotal = ref(0);
const records = ref<WithdrawalRecord[]>([]);

onShow(() => {
  loadData();
});

function formatMoney(value?: number | string) {
  return Number(value || 0).toFixed(2);
}

function formatDate(value?: string) {
  if (!value) return "-";
  return String(value).replace("T", " ").slice(0, 19);
}

async function loadData() {
  if (user.currentRole !== 2) {
    toast("请先进入讲师模式");
    uni.navigateBack();
    return;
  }
  if (!user.isSellerVerified) {
    toast("仅讲师可查看");
    uni.navigateBack();
    return;
  }
  loading.value = true;
  try {
    const result = await checkBalance();
    balance.value = Number(result.balance || 0);
    frozenBalance.value = Number(result.frozenBalance || 0);
    pendingWithdraw.value = Number(result.pendingWithdraw || 0);
    totalIncome.value = Number(result.totalIncome || 0);
    withdrawnTotal.value = Number(result.withdrawnTotal || 0);
    const list = await listWithdrawals({ page: 1, pageSize: 5 });
    records.value = list.items || [];
  } catch {
    balance.value = 0;
    frozenBalance.value = 0;
    pendingWithdraw.value = 0;
    totalIncome.value = 0;
    withdrawnTotal.value = 0;
    records.value = [];
  } finally {
    loading.value = false;
  }
}

function withdraw() {
  uni.navigateTo({ url: "/pages/seller/withdraw" });
}
</script>

<style scoped>
.income {
  text-align: center;
}

.label,
.amount,
.desc {
  display: block;
}

.amount {
  margin: 18rpx 0;
  font-size: 70rpx;
  font-weight: 900;
}

.desc {
  opacity: 0.82;
}

.card {
  margin: 24rpx 0;
  padding: 30rpx;
}

.row {
  display: flex;
  justify-content: space-between;
  padding: 24rpx 0;
  border-bottom: var(--stroke);
}

.records {
  margin-top: 24rpx;
  padding: 30rpx;
}

.records-head,
.record-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-title {
  color: var(--color-dark);
  font-size: 32rpx;
  font-weight: 900;
}

.more {
  color: var(--color-primary);
  font-size: 24rpx;
  font-weight: 800;
}

.empty {
  padding: 36rpx 0 10rpx;
  color: var(--color-text-muted);
  text-align: center;
}

.record-row {
  padding: 24rpx 0;
  border-bottom: var(--stroke);
}

.record-amount,
.record-time {
  display: block;
}

.record-amount {
  color: var(--color-dark);
  font-size: 30rpx;
  font-weight: 900;
}

.record-time {
  margin-top: 8rpx;
  color: var(--color-text-muted);
  font-size: 22rpx;
}
</style>
