<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  NH2,
  NP,
  NTag,
  NSelect,
  NButton,
  NCard,
  NCollapse,
  NCollapseItem,
  NTag as NTagItem,
  NAlert,
  NSpace,
  NModal,
  NForm,
  NFormItem,
  NInput,
  NUpload,
  NUploadDragger,
  NText,
  useDialog,
  useMessage
} from 'naive-ui'
import { api } from '../api'
import { useModuleData } from '../composables/useModuleData'
import { useSession } from '../composables/useSession'

const { payloads, moduleErrors, loadHomework } = useModuleData()
const { isSessionReady } = useSession()
const message = useMessage()
const dialog = useDialog()

const homeworkStatus = ref('all')
const submitVisible = ref(false)
const submitLoading = ref(false)
const activeHomework = ref(null)
const uploadFileList = ref([])
const submitForm = reactive({
  content: ''
})

const statusOptions = [
  { label: '全部', value: 'all' },
  { label: '待完成', value: 'open' },
  { label: '已完成', value: 'done' }
]

function sortHomeworkItems(items) {
  return [...items].sort((a, b) => {
    const dueA = Date.parse(a.due_at || '') || Number.MAX_SAFE_INTEGER
    const dueB = Date.parse(b.due_at || '') || Number.MAX_SAFE_INTEGER
    if (dueA !== dueB) return dueA - dueB
    return `${a.title || ''}`.localeCompare(`${b.title || ''}`, 'zh-Hans-CN')
  })
}

const groupedHomework = computed(() => {
  const items = Array.isArray(payloads.homework?.data?.items) ? payloads.homework.data.items : []
  const groups = new Map()
  items.forEach(item => {
    const courseId = item.course_id != null ? String(item.course_id) : 'unknown'
    const courseName = `${item.course || ''}`.trim() || '未命名课程'
    const groupKey = `${courseId}::${courseName}`
    if (!groups.has(groupKey)) {
      groups.set(groupKey, { courseId, courseName, items: [] })
    }
    groups.get(groupKey).items.push(item)
  })
  return [...groups.values()]
    .map(group => ({
      ...group,
      items: sortHomeworkItems(group.items),
      total: group.items.length,
      openCount: group.items.filter(item => item.status === 'open').length
    }))
    .sort((a, b) => a.courseName.localeCompare(b.courseName, 'zh-Hans-CN'))
})

async function handleLoad() {
  await loadHomework(homeworkStatus.value)
}

function isSubmitted(item) {
  return Boolean(item?.submitted_at)
}

function statusLabel(item) {
  return isSubmitted(item) ? '已提交' : '未提交'
}

function statusTagType(item) {
  return isSubmitted(item) ? 'success' : 'warning'
}

function submittedAtLabel(item) {
  return item?.submitted_at || '未提交'
}

function resetSubmitForm() {
  submitForm.content = ''
  uploadFileList.value = []
}

function openSubmitModal(item) {
  activeHomework.value = item
  resetSubmitForm()
  submitVisible.value = true
}

function handleSubmitClick(item) {
  if (isSubmitted(item)) {
    dialog.warning({
      title: '确认重新提交',
      content: `该作业已于 ${submittedAtLabel(item)} 提交。继续操作会再次提交作业。`,
      positiveText: '继续',
      negativeText: '取消',
      onPositiveClick: () => openSubmitModal(item)
    })
    return
  }
  openSubmitModal(item)
}

async function handleSubmitHomework() {
  if (!activeHomework.value?.homework_id || !activeHomework.value?.course_id) return
  submitLoading.value = true
  try {
    const files = uploadFileList.value
      .map(item => item.file)
      .filter(Boolean)
    await api.submitHomework(activeHomework.value.homework_id, {
      courseId: activeHomework.value.course_id,
      content: submitForm.content,
      files
    })
    message.success('作业提交成功')
    submitVisible.value = false
    await loadHomework(homeworkStatus.value)
  } catch (error) {
    message.error(error.message || '作业提交失败')
  } finally {
    submitLoading.value = false
  }
}

onMounted(async () => {
  if (isSessionReady.value && !payloads.homework?.data) {
    await loadHomework()
  }
})
</script>

