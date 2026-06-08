<template>
  <view class="ji-review-summary" v-if="stats.total > 0">
    <view class="sum-grid">
      <view class="sum-cell">
        <text class="sum-val">{{ stats.avg.toFixed(1) }}</text>
        <text class="sum-label">综合评分</text>
      </view>
      <view class="sum-cell">
        <text class="sum-val">{{ stats.total }}</text>
        <text class="sum-label">全部评价</text>
      </view>
      <view class="sum-cell">
        <text class="sum-val">{{ stats.recommendRate }}%</text>
        <text class="sum-label">好评率</text>
      </view>
      <view class="sum-cell">
        <text class="sum-val">{{ stats.completionRate }}%</text>
        <text class="sum-label">完成率</text>
      </view>
    </view>

    <view class="sum-dist">
      <view v-for="n in [5,4,3,2,1]" :key="n" class="dist-row">
        <text class="dist-label">{{ n }}星</text>
        <view class="dist-track"><view class="dist-fill" :style="{ width: barPct(n) }" /></view>
        <text class="dist-pct">{{ pct(n) }}%</text>
      </view>
    </view>

    <view v-if="topTags.length > 0" class="sum-tags">
      <text class="tags-title">热门标签</text>
      <view class="tags-row">
        <text
          v-for="t in topTags"
          :key="t.name"
          class="tag-chip"
        >{{ t.name }} {{ t.pct }}%</text>
      </view>
    </view>
  </view>
  <view v-else class="ji-review-summary-empty surface-card">
    <text class="empty-title">暂无评价</text>
    <text class="empty-desc">该服务还没有收到任何评价</text>
  </view>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { ReviewStats } from "@/types/domain";

const props = defineProps<{ stats: ReviewStats }>();

const maxDist = computed(() => Math.max(1, ...(Object.values(props.stats.distribution) as number[])));

function barPct(n: number) {
  return ((props.stats.distribution[n] || 0) / maxDist.value * 100).toFixed(0) + "%";
}

function pct(n: number) {
  if (props.stats.total === 0) return "0";
  return Math.round((props.stats.distribution[n] || 0) / props.stats.total * 100) + "";
}

const topTags = computed(() => {
  return Object.entries(props.stats.tagStats || {})
    .slice(0, 5)
    .map(([name, cnt]) => ({
      name,
      cnt,
      pct: props.stats.total > 0 ? Math.round(cnt / props.stats.total * 100) : 0,
    }));
});
</script>

<style scoped>
.ji-review-summary {
  padding: 28rpx 28rpx 24rpx;
  border: var(--stroke);
  border-radius: var(--radius-lg);
  background: var(--color-card);
  box-shadow: var(--shadow-sm);
}

.ji-review-summary-empty {
  padding: 48rpx;
  text-align: center;
}
.empty-title { display: block; color: var(--color-dark); font-size: 30rpx; font-weight: 800; }
.empty-desc { display: block; margin-top: 10rpx; color: var(--color-text-muted); font-size: 24rpx; }

/* 四宫格 */
.sum-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 2rpx;
}
.sum-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
  padding: 22rpx 0;
}
.sum-val {
  font-size: 40rpx;
  font-weight: 900;
  color: var(--color-primary);
  line-height: 1;
}
.sum-label {
  font-size: 22rpx;
  color: var(--color-text-muted);
}

/* 分布条 */
.sum-dist {
  margin-top: 20rpx;
  padding-top: 20rpx;
  border-top: var(--stroke);
}
.dist-row { display: flex; align-items: center; gap: 10rpx; margin-bottom: 8rpx; }
.dist-label { width: 40rpx; font-size: 20rpx; color: var(--color-text-muted); text-align: right; }
.dist-track { flex: 1; height: 8rpx; border-radius: 4rpx; background: #dde3f0; overflow: hidden; }
.dist-fill { height: 100%; border-radius: 4rpx; background: var(--color-accent-2); transition: width 0.4s; }
.dist-pct { width: 44rpx; font-size: 20rpx; color: var(--color-text-faint); }

/* 热门标签 */
.sum-tags { margin-top: 20rpx; padding-top: 18rpx; border-top: var(--stroke); }
.tags-title { font-size: 24rpx; font-weight: 700; color: var(--color-dark); }
.tags-row { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 14rpx; }
.tag-chip {
  padding: 10rpx 22rpx;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 22rpx;
  font-weight: 600;
  color: #2F5BFF;
  background: #EBF3FF;
}
</style>
