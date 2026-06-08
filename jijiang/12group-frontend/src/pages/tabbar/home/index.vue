<template>
  <view class="page-shell page-with-tab">
    <!-- Hero 玻璃拟态卡片 + 装饰气泡 -->
    <view class="hero-wrapper">
      <view class="hero-blob blob-blue" />
      <view class="hero-blob blob-pink" />
      <view class="hero-card home-hero">
        <view class="topline">
          <view class="campus-badge">
            <text class="campus-text">{{ (user.userInfo?.campusName || "默认校区").slice(0, 6) }}</text>
          </view>
          <button class="role-toggle" @click="toggleRole">
            切换{{ user.currentRole === 1 ? "讲师" : "买家" }}
          </button>
        </view>
        <text class="headline">找到身边同学的拿手技能</text>
        <view class="search" @click="goSearch">
          <text class="search-icon">&#8857;</text>
        <text class="search-placeholder">Python、考研、摄影、简历优化...</text>
        <view class="search-btn">搜</view>
      </view>
    </view>

    <!-- 分类金刚区：四色拼色方块 -->
    <view class="section-title">
      <text>技能分类</text>
      <text class="link" @click="goSearch">全部 ›</text>
    </view>
    <view class="category-grid">
      <view
        v-for="(item, i) in categories"
        :key="item.id"
        class="category-tile"
        :class="'cat-color-' + (i % 4)"
        @click="goCategory(item.id)"
      >
        <text class="cat-emoji">{{ catEmojis[i % catEmojis.length] }}</text>
        <text class="cat-name">{{ item.name }}</text>
      </view>
    </view>

    <!-- 今日推荐：错落卡片流 -->
    <view class="section-title">
      <text>今日推荐</text>
      <text class="link" @click="load">刷新</text>
    </view>
    <view v-if="loading && services.length === 0" class="home-skeleton-list">
      <view v-for="i in 3" :key="i" class="skeleton-card service-skeleton">
        <view class="skeleton-block skeleton-cover" />
        <view class="skeleton-info">
          <view class="skeleton-line skeleton-title" />
          <view class="skeleton-line skeleton-desc" />
          <view class="skeleton-line skeleton-meta" />
        </view>
      </view>
    </view>
    <ji-empty v-else-if="!loading && services.length === 0" title="还没有上架服务" desc="完成讲师审核后发布第一项技能服务。" />
    <view v-for="(item, idx) in services" :key="item.id" :class="['service-row', idx % 2 === 0 ? 'tilt-left' : 'tilt-right']">
      <ji-service-card :service="item" @click="openDetail" />
    </view>
    </view>
    <ji-tab-bar />
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import JiEmpty from "@/components/ji-empty.vue";
import JiServiceCard from "@/components/ji-service-card.vue";
import JiTabBar from "@/components/ji-tab-bar.vue";
import { searchServices } from "@/api/service";
import { useConfigStore } from "@/store/config";
import { useUserStore } from "@/store/user";
import type { ServiceItem } from "@/types/domain";

const user = useUserStore();
const config = useConfigStore();
const services = ref<ServiceItem[]>([]);
const loading = ref(false);
const categories = computed(() => config.categories.slice(0, 8));
const catEmojis = ["◆", "◷", "◫", "⬡", "◈", "⬒", "◴", "⬖"];

onShow(load);

async function load() {
  loading.value = true;
  try {
    await config.loadCategories();
    services.value = await searchServices({ campusId: user.campusId });
  } catch {
    services.value = [];
  } finally {
    loading.value = false;
  }
}

function goSearch() {
  uni.navigateTo({ url: "/pages/service/search" });
}

function goCategory(categoryId: number) {
  uni.navigateTo({ url: `/pages/service/search?categoryId=${categoryId}` });
}

function openDetail(service: ServiceItem) {
  uni.navigateTo({ url: `/pages/service/detail?id=${service.id}` });
}

async function toggleRole() {
  if (!user.isLogin) {
    uni.navigateTo({ url: "/pages/login/index" });
    return;
  }
  const targetRole = user.currentRole === 2 ? 1 : 2;
  try {
    await user.switchIdentity(targetRole);
  } catch {
    return;
  }
  if (targetRole === 1) {
    uni.switchTab({ url: "/pages/tabbar/home/index" });
    return;
  }
  uni.reLaunch({ url: "/pages/tabbar/seller-desk/index" });
}
</script>

