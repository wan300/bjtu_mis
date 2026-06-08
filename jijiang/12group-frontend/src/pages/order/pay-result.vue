<template>
  <view class="page-shell result">
    <view class="surface-card panel">
      <view class="mark" :class="{ pending: !paid && !closed, closed: closed }">{{ closed ? "⏰" : (paid ? "✓" : "¥") }}</view>
      <text class="title">{{ closed ? "支付已超时" : (paid ? "支付成功" : "扫码完成支付") }}</text>
      <text class="desc">
        {{ closed ? "订单已自动关闭，可重新下单购买。" : (paid ? "资金已进入平台担保，正在跳转订单详情。" : "请扫码付款，系统会自动确认支付状态。") }}
      </text>

      <view v-if="order" class="order-summary">
        <view class="summary-row">
          <text>订单编号</text>
          <text>{{ order.orderNo }}</text>
        </view>
        <view class="summary-row">
          <text>支付金额</text>
          <text class="amount">¥{{ money(order.amount) }}</text>
        </view>
      </view>

      <template v-if="!paid && !closed">
        <view v-if="paymentRemaining > 0" class="countdown-tip">
          <text class="countdown-num">{{ formatCountdown(paymentRemaining) }}</text>
          <text class="countdown-hint">内完成支付，超时订单将自动关闭</text>
        </view>

        <view v-if="hasQrCode" class="qr-box" @click="previewQr">
          <image class="qr-image" :src="qrCodeUrl" mode="aspectFit" @error="handleQrError" />
        </view>

        <view v-else class="fallback">
          <text class="fallback-title">付款二维码暂不可用</text>
          <text class="fallback-desc">可通过收银台继续完成支付。</text>
          <button v-if="payUrl" class="primary-btn" @click="openCashier">打开收银台</button>
          <button v-else class="ghost-btn" @click="detail">返回订单详情</button>
        </view>

        <view class="status-line">
          <view class="dot"></view>
          <text>{{ checking ? "正在确认支付状态" : "等待付款完成" }}</text>
        </view>
      </template>

      <template v-else-if="closed">
        <view class="status-line closed">
          <view class="dot"></view>
          <text>订单已关闭</text>
        </view>
        <button class="ghost-btn" @click="detail">查看订单详情</button>
      </template>

      <template v-else>
        <view class="status-line success">
          <view class="dot"></view>
          <text>支付已确认</text>
        </view>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onLoad, onUnload } from "@dcloudio/uni-app";
import { getOrderDetail } from "@/api/order";
import { syncPaymentStatus } from "@/api/payment";
import type { OrderItem } from "@/types/domain";
import { money } from "@/utils/money";
import { toast } from "@/utils/toast";

const orderId = ref(0);
const order = ref<OrderItem | null>(null);
const payUrl = ref("");
const qrCodeUrl = ref("");
const qrCodeBroken = ref(false);
const paid = ref(false);
const checking = ref(false);
const redirecting = ref(false);
const hasQrCode = computed(() => Boolean(qrCodeUrl.value && !qrCodeBroken.value));
const closed = ref(false);
let timer: ReturnType<typeof setInterval> | undefined;
let redirectTimer: ReturnType<typeof setTimeout> | undefined;

function isPaidStatus(status: number) {
  return status === 20 || status === 30 || status === 40 || status === 50;
}

function orderChangedMessage(status: number) {
  if (status === 70) return "订单退款处理中";
  if (status === 80) return "订单已退款";
  return "";
}

const paymentRemaining = ref(0);
let countdownTimer: ReturnType<typeof setInterval> | undefined;

