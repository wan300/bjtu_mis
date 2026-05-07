<script setup>
import { computed, onMounted } from 'vue'
import {
  NAlert,
  NAvatar,
  NButton,
  NH2,
  NIcon,
  NP,
  NSpace,
  NTag
} from 'naive-ui'
import { RefreshOutline } from '@vicons/ionicons5'
import { useModuleData } from '../composables/useModuleData'
import { useSession } from '../composables/useSession'

const { payloads, moduleErrors, loadProfile } = useModuleData()
const { isSessionReady } = useSession()

const profile = computed(() => payloads.profile?.data || {})
const fields = computed(() => Array.isArray(profile.value.fields) ? profile.value.fields : [])
const sections = computed(() => {
  const source = Array.isArray(profile.value.sections) ? profile.value.sections : []
  if (source.length) return source
  return fields.value.length ? [{ title: '学籍信息', fields: fields.value }] : []
})
const displayName = computed(() => profile.value.name || profile.value.account || '未获取姓名')
const subtitle = computed(() => [profile.value.college, profile.value.major, profile.value.class_name].filter(Boolean).join(' · '))
const avatarText = computed(() => displayName.value.slice(0, 1).toUpperCase())
const coverageType = computed(() => payloads.profile?.coverage === 'verified' ? 'success' : 'warning')

async function refreshProfile() {
  await loadProfile()
}

onMounted(async () => {
  if (isSessionReady.value && !payloads.profile?.data) {
    await loadProfile()
  }
})
</script>

<template>
  <div class="module-page">
    <div class="section-head">
      <div>
        <NH2>我的信息</NH2>
        <NP class="desc">AA 教学支撑平台 · 学籍信息 · {{ payloads.profile?.synced_at || '尚未同步' }}</NP>
      </div>
      <NSpace align="center">
        <NTag v-if="payloads.profile?.coverage" :type="coverageType" round>
          {{ payloads.profile.coverage }}
        </NTag>
        <NButton secondary @click="refreshProfile">
          <template #icon>
            <NIcon><RefreshOutline /></NIcon>
          </template>
          刷新
        </NButton>
      </NSpace>
    </div>

    <NAlert v-if="moduleErrors.profile" type="error" :title="moduleErrors.profile" />

    <section class="profile-panel">
      <div class="identity-strip">
        <NAvatar
          :size="104"
          :src="profile.avatar_url || undefined"
          object-fit="cover"
          class="profile-avatar"
        >
          {{ avatarText }}
        </NAvatar>
        <div class="identity-text">
          <h3>{{ displayName }}</h3>
          <p>{{ subtitle || '暂无院系专业信息' }}</p>
          <div class="identity-tags">
            <NTag v-if="profile.student_id" size="small" round>{{ profile.student_id }}</NTag>
            <NTag v-if="profile.student_status" size="small" round>{{ profile.student_status }}</NTag>
            <NTag v-if="profile.campus" size="small" round>{{ profile.campus }}</NTag>
          </div>
        </div>
      </div>

      <div
        v-if="sections.length"
        class="section-list"
      >
        <div
          v-for="section in sections"
          :key="section.title"
          class="profile-section"
        >
          <h3>{{ section.title }}</h3>
          <div class="field-grid">
            <div
              v-for="field in section.fields"
              :key="`${section.title}-${field.label}`"
              class="field-item"
            >
              <span>{{ field.label }}</span>
              <strong>{{ field.value || '-' }}</strong>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="empty-state">
        当前没有可展示的学籍信息。
      </div>
    </section>
  </div>
</template>

<style scoped>
.module-page {
  display: grid;
  gap: 18px;
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.section-head h2 {
  margin: 0;
}

.desc {
  margin: 6px 0 0;
  color: #776b5d;
}

.profile-panel {
  display: grid;
  gap: 18px;
  padding: 18px;
  border: 1px solid rgba(140, 116, 81, 0.2);
  border-radius: 8px;
  background: rgba(255, 252, 247, 0.86);
}

.identity-strip {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.profile-avatar {
  flex-shrink: 0;
  background: #b34f1f;
  color: #fffaf1;
  font-weight: 700;
}

.identity-text {
  min-width: 0;
}

.identity-text h3 {
  margin: 0;
  font-size: 22px;
  color: #2c261f;
}

.identity-text p {
  margin: 6px 0 0;
  color: #776b5d;
}

.identity-tags {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.section-list {
  display: grid;
  gap: 16px;
}

.profile-section {
  display: grid;
  gap: 10px;
}

.profile-section h3 {
  margin: 0;
  font-size: 15px;
  color: #2c261f;
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border: 1px solid rgba(140, 116, 81, 0.18);
  border-radius: 8px;
  overflow: hidden;
}

.field-item {
  min-width: 0;
  display: grid;
  gap: 6px;
  padding: 12px;
  border-right: 1px solid rgba(140, 116, 81, 0.12);
  border-bottom: 1px solid rgba(140, 116, 81, 0.12);
  background: rgba(255, 255, 255, 0.45);
}

.field-item:nth-child(4n) {
  border-right: 0;
}

.field-item span {
  color: #776b5d;
  font-size: 12px;
}

.field-item strong {
  min-width: 0;
  color: #2c261f;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.empty-state {
  min-height: 120px;
  display: grid;
  place-items: center;
  color: #776b5d;
  border: 1px dashed rgba(140, 116, 81, 0.28);
  border-radius: 8px;
}

@media (max-width: 1100px) {
  .field-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .field-item:nth-child(4n) {
    border-right: 1px solid rgba(140, 116, 81, 0.12);
  }

  .field-item:nth-child(2n) {
    border-right: 0;
  }
}

@media (max-width: 768px) {
  .identity-strip {
    align-items: flex-start;
  }

  .identity-text h3 {
    font-size: 20px;
  }

  .field-grid {
    grid-template-columns: 1fr;
  }

  .field-item,
  .field-item:nth-child(2n),
  .field-item:nth-child(4n) {
    border-right: 0;
  }
}
</style>
