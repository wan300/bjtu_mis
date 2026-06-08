<template>
  <view class="page-shell page-with-tab">
    <view class="hero-card slim">
      <text class="title">服务管理</text>
      <text class="desc">管理你发布的服务，可编辑、下架或重新提交审核。</text>
      <button class="white-btn" @click="publishNew">发布新服务</button>
    </view>

    <view v-if="loading" class="loading-row">
      <text class="loading-text">加载中...</text>
    </view>

    <view v-else-if="services.length === 0" class="empty-row">
      <ji-empty title="暂无服务" desc="发布你的第一个技能服务吧" action-text="去发布" @action="publishNew" />
    </view>

    <view v-else class="list">
      <view v-for="svc in services" :key="svc.id" class="svc-card surface-card">
        <view class="svc-head">
          <view class="svc-cover">
            <image v-if="coverUrl(svc.coverUrl)" :src="coverUrl(svc.coverUrl)" class="svc-cover-img" mode="aspectFill" @error="handleCoverError(svc.coverUrl)" />
            <text v-if="!coverUrl(svc.coverUrl)">匠</text>
          </view>
          <view class="svc-summary">
            <text class="svc-title">{{ svc.title }}</text>
            <text class="svc-category">{{ svc.categoryName || "未分类" }}</text>
            <view class="svc-meta">
              <text class="svc-price">¥{{ money(svc.price) }}</text>
              <text class="svc-sales">已售 {{ svc.salesCount || 0 }}</text>
            </view>
          </view>
          <ji-status-pill :text="statusText(svc.status)" :tone="statusTone(svc.status)" />
        </view>

        <view class="svc-actions">
          <button class="action-btn primary" @click="startEdit(svc)">编辑</button>
          <button v-if="svc.status === 1" class="action-btn warn" @click="confirmOffline(svc)">下架</button>
          <button v-if="svc.status === 2" class="action-btn active" @click="confirmResubmit(svc)">重新提交</button>
        </view>
      </view>
    </view>

    <!-- 编辑弹窗 -->
    <view v-if="editing" class="modal-mask" @click="cancelEdit">
      <view class="modal-card" @click.stop>
        <text class="modal-title">编辑服务</text>

        <view class="field">
          <view class="field-label">服务标题</view>
          <input v-model="editForm.title" class="field-input" placeholder="请输入标题" />
        </view>
        <view class="field">
          <view class="field-label">价格</view>
          <input v-model="editForm.price" class="field-input" type="digit" placeholder="单次价格" />
        </view>
        <view class="field">
          <view class="field-label">库存</view>
          <input v-model="editForm.stock" class="field-input" type="number" placeholder="可预约次数" />
        </view>
        <view class="field">
          <view class="field-label">封面图片 URL</view>
          <input v-model="editForm.coverUrl" class="field-input" placeholder="图片地址" />
          <view class="upload-btn" :class="{ disabled: uploading }" @click="handleUploadImage">
            <text>{{ uploading ? "上传中..." : "上传封面图片" }}</text>
          </view>
          <image v-if="editForm.coverUrl" class="cover-preview" :src="editForm.coverUrl" mode="aspectFill" />
        </view>
        <view class="field">
          <view class="field-label">服务描述</view>
          <textarea v-model="editForm.description" class="field-textarea" placeholder="描述服务内容" />
        </view>

        <view class="modal-btns">
          <button class="action-btn idle" @click="cancelEdit">取消</button>
          <button class="action-btn primary" :loading="saving" @click="saveEdit">保存</button>
        </view>
      </view>
    </view>

    <ji-tab-bar />
  </view>
</template>

<script setup lang="ts">
import { ref, reactive } from "vue";
import { onShow } from "@dcloudio/uni-app";
import JiEmpty from "@/components/ji-empty.vue";
import JiStatusPill from "@/components/ji-status-pill.vue";
import JiTabBar from "@/components/ji-tab-bar.vue";
import { getMyServices, editService, offlineService, resubmitService } from "@/api/service";
import { uploadServiceImage } from "@/api/upload";
import type { ServiceItem } from "@/types/domain";
import { serviceStatusText, serviceStatusTone } from "@/utils/status";
import { money } from "@/utils/money";
import { normalizeImageUrl } from "@/utils/image";
import { hitSensitiveContact, hitRiskService } from "@/utils/regex";
import { toast, modal } from "@/utils/toast";

