<template>
  <view class="page-shell">
    <view v-if="demand" class="surface-card detail-head">
      <view class="head-row">
        <text class="category">{{ demand.categoryName || "需求" }}</text>
        <text class="budget">¥{{ money(demand.budgetAmount) }}</text>
      </view>
      <text class="title">{{ demand.title }}</text>
      <text class="desc">{{ demand.description }}</text>
      <view class="meta-grid">
        <view><text class="meta-label">发布人</text><text class="meta-value">{{ demand.buyerName || "同学" }}</text></view>
        <view><text class="meta-label">期望时间</text><text class="meta-value">{{ demand.expectedTime || "可协商" }}</text></view>
        <view><text class="meta-label">竞标数</text><text class="meta-value">{{ demand.bidCount || 0 }}</text></view>
      </view>
    </view>

    <view v-if="demand && isBuyer" class="surface-card section">
      <text class="section-heading">讲师竞标</text>
      <ji-empty v-if="!loadingBids && bids.length === 0" title="暂无竞标" desc="需求公开后，讲师的报价会展示在这里。" />
      <view v-for="bid in bids" :key="bid.id" class="bid-card">
        <view class="bid-top">
          <view>
            <text class="seller">{{ bid.sellerName || `讲师 #${bid.sellerId}` }}</text>
            <text class="credit">信誉分 {{ bid.sellerCreditScore || 100 }}</text>
          </view>
          <text class="price">¥{{ money(bid.price) }}</text>
        </view>
        <text class="proposal">{{ bid.proposal }}</text>
        <text class="time">服务时间：{{ bid.serviceTime || "可协商" }}</text>
      </view>
    </view>

    <view v-if="demand && !isBuyer" class="surface-card section">
      <text class="section-heading">讲师竞标</text>
      <view v-if="myBid" class="my-bid">
        <text>已提交报价 ¥{{ money(myBid.price) }}</text>
        <text>{{ myBid.serviceTime || "服务时间可协商" }}</text>
      </view>
      <view v-if="!user.isLogin" class="login-tip">
        <text>登录并完成讲师认证后可参与竞标。</text>
        <button class="ghost-btn" @click="login">去登录</button>
      </view>
      <view v-else>
        <view class="field">
          <view class="field-label">报价</view>
          <input v-model="bidForm.price" class="field-input" type="digit" placeholder="请输入你的报价" />
        </view>
        <view class="field">
          <view class="field-label">服务时间</view>
          <input v-model="bidForm.serviceTime" class="field-input" maxlength="128" placeholder="如 周末下午 / 今晚可沟通" />
        </view>
        <view class="field">
          <view class="field-label">竞标方案</view>
          <textarea v-model="bidForm.proposal" class="field-textarea" maxlength="500" placeholder="说明你能提供的帮助、方式和交付成果" />
        </view>
        <button class="primary-btn" :loading="submitting" @click="submitBid">{{ myBid ? "更新竞标" : "提交竞标" }}</button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import JiEmpty from "@/components/ji-empty.vue";
import { bidDemand, getDemandDetail, getMyDemandBid, listDemandBids } from "@/api/demand";
import { useUserStore } from "@/store/user";
import type { DemandBid, DemandItem } from "@/types/domain";
import { money } from "@/utils/money";
import { hitRiskService, hitSensitiveContact } from "@/utils/regex";
import { toast } from "@/utils/toast";

const user = useUserStore();
const demandId = ref(0);
const demand = ref<DemandItem | null>(null);
const bids = ref<DemandBid[]>([]);
const myBid = ref<DemandBid | null>(null);
const loadingBids = ref(false);
const submitting = ref(false);
const bidForm = reactive({
  price: "",
  proposal: "",
  serviceTime: "",
});

const isBuyer = computed(() => Boolean(user.userInfo?.id && demand.value?.buyerId === user.userInfo.id));

onLoad(async (query) => {
  demandId.value = Number(query?.id || 0);
  await load();
});

