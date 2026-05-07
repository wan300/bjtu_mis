<script setup>
import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NBreadcrumb,
  NBreadcrumbItem,
  NAlert,
  NButton,
  NDataTable,
  NH2,
  NIcon,
  NInput,
  NP,
  NSelect,
  NSpace,
  NTag
} from 'naive-ui'
import {
  DownloadOutline,
  FolderOpenOutline,
  RefreshOutline,
  SearchOutline
} from '@vicons/ionicons5'
import { useRoute } from 'vue-router'
import { api } from '../api'
import { useModuleData } from '../composables/useModuleData'
import { useSession } from '../composables/useSession'

const route = useRoute()
const { payloads, moduleErrors, loadCourseResources } = useModuleData()
const { isSessionReady } = useSession()

const selectedCourseId = ref('')
const folderId = ref('0')
const searchText = ref('')
const loading = ref(false)
const COVERAGE_LABELS = {
  verified: '已验证',
  provisional: '临时覆盖'
}

const resourceData = computed(() => payloads.courseResources?.data || {})
const courses = computed(() => Array.isArray(resourceData.value.courses) ? resourceData.value.courses : [])
const folders = computed(() => Array.isArray(resourceData.value.folders) ? resourceData.value.folders : [])
const tree = computed(() => Array.isArray(resourceData.value.tree) ? resourceData.value.tree : [])
const resources = computed(() => Array.isArray(resourceData.value.resources) ? resourceData.value.resources : [])

const courseOptions = computed(() =>
  courses.value.map((course) => ({
    label: [course.course_name, course.teacher_name].filter(Boolean).join(' · '),
    value: String(course.course_id)
  }))
)

const coverageType = computed(() => (
  payloads.courseResources?.coverage === 'verified' ? 'success' : 'warning'
))

function coverageLabel(value) {
  return COVERAGE_LABELS[value] || value || '-'
}

function routeCourseId() {
  const value = route.query.course_id
  return Array.isArray(value) ? value[0] : value
}

const folderPath = computed(() => {
  const byId = new Map(tree.value.map((folder) => [String(folder.folder_id), folder]))
  const path = []
  const seen = new Set()
  let current = String(folderId.value || '0')

  while (current && current !== '0' && byId.has(current) && !seen.has(current)) {
    const folder = byId.get(current)
    path.unshift(folder)
    seen.add(current)
    current = String(folder.parent_id || '0')
  }

  return path
})

function rowKey(row) {
  return row.rp_id || row.resource_id || `${row.folder_id}-${row.name}`
}

async function fetchResources(overrides = {}) {
  const nextCourseId = overrides.courseId ?? selectedCourseId.value
  const nextFolderId = overrides.folderId ?? folderId.value ?? '0'
  const nextSearch = overrides.search ?? searchText.value

  loading.value = true
  try {
    await loadCourseResources({
      course_id: nextCourseId,
      folder_id: nextFolderId || '0',
      search: nextSearch
    })

    const loaded = payloads.courseResources?.data || {}
    selectedCourseId.value = loaded.selected_course_id != null
      ? String(loaded.selected_course_id)
      : String(nextCourseId || '')
    folderId.value = loaded.folder_id != null ? String(loaded.folder_id) : String(nextFolderId || '0')
  } catch (error) {
    moduleErrors.courseResources = error.message
  } finally {
    loading.value = false
  }
}

async function handleCourseUpdate(value) {
  selectedCourseId.value = value ? String(value) : ''
  folderId.value = '0'
  await fetchResources({ courseId: selectedCourseId.value, folderId: '0' })
}

async function openFolder(value) {
  folderId.value = String(value || '0')
  await fetchResources({ folderId: folderId.value })
}

async function applySearch() {
  await fetchResources({ search: searchText.value })
}

async function clearSearch() {
  searchText.value = ''
  await fetchResources({ search: '' })
}

function handleDownload(row) {
  if (!row.can_download || !row.rp_id) return
  window.open(api.getCourseResourceDownloadUrl(row.rp_id, row.name), '_blank', 'noopener')
}

const resourceColumns = [
  {
    title: '课件名称',
    key: 'name',
    minWidth: 280,
    ellipsis: {
      tooltip: true
    }
  },
  {
    title: '类型',
    key: 'extension',
    width: 110,
    render(row) {
      if (!row.extension) return '-'
      return h(NTag, { size: 'small', round: true }, { default: () => row.extension.toUpperCase() })
    }
  },
  {
    title: '大小',
    key: 'size',
    width: 90,
    render(row) {
      return row.size ? `${row.size} MB` : '-'
    }
  },
  {
    title: '上传时间',
    key: 'uploaded_at',
    width: 170,
    ellipsis: {
      tooltip: true
    },
    render(row) {
      return row.uploaded_at || '-'
    }
  },
  {
    title: '教师',
    key: 'teacher_name',
    width: 120,
    ellipsis: {
      tooltip: true
    },
    render(row) {
      return row.teacher_name || '-'
    }
  },
  {
    title: '下载次数',
    key: 'download_count',
    width: 100,
    render(row) {
      return row.download_count ?? '-'
    }
  },
  {
    title: '状态',
    key: 'can_download',
    width: 110,
    render(row) {
      return h(
        NTag,
        { size: 'small', round: true, type: row.can_download ? 'success' : 'default' },
        { default: () => (row.can_download ? '可下载' : '不可下载') }
      )
    }
  },
  {
    title: '操作',
    key: 'actions',
    width: 120,
    align: 'right',
    render(row) {
      return h(
        NButton,
        {
          size: 'small',
          type: 'primary',
          tertiary: true,
          disabled: !row.can_download || !row.rp_id,
          onClick: () => handleDownload(row)
        },
        {
          icon: () => h(NIcon, null, { default: () => h(DownloadOutline) }),
          default: () => '下载'
        }
      )
    }
  }
]

