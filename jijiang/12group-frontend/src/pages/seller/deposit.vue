<template>
  <view class="page-shell">
    <view class="hero-card">
      <text class="title">讲师保证金</text>
      <text class="amount">¥{{ depositAmount }}</text>
      <text class="desc">缴纳保证金后可发布服务。保证金用于约束履约，无未完结订单后可申请退还。</text>
    </view>
    <view class="surface-card card">
      <view class="row"><text>缴纳状态</text><text :class="user.hasDeposit ? 'green' : 'orange'">{{ statusText }}</text></view>
      <view class="row" v-if="lastRecord"><text>支付单号</text><text>{{ lastRecord.outTradeNo || "-" }}</text></view>
      <view class="row" v-if="lastRecord?.payTime"><text>缴纳时间</text><text>{{ lastRecord.payTime }}</text></view>
    </view>
    <button v-if="!user.hasDeposit" class="primary-btn" :loading="loading" @click="pay">缴纳保证金</button>
    <button v-else class="ghost-btn" disabled>已缴纳</button>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { createDeposit, getDepositStatus } from "@/api/deposit";
import { useUserStore } from "@/store/user";
import { toast } from "@/utils/toast";

const user = useUserStore();
const loading = ref(false);
const depositAmount = ref(0.01);
const lastRecord = ref<{ outTradeNo?: string; payTime?: string } | null>(null);
const redirect = ref("/pages/service/publish");

const statusText = computed(() => (user.hasDeposit ? "已缴纳" : "未缴纳"));

onLoad((query) => {
  if (query?.redirect) redirect.value = decodeURIComponent(String(query.redirect));
});

async function fetchStatus() {
  try {
    const result = await getDepositStatus();
    user.updateDepositPaid(result.depositPaid === 1);
    if (result.records?.length) {
      depositAmount.value = Number(result.records[0].amount || depositAmount.value);
      lastRecord.value = result.records[0];
      const paid = result.records.find((r) => r.status === 1);
      if (paid) lastRecord.value = paid;
    }
  } catch {
    // 忽略加载失败
  }
}

async function pay() {
  loading.value = true;
  try {
    const result = await createDeposit();
    depositAmount.value = Number(result.amount);
    if (result.paid) {
      user.updateDepositPaid(true);
      toast(result.message || "保证金已缴纳", "success");
      goRedirect();
      return;
    }
    toast(result.message || "保证金支付单已创建", "success");
    uni.redirectTo({
      url:
        `/pages/seller/deposit-result?outTradeNo=${encodeURIComponent(result.outTradeNo || result.tradeOrderId || "")}` +
        `&payUrl=${encodeURIComponent(result.payUrl || "")}` +
        `&qrCodeUrl=${encodeURIComponent(result.qrCodeUrl || "")}` +
        `&redirect=${encodeURIComponent(redirect.value)}`,
    });
  } catch {
    // 错误已在 request 中处理
  } finally {
    loading.value = false;
  }
}

fetchStatus();

function goRedirect() {
  if (redirect.value.startsWith("/pages/tabbar/")) {
    uni.switchTab({ url: redirect.value });
    return;
  }
  uni.redirectTo({ url: redirect.value });
}
</script>

<style scoped>
.title,
.amount,
.desc {
  display: block;
}

.title {
  font-size: 36rpx;
  font-weight: 900;
}

.amount {
  margin: 24rpx 0;
  font-size: 76rpx;
  font-weight: 900;
}

.desc {
  opacity: 0.82;
  line-height: 1.6;
}

.card {
  margin: 24rpx 0;
  padding: 30rpx;
}

.row {
  display: flex;
  justify-content: space-between;
  padding: 24rpx 0;
}

.green {
  color: #16a34a;
  font-weight: 800;
}

.orange {
  color: #ea580c;
  font-weight: 800;
}
</style>
