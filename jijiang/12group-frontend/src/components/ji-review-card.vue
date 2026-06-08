<template>
  <view :class="['ji-review-card', scoreClass(item.score)]">
    <view class="card-head">
      <view class="avatar">
        <text v-if="item.isAnonymous" class="ava-emoji">🕶</text>
        <text v-else class="ava-text">{{ (item.reviewerName || '用').slice(0, 1) }}</text>
      </view>
      <view class="head-info">
        <view class="name-row">
          <text class="name">{{ item.isAnonymous ? '匿名学员' : item.reviewerName }}</text>
          <view class="star-row">
            <text v-for="n in 5" :key="n" :class="{ star: true, active: n <= item.score }">★</text>
          </view>
        </view>
        <text class="card-time">{{ fmtTime(item.createTime) }}</text>
      </view>
    </view>

    <view v-if="item.tags && item.tags.length > 0" class="tag-row">
      <text v-for="t in item.tags" :key="t" class="tag-pill">{{ t }}</text>
    </view>

    <view v-if="item.images && item.images.length > 0" class="img-row">
      <image v-for="(img, i) in item.images.slice(0, 4)" :key="img" :src="img" class="rv-img" mode="aspectFill" @click.stop="$emit('previewImages', item.images, i)" />
      <view v-if="item.images.length > 4" class="img-more">+{{ item.images.length - 4 }}</view>
    </view>

    <text class="content">{{ item.content }}</text>

    <view v-if="item.replyContent" class="reply-box">
      <text class="reply-label">卖家回复</text>
      <text class="reply-text">{{ item.replyContent }}</text>
      <text class="reply-time">{{ fmtTime(item.replyTime) }}</text>
    </view>

    <!-- 追评内容 -->
    <view v-if="item.followUpContent" class="follow-up-box">
      <text class="follow-up-label">追加评价</text>
      <view v-if="item.followUpImages && item.followUpImages.length > 0" class="img-row follow-up-img-row">
        <image v-for="(img, i) in item.followUpImages.slice(0, 4)" :key="img" :src="img" class="rv-img" mode="aspectFill" @click.stop="$emit('previewImages', item.followUpImages, i)" />
        <view v-if="item.followUpImages.length > 4" class="img-more">+{{ item.followUpImages.length - 4 }}</view>
      </view>
      <text class="follow-up-text">{{ item.followUpContent }}</text>
      <text class="follow-up-time">{{ fmtTime(item.followUpTime) }}</text>
      <!-- 卖家追评回复 -->
      <view v-if="item.followUpReplyContent" class="reply-box follow-up-reply-box">
        <text class="reply-label">卖家回复</text>
        <text class="reply-text">{{ item.followUpReplyContent }}</text>
        <text class="reply-time">{{ fmtTime(item.followUpReplyTime) }}</text>
      </view>
      <view v-if="showReplyBtn && !item.followUpReplyContent" class="reply-action" @click="$emit('reply', item, 'followUp')">
        <text class="reply-link">回复追评</text>
      </view>
    </view>

    <view v-if="showReplyBtn && !item.replyContent" class="reply-action" @click="$emit('reply', item)">
      <text class="reply-link">回复评价</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import type { ReviewItem } from "@/types/domain";

defineProps<{ item: ReviewItem; showReplyBtn?: boolean }>();
defineEmits<{ reply: [item: ReviewItem, target?: string]; previewImages: [urls: string[], index: number] }>();

function scoreClass(score: number) {
  if (score >= 4) return "score-high";
  if (score === 3) return "score-mid";
  return "score-low";
}

function fmtTime(t?: string) {
  if (!t) return "";
  const d = new Date(t.replace("T", " "));
  const diff = Date.now() - d.getTime();
  if (diff < 6e4) return "刚刚";
  if (diff < 36e5) return Math.floor(diff / 6e4) + "分钟前";
  if (diff < 864e5) return Math.floor(diff / 36e5) + "小时前";
  if (diff < 2592e6) return Math.floor(diff / 864e5) + "天前";
  if (diff < 31536e6) return Math.floor(diff / 2592e6) + "个月前";
  return t.substring(0, 10);
}
</script>

