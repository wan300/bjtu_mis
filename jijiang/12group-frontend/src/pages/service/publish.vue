<template>
  <view class="page-shell">
    <view class="hero-card">
      <text class="title">发布你的技能服务</text>
      <text class="desc">标题清晰、价格透明、描述具体，更容易获得同学信任。</text>
    </view>
    <view class="surface-card form">
      <view class="field">
        <view class="field-label">服务标题</view>
        <input v-model="form.title" class="field-input" placeholder="如 Python 数据分析 1v1 辅导" />
      </view>
      <view class="field">
        <view class="field-label">分类</view>
        <picker :range="categoryNames" @change="pickCategory">
          <view class="field-input">{{ selectedCategoryName }}</view>
        </picker>
      </view>

      <!-- ── 多档价格配置 ── -->
      <view class="field">
        <view class="field-label">套餐配置</view>
        <view v-for="(tier, idx) in priceTiers" :key="idx" class="tier-card">
          <view class="tier-row">
            <input v-model="tier.name" class="tier-input name" placeholder="套餐名" />
            <input v-model="tier.price" class="tier-input price" type="digit" placeholder="价格" />
            <text class="tier-remove" @click="removeTier(idx)">✕</text>
          </view>
          <view class="tier-row sub">
            <input v-model="tier.qty" class="tier-input qty" type="number" placeholder="次数" />
            <input v-model="tier.unit" class="tier-input unit" placeholder="单位(次/小时)" />
          </view>
        </view>
        <view class="add-tier" @click="addTier">+ 添加套餐档次</view>
      </view>

      <view class="field">
        <view class="field-label">库存/可预约次数</view>
        <input v-model="form.stock" class="field-input" type="number" placeholder="默认 1" />
      </view>
      <view class="field">
        <view class="field-label">封面图片 URL</view>
        <input v-model="form.coverUrl" class="field-input" placeholder="可先填写网络图片地址或留空" />
        <view class="upload-btn" :class="{ disabled: uploading }" @click="handleUploadImage">
          <text>{{ uploading ? "上传中..." : "上传封面图片" }}</text>
        </view>
        <image v-if="form.coverUrl" class="cover-preview" :src="form.coverUrl" mode="aspectFill" />
      </view>
      <view class="field">
        <view class="field-label">服务描述</view>
        <textarea v-model="form.description" class="field-textarea" placeholder="说明适合人群、交付方式、可约时间和注意事项" />
      </view>

      <!-- 预览套餐 -->
      <view v-if="priceTiers.length > 0" class="preview surface-card">
        <text class="preview-title">套餐预览</text>
        <view v-for="t in priceTiers" :key="t.name" class="preview-row">
          <text>{{ t.name }}</text>
          <text class="preview-price">¥{{ Number(t.price || 0).toFixed(2) }} / {{ t.qty || 1 }}{{ t.unit || '次' }}</text>
        </view>
      </view>

      <button class="primary-btn" :loading="loading" @click="submit">提交审核</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { publishService } from "@/api/service";
import { uploadServiceImage } from "@/api/upload";
import { useConfigStore } from "@/store/config";
import { useUserStore } from "@/store/user";
import { normalizeImageUrl } from "@/utils/image";
import { hitRiskService, hitSensitiveContact } from "@/utils/regex";
import { toast } from "@/utils/toast";

interface PriceTier { name: string; price: string; unit: string; qty: string }
interface PriceTierPayload { key: string; name: string; price: number; unit: string; qty: number }

const config = useConfigStore();
const user = useUserStore();
const categoryIndex = ref(0);
const loading = ref(false);
const uploading = ref(false);
const priceTiers = ref<PriceTier[]>([
  { name: "单次体验", price: "", unit: "次", qty: "1" },
]);
const form = reactive({
  title: "",
  description: "",
  price: "",
  stock: "1",
  coverUrl: "",
});
const categoryNames = computed(() => config.categories.map((item) => item.name));
const selectedCategoryName = computed(() => categoryNames.value[categoryIndex.value] || "请选择分类");

onLoad(() => config.loadCategories());

function pickCategory(event: { detail: { value: number } }) {
  categoryIndex.value = Number(event.detail.value);
}

function addTier() {
  priceTiers.value.push({ name: "", price: "", unit: "次", qty: "1" });
}

function removeTier(idx: number) {
  if (priceTiers.value.length <= 1) return;
  priceTiers.value.splice(idx, 1);
}

async function handleUploadImage() {
  if (uploading.value) return;
  uploading.value = true;
  try {
    const url = await uploadServiceImage();
    if (url) form.coverUrl = url;
  } finally {
    uploading.value = false;
  }
}

