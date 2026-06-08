<template>
  <view class="page-shell detail">
    <view class="cover">
      <image v-if="coverUrl" :src="coverUrl" class="cover-img" mode="aspectFill" @error="handleCoverError" />
      <text v-if="!coverUrl">技匠服务</text>
    </view>

    <view v-if="service" class="surface-card panel">
      <view class="title-row">
        <text class="title">{{ service.title }}</text>
        <text class="price">¥{{ money(selectedTier?.price || service.price) }}</text>
      </view>
      <view class="seller">
        <view class="avatar">{{ (service.sellerName || "讲").slice(0, 1) }}</view>
        <view>
          <text class="seller-name">{{ service.sellerName || `讲师 #${service.sellerId}` }}</text>
          <text class="muted">已成交 {{ service.salesCount || 0 }} 单 · 有 {{ reviewTotal }} 人评价</text>
        </view>
      </view>

      <ji-review-summary :stats="reviewStats" />

      <view v-if="priceTiers.length > 0" class="tier-section">
        <view class="section-title"><text>套餐选择</text></view>
        <view
          v-for="tier in priceTiers"
          :key="tier.key"
          class="tier-row"
          :class="{ active: tier.key === selectedTierKey }"
          @click="selectedTierKey = tier.key"
        >
          <view>
            <text class="tier-name">{{ tier.name }}</text>
            <text class="tier-meta">{{ tier.qty }}{{ tier.unit }}</text>
          </view>
          <text class="tier-price">¥{{ money(tier.price) }}</text>
        </view>
      </view>

      <view class="section-title"><text>服务说明</text></view>
      <text class="desc">{{ service.description }}</text>
      <view class="notice">支付前请勿交换联系方式，平台担保交易更安全。</view>

      <view class="review-section">
        <view class="section-title"><text>精选评价</text></view>
        <ji-review-list
          :items="reviews"
          :loading="reviewLoading"
          :hasMore="reviewHasMore"
          :tagStats="reviewStats.tagStats"
          :total="reviewStats.total"
          @loadMore="loadMoreReviews"
        />
        <view v-if="reviewTotal > 5" class="view-all" @click="viewAllReviews">
          <text>查看全部 {{ reviewTotal }} 条评价 ›</text>
        </view>
      </view>
    </view>

    <view class="bottom-bar">
      <button class="ghost-btn" @click="chat">咨询</button>
      <button class="primary-btn" @click="buy">立即下单</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import JiReviewSummary from "@/components/ji-review-summary.vue";
import JiReviewList from "@/components/ji-review-list.vue";
import { getServiceDetail } from "@/api/service";
import { getReviewList } from "@/api/review";
import { useOrderStore } from "@/store/order";
import { useUserStore } from "@/store/user";
import type { ReviewStats, ServiceItem, ReviewItem } from "@/types/domain";
import { money } from "@/utils/money";
import { normalizeImageUrl } from "@/utils/image";
import { parseServicePriceTiers } from "@/utils/price-tiers";
import { toast } from "@/utils/toast";

const user = useUserStore();
const orderStore = useOrderStore();
const service = ref<ServiceItem | null>(null);
const serviceId = ref(0);
const selectedTierKey = ref("");
const failedCoverUrl = ref("");
const normalizedCoverUrl = computed(() => normalizeImageUrl(service.value?.coverUrl));
const coverUrl = computed(() => {
  const url = normalizedCoverUrl.value;
  return url && url !== failedCoverUrl.value ? url : "";
});
const priceTiers = computed(() => parseServicePriceTiers(service.value));
const selectedTier = computed(() => priceTiers.value.find((tier) => tier.key === selectedTierKey.value) || priceTiers.value[0]);

const reviews = ref<ReviewItem[]>([]);
const reviewTotal = ref(0);
const reviewPage = ref(1);
const reviewLoading = ref(false);
const reviewHasMore = ref(false);
const reviewStats = ref<ReviewStats>({ total: 0, avg: 5.0, distribution: {}, recommendRate: 100, completionRate: 100, tagStats: {} });

onLoad((query) => {
  serviceId.value = Number(query?.id || 0);
});

watch(normalizedCoverUrl, () => {
  failedCoverUrl.value = "";
});

onShow(async () => {
  if (serviceId.value) {
    service.value = await getServiceDetail(serviceId.value);
    if (!priceTiers.value.some((tier) => tier.key === selectedTierKey.value)) {
      selectedTierKey.value = priceTiers.value[0]?.key || "";
    }
    loadReviews(serviceId.value);
  }
});

