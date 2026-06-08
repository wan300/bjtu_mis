<template>
  <view class="page-shell">
    <view class="hero-card review-hero">
      <text class="hero-title">{{ isFollowUp ? '追加评价' : '评价服务' }}</text>
      <text class="hero-sub">{{ isFollowUp ? '补充你的学习体验和使用心得' : '分享你的体验，帮助更多人找到好讲师' }}</text>
    </view>

    <view v-if="orderCtx" class="surface-card order-ctx-card" @click="goOrder">
      <image v-if="ctxCover" :src="ctxCover" class="ctx-cover" mode="aspectFill" />
      <view v-else class="ctx-cover-placeholder">课</view>
      <view class="ctx-info">
        <text class="ctx-title">{{ orderCtx.serviceTitle || '服务' }}</text>
        <text class="ctx-party">{{ orderCtx.isBuyer ? '讲师: ' + (orderCtx.sellerName || '对方') : '买家: ' + (orderCtx.buyerName || '对方') }}</text>
        <text class="ctx-amount">¥{{ orderCtx.amount }}</text>
      </view>
      <text class="ctx-arrow">›</text>
    </view>

    <view v-if="!isFollowUp" class="surface-card rating-card">
      <text class="section-label">整体评分</text>
      <view class="stars-row">
        <text
          v-for="n in 5"
          :key="n"
          :class="{ star: true, active: n <= score }"
          @click="score = n"
        >★</text>
      </view>
      <text :class="{ 'score-label': true, muted: score === 3 }">{{ scoreLabel }}</text>
    </view>

    <view v-if="!isFollowUp && currentTags.length > 0" class="surface-card tags-card">
      <text class="section-label">贴个标签 <text class="label-hint">最多3个</text></text>
      <view class="tag-cloud">
        <text
          v-for="t in currentTags"
          :key="t"
          :class="{ 'tag-chip': true, active: selectedTags.includes(t) }"
          @click="toggleTag(t)"
        >{{ t }}</text>
      </view>
    </view>

    <view class="surface-card content-card">
      <text class="section-label">详细评价</text>
      <textarea
        v-model="content"
        class="review-textarea"
        placeholder="分享讲师的专业度、响应速度和学习收获…"
        :maxlength="500"
      />
      <text class="char-count">{{ content.length }}/500</text>

      <view class="card-divider" />

      <view v-if="!isFollowUp" class="anon-row">
        <view class="anon-info">
          <text class="anon-title">匿名评价</text>
          <text class="anon-desc">开启后不会显示你的头像和昵称</text>
        </view>
        <switch :checked="isAnonymous" @change="onAnonChange" color="#5A9AFC" />
      </view>
    </view>

    <button class="primary-btn submit-btn" @click="submit">{{ isFollowUp ? '提交追评' : '提交评价' }}</button>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { submitReview, submitFollowUp } from "@/api/review";
import { getOrderDetail } from "@/api/order";
import { normalizeImageUrl } from "@/utils/image";
import type { OrderItem } from "@/types/domain";
import { toast } from "@/utils/toast";

const POSITIVE_TAGS = ["专业耐心", "讲解清晰", "干货满满", "响应迅速", "性价比高"];
const NEGATIVE_TAGS = ["态度敷衍", "不够专业", "响应太慢", "内容缩水", "不如预期"];

const orderId = ref(0);
const isFollowUp = ref(false);
const score = ref(5);
const content = ref("");
const selectedTags = ref<string[]>([]);
const isAnonymous = ref(false);

const orderCtx = ref<(OrderItem & { isBuyer?: boolean }) | null>(null);
const ctxCover = computed(() => normalizeImageUrl(orderCtx.value?.serviceCoverUrl));

const currentTags = computed(() => {
  if (score.value >= 4) return POSITIVE_TAGS;
  if (score.value <= 2) return NEGATIVE_TAGS;
  return [];
});

const scoreLabel = computed(() => {
  const labels: Record<number, string> = {
    1: "非常差",
    2: "比较差",
    3: "一般",
    4: "很好",
    5: "非常好",
  };
  return labels[score.value] || "";
});

onLoad(async (query) => {
  orderId.value = Number(query?.orderId || 0);
  isFollowUp.value = query?.followUp === "1";
  if (orderId.value) {
    try {
      const o = await getOrderDetail(orderId.value) as OrderItem & { isBuyer?: boolean };
      o.isBuyer = true; // 评价页只能买家进入
      orderCtx.value = o;
    } catch { /* no ctx */ }
  }
});

function toggleTag(tag: string) {
  const idx = selectedTags.value.indexOf(tag);
  if (idx >= 0) {
    selectedTags.value.splice(idx, 1);
  } else if (selectedTags.value.length < 3) {
    selectedTags.value.push(tag);
  }
}

