<template>
  <view class="page-shell">
    <view class="hero-card balance-card">
      <text class="label">可提现余额</text>
      <text class="amount">¥{{ formatMoney(balance) }}</text>
      <text class="desc">提现后约 1-3 个工作日到账</text>
    </view>
    <view class="surface-card form">
      <view class="field">
        <view class="field-label">提现金额</view>
        <input v-model="amount" class="field-input" type="digit" placeholder="请输入金额" />
        <text class="hint">当前可提现 ¥{{ formatMoney(balance) }}</text>
      </view>
      <view class="field">
        <view class="field-label">收款信息</view>
        <input v-model="account" class="field-input" maxlength="255" placeholder="如 微信实名：张三" />
      </view>
      <button class="primary-btn" :loading="loading" :disabled="pageLoading" @click="submit">提交提现申请</button>
    </view>
    <view class="surface-card records">
      <text class="section-title">提现记录</text>
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
import { applyWithdraw, checkBalance, listWithdrawals } from "@/api/withdrawal";
import { useUserStore } from "@/store/user";
import type { WithdrawalRecord } from "@/types/domain";
import { withdrawStatusText, withdrawStatusTone } from "@/utils/status";
import { toast } from "@/utils/toast";

const user = useUserStore();
const amount = ref("");
const account = ref("");
const balance = ref(0);
const records = ref<WithdrawalRecord[]>([]);
const loading = ref(false);
const pageLoading = ref(false);

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
  pageLoading.value = true;
  try {
    const result = await checkBalance();
    balance.value = Number(result.balance || 0);
    const list = await listWithdrawals({ page: 1, pageSize: 20 });
    records.value = list.items || [];
  } catch {
    balance.value = 0;
    records.value = [];
  } finally {
    pageLoading.value = false;
  }
}

async function submit() {
  if (loading.value) return;
  if (pageLoading.value) {
    toast("余额加载中，请稍候");
    return;
  }
  const withdrawAmount = Number(amount.value);
  if (!withdrawAmount || withdrawAmount <= 0) {
    toast("提现金额必须大于 0");
    return;
  }
  if (withdrawAmount > balance.value) {
    toast("可提现余额不足");
    return;
  }
  if (!account.value.trim()) {
    toast("请填写收款信息");
    return;
  }
  loading.value = true;
  try {
    await applyWithdraw({ amount: withdrawAmount, payeeInfo: account.value.trim() });
    toast("已提交审核", "success");
    uni.navigateBack();
  } catch {
    await loadData();
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.balance-card {
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

.form {
  margin-top: 24rpx;
  padding: 34rpx;
}

.hint {
  display: block;
  margin-top: 12rpx;
  color: var(--color-text-muted);
  font-size: 24rpx;
}

.records {
  margin-top: 24rpx;
  padding: 30rpx;
}

.section-title {
  display: block;
  color: var(--color-dark);
  font-size: 32rpx;
  font-weight: 900;
}

.empty {
  padding: 36rpx 0 10rpx;
  color: var(--color-text-muted);
  text-align: center;
}

.record-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
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