async function loadReviews(serviceId: number) {
  reviewLoading.value = true;
  try {
    const res = await getReviewList(serviceId, 1, 5, {});
    reviews.value = res.items || [];
    reviewTotal.value = res.total || 0;
    reviewHasMore.value = res.total > 5;
    if (res.stats) reviewStats.value = res.stats;
  } finally {
    reviewLoading.value = false;
  }
}

async function loadMoreReviews() {
  if (reviewLoading.value || !service.value) return;
  reviewLoading.value = true;
  reviewPage.value++;
  try {
    const res = await getReviewList(service.value.id, reviewPage.value, 5, {});
    reviews.value.push(...(res.items || []));
    reviewHasMore.value = reviews.value.length < reviewTotal.value;
  } finally {
    reviewLoading.value = false;
  }
}

function viewAllReviews() {
  if (!service.value) return;
  uni.navigateTo({ url: `/pages/review/list?serviceId=${service.value.id}` });
}

function chat() {
  toast("请先下单后在订单内沟通");
}

function handleCoverError() {
  if (coverUrl.value) failedCoverUrl.value = coverUrl.value;
}

function buy() {
  if (!service.value) return;
  if (!selectedTier.value) {
    toast("请选择套餐");
    return;
  }
  if (!user.isLogin) {
    uni.navigateTo({ url: `/pages/login/index?redirect=${encodeURIComponent(`/pages/service/detail?id=${service.value.id}`)}` });
    return;
  }
  orderStore.setService(service.value, selectedTier.value);
  uni.navigateTo({ url: `/pages/order/create?serviceId=${service.value.id}&tierKey=${encodeURIComponent(selectedTier.value.key)}` });
}
</script>

<style scoped>
.detail {
  padding-bottom: 150rpx;
}

/* ── 顶部大图：大圆角倒切 + 墨黑边框 ── */
.cover {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 430rpx;
  border: var(--stroke);
  border-radius: var(--radius-xl) var(--radius-xl) var(--radius-lg) var(--radius-lg);
  color: #fff;
  font-size: 48rpx;
  font-weight: 900;
  background: var(--color-primary);
  overflow: hidden;
  position: relative;
}

.cover-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.cover text {
  position: relative;
  z-index: 1;
}

/* ── 面板 ── */
.panel {
  margin-top: -42rpx;
  padding: 34rpx;
}

.title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24rpx;
}

.title {
  flex: 1;
  color: var(--color-dark);
  font-size: 38rpx;
  font-weight: 900;
  line-height: 1.35;
}

/* 价格：超大薄荷绿粗体 */
.price {
  padding: 4rpx 16rpx;
  border-radius: 8rpx;
  color: #0F766E;
  font-size: 46rpx;
  font-weight: 800;
  background: #E8F8F5;
  flex-shrink: 0;
}

/* ── 卖家信息 ── */
.seller {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-top: 28rpx;
  padding: 22rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  background: var(--color-card-muted);
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 82rpx;
  height: 82rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  color: #fff;
  font-weight: 900;
  background: var(--color-primary);
}

.seller-name,
.desc {
  display: block;
}

.seller-name {
  color: var(--color-dark);
  font-size: 28rpx;
  font-weight: 800;
}

.desc {
  color: var(--color-text-secondary);
  font-size: 28rpx;
  line-height: 1.8;
}

/* ── 安全提示：珊瑚粉气泡 ── */
.notice {
  margin-top: 28rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  padding: 22rpx;
  color: var(--color-warning);
  font-size: 24rpx;
  background: var(--color-accent-2);
  box-shadow: var(--shadow-sm);
}

.tier-section {
  margin-top: 30rpx;
}

.tier-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18rpx;
  min-height: 92rpx;
  padding: 18rpx 20rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  margin-top: 14rpx;
  background: #fff;
}

.tier-row.active {
  border-color: var(--color-primary);
  background: #eef6ff;
}

.tier-name,
.tier-meta {
  display: block;
}

.tier-name {
  color: var(--color-dark);
  font-size: 28rpx;
  font-weight: 900;
}

.tier-meta {
  margin-top: 6rpx;
  color: var(--color-text-muted);
  font-size: 23rpx;
}

.tier-price {
  color: #0F766E;
  font-size: 34rpx;
  font-weight: 900;
  flex-shrink: 0;
}

.review-section { margin-top: 32rpx; }

.view-all {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6rpx;
  padding: 24rpx 0 10rpx;
  color: var(--color-primary);
  font-size: 26rpx;
}

.arrow {
  font-size: 32rpx;
}

/* ── 底部操作栏：双按钮并排 ── */
.bottom-bar {
  position: fixed;
  right: 24rpx;
  bottom: 24rpx;
  left: 24rpx;
  display: grid;
  grid-template-columns: 220rpx 1fr;
  gap: 18rpx;
}
</style>
