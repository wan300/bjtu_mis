<template>
  <view class="page-shell result">
    <view class="surface-card panel">
      <view class="mark" :class="{ pending: !paid }">{{ paid ? "✓" : "¥" }}</view>
      <text class="title">{{ paid ? "保证金已缴纳" : "等待支付确认" }}</text>
      <text class="desc">
        {{ paid ? "讲师保证金已生效，可以继续发布服务。" : "完成付款后，页面会自动同步保证金状态。" }}
      </text>

      <template v-if="paid">
        <button class="primary-btn" @click="goRedirect">继续发布服务</button>
      </template>

      <template v-else>
        <button v-if="payUrl" class="primary-btn" @click="openCashier">打开收银台</button>
        <button v-if="qrCodeUrl" class="ghost-btn" @click="previewQr">查看付款码</button>
        <button class="ghost-btn" :loading="checking" @click="checkPaid">刷新状态</button>
        <button class="ghost-btn" @click="backToDeposit">返回保证金</button>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onLoad, onUnload } from "@dcloudio/uni-app";
import { syncDeposit } from "@/api/deposit";
import { useUserStore } from "@/store/user";
import { toast } from "@/utils/toast";

const user = useUserStore();
const outTradeNo = ref("");
const payUrl = ref("");
const qrCodeUrl = ref("");
const redirect = ref("/pages/service/publish");
const paid = ref(false);
const checking = ref(false);
let timer: ReturnType<typeof setInterval> | undefined;

onLoad((query) => {
  outTradeNo.value = String(query?.outTradeNo || "");
  payUrl.value = normalizeExternalUrl(decodeURIComponent(String(query?.payUrl || "")), "");
  qrCodeUrl.value = normalizeExternalUrl(decodeURIComponent(String(query?.qrCodeUrl || "")), payUrl.value);
  if (query?.redirect) redirect.value = decodeURIComponent(String(query.redirect));
  timer = setInterval(checkPaid, 2000);
  checkPaid();
});

onUnload(() => {
  if (timer) clearInterval(timer);
});

function openCashier() {
  if (!payUrl.value) return;
  uni.navigateTo({ url: `/pages/common/webview?url=${encodeURIComponent(payUrl.value)}&title=${encodeURIComponent("保证金收银台")}` });
}

function previewQr() {
  if (!qrCodeUrl.value) return;
  uni.previewImage({ urls: [qrCodeUrl.value] });
}

async function checkPaid() {
  if (!outTradeNo.value || checking.value || paid.value) return;
  checking.value = true;
  try {
    const result = await syncDeposit(outTradeNo.value);
    if (result.paid || result.depositPaid === 1 || result.status === 1) {
      paid.value = true;
      user.updateDepositPaid(true);
      if (timer) clearInterval(timer);
      toast("保证金支付成功", "success");
      setTimeout(goRedirect, 600);
    }
  } finally {
    checking.value = false;
  }
}

function goRedirect() {
  if (redirect.value.startsWith("/pages/tabbar/")) {
    uni.switchTab({ url: redirect.value });
    return;
  }
  uni.redirectTo({ url: redirect.value });
}

function backToDeposit() {
  uni.redirectTo({ url: `/pages/seller/deposit?redirect=${encodeURIComponent(redirect.value)}` });
}

function normalizeExternalUrl(value: string, baseUrl: string) {
  const trimmed = value.trim();
  if (!trimmed) return "";
  if (trimmed.startsWith("//")) return `https:${trimmed}`;
  if (/^https?:\/\//i.test(trimmed)) return trimmed;
  if (!baseUrl) return trimmed;
  try {
    return new URL(trimmed, baseUrl).toString();
  } catch {
    return trimmed;
  }
}
</script>

<style scoped>
.result {
  display: flex;
  align-items: center;
  justify-content: center;
}

.panel {
  width: 100%;
  padding: 60rpx 36rpx;
  text-align: center;
}

/* 状态图标：黑边方块风格 */
.mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 140rpx;
  height: 140rpx;
  margin: 0 auto 30rpx;
  border: var(--stroke);
  border-radius: var(--radius-lg);
  color: #fff;
  font-size: 72rpx;
  font-weight: 900;
  background: var(--color-accent-1);
  box-shadow: var(--shadow-md);
}

.mark.pending {
  background: var(--color-accent-2);
  color: var(--color-dark);
}

.title,
.desc {
  display: block;
}

.title {
  color: var(--color-dark);
  font-size: 40rpx;
  font-weight: 900;
}

.desc {
  margin: 18rpx 0 40rpx;
  color: var(--color-text-muted);
}

.ghost-btn {
  margin-top: 20rpx;
}
</style>
