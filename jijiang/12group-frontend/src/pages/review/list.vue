<template>
  <view class="page-shell">
    <ji-review-summary v-if="stats.total > 0" :stats="stats" />
    <view v-else class="surface-card empty-stats">
      <text class="empty-title">暂无评价</text>
      <text class="empty-desc">该服务还没有收到任何评价</text>
    </view>

    <view class="list-section">
      <ji-review-list
        :items="items"
        :loading="loading"
        :hasMore="hasMore"
        :scoreFilter="true"
        :activeScore="scoreFilter"
        :tagStats="stats.tagStats"
        :activeTag="activeTag"
        :total="stats.total"
        @loadMore="loadMore"
        @filter="onFilter"
        @filterTag="onFilterTag"
      />
    </view>
  </view>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { onLoad, onUnload } from "@dcloudio/uni-app";
import JiReviewSummary from "@/components/ji-review-summary.vue";
import JiReviewList from "@/components/ji-review-list.vue";
import { getReviewList } from "@/api/review";
import type { ReviewItem, ReviewStats } from "@/types/domain";

const serviceId = ref(0);
const items = ref<ReviewItem[]>([]);
const stats = reactive<ReviewStats>({ total: 0, avg: 5.0, distribution: {}, recommendRate: 100, completionRate: 100, tagStats: {} });
const page = ref(1);
const scoreFilter = ref<number | null>(null);
const activeTag = ref<string | null>(null);
const loading = ref(false);
const hasMore = ref(false);

function onFilter(score: number | null) {
  scoreFilter.value = score;
  fetchReviews();
}

function onFilterTag(tag: string | null) {
  activeTag.value = tag;
  fetchReviews();
}

onLoad((query) => {
  serviceId.value = Number(query?.serviceId || 0);
  fetchReviews();
});

onUnload(() => {
  uni.$off("reviewFilter", onFilter);
  uni.$off("reviewFilterTag", onFilterTag);
});

async function fetchReviews() {
  if (!serviceId.value) return;
  loading.value = true;
  page.value = 1;
  try {
    const res = await getReviewList(serviceId.value, 1, 10, {
      score: scoreFilter.value ?? undefined,
      tag: activeTag.value ?? undefined,
    });
    items.value = res.items || [];
    if (res.stats) Object.assign(stats, res.stats);
    hasMore.value = items.value.length < res.total;
  } finally { loading.value = false; }
}

async function loadMore() {
  if (loading.value || !hasMore.value) return;
  loading.value = true;
  page.value++;
  try {
    const res = await getReviewList(serviceId.value, page.value, 10, {
      score: scoreFilter.value ?? undefined,
      tag: activeTag.value ?? undefined,
    });
    items.value.push(...(res.items || []));
    hasMore.value = items.value.length < res.total;
  } finally { loading.value = false; }
}
</script>

<style scoped>
.empty-stats { padding: 48rpx; text-align: center; }
.empty-title { display: block; color: var(--color-dark); font-size: 30rpx; font-weight: 800; }
.empty-desc { display: block; margin-top: 10rpx; color: var(--color-text-muted); font-size: 24rpx; }
.list-section { margin-top: 24rpx; }
</style>
