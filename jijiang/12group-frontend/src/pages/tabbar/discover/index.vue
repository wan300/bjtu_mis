<template>
  <view class="page-shell page-with-tab">
    <view class="surface-card discover-head">
      <text class="kicker">DISCOVER</text>
      <text class="title">按目标找到最合适的同学</text>
      <input v-model="keyword" class="field-input" placeholder="搜索服务、技能或讲师" confirm-type="search" @confirm="search" />
    </view>

    <view class="section-title"><text>热门方向</text></view>
    <view class="chips">
      <text v-for="(item, i) in config.categories" :key="item.id" :class="['chip', 'chip-color-' + (i % 4)]" @click="openCategory(item.id)">{{ item.name }}</text>
    </view>

    <view class="surface-card bounty-entry">
      <view>
        <text class="entry-title">悬赏需求大厅</text>
        <text class="entry-desc">发布具体学习需求，让讲师带着方案来竞标。</text>
      </view>
      <view class="entry-actions">
        <button class="primary-btn" @click="openDemandHall">逛大厅</button>
        <button class="ghost-btn" @click="publishDemand">发布需求</button>
      </view>
    </view>

    <view class="section-title"><text>校园服务灵感</text></view>
    <view class="ideas">
      <view v-for="item in ideas" :key="item.title" class="idea surface-card">
        <text class="idea-title">{{ item.title }}</text>
        <text class="idea-desc">{{ item.desc }}</text>
      </view>
    </view>
    <ji-tab-bar />
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import JiTabBar from "@/components/ji-tab-bar.vue";
import { useConfigStore } from "@/store/config";

const config = useConfigStore();
const keyword = ref("");
const ideas = [
  { title: "期末冲刺", desc: "高数、线代、专业课重点梳理" },
  { title: "作品集打磨", desc: "设计、摄影、视频剪辑协作" },
  { title: "求职加速", desc: "简历、面试、项目复盘" },
  { title: "兴趣技能", desc: "吉他、舞蹈、运动陪练" },
];

onShow(() => config.loadCategories());

function search() {
  uni.navigateTo({ url: `/pages/service/search?keyword=${encodeURIComponent(keyword.value)}` });
}

function openCategory(categoryId: number) {
  uni.navigateTo({ url: `/pages/service/search?categoryId=${categoryId}` });
}

function openDemandHall() {
  uni.navigateTo({ url: "/pages/demand/list" });
}

function publishDemand() {
  uni.navigateTo({ url: "/pages/demand/publish" });
}
</script>

<style scoped>
.discover-head {
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
  margin: 18rpx 0 28rpx;
  color: var(--color-dark);
  font-size: 42rpx;
  font-weight: 900;
  line-height: 1.4;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.chip {
  border: none;
  border-radius: var(--radius-sm);
  padding: 18rpx 26rpx;
  color: var(--color-dark);
  font-size: 25rpx;
  font-weight: 800;
  box-shadow: var(--shadow-sm);
}

/* 四色轮换 — 对齐首页金刚区 */
.chip-color-0 { background: var(--color-accent-2); }
.chip-color-1 { background: var(--color-primary); color: #fff; }
.chip-color-2 { background: var(--color-accent-1); }
.chip-color-3 { background: var(--color-accent-3); color: #fff; }

.bounty-entry {
  display: flex;
  flex-direction: column;
  gap: 24rpx;
  margin-top: 28rpx;
  padding: 30rpx;
}

.entry-title {
  display: block;
  color: var(--color-dark);
  font-size: 32rpx;
  font-weight: 900;
}

.entry-desc {
  display: block;
  margin-top: 10rpx;
  color: var(--color-text-muted);
  font-size: 24rpx;
  line-height: 1.55;
}

.entry-actions {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 18rpx;
}

.entry-actions .primary-btn,
.entry-actions .ghost-btn {
  height: 76rpx;
  font-size: 26rpx;
}

.ideas {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 18rpx;
}

.idea {
  position: relative;
  min-height: 190rpx;
  padding: 28rpx;
  overflow: hidden;
}

/* 灵感卡片对角斜切 + 装饰圆点 */
.idea:nth-child(1) { background: linear-gradient(135deg, #fff 60%, rgba(90,154,252,0.08) 60%); }
.idea:nth-child(2) { background: linear-gradient(135deg, #fff 60%, rgba(116,214,193,0.10) 60%); }
.idea:nth-child(3) { background: linear-gradient(135deg, #fff 60%, rgba(249,229,138,0.12) 60%); }
.idea:nth-child(4) { background: linear-gradient(135deg, #fff 60%, rgba(247,141,167,0.08) 60%); }

.idea::after {
  content: "+";
  position: absolute;
  right: 20rpx;
  bottom: 16rpx;
  font-size: 36rpx;
  font-weight: 300;
  opacity: 0.2;
  color: var(--color-dark);
}

.idea-title {
  display: block;
  color: var(--color-dark);
  font-size: 30rpx;
  font-weight: 900;
}

.idea-desc {
  display: block;
  margin-top: 18rpx;
  color: var(--color-text-muted);
  font-size: 24rpx;
  line-height: 1.55;
}
</style>