const services = ref<ServiceItem[]>([]);
const loading = ref(false);
const failedCoverUrls = ref<Record<string, true>>({});

// 编辑状态
const editing = ref(false);
const saving = ref(false);
const uploading = ref(false);
const editingId = ref<number>(0);
const editForm = reactive({
  title: "",
  description: "",
  price: "",
  stock: "1",
  coverUrl: "",
});

function statusText(status?: number) { return serviceStatusText(status); }
function statusTone(status?: number) { return serviceStatusTone(status); }

function coverUrl(url?: string) {
  const normalized = normalizeImageUrl(url);
  if (!normalized || failedCoverUrls.value[normalized]) return "";
  return normalized;
}

function handleCoverError(url?: string) {
  const normalized = normalizeImageUrl(url);
  if (normalized) {
    failedCoverUrls.value = { ...failedCoverUrls.value, [normalized]: true };
  }
}

function publishNew() {
  uni.navigateTo({ url: "/pages/service/publish" });
}

async function loadServices() {
  loading.value = true;
  try {
    services.value = await getMyServices();
  } catch {
    // toast handled by request interceptor
  } finally {
    loading.value = false;
  }
}

function startEdit(svc: ServiceItem) {
  editingId.value = svc.id;
  editForm.title = svc.title;
  editForm.description = svc.description || "";
  editForm.price = String(svc.price);
  editForm.stock = String(svc.stock);
  editForm.coverUrl = svc.coverUrl || "";
  editing.value = true;
}

function cancelEdit() {
  editing.value = false;
}

async function handleUploadImage() {
  if (uploading.value) return;
  uploading.value = true;
  try {
    const url = await uploadServiceImage();
    if (url) {
      editForm.coverUrl = url;
    }
  } finally {
    uploading.value = false;
  }
}

async function saveEdit() {
  const title = editForm.title.trim();
  const description = editForm.description.trim();
  const rawCoverUrl = editForm.coverUrl.trim();
  if (!title || !description || !editForm.price) {
    toast("请填写标题、描述和价格");
    return;
  }
  const priceNum = Number(editForm.price);
  const stockNum = Number(editForm.stock);
  if (isNaN(priceNum) || priceNum <= 0) {
    toast("价格必须为大于 0 的数字");
    return;
  }
  if (isNaN(stockNum) || stockNum < 0 || !Number.isInteger(stockNum)) {
    toast("库存必须为非负整数");
    return;
  }
  const coverUrl = normalizeImageUrl(rawCoverUrl);
  if (rawCoverUrl && !coverUrl) {
    toast("封面图片地址无效，请填写 http(s)、wxfile、cloud、data:image 或 /static 开头的地址");
    return;
  }
  const text = `${title} ${description}`;
  if (hitSensitiveContact(text) || hitRiskService(text)) {
    toast("内容包含联系方式或高风险词");
    return;
  }
  saving.value = true;
  try {
    await editService({
      serviceId: editingId.value,
      title,
      description,
      price: priceNum,
      priceConfig: JSON.stringify([{ key: "single", name: "单次体验", price: priceNum, unit: "次", qty: 1 }]),
      stock: stockNum,
      coverUrl: coverUrl || undefined,
    });
    toast("已保存", "success");
    editing.value = false;
    loadServices();
  } catch {
    // toast handled by interceptor
  } finally {
    saving.value = false;
  }
}

async function confirmOffline(svc: ServiceItem) {
  const result = await modal("确认下架", `确定要下架「${svc.title}」吗？下架后可从列表重新提交审核。`);
  if (!result.confirm) return;
  try {
    await offlineService(svc.id);
    toast("已下架", "success");
    loadServices();
  } catch {
    // handled
  }
}

