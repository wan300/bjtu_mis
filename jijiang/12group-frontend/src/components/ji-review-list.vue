<template>
  <view class="ji-review-list">
    <view v-if="tagStats && Object.keys(tagStats).length > 0" class="hot-tags">
      <text class="hot-tags-title">热门标签</text>
      <view class="hot-tags-row">
        <text
          v-for="(cnt, name) in tagStats"
          :key="name"
          :class="['hot-tag', { active: activeTag === name }]"
          @click="onTagClick(name)"
        >{{ name }} {{ tagPct(cnt) }}%</text>
      </view>
    </view>

    <view v-if="scoreFilter" class="filter-row">
      <text
        v-for="s in scoreOptions"
        :key="s.label"
        :class="['filter-chip', { active: activeScore === s.value }]"
        @click="onFilter(s.value)"
      >{{ s.label }}</text>
    </view>

    <view v-if="items.length === 0" class="empty-wrap">
      <ji-empty title="暂无评价" desc="该服务还没有收到评价" />
    </view>

    <ji-review-card
      v-for="item in items"
      :key="item.id"
      :item="item"
      :showReplyBtn="showReplyBtn"
      @reply="(item, target) => emit('reply', item, target)"
      @previewImages="(urls, idx) => emit('previewImages', urls, idx)"
    />

    <view v-if="hasMore && items.length > 0" class="load-more">
      <view v-if="loading" class="loading-pill loading-text">
        <view class="loading-dot" />
        <text>加载中</text>
      </view>
      <text v-else class="load-text" @click="$emit('loadMore')">加载更多</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import JiReviewCard from "@/components/ji-review-card.vue";
import JiEmpty from "@/components/ji-empty.vue";
import type { ReviewItem } from "@/types/domain";

const props = defineProps<{
  items: ReviewItem[];
  loading?: boolean;
  hasMore?: boolean;
  scoreFilter?: boolean;
  activeScore?: number | null;
  showReplyBtn?: boolean;
  tagStats?: Record<string, number>;
  activeTag?: string | null;
  total?: number;
}>();

const emit = defineEmits<{
  loadMore: [];
  reply: [item: ReviewItem, target?: string];
  previewImages: [urls: string[], index: number];
  filter: [score: number | null];
  filterTag: [tag: string | null];
}>();

const scoreOptions = [
  { label: "全部", value: null },
  { label: "5星", value: 5 },
  { label: "4星", value: 4 },
  { label: "3星", value: 3 },
  { label: "2星", value: 2 },
  { label: "1星", value: 1 },
];

function tagPct(cnt: number) {
  if (!props.total || props.total === 0) return 0;
  return Math.round(cnt / props.total * 100);
}

function onFilter(score: number | null) {
  emit("filter", score);
}

function onTagClick(tag: string) {
  emit("filterTag", tag === props.activeTag ? null : tag);
}
</script>

<style scoped>
/* Hot tags */
.hot-tags {
  margin-bottom: 22rpx;
  padding: 20rpx 22rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  background: var(--color-card);
  box-shadow: var(--shadow-sm);
}
.hot-tags-title { font-size: 24rpx; font-weight: 700; color: var(--color-dark); }
.hot-tags-row { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 14rpx; }
.hot-tag {
  padding: 10rpx 22rpx;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 22rpx;
  font-weight: 600;
  color: #2F5BFF;
  background: #EBF3FF;
  transition: all 0.14s;
}
.hot-tag.active {
  color: #fff;
  background: var(--color-primary);
}

/* Filter */
.filter-row { display: flex; gap: 12rpx; padding: 8rpx 0 22rpx; }
.filter-chip {
  padding: 10rpx 26rpx;
  border: var(--stroke);
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
  font-size: 24rpx;
  font-weight: 600;
  background: var(--color-card);
}
.filter-chip.active { color: #fff; background: var(--color-primary); }

.empty-wrap { padding-top: 40rpx; }

.load-more { padding: 30rpx; text-align: center; }
.load-text { color: var(--color-primary); font-size: 24rpx; }
.loading-text { margin-top: 8rpx; }
</style>
