<template>
  <view class="page-shell">
    <view class="hero-card">
      <text class="title">学生身份认证</text>
      <text class="desc">认证通过后可下单、发布服务，并建立校园信誉档案。</text>
    </view>
    <view class="surface-card form">
      <view class="field">
        <view class="field-label">学校/校区</view>
        <picker :range="campusNames" @change="pickCampus">
          <view class="field-input">{{ campusNames[campusIndex] }}</view>
        </picker>
      </view>
      <view class="field">
        <view class="field-label">真实姓名</view>
        <input v-model="realName" class="field-input" placeholder="请输入姓名" />
      </view>
      <view class="field">
        <view class="field-label">学号</view>
        <input v-model="studentNo" class="field-input" placeholder="请输入学号" />
      </view>
      <view class="field">
        <view class="field-label">学生证/校园卡</view>
        <view class="upload" @click="chooseImage">
          <text v-if="uploading">上传中...</text>
          <text v-else-if="certImageUrl">{{ certImageUrl.startsWith("http") ? "已上传认证图片" : "已选择图片" }}</text>
          <text v-else>点击选择图片</text>
        </view>
      </view>
      <button class="primary-btn" :loading="loading" :disabled="!realName || !studentNo" @click="submit">提交认证</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { submitVerify } from "@/api/user";
import { getUploadToken } from "@/api/upload";
import { useUserStore } from "@/store/user";
import { toast } from "@/utils/toast";

const user = useUserStore();
const campusNames = ["示例大学", "主校区", "东校区"];
const campusIds = [1, 1, 1];
const campusIndex = ref(0);
const realName = ref("");
const studentNo = ref("");
const certImageUrl = ref("");
const loading = ref(false);
const uploading = ref(false);

function pickCampus(event: { detail: { value: number } }) {
  campusIndex.value = Number(event.detail.value);
}

async function chooseImage() {
  const res = await uni.chooseImage({ count: 1, sizeType: ["compressed"] });
  const tempPath = res.tempFilePaths?.[0];
  if (!tempPath) return;

  uploading.value = true;
  try {
    const fileName = tempPath.split("/").pop() || "cert.jpg";
    const token = await getUploadToken({ fileName, maxSizeBytes: 5 * 1024 * 1024 });

    if (token.url.startsWith("https://mock-cos")) {
      // 开发环境：COS 未配置，直接使用本地路径
      certImageUrl.value = tempPath;
      toast("图片已选择（开发模式）", "success");
    } else {
      // 生产环境：读取文件二进制，PUT 到 COS 预签名 URL
      const fs = uni.getFileSystemManager();
      const data = fs.readFileSync(tempPath);
      await uni.request({
        url: token.url,
        method: "PUT",
        data,
        header: { "Content-Type": "" }, // 空 Content-Type 让 COS 自动识别
      });
      // 去掉签名参数得到公开 URL
      certImageUrl.value = token.url.split("?")[0];
      toast("图片上传成功", "success");
    }
  } catch {
    toast("图片上传失败，请重试");
  } finally {
    uploading.value = false;
  }
}

async function submit() {
  if (!realName.value || !studentNo.value) {
    toast("请补充姓名和学号");
    return;
  }
  loading.value = true;
  try {
    const result = await submitVerify({
      campusId: campusIds[campusIndex.value],
      certType: 1,
      certImageUrl: certImageUrl.value || "mock://student-card.jpg",
      realName: realName.value,
      studentNo: studentNo.value,
    });
    toast(result.message || "提交成功", "success");
    await user.refreshUserInfo().catch(() => undefined);
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
/* ── 顶部说明：玻璃拟态卡片 ── */
.hero-card {
  color: var(--color-text);
}

.title,
.desc {
  display: block;
}

.title {
  font-size: 42rpx;
  font-weight: 900;
  line-height: 1.3;
  color: var(--color-dark);
}

.desc {
  width: 560rpx;
  margin-top: 18rpx;
  color: var(--color-text-secondary);
  font-size: 26rpx;
  line-height: 1.6;
}

.form {
  margin-top: 24rpx;
  padding: 34rpx;
}

/* ── OCR 上传框：3px 粗黑虚线框 + 珊瑚粉 3D 相机 ── */
.upload {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16rpx;
  height: 220rpx;
  border: 3px dashed var(--color-dark);
  border-radius: var(--radius-md);
  color: var(--color-accent-3);
  font-size: 48rpx;
  font-weight: 800;
  background: var(--color-card);
  transition: transform 0.14s ease, box-shadow 0.14s ease;
}

.upload:active {
  transform: translate(2rpx, 2rpx);
}

.upload-text {
  font-size: 24rpx;
  color: var(--color-text-muted);
}

/* ── 上传成功：奶油黄便利贴 ── */
.upload.done {
  border-style: solid;
  background: var(--color-accent-2);
  color: var(--color-dark);
  font-size: 28rpx;
  position: relative;
}

.upload.done::after {
  content: "";
  position: absolute;
  top: -8rpx;
  right: 12rpx;
  width: 0;
  height: 0;
  border-left: 20rpx solid transparent;
  border-bottom: 20rpx solid rgba(0,0,0,0.12);
}
</style>
