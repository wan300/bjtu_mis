<template>
  <view class="page-shell detail">
    <!-- 状态看板：根据 status 动态切换背景色 -->
    <view v-if="order" class="status-board" :class="'status-bg-' + order.status">
      <ji-status-pill :text="orderStatusText(order.status)" :tone="ORDER_STATUS[order.status]?.tone || 'idle'" />
      <text class="no">{{ order.orderNo }}</text>
      <text class="amount">¥{{ money(order.amount) }}</text>
    </view>

    <!-- 倒计时横幅 -->
    <view v-if="countdownText" class="countdown-banner" :class="{ urgent: countdownUrgent }">
      <text class="countdown-icon">⏳</text>
      <text class="countdown-label">{{ countdownText }}</text>
    </view>

    <!-- 服务信息卡片 -->
    <view v-if="order" class="surface-card card">
      <view class="service-row">
        <image v-if="coverUrl" :src="coverUrl" class="cover-img" mode="aspectFill" />
        <view v-else class="cover-placeholder">课</view>
        <view class="svc-info">
          <text class="svc-title">{{ order.serviceTitle || `服务 #${order.serviceId}` }}</text>
          <text class="svc-sub">
            {{ isBuyer ? `讲师: ${order.sellerName || `用户 #${order.sellerId}`}` : `买家: ${order.buyerName || `用户 #${order.buyerId}`}` }}
          </text>
        </view>
      </view>

      <view class="row"><text>订单备注</text><text class="val">{{ order.remark || "无" }}</text></view>

      <view v-if="order.deliverText" class="deliver-card">
        <text class="deliver-title">交付内容</text>
        <text class="deliver-body">{{ order.deliverText }}</text>
      </view>
    </view>

    <!-- 时间线 -->
    <view v-if="order" class="surface-card card">
      <view class="section-title"><text>订单进度</text></view>
      <view class="timeline">
        <view v-for="step in timeline" :key="step.label" :class="['tl-item', { done: step.done, current: step.current }]">
          <view class="tl-dot"><view class="tl-dot-inner" /></view>
          <view class="tl-body">
            <text class="tl-label">{{ step.label }}</text>
            <text class="tl-time">{{ step.time || '-' }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 操作区：双按钮并排 -->
    <view v-if="order" class="surface-card card">
      <view class="section-title"><text>操作</text></view>
      <view class="action-row">
        <button v-if="isBuyer && order.status === 10" class="primary-btn" :loading="paying" @click="continuePay">去支付 ¥{{ money(order.amount) }}</button>
        <button v-if="isBuyer && order.status === 40" class="primary-btn" @click="confirm">确认完成</button>
        <button v-if="isBuyer && order.status === 50 && !hasReview" class="primary-btn" @click="review">去评价</button>
        <button v-if="isBuyer && order.status === 50 && hasReview && !hasFollowUp" class="primary-btn" @click="followUp">追加评价</button>
        <button v-if="isBuyer && order.status === 50 && hasReview" class="ghost-btn" @click="viewReview">查看我的评价</button>
        <button v-if="isSeller && order.status === 20" class="primary-btn" @click="accept">立即接单</button>
      </view>
      <view v-if="isSeller && order.status === 30" class="field">
        <view class="field-label">交付说明</view>
        <textarea v-model="deliverText" class="field-textarea" placeholder="说明已完成的内容、学习成果、附件链接等" />
        <button class="primary-btn" @click="deliver">提交交付</button>
      </view>
      <view class="action-row">
        <button class="ghost-btn" @click="chat">订单站内信</button>
        <button class="ghost-btn danger" @click="refund">申请退款</button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onLoad, onShow, onUnload } from "@dcloudio/uni-app";
import JiStatusPill from "@/components/ji-status-pill.vue";
import { acceptOrder, confirmOrder, deliverOrder, getOrderDetail } from "@/api/order";
import { payOrder } from "@/api/payment";
import { getReviewByOrder } from "@/api/review";
import { useUserStore } from "@/store/user";
import type { OrderItem } from "@/types/domain";
import { money } from "@/utils/money";
import { normalizeImageUrl } from "@/utils/image";
import { ORDER_STATUS, orderStatusText } from "@/utils/status";
import { toast } from "@/utils/toast";

const user = useUserStore();
const orderId = ref(0);
const order = ref<OrderItem | null>(null);
const deliverText = ref("");
const paying = ref(false);
const hasReview = ref(false);
const hasFollowUp = ref(false);
const myReviewId = ref(0);
const isBuyer = computed(() => user.userInfo?.id === order.value?.buyerId);
const isSeller = computed(() => user.userInfo?.id === order.value?.sellerId);
const coverUrl = computed(() => normalizeImageUrl(order.value?.serviceCoverUrl));

const remainingSeconds = ref(0);
let countdownTimer: ReturnType<typeof setInterval> | undefined;

const countdownText = computed(() => {
  if (!order.value || remainingSeconds.value <= 0) return "";
  const s = remainingSeconds.value;
  const status = order.value.status;
  if (status === 10) {
    const m = Math.floor(s / 60);
    const sec = s % 60;
    return `请在 ${m} 分 ${sec} 秒内完成支付，超时订单将自动关闭`;
  }
  if (status === 20) {
    const h = Math.floor(s / 3600);
    return `若 ${h} 小时内卖家未接单，系统将自动退款`;
  }
  if (status === 40) {
    const d = Math.floor(s / 86400);
    return `若 ${d} 天内未确认，系统将自动确认完成`;
  }
  return "";
});

const countdownUrgent = computed(() => {
  if (!order.value) return false;
  return order.value.status === 10 && remainingSeconds.value < 120;
});

function startCountdown(initial: number) {
  stopCountdown();
  remainingSeconds.value = initial;
  if (initial <= 0) return;
  countdownTimer = setInterval(() => {
    remainingSeconds.value--;
    if (remainingSeconds.value <= 0) {
      stopCountdown();
    }
  }, 1000);
}

function stopCountdown() {
  if (countdownTimer) {
    clearInterval(countdownTimer);
    countdownTimer = undefined;
  }
}

const timeline = computed(() => {
  if (!order.value) return [];
  const o = order.value;
  return [
    { label: "提交订单", time: fmt(o.createTime), done: true, current: false },
    { label: "完成支付", time: fmt(o.payTime), done: !!o.payTime, current: !o.payTime },
    { label: "讲师接单", time: fmt((o as any).acceptTime), done: !!((o as any).acceptTime), current: false },
    { label: "交付完成", time: fmt((o as any).deliverTime), done: !!((o as any).deliverTime), current: false },
    { label: "确认完成", time: fmt((o as any).confirmTime), done: !!((o as any).confirmTime), current: false },
  ];
});

function fmt(t?: string) {
  if (!t) return "";
  return t.replace("T", " ").substring(0, 16);
}

onLoad((query) => {
  orderId.value = Number(query?.orderId || 0);
});

onShow(async () => {
  await load();
});

onUnload(() => {
  stopCountdown();
});

async function load() {
  if (orderId.value) {
    order.value = await getOrderDetail(orderId.value);
    if (order.value?.remainingSeconds) {
      startCountdown(order.value.remainingSeconds);
    }
    try {
      const review = await getReviewByOrder(orderId.value);
      hasReview.value = review != null;
      hasFollowUp.value = review != null && !!review.followUpContent;
      if (review) myReviewId.value = review.id;
    } catch { /* ignore */ }
  }
}

async function accept() { await acceptOrder(orderId.value); toast("已接单", "success"); await load(); }
async function deliver() { await deliverOrder(orderId.value, deliverText.value || "讲师已完成交付"); toast("已提交交付", "success"); await load(); }
async function confirm() { await confirmOrder(orderId.value); toast("订单已完成", "success"); await load(); }

async function continuePay() {
  if (!orderId.value || paying.value) return;
  paying.value = true;
  try {
    const cashier = await payOrder(orderId.value);
    uni.redirectTo({ url: `/pages/order/pay-result?orderId=${orderId.value}&payUrl=${encodeURIComponent(cashier.payUrl)}&qrCodeUrl=${encodeURIComponent(cashier.qrCodeUrl || "")}` });
  } finally { paying.value = false; }
}

function chat() { uni.navigateTo({ url: `/pages/chat/detail?orderId=${orderId.value}` }); }
function review() { uni.navigateTo({ url: `/pages/review/submit?orderId=${orderId.value}` }); }
function followUp() { uni.navigateTo({ url: `/pages/review/submit?orderId=${orderId.value}&followUp=1` }); }
function viewReview() { uni.navigateTo({ url: `/pages/review/list?serviceId=${order.value!.serviceId}` }); }
function refund() { uni.navigateTo({ url: `/pages/order/refund?orderId=${orderId.value}` }); }
</script>

<style scoped>
.detail { padding-bottom: 60rpx; }

/* ── 状态看板：根据 status 动态切换背景色 ── */
.status-board {
  display: flex;
  flex-direction: column;
  gap: 18rpx;
  border: var(--stroke);
  border-radius: var(--radius-xl);
  padding: 36rpx;
  box-shadow: var(--shadow-md);
}

.status-bg-10 { background: var(--color-accent-2); }  /* 待支付 → 奶油黄 */
.status-bg-20 { background: var(--color-primary); color: #fff; }  /* 待接单 → 冰川蓝 */
.status-bg-30 { background: var(--color-accent-1); }  /* 服务中 → 薄荷绿 */
.status-bg-40 { background: var(--color-accent-1); }  /* 待确认 → 薄荷绿 */
.status-bg-50 { background: var(--color-bg-main); }   /* 已完成 → 极光白 */
.status-bg-60 { background: var(--color-accent-3); color: #fff; }  /* 已关闭 → 珊瑚粉 */
.status-bg-70 { background: var(--color-accent-3); color: #fff; }  /* 退款中 → 珊瑚粉 */
.status-bg-80 { background: var(--color-accent-3); color: #fff; }  /* 已退款 → 珊瑚粉 */

.status-bg-20 .no,
.status-bg-60 .no,
.status-bg-70 .no,
.status-bg-80 .no {
  color: rgba(255,255,255,0.82);
}

.status-bg-20 .amount,
.status-bg-60 .amount,
.status-bg-70 .amount,
.status-bg-80 .amount {
  color: #fff;
}

.no { opacity: 0.82; font-size: 24rpx; }
.amount { font-size: 58rpx; font-weight: 900; color: var(--color-dark); }

/* ── 倒计时横幅 ── */
.countdown-banner {
  display: flex;
  align-items: center;
  gap: 14rpx;
  margin-top: 24rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  padding: 22rpx 28rpx;
  background: var(--color-primary-soft);
  box-shadow: var(--shadow-sm);
}
.countdown-banner.urgent {
  background: var(--color-accent-2);
}
.countdown-icon { font-size: 32rpx; flex-shrink: 0; }
.countdown-label { color: var(--color-primary); font-size: 26rpx; font-weight: 700; }
.urgent .countdown-label { color: var(--color-accent-3); }

/* ── 卡片 ── */
.card { margin-top: 24rpx; padding: 30rpx; }

.row { display: flex; justify-content: space-between; padding: 20rpx 0; color: var(--color-text-muted); border-bottom: var(--stroke); }
.row .val { color: var(--color-dark); font-weight: 700; text-align: right; max-width: 60%; }

.service-row { display: flex; align-items: center; gap: 18rpx; padding: 12rpx 0 22rpx; border-bottom: var(--stroke); }
.cover-img, .cover-placeholder { width: 90rpx; height: 90rpx; border: var(--stroke); border-radius: var(--radius-md); flex-shrink: 0; }
.cover-img { background: #f0f2f7; }
.cover-placeholder { display: flex; align-items: center; justify-content: center; color: #fff; font-size: 36rpx; font-weight: 900; background: var(--color-primary); }
.svc-info { flex: 1; min-width: 0; }
.svc-title { display: block; color: var(--color-dark); font-size: 30rpx; font-weight: 800; }
.svc-sub { display: block; margin-top: 6rpx; color: var(--color-text-muted); font-size: 24rpx; }

.deliver-card { margin-top: 22rpx; border: var(--stroke); border-radius: var(--radius-md); padding: 24rpx; background: var(--color-card-muted); }
.deliver-title { display: block; color: var(--color-primary); font-size: 26rpx; font-weight: 800; margin-bottom: 12rpx; }
.deliver-body { display: block; color: var(--color-text-secondary); font-size: 26rpx; line-height: 1.9; white-space: pre-line; }

/* ── 时间线：粗线条 + 冰川蓝圆点 ── */
.timeline { padding-left: 8rpx; }
.tl-item { display: flex; gap: 18rpx; padding-bottom: 26rpx; position: relative; }
.tl-item:not(:last-child)::after { content: ''; position: absolute; left: 11rpx; top: 28rpx; bottom: 0; width: 3rpx; background: var(--color-dark); opacity: 0.18; }
.tl-item.done:not(:last-child)::after { background: var(--color-primary); opacity: 1; }
.tl-dot { flex-shrink: 0; width: 24rpx; height: 24rpx; border: var(--stroke); border-radius: 50%; background: var(--color-bg-main); display: flex; align-items: center; justify-content: center; margin-top: 4rpx; }
.tl-item.done .tl-dot { background: var(--color-primary); border-color: var(--color-primary); }
.tl-item.current .tl-dot { background: #fff; border-color: var(--color-primary); border-width: 4rpx; }
.tl-dot-inner { width: 10rpx; height: 10rpx; border-radius: 50%; background: #fff; }
.tl-body { flex: 1; }
.tl-label { display: block; color: var(--color-text-faint); font-size: 26rpx; }
.tl-item.done .tl-label, .tl-item.current .tl-label { color: var(--color-dark); font-weight: 700; }
.tl-time { display: block; margin-top: 4rpx; color: var(--color-text-faint); font-size: 22rpx; }

/* ── 操作按钮：双按钮并排 ── */
.action-row {
  display: flex;
  gap: 16rpx;
}

.action-row .primary-btn,
.action-row .ghost-btn {
  flex: 1;
}

.ghost-btn { margin-top: 18rpx; }
.danger { color: var(--color-accent-3); }
.field-label { display: block; margin-bottom: 14rpx; color: var(--color-text-muted); font-size: 26rpx; font-weight: 700; }
</style>
