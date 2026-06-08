<template>
  <view class="page-shell">
    <view class="surface-card card">
      <image v-if="user.userInfo?.avatarUrl" class="avatar avatar-img" :src="user.userInfo.avatarUrl" mode="aspectFill" />
      <view v-else class="avatar">{{ (user.userInfo?.nickname || "技").slice(0, 1) }}</view>
      <text class="name">{{ user.userInfo?.nickname || "技匠用户" }}</text>
      <text class="muted">{{ user.userInfo?.campusName || "未选择校区" }}</text>
    </view>
    <view class="surface-card card">
      <view class="profile-form">
        <button class="avatar-picker" open-type="chooseAvatar" @chooseavatar="chooseAvatar">
          <image v-if="editAvatarPreview" class="edit-avatar-img" :src="editAvatarPreview" mode="aspectFill" />
          <text v-else class="edit-avatar-placeholder">头像</text>
        </button>
        <input v-model="editNickname" class="field-input nickname-input" type="nickname" placeholder="请输入昵称" />
      </view>
      <button class="primary-btn" :loading="saving" :disabled="!canSave" @click="saveProfile">保存资料</button>
    </view>
    <view class="surface-card card">
      <view class="row"><text>用户编号</text><text>#{{ user.userInfo?.id || "-" }}</text></view>
      <view class="row"><text>当前身份</text><text>{{ user.currentRole === 2 ? "讲师" : "买家" }}</text></view>
      <view class="row"><text>实名认证</text><text>{{ user.isVerified ? "已通过" : "未完成" }}</text></view>
      <view class="row"><text>BJTU 身份</text><text>{{ user.userInfo?.hasMisIdentity ? "已授权" : "未授权" }}</text></view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { uploadAvatar } from "@/api/upload";
import { useUserStore } from "@/store/user";
import { toast } from "@/utils/toast";

const user = useUserStore();
const editNickname = ref("");
const editAvatarUrl = ref("");
const editAvatarTempPath = ref("");
const saving = ref(false);
const editAvatarPreview = computed(() => editAvatarTempPath.value || editAvatarUrl.value);
const canSave = computed(() => Boolean(editNickname.value.trim() && !saving.value));

onShow(seedProfile);

function seedProfile() {
  editNickname.value = user.userInfo?.nickname || "";
  editAvatarUrl.value = user.userInfo?.avatarUrl || "";
  editAvatarTempPath.value = "";
}

function chooseAvatar(event: { detail?: { avatarUrl?: string } }) {
  const avatarUrl = event.detail?.avatarUrl;
  if (!avatarUrl) return;
  editAvatarTempPath.value = avatarUrl;
  editAvatarUrl.value = avatarUrl;
}

async function saveProfile() {
  if (!canSave.value) return;
  saving.value = true;
  try {
    const avatarUrl = editAvatarTempPath.value ? await uploadAvatar(editAvatarTempPath.value) : editAvatarUrl.value || undefined;
    await user.updateProfile(editNickname.value.trim(), avatarUrl);
    seedProfile();
    toast("资料已更新", "success");
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.card {
  margin-bottom: 24rpx;
  padding: 34rpx;
  text-align: center;
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 132rpx;
  height: 132rpx;
  margin: 0 auto 20rpx;
  border-radius: 44rpx;
  color: #fff;
  font-size: 50rpx;
  font-weight: 900;
  background: var(--color-primary);
}

.avatar-img {
  display: block;
}

.profile-form {
  display: flex;
  align-items: center;
  gap: 22rpx;
  margin-bottom: 24rpx;
}

.avatar-picker {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 112rpx;
  height: 112rpx;
  border-radius: 36rpx;
  overflow: hidden;
  background: var(--color-primary);
}

.edit-avatar-img {
  width: 112rpx;
  height: 112rpx;
}

.edit-avatar-placeholder {
  color: var(--color-primary);
  font-size: 24rpx;
  font-weight: 900;
}

.nickname-input {
  flex: 1;
  text-align: left;
}

.name {
  display: block;
  color: var(--color-dark);
  font-size: 34rpx;
  font-weight: 900;
}

.row {
  display: flex;
  justify-content: space-between;
  padding: 24rpx 0;
  border-bottom: var(--stroke);
}

.row:last-of-type {
  border-bottom: 0;
}

.bind-btn {
  margin-top: 24rpx;
}
</style>