watch(
  () => resourceData.value.selected_course_id,
  (value) => {
    if (value != null && !selectedCourseId.value) {
      selectedCourseId.value = String(value)
    }
  },
  { immediate: true }
)

watch(
  () => resourceData.value.folder_id,
  (value) => {
    if (value != null) {
      folderId.value = String(value)
    }
  },
  { immediate: true }
)

watch(
  () => route.query.course_id,
  async () => {
    const nextCourseId = routeCourseId()
    if (!isSessionReady.value || !nextCourseId) return
    selectedCourseId.value = String(nextCourseId)
    folderId.value = '0'
    await fetchResources({ courseId: selectedCourseId.value, folderId: '0', search: '' })
  }
)

onMounted(async () => {
  if (isSessionReady.value && (!payloads.courseResources?.data || routeCourseId())) {
    await fetchResources({ courseId: routeCourseId() || selectedCourseId.value, folderId: '0' })
  }
})
</script>

<template>
  <div class="module-page">
    <div class="section-head">
      <div>
        <NH2>课程资源</NH2>
        <NP class="desc">电子课件 · {{ resourceData.current_term || '当前学期' }}</NP>
      </div>
      <NTag v-if="payloads.courseResources?.coverage" :type="coverageType" round>
        {{ coverageLabel(payloads.courseResources.coverage) }}
      </NTag>
    </div>

    <div class="toolbar">
      <NSelect
        :value="selectedCourseId || null"
        :options="courseOptions"
        filterable
        clearable
        placeholder="选择课程"
        class="course-select"
        @update:value="handleCourseUpdate"
      />
      <NInput
        v-model:value="searchText"
        clearable
        placeholder="搜索课件名称"
        @keydown.enter="applySearch"
        @clear="clearSearch"
      />
      <NButton type="primary" :loading="loading" @click="applySearch">
        <template #icon>
          <NIcon><SearchOutline /></NIcon>
        </template>
        搜索
      </NButton>
      <NButton :loading="loading" @click="fetchResources()">
        <template #icon>
          <NIcon><RefreshOutline /></NIcon>
        </template>
        刷新
      </NButton>
    </div>

    <NAlert v-if="moduleErrors.courseResources" type="error" :title="moduleErrors.courseResources" />

    <div class="resource-layout">
      <aside class="folder-panel">
        <div class="panel-title">目录</div>
        <button
          class="folder-button"
          :class="{ active: folderId === '0' }"
          type="button"
          @click="openFolder('0')"
        >
          <NIcon><FolderOpenOutline /></NIcon>
          <span>全部课件</span>
        </button>
        <button
          v-for="folder in folders"
          :key="folder.folder_id"
          class="folder-button"
          type="button"
          @click="openFolder(folder.folder_id)"
        >
          <NIcon><FolderOpenOutline /></NIcon>
          <span>{{ folder.name }}</span>
        </button>
        <NP v-if="!folders.length" class="muted-text">当前目录没有子目录</NP>
      </aside>

      <main class="resource-main">
        <div class="resource-head">
          <NBreadcrumb>
            <NBreadcrumbItem class="breadcrumb-link" @click="openFolder('0')">
              全部课件
            </NBreadcrumbItem>
            <NBreadcrumbItem
              v-for="folder in folderPath"
              :key="folder.folder_id"
              class="breadcrumb-link"
              @click="openFolder(folder.folder_id)"
            >
              {{ folder.name }}
            </NBreadcrumbItem>
          </NBreadcrumb>
          <NSpace :size="8" align="center">
            <NTag size="small" round>{{ resources.length }} 个文件</NTag>
          </NSpace>
        </div>

        <NDataTable
          :columns="resourceColumns"
          :data="resources"
          :loading="loading"
          :bordered="true"
          size="small"
          :scroll-x="1100"
          :row-key="rowKey"
        />
      </main>
    </div>
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

.toolbar {
  display: grid;
  grid-template-columns: minmax(260px, 360px) minmax(220px, 1fr) auto auto;
  gap: 12px;
  align-items: center;
}

.course-select {
  width: 100%;
}

.resource-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.folder-panel,
.resource-main {
  border: 1px solid rgba(140, 116, 81, 0.2);
  border-radius: 8px;
  background: rgba(255, 252, 247, 0.86);
}

.folder-panel {
  padding: 12px;
}

.panel-title {
  margin: 2px 4px 10px;
  font-size: 13px;
  font-weight: 700;
  color: #4f4336;
}

.folder-button {
  width: 100%;
  min-height: 36px;
  padding: 7px 9px;
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #4f4336;
  text-align: left;
  cursor: pointer;
}

.folder-button span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.folder-button:hover,
.folder-button.active {
  background: rgba(179, 79, 31, 0.1);
  color: #9b421a;
}

.muted-text {
  margin: 12px 4px 2px;
  color: #776b5d;
  font-size: 12px;
}

.resource-main {
  min-width: 0;
  overflow: hidden;
}

.resource-head {
  min-height: 48px;
  padding: 10px 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid rgba(140, 116, 81, 0.16);
}

.breadcrumb-link {
  cursor: pointer;
}

@media (max-width: 900px) {
  .toolbar,
  .resource-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .toolbar {
    gap: 10px;
  }

  .toolbar :deep(.n-button) {
    width: 100%;
  }

  .folder-panel {
    padding: 10px;
  }

  .resource-head {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
}
</style>