function formatCountdown(seconds: number) {
  if (seconds <= 0) return "00:00";
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

function startPaymentCountdown(seconds: number) {
  stopPaymentCountdown();
  paymentRemaining.value = seconds;
  if (seconds <= 0) return;
  countdownTimer = setInterval(() => {
    paymentRemaining.value--;
    if (paymentRemaining.value <= 0) {
      stopPaymentCountdown();
    }
  }, 1000);
}

function stopPaymentCountdown() {
  if (countdownTimer) {
    clearInterval(countdownTimer);
    countdownTimer = undefined;
  }
}

onLoad((query) => {
  orderId.value = Number(query?.orderId || 0);
  payUrl.value = normalizeExternalUrl(decodeURIComponent(String(query?.payUrl || "")), "");
  qrCodeUrl.value = normalizeExternalUrl(decodeURIComponent(String(query?.qrCodeUrl || "")), payUrl.value);
  qrCodeBroken.value = false;
  loadOrder();
  startPolling();
  checkPaid();
});

onUnload(() => {
  clearPolling();
  stopPaymentCountdown();
  if (redirectTimer) clearTimeout(redirectTimer);
});

function openCashier() {
  if (!payUrl.value) return;
  uni.navigateTo({ url: `/pages/common/webview?url=${encodeURIComponent(payUrl.value)}&title=${encodeURIComponent("虎皮椒收银台")}` });
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

async function loadOrder() {
  if (!orderId.value) return;
  try {
    const detail = await getOrderDetail(orderId.value);
    order.value = detail;
    const changedMessage = orderChangedMessage(detail.status);
    if (detail.status === 60) {
      finishClosed();
    } else if (changedMessage) {
      finishOrderChanged(changedMessage);
    } else if (isPaidStatus(detail.status)) {
      finishPaid();
    } else {
      const secs = detail.remainingSeconds ?? 300;
      startPaymentCountdown(secs);
    }
  } catch {
    // Payment polling can still complete even when the display summary fails.
  }
}

function startPolling() {
  clearPolling();
  timer = setInterval(() => {
    checkPaid();
  }, 2000);
}

function clearPolling() {
  if (timer) {
    clearInterval(timer);
    timer = undefined;
  }
}

async function checkPaid() {
  if (!orderId.value || checking.value || paid.value || redirecting.value || closed.value) return;
  checking.value = true;
  try {
    const synced = await syncPaymentStatus(orderId.value, { silent: true });
    const syncedChangedMessage = orderChangedMessage(synced.status);
    if (synced.status === 60) {
      finishClosed();
      return;
    }
    if (syncedChangedMessage) {
      finishOrderChanged(syncedChangedMessage);
      return;
    }
    if (synced.paid || isPaidStatus(synced.status)) {
      finishPaid();
      return;
    }
    const detail = await getOrderDetail(orderId.value);
    order.value = detail;
    const changedMessage = orderChangedMessage(detail.status);
    if (detail.status === 60) {
      finishClosed();
    } else if (changedMessage) {
      finishOrderChanged(changedMessage);
    } else if (isPaidStatus(detail.status)) {
      finishPaid();
    }
  } catch {
    // Keep polling quietly; transient payment status failures are common while the user is paying.
  } finally {
    checking.value = false;
  }
}

function finishPaid() {
  if (redirecting.value) return;
  paid.value = true;
  redirecting.value = true;
  clearPolling();
  toast("支付成功", "success");
  redirectTimer = setTimeout(detail, 700);
}

function finishClosed() {
  if (redirecting.value) return;
  closed.value = true;
  redirecting.value = true;
  clearPolling();
  stopPaymentCountdown();
  toast("订单已超时关闭", "none");
  redirectTimer = setTimeout(detail, 1500);
}

function finishOrderChanged(message: string) {
  if (redirecting.value) return;
  redirecting.value = true;
  clearPolling();
  stopPaymentCountdown();
  toast(message, "none");
  redirectTimer = setTimeout(detail, 1500);
}

function detail() {
  uni.redirectTo({ url: `/pages/order/detail?orderId=${orderId.value}` });
}

function previewQr() {
  if (!hasQrCode.value) return;
  uni.previewImage({ urls: [qrCodeUrl.value] });
}

function handleQrError() {
  qrCodeBroken.value = true;
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

/* ── 状态图标：黑边方块风格 ── */
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

.mark.closed {
  background: var(--color-accent-3);
  color: #fff;
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

/* ── 倒计时 ── */
.countdown-tip {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 6rpx;
  margin-bottom: 28rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  padding: 18rpx 24rpx;
  background: var(--color-accent-2);
  box-shadow: var(--shadow-sm);
}
.countdown-num {
  color: var(--color-accent-3);
  font-size: 36rpx;
  font-weight: 900;
  font-variant-numeric: tabular-nums;
}
.countdown-hint {
  color: var(--color-warning);
  font-size: 24rpx;
}

/* ── 订单摘要 ── */
.order-summary {
  margin-bottom: 32rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  padding: 6rpx 24rpx;
  background: var(--color-card-muted);
}

.summary-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  padding: 18rpx 0;
  color: var(--color-text-muted);
  font-size: 26rpx;
  border-bottom: var(--stroke);
}

.summary-row:last-child {
  border-bottom: 0;
}

.summary-row text:last-child {
  min-width: 0;
  color: var(--color-dark);
  font-weight: 800;
  text-align: right;
  word-break: break-all;
}

.summary-row .amount {
  color: var(--color-price);
  font-size: 34rpx;
}

/* ── 二维码框 ── */
.qr-box {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 420rpx;
  height: 420rpx;
  margin: 0 auto 30rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  padding: 24rpx;
  background: #fff;
  box-shadow: var(--shadow-md);
}

.qr-image {
  width: 100%;
  height: 100%;
}

/* ── 收银台降级 ── */
.fallback {
  margin-bottom: 28rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  padding: 34rpx 28rpx;
  background: var(--color-card-muted);
}

.fallback-title,
.fallback-desc {
  display: block;
}

.fallback-title {
  color: var(--color-dark);
  font-size: 30rpx;
  font-weight: 800;
}

.fallback-desc {
  margin: 10rpx 0 24rpx;
  color: var(--color-text-muted);
  font-size: 24rpx;
}

/* ── 状态指示行 ── */
.status-line {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
  margin-top: 8rpx;
  color: var(--color-text-muted);
  font-size: 26rpx;
}

.status-line.success {
  color: var(--color-accent-1);
  font-weight: 800;
}

.dot {
  width: 14rpx;
  height: 14rpx;
  border: 2px solid var(--color-dark);
  background: var(--color-accent-2);
}

.success .dot {
  background: var(--color-accent-1);
}

.status-line.closed {
  color: var(--color-text-faint);
  font-weight: 800;
}

.closed .dot {
  background: var(--color-text-faint);
}

.ghost-btn {
  margin-top: 20rpx;
}
</style>