<style scoped>
/* ── Hero 玻璃拟态 + 装饰气泡 ── */
.hero-wrapper {
  position: relative;
  overflow: hidden;
  border-radius: var(--radius-xl);
}

.hero-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.55;
  z-index: 0;
}

.blob-blue {
  width: 260rpx;
  height: 260rpx;
  top: -80rpx;
  right: -60rpx;
  background: var(--color-primary);
}

.blob-pink {
  width: 200rpx;
  height: 200rpx;
  bottom: -50rpx;
  left: -40rpx;
  background: var(--color-accent-3);
}

.home-hero {
  position: relative;
  z-index: 1;
  padding: 32rpx 36rpx 36rpx;
  color: var(--color-text);
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: var(--stroke);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-md);
}

.topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.campus-badge {
  display: inline-flex;
  align-items: center;
  border: var(--stroke);
  border-radius: var(--radius-sm);
  padding: 10rpx 20rpx;
  background: var(--color-primary);
  color: #fff;
  font-size: 22rpx;
  font-weight: 900;
  transform: rotate(-1deg);
  box-shadow: var(--shadow-sm);
}

.campus-text {
  color: #fff;
}

.role-toggle {
  border: var(--stroke);
  border-radius: var(--radius-sm);
  padding: 12rpx 24rpx;
  color: var(--color-dark);
  font-size: 22rpx;
  font-weight: 800;
  background: var(--color-card);
  box-shadow: var(--shadow-sm);
  transition: transform 0.14s ease, box-shadow 0.14s ease;
}

.role-toggle:active {
  transform: translate(2rpx, 2rpx);
  box-shadow: none;
}

.headline {
  display: block;
  width: 540rpx;
  margin-top: 42rpx;
  color: var(--color-dark);
  font-size: 48rpx;
  font-weight: 900;
  line-height: 1.25;
  word-break: break-all;
}

/* ── 搜索框：纯白 + 3D放大镜 + 冰川蓝方块按钮 ── */
.search {
  display: flex;
  align-items: center;
  height: 88rpx;
  margin-top: 36rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  padding: 0 16rpx 0 24rpx;
  background: var(--color-card);
  box-shadow: var(--shadow-sm);
  gap: 14rpx;
}

.search-icon {
  font-size: 36rpx;
  font-weight: 900;
  color: var(--color-dark);
  flex-shrink: 0;
}

.search-placeholder {
  flex: 1;
  color: var(--color-text-faint);
  font-size: 24rpx;
}

.search-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72rpx;
  height: 88rpx;
  margin: 0 -12rpx 0 0;
  border: none;
  border-radius: 0 var(--radius-md) var(--radius-md) 0;
  color: #fff;
  font-size: 24rpx;
  font-weight: 900;
  background: var(--color-primary);
  flex-shrink: 0;
}

.link {
  color: var(--color-primary);
  font-size: 24rpx;
  font-weight: 800;
}

/* ── 分类金刚区：四色拼色方块 ── */
.category-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16rpx;
}

.category-tile {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  height: 150rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  gap: 10rpx;
  box-shadow: var(--shadow-md);
  transition: transform 0.14s ease, box-shadow 0.14s ease;
}

.category-tile:active {
  transform: translate(2rpx, 2rpx);
  box-shadow: var(--shadow-sm);
}

/* 四色轮换 */
.cat-color-0 { background: var(--color-accent-2); }
.cat-color-1 { background: var(--color-primary); color: #fff; }
.cat-color-2 { background: var(--color-accent-1); }
.cat-color-3 { background: var(--color-accent-3); color: #fff; }

.cat-color-0 .cat-name,
.cat-color-2 .cat-name {
  color: var(--color-dark);
}

.cat-color-1 .cat-name,
.cat-color-3 .cat-name {
  color: #fff;
}

.cat-emoji {
  font-size: 44rpx;
  font-weight: 900;
  line-height: 1;
  opacity: 0.85;
}

.cat-name {
  font-size: 22rpx;
  font-weight: 800;
}

/* ── 服务卡片错落排版 ── */
.service-row.tilt-left {
  transform: rotate(-0.5deg);
}

.service-row.tilt-right {
  transform: rotate(0.5deg);
}

/* ── 骨架屏 ── */
.home-skeleton-list {
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
  .headline {
    width: 100%;
    font-size: 42rpx;
  }

  .category-grid {
    gap: 12rpx;
  }

  .category-tile {
    height: 136rpx;
  }

  .cat-emoji {
    font-size: 34rpx;
  }

  .cat-name {
    font-size: 20rpx;
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