async function confirmResubmit(svc: ServiceItem) {
  const result = await modal("重新提交审核", `确定将「${svc.title}」重新提交审核吗？`);
  if (!result.confirm) return;
  try {
    await resubmitService(svc.id);
    toast("已重新提交审核", "success");
    loadServices();
  } catch {
    // handled
  }
}

onShow(() => {
  loadServices();
});
</script>

<style scoped>
.slim {
  padding: 34rpx;
}

.title,
.desc {
  display: block;
}

.title {
  font-size: 42rpx;
  font-weight: 900;
}

.desc {
  margin-top: 16rpx;
  opacity: 0.82;
  line-height: 1.6;
}

.white-btn {
  margin-top: 28rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  padding: 20rpx;
  color: var(--color-primary);
  font-weight: 900;
  background: var(--color-card);
}

.loading-row {
  display: flex;
  justify-content: center;
  padding: 80rpx;
}

.loading-text {
  color: #98A3B7;
  font-size: 28rpx;
}

.empty-row {
  padding: 40rpx;
}

.list {
  padding: 20rpx 28rpx;
}

.svc-card {
  padding: 24rpx;
  margin-bottom: 20rpx;
  border-radius: 20rpx;
}

.svc-head {
  display: flex;
  gap: 20rpx;
  align-items: flex-start;
}

.svc-cover {
  flex: 0 0 100rpx;
  height: 100rpx;
  border-radius: 18rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f4fd8, #25c5a9);
  color: #fff;
  font-weight: 900;
  font-size: 28rpx;
  overflow: hidden;
  position: relative;
}

.svc-cover-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.svc-cover text {
  position: relative;
  z-index: 1;
}

.svc-summary {
  flex: 1;
  min-width: 0;
}

.svc-title {
  display: block;
  font-size: 30rpx;
  font-weight: 800;
  color: #152033;
  line-height: 1.4;
}

.svc-category {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #98A3B7;
}

.svc-meta {
  display: flex;
  gap: 16rpx;
  margin-top: 10rpx;
}

.svc-price {
  color: #ff7a45;
  font-size: 26rpx;
  font-weight: 900;
}

.svc-sales {
  color: #748198;
  font-size: 22rpx;
}

.svc-actions {
  display: flex;
  gap: 16rpx;
  margin-top: 20rpx;
  padding-top: 20rpx;
  border-top: 1rpx solid #F0F2F6;
}

.action-btn {
  flex: 1;
  border-radius: 12rpx;
  padding: 16rpx;
  font-size: 24rpx;
  font-weight: 700;
  text-align: center;
  border: none;
  line-height: 1.4;
}

.action-btn.primary {
  color: #fff;
  background: #1f4fd8;
}

.action-btn.warn {
  color: #a35b00;
  background: #fff2d6;
}

.action-btn.active {
  color: #1f4fd8;
  background: #e8efff;
}

.action-btn.idle {
  color: #718096;
  background: #edf2f7;
}

/* 编辑弹窗 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.45);
  z-index: 999;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.modal-card {
  width: 100%;
  max-height: 80vh;
  background: #fff;
  border-radius: 30rpx 30rpx 0 0;
  padding: 34rpx;
  overflow-y: auto;
}

.modal-title {
  display: block;
  font-size: 36rpx;
  font-weight: 900;
  color: #152033;
  margin-bottom: 24rpx;
}

.modal-btns {
  display: flex;
  gap: 16rpx;
  margin-top: 24rpx;
}

.modal-btns .action-btn {
  flex: 1;
}

.upload-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 120rpx;
  margin-top: 18rpx;
  border: 2rpx dashed #b8c5dc;
  border-radius: 20rpx;
  color: #1f4fd8;
  font-weight: 800;
  background: #f4f8ff;
}

.upload-btn.disabled {
  opacity: 0.64;
}

.cover-preview {
  width: 200rpx;
  height: 200rpx;
  margin-top: 18rpx;
  border-radius: 20rpx;
  background: #f4f8ff;
}
</style>