function buildPriceConfig(): PriceTierPayload[] | null {
  const tiers: PriceTierPayload[] = [];
  for (const [index, tier] of priceTiers.value.entries()) {
    const name = tier.name.trim();
    const unit = (tier.unit || "次").trim();
    const price = Number(tier.price);
    const qty = Number(tier.qty || 1);
    const isBlank = !name && !tier.price;
    if (isBlank) continue;
    if (!name || name.length > 32) {
      toast("请填写有效套餐名");
      return null;
    }
    if (!Number.isFinite(price) || price <= 0) {
      toast("请填写有效套餐价格");
      return null;
    }
    if (!Number.isInteger(qty) || qty <= 0) {
      toast("请填写有效套餐次数");
      return null;
    }
    if (!unit || unit.length > 12) {
      toast("请填写有效套餐单位");
      return null;
    }
    tiers.push({ key: `tier-${index}`, name, price, unit, qty });
  }
  return tiers;
}

async function submit() {
  if (!user.isVerified) { toast("请先完成实名认证"); return; }
  if (!user.isSellerVerified) { toast("请先完成讲师认证"); return; }
  if (!user.hasDeposit) {
    toast("请先缴纳讲师保证金");
    uni.navigateTo({ url: `/pages/seller/deposit?redirect=${encodeURIComponent("/pages/service/publish")}` });
    return;
  }
  const text = `${form.title} ${form.description}`;
  if (!form.title || !form.description) { toast("请填写标题和描述"); return; }
  if (hitSensitiveContact(text) || hitRiskService(text)) { toast("内容包含联系方式或高风险词"); return; }
  const category = config.categories[categoryIndex.value];
  if (!category) { toast("请选择分类"); return; }

  const tiers = buildPriceConfig();
  if (!tiers) return;
  if (tiers.length === 0) { toast("请至少配置一个有效套餐"); return; }

  const coverUrl = normalizeImageUrl(form.coverUrl);
  if (form.coverUrl.trim() && !coverUrl) {
    toast("封面图片地址无效"); return;
  }

  loading.value = true;
  try {
    const primaryPrice = tiers[0].price;
    const result = await publishService({
      categoryId: category.id,
      title: form.title,
      description: form.description,
      price: primaryPrice,
      priceConfig: JSON.stringify(tiers),
      coverUrl,
      stock: Number(form.stock || 1),
    });
    toast(result.message || "已提交审核", "success");
    uni.navigateBack();
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.title, .desc { display: block; }
.title { font-size: 42rpx; font-weight: 900; }
.desc { width: 560rpx; margin-top: 18rpx; opacity: 0.82; line-height: 1.6; }
.form { margin-top: 24rpx; padding: 34rpx; }

.tier-card {
  background: #f8fafc;
  border-radius: 16rpx;
  padding: 18rpx;
  margin-bottom: 14rpx;
}
.tier-row {
  display: flex;
  gap: 12rpx;
  align-items: center;
}
.tier-row.sub { margin-top: 10rpx; }
.tier-input {
  flex: 1;
  height: 64rpx;
  border-radius: 12rpx;
  padding: 0 16rpx;
  font-size: 26rpx;
  background: #fff;
  border: 1rpx solid #e5e7eb;
}
.tier-input.name { flex: 2; }
.tier-input.price { flex: 1.5; }
.tier-input.qty { flex: 1; }
.tier-input.unit { flex: 1; }
.tier-remove {
  width: 48rpx;
  text-align: center;
  color: #ef4444;
  font-size: 32rpx;
  font-weight: 900;
}
.add-tier {
  text-align: center;
  padding: 20rpx;
  border: 2rpx dashed #c7d2fe;
  border-radius: 16rpx;
  color: var(--color-primary);
  font-weight: 800;
  font-size: 26rpx;
}

.preview {
  margin: 24rpx 0;
  padding: 24rpx;
}
.preview-title {
  display: block;
  font-weight: 900;
  font-size: 28rpx;
  margin-bottom: 16rpx;
  color: var(--color-dark);
}
.preview-row {
  display: flex;
  justify-content: space-between;
  padding: 10rpx 0;
  font-size: 26rpx;
  border-bottom: 1rpx solid #f3f4f6;
}
.preview-price {
  color: var(--color-primary);
  font-weight: 800;
}

.upload-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 120rpx;
  margin-top: 18rpx;
  border: 2rpx dashed #b8c5dc;
  border-radius: 20rpx;
  color: var(--color-primary);
  font-weight: 800;
  background: #f4f8ff;
}
.upload-btn.disabled { opacity: 0.64; }
.cover-preview {
  width: 200rpx;
  height: 200rpx;
  margin-top: 18rpx;
  border-radius: 20rpx;
  background: #f4f8ff;
}
</style>
