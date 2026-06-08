<template>
  <view class="page-shell chat">
    <view v-if="orderCtx" class="order-ctx" @click="goOrder">
      <view class="ctx-img-wrap">
        <image v-if="ctxCoverUrl" :src="ctxCoverUrl" class="ctx-img" mode="aspectFill" />
        <text v-else class="ctx-placeholder">课</text>
      </view>
      <view class="ctx-info">
        <text class="ctx-title">{{ orderCtx.serviceTitle || '订单 #' + orderId }}</text>
        <view class="ctx-row">
          <text class="ctx-amount">¥{{ orderCtx.amount }}</text>
          <text class="ctx-status">{{ orderStatusText(orderCtx.status) }}</text>
        </view>
        <text class="ctx-party">
          与{{ orderCtx.isBuyer ? '讲师 ' + (orderCtx.sellerName || '对方') : '买家 ' + (orderCtx.buyerName || '对方') }}的沟通
        </text>
      </view>
      <text class="ctx-arrow">›</text>
    </view>

    <view class="tips">平台内沟通会留存为仲裁凭证，请勿交换微信、电话等联系方式。</view>

    <scroll-view class="messages" scroll-y :scroll-top="scrollTop" :scroll-with-animation="true">
      <block v-for="(item, i) in messages" :key="item.id">
        <view v-if="showTimeGap(i)" class="time-divider">
          <text>{{ formatMsgTime(item.createTime) }}</text>
        </view>
        <view :class="['message-row', { mine: isMine(item) }]">
          <view class="chat-avatar">
            <image v-if="messageAvatar(item)" :src="messageAvatar(item)" class="avatar-img" mode="aspectFill" />
            <text v-else class="avatar-text">{{ avatarInitial(item) }}</text>
          </view>
          <view class="message-bubble">
            <view class="bubble-tail" />
            <text class="bubble-text">{{ item.content }}</text>
          </view>
        </view>
      </block>
      <ji-empty v-if="messages.length === 0" title="还没有消息" desc="主动打个招呼吧，说明你的学习目标和时间安排。" />
      <view ref="msgEnd" class="msg-end" />
    </scroll-view>

    <view class="inputbar">
      <input v-model="content" class="msg-input" placeholder="输入消息..." confirm-type="send" @confirm="send" />
      <button class="send-btn" @click="send" :disabled="!content.trim()">发送</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import JiEmpty from "@/components/ji-empty.vue";
import { listMessages, sendMessage } from "@/api/message";
import { getOrderDetail } from "@/api/order";
import { useUserStore } from "@/store/user";
import type { MessageItem, OrderItem } from "@/types/domain";
import { hitSensitiveContact } from "@/utils/regex";
import { normalizeImageUrl } from "@/utils/image";
import { orderStatusText } from "@/utils/status";
import { toast } from "@/utils/toast";

const user = useUserStore();
const orderId = ref(0);
const messages = ref<MessageItem[]>([]);
const content = ref("");
const scrollTop = ref(0);

const orderCtx = ref<(OrderItem & { isBuyer?: boolean }) | null>(null);
const ctxCoverUrl = computed(() => normalizeImageUrl(orderCtx.value?.serviceCoverUrl));

onLoad(async (query) => {
  orderId.value = Number(query?.orderId || 0);
  if (orderId.value) {
    try { orderCtx.value = await getOrderDetail(orderId.value) as any;
      orderCtx.value!.isBuyer = user.userInfo?.id === orderCtx.value?.buyerId;
    } catch { /* no ctx */ }
    await refresh();
  }
});

watch(messages, () => {
  nextTick(() => { scrollTop.value = 999999; });
}, { deep: true });

async function refresh() {
  if (orderId.value) messages.value = await listMessages(orderId.value);
}

async function send() {
  const text = content.value.trim();
  if (!text) return;
  if (hitSensitiveContact(text)) { toast("请勿交换联系方式"); return; }
  await sendMessage(orderId.value, text);
  content.value = "";
  await refresh();
}

function goOrder() {
  uni.navigateTo({ url: `/pages/order/detail?orderId=${orderId.value}` });
}

function isMine(item: MessageItem) {
  return item.senderId === user.userInfo?.id;
}

function messageAvatar(item: MessageItem) {
  return normalizeImageUrl(isMine(item) ? (item.senderAvatar || user.userInfo?.avatarUrl) : item.senderAvatar);
}

function avatarInitial(item: MessageItem) {
  const name = isMine(item) ? (user.userInfo?.nickname || item.senderName) : item.senderName;
  return (name || "用").trim().slice(0, 1);
}

function showTimeGap(i: number) {
  if (i === 0) return true;
  const prev = new Date(messages.value[i - 1].createTime!.replace("T", " "));
  const cur = new Date(messages.value[i].createTime!.replace("T", " "));
  return (cur.getTime() - prev.getTime()) > 5 * 60 * 1000;
}