function onAnonChange(e: any) {
  isAnonymous.value = e.detail.value;
}

function goOrder() {
  if (orderId.value) uni.navigateTo({ url: `/pages/order/detail?orderId=${orderId.value}` });
}

async function submit() {
  if (!content.value.trim()) {
    toast("请填写评价内容", "error");
    return;
  }
  try {
    if (isFollowUp.value) {
      await submitFollowUp({
        orderId: orderId.value,
        content: content.value.trim(),
      });
      toast("追评成功", "success");
    } else {
      await submitReview({
        orderId: orderId.value,
        score: score.value,
        content: content.value.trim(),
        tags: selectedTags.value,
        isAnonymous: isAnonymous.value,
      });
      toast("评价成功", "success");
    }
    uni.navigateBack();
  } catch {
    // request 层已 toast 错误信息
  }
}
</script>

<style scoped>
/* Hero header */
.review-hero {
  padding: 44rpx 36rpx;
}

.hero-title {
  display: block;
  color: #fff;
  font-size: 42rpx;
  font-weight: 900;
}

.hero-sub {
  display: block;
  margin-top: 10rpx;
  color: rgba(255, 255, 255, 0.78);
  font-size: 24rpx;
}

/* Order context card */
.order-ctx-card {
  display: flex; align-items: center; gap: 18rpx;
  margin-top: 24rpx; padding: 20rpx 24rpx;
}
.ctx-cover, .ctx-cover-placeholder { width: 80rpx; height: 80rpx; border-radius: 20rpx; flex-shrink: 0; }
.ctx-cover { background: #f0f2f7; }
.ctx-cover-placeholder { display: flex; align-items: center; justify-content: center; color: #fff; font-size: 32rpx; font-weight: 900; background: var(--color-primary); border: var(--stroke); border-radius: var(--radius-sm); }
.ctx-info { flex: 1; min-width: 0; }
.ctx-title { display: block; color: var(--color-dark); font-size: 26rpx; font-weight: 800; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ctx-party { display: block; margin-top: 2rpx; color: #7a869d; font-size: 22rpx; }
.ctx-amount { display: block; margin-top: 2rpx; color: var(--color-price); font-size: 22rpx; font-weight: 700; }
.ctx-arrow { color: var(--color-text-faint); font-size: 36rpx; flex-shrink: 0; }

/* Rating card */
.rating-card {
  margin-top: 24rpx;
  padding: 36rpx;
  text-align: center;
}

.section-label {
  display: block;
  color: var(--color-dark);
  font-size: 28rpx;
  font-weight: 800;
}

.label-hint {
  color: var(--color-text-faint);
  font-size: 22rpx;
  font-weight: 400;
}

.stars-row {
  display: flex;
  justify-content: center;
  gap: 22rpx;
  margin: 28rpx 0 18rpx;
}

.star {
  color: var(--color-text-faint);
  font-size: 72rpx;
  transition: transform 0.15s;
}

.star.active {
  color: var(--color-accent-2);
  transform: scale(1.08);
}

.score-label {
  display: block;
  color: var(--color-accent-1);
  font-size: 30rpx;
  font-weight: 800;
}

.score-label.muted {
  color: var(--color-text-muted);
}

/* Tags card */
.tags-card {
  margin-top: 24rpx;
  padding: 30rpx 36rpx;
}

.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 22rpx;
}

.tag-chip {
  padding: 14rpx 30rpx;
  border: var(--stroke);
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
  font-size: 26rpx;
  font-weight: 600;
  background: var(--color-card-muted);
  transition: all 0.14s;
}

.tag-chip.active {
  color: #fff;
  background: var(--color-primary);
  box-shadow: var(--shadow-sm);
}

/* Content card */
.content-card {
  margin-top: 24rpx;
  padding: 30rpx 36rpx;
}

.review-textarea {
  width: 100%;
  min-height: 200rpx;
  margin-top: 20rpx;
  padding: 22rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  color: var(--color-dark);
  font-size: 28rpx;
  line-height: 1.7;
  background: var(--color-card-muted);
  box-sizing: border-box;
}

.char-count {
  display: block;
  margin-top: 10rpx;
  color: var(--color-text-faint);
  font-size: 22rpx;
  text-align: right;
}

.card-divider {
  height: 1rpx;
  margin: 28rpx 0;
  background: var(--color-dark);
  opacity: 0.12;
}

/* Anonymous row */
.anon-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.anon-info {
  flex: 1;
}

.anon-title {
  display: block;
  color: var(--color-dark);
  font-size: 28rpx;
  font-weight: 700;
}

.anon-desc {
  display: block;
  margin-top: 4rpx;
  color: var(--color-text-faint);
  font-size: 22rpx;
}

/* Submit button */
.submit-btn {
  margin-top: 44rpx;
}
</style>
