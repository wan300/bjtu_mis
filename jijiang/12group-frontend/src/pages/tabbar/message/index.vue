<template>
  <view class="page-shell msg-page">
    <view class="msg-hero">
      <text class="hero-title">消息</text>
    </view>

    <view v-if="!user.isLogin" class="surface-card login-card">
      <ji-empty title="登录后查看消息" desc="请先登录以查看订单沟通记录" action-text="去登录" @action="goLogin" />
    </view>

    <view v-else-if="conversations.length === 0" class="surface-card empty-card">
      <ji-empty title="暂无消息" desc="下单后即可与讲师/买家在线沟通" />
    </view>

    <view v-else class="conv-list">
      <view
        v-for="c in conversations"
        :key="c.orderId"
        class="conv-card surface-card"
        @click="openChat(c.orderId)"
      >
        <view class="conv-avatar">
          <text class="ava-text">{{ (c.counterpartyName || '用').slice(0, 1) }}</text>
        </view>
        <view class="conv-body">
          <view class="conv-top">
            <text class="conv-name">{{ c.counterpartyName }}</text>
            <view class="conv-badges">
              <text class="conv-role">{{ c.counterpartyRole === 'seller' ? '讲师' : '买家' }}</text>
              <view v-if="c.unreadCount > 0" class="unread-badge">{{ c.unreadCount > 99 ? '99+' : c.unreadCount }}</view>
            </view>
          </view>
          <text class="conv-service">{{ c.serviceTitle || '订单' }}</text>
          <view class="conv-bottom">
            <text class="conv-preview">{{ c.lastContent }}</text>
            <text class="conv-time">{{ fmtTime(c.lastTime) }}</text>
          </view>
        </view>
      </view>
    </view>

    <ji-tab-bar />
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import JiEmpty from "@/components/ji-empty.vue";
import JiTabBar from "@/components/ji-tab-bar.vue";
import { getConversations } from "@/api/message";
import type { ConversationItem } from "@/api/message";
import { useUserStore } from "@/store/user";

const user = useUserStore();
const conversations = ref<ConversationItem[]>([]);

onShow(async () => {
  if (user.isLogin) {
    try { conversations.value = await getConversations(); }
    catch { conversations.value = []; }
  }
});

function openChat(orderId: number) {
  uni.navigateTo({ url: `/pages/chat/detail?orderId=${orderId}` });
}

function goLogin() { uni.navigateTo({ url: "/pages/login/index" }); }

function fmtTime(t?: string) {
  if (!t) return "";
  const d = new Date(t.replace("T", " "));
  const diff = Date.now() - d.getTime();
  if (diff < 6e4) return "刚刚";
  if (diff < 36e5) return Math.floor(diff / 6e4) + "分钟前";
  if (diff < 864e5) return Math.floor(diff / 36e5) + "小时前";
  if (diff < 1728e5) return "昨天";
  if (diff < 6048e5) { const days = ["日","一","二","三","四","五","六"]; return "周" + days[d.getDay()]; }
  return t.substring(0, 10);
}
</script>

<style scoped>
.msg-page { padding-bottom: 140rpx; }
.msg-hero { padding: 36rpx 34rpx 10rpx; }
.hero-title { display: block; color: var(--color-dark); font-size: 44rpx; font-weight: 900; }
.login-card, .empty-card { margin: 24rpx; }
.conv-list { padding: 0 24rpx; }
.conv-card { display: flex; gap: 20rpx; padding: 26rpx 24rpx; margin-bottom: 16rpx; }
.conv-avatar { flex-shrink: 0; width: 88rpx; height: 88rpx; border: var(--stroke); border-radius: var(--radius-md); overflow: hidden; background: var(--color-primary); display: flex; align-items: center; justify-content: center; }
.ava-text { color: #fff; font-size: 34rpx; font-weight: 900; }
.conv-body { flex: 1; min-width: 0; }
.conv-top { display: flex; align-items: center; justify-content: space-between; }
.conv-name { color: var(--color-dark); font-size: 28rpx; font-weight: 700; }
.conv-badges { display: flex; align-items: center; gap: 10rpx; flex-shrink: 0; }
.conv-role { color: #596579; font-size: 20rpx; padding: 2rpx 12rpx; border: none; border-radius: var(--radius-sm); background: #F1F4F8; }
.unread-badge { min-width: 34rpx; height: 34rpx; line-height: 34rpx; padding: 0 8rpx; border-radius: var(--radius-sm); color: #fff; font-size: 20rpx; font-weight: 700; text-align: center; background: var(--color-accent-3); }
.conv-service { display: block; margin-top: 4rpx; color: var(--color-primary); font-size: 22rpx; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv-bottom { display: flex; align-items: center; justify-content: space-between; margin-top: 6rpx; }
.conv-preview { flex: 1; color: var(--color-text-faint); font-size: 24rpx; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv-time { flex-shrink: 0; margin-left: 16rpx; color: var(--color-text-faint); font-size: 20rpx; }
</style>