function formatMsgTime(t?: string) {
  if (!t) return "";
  const d = new Date(t.replace("T", " "));
  const now = new Date();
  const isToday = d.toDateString() === now.toDateString();
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  if (isToday) return hh + ":" + mm;
  return (d.getMonth() + 1) + "/" + d.getDate() + " " + hh + ":" + mm;
}
</script>

<style scoped>
.chat { display: flex; flex-direction: column; height: 100vh; padding-bottom: 0; }

.order-ctx {
  display: flex; align-items: center; gap: 18rpx;
  margin: 16rpx 0; padding: 20rpx 24rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  background: var(--color-card);
  box-shadow: var(--shadow-sm);
}
.ctx-img-wrap { width: 80rpx; height: 80rpx; border: var(--stroke); border-radius: var(--radius-sm); overflow: hidden; flex-shrink: 0; }
.ctx-img { width: 100%; height: 100%; background: #f0f2f7; }
.ctx-placeholder { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; color: #fff; font-size: 32rpx; font-weight: 900; background: var(--color-primary); }
.ctx-info { flex: 1; min-width: 0; }
.ctx-title { display: block; color: var(--color-dark); font-size: 26rpx; font-weight: 800; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ctx-row { display: flex; align-items: center; gap: 14rpx; margin-top: 6rpx; }
.ctx-amount { color: var(--color-price); font-size: 22rpx; font-weight: 700; }
.ctx-status { font-size: 20rpx; color: var(--color-text-muted); }
.ctx-party { display: block; margin-top: 4rpx; color: var(--color-text-faint); font-size: 20rpx; }
.ctx-arrow { color: var(--color-text-faint); font-size: 36rpx; }

.tips { margin-bottom: 16rpx; border: var(--stroke); border-radius: var(--radius-sm); padding: 16rpx 20rpx; color: var(--color-warning); font-size: 22rpx; background: var(--color-accent-2); }

.messages { flex: 1; overflow-y: auto; padding: 8rpx 4rpx 40rpx; }

.time-divider { text-align: center; padding: 20rpx 0; }
.time-divider text { color: var(--color-text-faint); font-size: 22rpx; background: var(--color-bg-main); padding: 6rpx 20rpx; border-radius: var(--radius-sm); }

.message-row {
  display: flex;
  align-items: flex-start;
  width: 100%;
  margin: 18rpx 0;
  padding: 0 6rpx;
}
.message-row.mine { flex-direction: row-reverse; }
.chat-avatar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72rpx;
  height: 72rpx;
  border: 1.5px solid #1A1A1A;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--color-primary);
}
.avatar-img { width: 100%; height: 100%; background: #e8ecf3; }
.avatar-text { color: #fff; font-size: 28rpx; font-weight: 800; line-height: 72rpx; }
.message-bubble {
  display: flex;
  align-items: flex-start;
  max-width: calc(100% - 120rpx);
  margin-left: 12rpx;
}
.message-row.mine .message-bubble {
  flex-direction: row-reverse;
  margin-left: 0;
  margin-right: 12rpx;
}
.bubble-tail {
  width: 0;
  height: 0;
  margin-top: 22rpx;
  border-top: 10rpx solid transparent;
  border-bottom: 10rpx solid transparent;
  border-right: 12rpx solid var(--color-card);
}
.message-row.mine .bubble-tail {
  border-right: 0;
  border-left: 12rpx solid #95ec69;
}
.bubble-text {
  display: block;
  max-width: 100%;
  min-width: 44rpx;
  min-height: 68rpx;
  padding: 14rpx 22rpx;
  border: var(--stroke);
  border-radius: var(--radius-sm);
  color: var(--color-text);
  background: var(--color-card);
  font-size: 30rpx;
  line-height: 40rpx;
  word-break: break-all;
  white-space: pre-wrap;
}
.message-row.mine .bubble-text {
  background: #95ec69;
  border-color: #6db84d;
}

.msg-end { height: 1rpx; }

.inputbar { position: sticky; bottom: 0; z-index: 9999; display: flex; align-items: center; gap: 14rpx; padding: 20rpx 20rpx 28rpx; background: #FFFFFF !important; border-top: 2px solid #1A1A1A; }
.msg-input { flex: 1; height: 76rpx; padding: 0 22rpx; border: var(--stroke); border-radius: var(--radius-sm); background: var(--color-card); font-size: 28rpx; }
.send-btn { width: 120rpx; height: 76rpx; border: 2px solid #1A1A1A; border-radius: var(--radius-sm); color: #fff; font-size: 28rpx; font-weight: 900; background: #07C160; box-shadow: 4rpx 4rpx 0 0 #1A1A1A; display: flex; align-items: center; justify-content: center; }
.send-btn:active { transform: translate(2rpx, 2rpx); box-shadow: none; }
.send-btn[disabled] { opacity: 0.45; background: #94A3B8; border-color: #64748B; box-shadow: none; color: #fff; }
</style>
