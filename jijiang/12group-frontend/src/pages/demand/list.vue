<template>
  <view class="page-shell">
    <view class="surface-card hall-head">
      <text class="kicker">BOUNTY HALL</text>
      <text class="title">悬赏需求大厅</text>
      <text class="desc">发布个性化学习需求，让合适的讲师主动给出方案。</text>
      <view class="actions">
        <input v-model="keyword" class="search-input" placeholder="搜索需求、课程或技能" confirm-type="search" @confirm="load" />
        <button class="search-btn" @click="load">搜索</button>
      </view>
      <button class="primary-btn publish-btn" @click="publish">发布需求</button>
    </view>

    <scroll-view class="category-scroll" scroll-x>
      <text class="chip" :class="{ active: !categoryId }" @click="pickCategory(undefined)">全部</text>
      <text
        v-for="item in config.categories"
        :key="item.id"
        class="chip"
        :class="{ active: categoryId === item.id }"
        @click="pickCategory(item.id)"
      >
        {{ item.name }}
      </text>
    </scroll-view>

    <ji-empty v-if="!loading && demands.length === 0" title="暂无悬赏需求" desc="换个关键词，或发布第一条需求。" action-text="发布需求" @action="publish" />
    <view v-for="item in demands" :key="item.id" class="surface-card demand-card" @click="open(item)">
      <view class="demand-top">
        <text class="category">{{ item.categoryName || "需求" }}</text>
        <text class="budget">¥{{ money(item.budgetAmount) }}</text>
      </view>
      <text class="demand-title">{{ item.title }}</text>
      <text class="demand-desc">{{ item.description }}</text>
      <view class="meta-row">
        <text>{{ item.buyerName || "同学" }}</text>
        <text>{{ item.expectedTime || "时间可协商" }}</text>
        <text>{{ item.bidCount || 0 }} 个竞标</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import JiEmpty from "@/components/ji-empty.vue";
import { listDemands } from "@/api/demand";
import { useConfigStore } from "@/store/config";
import { useUserStore } from "@/store/user";
import type { DemandItem } from "@/types/domain";
import { money } from "@/utils/money";

const user = useUserStore();
const config = useConfigStore();
const keyword = ref("");
const categoryId = ref<number | undefined>();
const demands = ref<DemandItem[]>([]);
const loading = ref(false);

onLoad(async (query) => {
  keyword.value = String(query?.keyword || "");
  categoryId.value = query?.categoryId ? Number(query.categoryId) : undefined;
  await config.loadCategories();
  await load();
});

async function load() {
  loading.value = true;
  try {
    demands.value = await listDemands({ campusId: user.campusId, categoryId: categoryId.value, keyword: keyword.value });
  } finally {
    loading.value = false;
  }
}

function pickCategory(id?: number) {
  categoryId.value = id;
  load();
}

function publish() {
  uni.navigateTo({ url: "/pages/demand/publish" });
}

function open(item: DemandItem) {
  uni.navigateTo({ url: `/pages/demand/detail?id=${item.id}` });
}
</script>

<style scoped>
.hall-head {
  padding: 34rpx;
}

.kicker {
  color: var(--color-accent-1);
  font-size: 22rpx;
  font-weight: 900;
  letter-spacing: 4rpx;
}

.title {
  display: block;
  margin-top: 16rpx;
  color: var(--color-dark);
  font-size: 44rpx;
  font-weight: 900;
}

.desc {
  display: block;
  margin-top: 12rpx;
  color: #68748a;
  font-size: 25rpx;
  line-height: 1.6;
}

.actions {
  display: flex;
  align-items: center;
  gap: 14rpx;
  margin-top: 28rpx;
}

.search-input {
  flex: 1;
  height: 78rpx;
  border-radius: 24rpx;
  padding: 0 24rpx;
  color: var(--color-dark);
  font-size: 26rpx;
  background: #f5f8ff;
}

.search-btn {
  width: 128rpx;
  height: 72rpx;
  border-radius: var(--radius-sm);
  color: #fff;
  font-size: 26rpx;
  font-weight: 800;
  background: var(--color-primary);
}

.publish-btn {
  margin-top: 22rpx;
}

.category-scroll {
  white-space: nowrap;
  margin: 24rpx 0;
}

.chip {
  display: inline-flex;
  margin-right: 14rpx;
  border-radius: var(--radius-sm);
  padding: 16rpx 24rpx;
  color: #6e7a91;
  font-size: 24rpx;
  background: #fff;
}

.chip.active {
  color: #fff;
  background: var(--color-primary);
}

.demand-card {
  padding: 28rpx;
  margin-bottom: 18rpx;
}

.demand-top,
.meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
}

.category {
  max-width: 320rpx;
  border-radius: var(--radius-sm);
  padding: 8rpx 18rpx;
  color: var(--color-primary);
  font-size: 22rpx;
  font-weight: 800;
  background: #e9f0ff;
}

.budget {
  color: var(--color-accent-3);
  font-size: 34rpx;
  font-weight: 900;
}

.demand-title {
  display: block;
  margin-top: 22rpx;
  color: var(--color-dark);
  font-size: 32rpx;
  font-weight: 900;
  line-height: 1.45;
}

.demand-desc {
  display: -webkit-box;
  margin-top: 12rpx;
  overflow: hidden;
  color: #68748a;
  font-size: 25rpx;
  line-height: 1.6;
  text-overflow: ellipsis;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.meta-row {
  margin-top: 22rpx;
  color: #8a95aa;
  font-size: 22rpx;
}
</style>
