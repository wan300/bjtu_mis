<template>
  <view class="page-shell">
    <view class="surface-card form">
      <text class="title">举报反馈</text>
      <text class="desc">发现跳单、违规内容、代写作弊等情况，可提交给平台运营处理。</text>
      <view class="field">
        <view class="field-label">举报对象</view>
        <input v-model="target" class="field-input" placeholder="用户/服务/订单编号" />
      </view>
      <view class="field">
        <view class="field-label">举报原因</view>
        <textarea v-model="reason" class="field-textarea" placeholder="请描述违规行为" />
      </view>
      <button class="primary-btn" @click="submit">提交举报</button>
      <text class="tips">举报接口待后端接入，当前会先做本地敏感信息校验。</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { hitSensitiveContact } from "@/utils/regex";
import { toast } from "@/utils/toast";

const target = ref("");
const reason = ref("");

function submit() {
  if (!target.value || !reason.value) {
    toast("请填写对象和原因");
    return;
  }
  if (hitSensitiveContact(reason.value)) {
    toast("举报原因中请勿填写联系方式");
    return;
  }
  toast("举报接口待接入");
}
</script>

<style scoped>
.form {
  padding: 34rpx;
}

.title,
.desc,
.tips {
  display: block;
}

.title {
  color: var(--color-dark);
  font-size: 38rpx;
  font-weight: 900;
}

.desc,
.tips {
  margin: 18rpx 0 28rpx;
  color: #748198;
  line-height: 1.6;
}
</style>