<style scoped>
.ji-review-card {
  position: relative;
  padding: 28rpx 24rpx 20rpx;
  margin-bottom: 18rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  background: var(--color-card);
  box-shadow: var(--shadow-sm);
}
.ji-review-card::before {
  content: "";
  position: absolute;
  top: 26rpx; bottom: 26rpx; left: 8rpx;
  width: 6rpx;
  border-radius: 0 3rpx 3rpx 0;
}
.score-high::before { background: var(--color-accent-1); }
.score-mid::before { background: var(--color-accent-2); }
.score-low::before { background: var(--color-accent-3); }

.card-head { display: flex; align-items: flex-start; gap: 16rpx; }
.avatar { flex-shrink: 0; width: 68rpx; height: 68rpx; border: var(--stroke); border-radius: var(--radius-md); display: flex; align-items: center; justify-content: center; background: var(--color-primary); }
.ava-text { color: #fff; font-size: 28rpx; font-weight: 800; }
.ava-emoji { font-size: 32rpx; }
.head-info { flex: 1; min-width: 0; }
.name-row { display: flex; align-items: center; justify-content: space-between; }
.name { color: var(--color-dark); font-size: 28rpx; font-weight: 700; }
.star-row { display: flex; gap: 2rpx; flex-shrink: 0; }
.star { color: #cbd5e1; font-size: 24rpx; }
.star.active { color: var(--color-accent-2); }
.card-time { display: block; margin-top: 4rpx; color: var(--color-text-faint); font-size: 20rpx; }

.tag-row { display: flex; flex-wrap: wrap; gap: 10rpx; margin-top: 14rpx; }
.tag-pill { padding: 6rpx 18rpx; border: none; border-radius: var(--radius-sm); color: #2F5BFF; font-size: 22rpx; font-weight: 600; background: #EBF3FF; }

.img-row { display: flex; gap: 10rpx; margin-top: 14rpx; }
.rv-img { width: 144rpx; height: 144rpx; border: var(--stroke); border-radius: var(--radius-sm); background: #f0f2f7; }
.img-more { display: flex; align-items: center; justify-content: center; width: 144rpx; height: 144rpx; border: var(--stroke); border-radius: var(--radius-sm); background: var(--color-card-muted); color: var(--color-primary); font-size: 28rpx; font-weight: 700; }

.content { display: block; margin-top: 14rpx; color: var(--color-text-secondary); font-size: 26rpx; line-height: 1.75; white-space: pre-line; word-break: break-word; }

.reply-box { margin-top: 14rpx; padding: 16rpx 20rpx; border: var(--stroke); border-left: 6rpx solid var(--color-dark); border-radius: var(--radius-sm); background: var(--color-card-muted); }
.reply-label { color: var(--color-primary); font-size: 22rpx; font-weight: 700; }
.reply-text { display: block; margin-top: 6rpx; color: var(--color-text-secondary); font-size: 24rpx; line-height: 1.65; }
.reply-time { display: block; margin-top: 6rpx; color: var(--color-text-faint); font-size: 20rpx; }

.reply-action { margin-top: 14rpx; text-align: right; }
.reply-link { display: inline-block; padding: 8rpx 24rpx; border: none; border-radius: var(--radius-sm); color: #2F5BFF; font-size: 22rpx; font-weight: 600; background: #EBF3FF; }

/* 追评区域 */
.follow-up-box { margin-top: 14rpx; padding: 18rpx 20rpx; border: var(--stroke); border-radius: var(--radius-sm); background: #F0F5FF; }
.follow-up-label { color: var(--color-primary); font-size: 22rpx; font-weight: 700; }
.follow-up-text { display: block; margin-top: 8rpx; color: var(--color-text-secondary); font-size: 24rpx; line-height: 1.65; white-space: pre-line; word-break: break-word; }
.follow-up-time { display: block; margin-top: 6rpx; color: var(--color-text-faint); font-size: 20rpx; }
.follow-up-img-row { margin-bottom: 10rpx; }
.follow-up-reply-box { margin-top: 12rpx; }
</style>