<template>
  <div class="module-page">
    <div class="section-head">
      <div>
        <NH2>作业</NH2>
        <NP class="desc">先聚合课程列表，再按课程抓取待完成与已完成作业。</NP>
      </div>
      <NTag v-if="payloads.homework?.coverage" :type="payloads.homework.coverage === 'verified' ? 'success' : 'warning'" round>
        {{ payloads.homework.coverage }}
      </NTag>
    </div>

    <NSpace class="module-toolbar">
      <NSelect v-model:value="homeworkStatus" :options="statusOptions" style="width: 160px" />
      <NButton @click="handleLoad">应用筛选</NButton>
    </NSpace>

    <NAlert v-if="moduleErrors.homework" type="error" :title="moduleErrors.homework" />

    <NCollapse v-if="groupedHomework.length" accordion>
      <NCollapseItem
        v-for="group in groupedHomework"
        :key="`${group.courseId}-${group.courseName}`"
      >
        <template #header>
          <div class="collapse-header">
            <strong>{{ group.courseName }}</strong>
            <NSpace :size="8">
              <NTagItem size="small" round>共 {{ group.total }} 条</NTagItem>
              <NTagItem v-if="group.openCount" size="small" round type="warning">待完成 {{ group.openCount }} 条</NTagItem>
            </NSpace>
          </div>
        </template>
        <div class="homework-items">
          <NCard
            v-for="item in group.items"
            :key="`${item.homework_id || 'noid'}-${item.course_id}-${item.sub_type}-${item.title}`"
            size="small"
            :bordered="true"
            class="homework-card"
          >
            <div class="hw-header">
              <h4>{{ item.title }}</h4>
              <NTagItem size="tiny" :type="statusTagType(item)" round>
                {{ statusLabel(item) }}
              </NTagItem>
            </div>
            <NP class="hw-meta">开始：{{ item.opened_at || '-' }} | 截止：{{ item.due_at || '-' }}</NP>
            <NP class="hw-meta">提交时间：{{ submittedAtLabel(item) }}</NP>
            <NP class="hw-excerpt">{{ item.content_excerpt || '-' }}</NP>
            <div class="hw-actions">
              <NButton
                size="small"
                type="primary"
                secondary
                :disabled="!item.can_submit || !item.homework_id"
                @click="handleSubmitClick(item)"
              >
                {{ isSubmitted(item) ? '重新提交' : '提交' }}
              </NButton>
            </div>
          </NCard>
        </div>
      </NCollapseItem>
    </NCollapse>

    <NP v-if="!groupedHomework.length" class="empty-text">当前没有可展示的作业记录。</NP>

    <NModal v-model:show="submitVisible" preset="card" title="提交作业" class="submit-modal">
      <NForm label-placement="top">
        <NFormItem label="作业">
          <NText>{{ activeHomework?.title || '-' }}</NText>
        </NFormItem>
        <NFormItem label="提交内容">
          <NInput
            v-model:value="submitForm.content"
            type="textarea"
            :autosize="{ minRows: 5, maxRows: 10 }"
            maxlength="3000"
            show-count
            placeholder="输入本次提交的文字说明"
          />
        </NFormItem>
        <NFormItem label="附件">
          <NUpload
            v-model:file-list="uploadFileList"
            multiple
            :default-upload="false"
          >
            <NUploadDragger>
              <div class="upload-title">点击或拖拽文件到此处</div>
              <NText depth="3">可提交文档、图片、压缩包等课程平台支持的文件。</NText>
            </NUploadDragger>
          </NUpload>
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="submitLoading" @click="submitVisible = false">取消</NButton>
          <NButton type="primary" :loading="submitLoading" @click="handleSubmitHomework">提交</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.module-page { display: grid; gap: 18px; }
.section-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.section-head h2 { margin: 0; }
.desc { margin: 6px 0 0; color: #776b5d; }
.collapse-header { display: flex; justify-content: space-between; align-items: center; width: 100%; padding-right: 16px; }
.homework-items { display: grid; gap: 10px; padding: 8px 0; }
.homework-card { border-radius: 12px; }
.hw-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.hw-header h4 { margin: 0; font-size: 14px; }
.hw-meta { margin: 6px 0; color: #776b5d; font-size: 12px; }
.hw-excerpt { margin: 4px 0 0; font-size: 13px; color: #555; }
.hw-actions { display: flex; justify-content: flex-end; margin-top: 10px; }
.empty-text { color: #776b5d; text-align: center; }
.submit-modal { max-width: 680px; }
.upload-title { margin-bottom: 6px; font-size: 14px; font-weight: 600; color: #2d2a26; }

@media (max-width: 768px) {
  .collapse-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
    padding-right: 8px;
  }

  .hw-header {
    flex-direction: column;
    gap: 8px;
  }

  .hw-meta,
  .hw-excerpt {
    word-break: break-word;
  }

  .hw-actions {
    justify-content: flex-start;
  }
}
</style>
