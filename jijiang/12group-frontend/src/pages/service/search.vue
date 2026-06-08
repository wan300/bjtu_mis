<template>
  <view class="page-shell">
    <view class="searchbar surface-card">
      <input v-model="keyword" placeholder="搜索技能服务" confirm-type="search" @confirm="load" />
      <button @click="load">搜索</button>
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

    <view v-if="loading && list.length === 0" class="search-skeleton-list">
      <view v-for="i in 3" :key="i" class="skeleton-card service-skeleton">
        <view class="skeleton-block skeleton-cover" />
        <view class="skeleton-info">
          <view class="skeleton-line skeleton-title" />
          <view class="skeleton-line skeleton-desc" />
          <view class="skeleton-line skeleton-meta" />
        </view>
      </view>
    </view>
    <ji-empty v-else-if="!loading && list.length === 0" title="没有找到服务" desc="换个关键词或分类试试。" />
    <ji-service-card v-for="item in list" :key="item.id" :service="item" @click="open" />
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import JiEmpty from "@/components/ji-empty.vue";
import JiServiceCard from "@/components/ji-service-card.vue";
import { searchServices } from "@/api/service";
import { useConfigStore } from "@/store/config";
import { useUserStore } from "@/store/user";
import type { ServiceItem } from "@/types/domain";

const user = useUserStore();
const config = useConfigStore();
const keyword = ref("");
const categoryId = ref<number | undefined>();
const list = ref<ServiceItem[]>([]);
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
    list.value = await searchServices({ campusId: user.campusId, keyword: keyword.value, categoryId: categoryId.value });
  } finally {
    loading.value = false;
  }
}

function pickCategory(id?: number) {
  categoryId.value = id;
  load();
}

function open(service: ServiceItem) {
  uni.navigateTo({ url: `/pages/service/detail?id=${service.id}` });
}
</script>

<style scoped>
.searchbar {
  display: flex;
  align-items: center;
  height: 96rpx;
  padding: 0 18rpx 0 30rpx;
  gap: 16rpx;
}

.searchbar input {
  flex: 1;
  min-width: 0;
  color: var(--color-text);
  font-size: 28rpx;
}

.searchbar button {
  width: 136rpx;
  height: 72rpx;
  margin: -4rpx -10rpx -4rpx 0;
  border: none;
  border-radius: var(--radius-sm);
  color: #fff;
  font-size: 26rpx;
  font-weight: 800;
  background: var(--color-primary);
}

.searchbar button:active {
  opacity: 0.9;
}

.category-scroll {
  white-space: nowrap;
  margin: 24rpx 0;
}

.chip {
  display: inline-flex;
  margin-right: 14rpx;
  border: var(--stroke);
  border-radius: var(--radius-sm);
  padding: 16rpx 24rpx;
  color: var(--color-text-muted);
  font-size: 24rpx;
  font-weight: 700;
  background: var(--color-card);
  box-shadow: var(--shadow-sm);
}

.chip:active {
  transform: translate(2rpx, 2rpx);
}

.chip.active {
  color: #fff;
  background: var(--color-primary);
  box-shadow: var(--shadow-sm);
}

.search-skeleton-list {
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}

.service-skeleton {
  display: flex;
  gap: 24rpx;
  padding: 24rpx;
}

.skeleton-cover {
  width: 176rpx;
  height: 176rpx;
  flex-shrink: 0;
}

.skeleton-info {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: center;
  gap: 20rpx;
}

.skeleton-title {
  width: 76%;
  height: 28rpx;
}

.skeleton-desc {
  width: 92%;
  height: 22rpx;
}

.skeleton-meta {
  width: 58%;
  height: 24rpx;
}

@media (max-width: 360px) {
  .searchbar {
    height: 88rpx;
    padding-left: 22rpx;
  }

  .searchbar button {
    width: 118rpx;
    height: 62rpx;
    font-size: 24rpx;
  }

  .chip {
    padding: 14rpx 20rpx;
    font-size: 22rpx;
  }

  .service-skeleton {
    gap: 18rpx;
    padding: 20rpx;
  }

  .skeleton-cover {
    width: 148rpx;
    height: 148rpx;
  }
}
</style>
