<template>
  <view class="page-shell">
    <view class="hero-card">
      <text class="title">发布悬赏需求</text>
      <text class="desc">说明目标、预算和期望时间，讲师会根据需求给出报价与方案。</text>
    </view>

    <view class="surface-card form">
      <view class="field">
        <view class="field-label">需求标题</view>
        <input v-model="form.title" class="field-input" maxlength="128" placeholder="如 求高数历年真题解析" />
      </view>
      <view class="field">
        <view class="field-label">分类</view>
        <picker :range="categoryNames" @change="pickCategory">
          <view class="field-input">{{ selectedCategoryName }}</view>
        </picker>
      </view>
      <view class="field">
        <view class="field-label">预算金额</view>
        <input v-model="form.budgetAmount" class="field-input" type="digit" placeholder="请输入预算，例如 50" />
      </view>
      <view class="field">
        <view class="field-label">期望时间</view>
        <input v-model="form.expectedTime" class="field-input" maxlength="128" placeholder="如 本周内 / 周末下午" />
      </view>
      <view class="field">
        <view class="field-label">需求描述</view>
        <textarea
          v-model="form.description"
          class="field-textarea"
          maxlength="1000"
          placeholder="说明课程、基础情况、希望讲师提供的帮助和交付方式"
        />
      </view>
      <button class="primary-btn" :loading="loading" @click="submit">发布需求</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { publishDemand } from "@/api/demand";
import { useConfigStore } from "@/store/config";
import { hitRiskService, hitSensitiveContact } from "@/utils/regex";
import { toast } from "@/utils/toast";

const config = useConfigStore();
const categoryIndex = ref(0);
const loading = ref(false);
const form = reactive({
  title: "",
  description: "",
  budgetAmount: "",
  expectedTime: "",
});

const categoryNames = computed(() => config.categories.map((item) => item.name));
const selectedCategoryName = computed(() => categoryNames.value[categoryIndex.value] || "请选择分类");

onLoad(() => config.loadCategories());

function pickCategory(event: { detail: { value: number } }) {
  categoryIndex.value = Number(event.detail.value);
}

async function submit() {
  const title = form.title.trim();
  const description = form.description.trim();
  const expectedTime = form.expectedTime.trim();
  const budgetAmount = Number(form.budgetAmount);
  const text = `${title} ${description}`;
  if (!title || !description || !form.budgetAmount) {
    toast("请填写标题、描述和预算");
    return;
  }
  if (title.length > 128 || description.length > 1000 || expectedTime.length > 128) {
    toast("内容长度超出限制");
    return;
  }
  if (!Number.isFinite(budgetAmount) || budgetAmount <= 0) {
    toast("预算金额必须大于0");
    return;
  }
  if (hitSensitiveContact(text) || hitRiskService(text)) {
    toast("内容包含联系方式或高风险词");
    return;
  }
  const category = config.categories[categoryIndex.value];
  if (!category) {
    toast("请选择分类");
    return;
  }
  loading.value = true;
  try {
    const result = await publishDemand({
      categoryId: category.id,
      title,
      description,
      budgetAmount,
      expectedTime: expectedTime || undefined,
    });
    toast(result.message || "需求已发布", "success");
    uni.redirectTo({ url: `/pages/demand/detail?id=${result.demandId}` });
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.title,
.desc {
  display: block;
}

.title {
  font-size: 42rpx;
  font-weight: 900;
}

.desc {
  width: 560rpx;
  margin-top: 18rpx;
  opacity: 0.82;
  line-height: 1.6;
}

.form {
  margin-top: 24rpx;
  padding: 34rpx;
}
</style>