async function load() {
  if (!demandId.value) return;
  demand.value = await getDemandDetail(demandId.value);
  bids.value = [];
  myBid.value = null;
  if (!user.isLogin) return;
  if (isBuyer.value) {
    loadingBids.value = true;
    try {
      bids.value = await listDemandBids(demandId.value);
    } finally {
      loadingBids.value = false;
    }
    return;
  }
  myBid.value = await getMyDemandBid(demandId.value);
  if (myBid.value) {
    bidForm.price = String(myBid.value.price);
    bidForm.proposal = myBid.value.proposal;
    bidForm.serviceTime = myBid.value.serviceTime || "";
  }
}

async function submitBid() {
  if (!user.isLogin) {
    login();
    return;
  }
  if (!user.isVerified || !user.isSellerVerified || !user.hasDeposit) {
    toast("请先完成实名认证和讲师认证");
    return;
  }
  const price = Number(bidForm.price);
  const proposal = bidForm.proposal.trim();
  const serviceTime = bidForm.serviceTime.trim();
  if (!Number.isFinite(price) || price <= 0) {
    toast("报价必须大于0");
    return;
  }
  if (!proposal) {
    toast("请填写竞标方案");
    return;
  }
  if (proposal.length > 500 || serviceTime.length > 128) {
    toast("内容长度超出限制");
    return;
  }
  if (hitSensitiveContact(proposal) || hitRiskService(proposal)) {
    toast("竞标方案包含联系方式或高风险词");
    return;
  }
  submitting.value = true;
  try {
    const result = await bidDemand({
      demandId: demandId.value,
      price,
      proposal,
      serviceTime: serviceTime || undefined,
    });
    toast(result.message || "竞标已提交", "success");
    await load();
  } finally {
    submitting.value = false;
  }
}

function login() {
  uni.navigateTo({ url: "/pages/login/index" });
}
</script>

<style scoped>
.detail-head,
.section {
  padding: 32rpx;
}

.section {
  margin-top: 24rpx;
}

.head-row,
.bid-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.category {
  max-width: 360rpx;
  border-radius: var(--radius-sm);
  padding: 8rpx 18rpx;
  color: var(--color-primary);
  font-size: 22rpx;
  font-weight: 800;
  background: #e9f0ff;
}

.budget,
.price {
  color: var(--color-accent-3);
  font-weight: 900;
}

.budget {
  font-size: 40rpx;
}

.price {
  font-size: 34rpx;
}

.title {
  display: block;
  margin-top: 26rpx;
  color: var(--color-dark);
  font-size: 38rpx;
  font-weight: 900;
  line-height: 1.45;
}

.desc {
  display: block;
  margin-top: 18rpx;
  color: var(--color-text-muted);
  font-size: 27rpx;
  line-height: 1.75;
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12rpx;
  margin-top: 28rpx;
}

.meta-label,
.meta-value {
  display: block;
  text-align: center;
}

.meta-label {
  color: #8a95aa;
  font-size: 21rpx;
}

.meta-value {
  margin-top: 4rpx;
  color: var(--color-dark);
  font-size: 24rpx;
  font-weight: 800;
}

.section-heading {
  display: block;
  margin-bottom: 22rpx;
  color: var(--color-dark);
  font-size: 32rpx;
  font-weight: 900;
}

.bid-card {
  padding: 24rpx 0;
  border-top: var(--stroke);
}

.seller,
.credit,
.proposal,
.time,
.my-bid text,
.login-tip text {
  display: block;
}

.seller {
  color: var(--color-dark);
  font-size: 28rpx;
  font-weight: 900;
}

.credit,
.time,
.login-tip text {
  color: #8a95aa;
  font-size: 23rpx;
}

.proposal {
  margin-top: 16rpx;
  color: #4b5870;
  font-size: 26rpx;
  line-height: 1.65;
}

.time {
  margin-top: 10rpx;
}

.my-bid {
  margin-bottom: 22rpx;
  border-radius: 22rpx;
  padding: 20rpx 24rpx;
  color: var(--color-primary);
  font-size: 24rpx;
  font-weight: 800;
  background: #eef4ff;
}

.login-tip {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}
</style>
