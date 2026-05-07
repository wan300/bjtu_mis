<script setup>
import { onMounted } from 'vue'
import { NH2, NP, NTag, NInput, NButton, NDataTable, NAlert, NSpace } from 'naive-ui'
import { useModuleData } from '../composables/useModuleData'
import { useSession } from '../composables/useSession'
import { ref } from 'vue'

const { payloads, moduleErrors, loadExams } = useModuleData()
const { isSessionReady } = useSession()

const examsTerm = ref('')

const columns = [
  { title: '课程', key: 'course_name', width: 200, ellipsis: { tooltip: true } },
  { title: '时间地点', key: 'schedule', width: 200 },
  { title: '考试方式', key: 'exam_mode', width: 120 },
  { title: '报名信息', key: 'registration', width: 160 },
  { title: '状态', key: 'status', width: 100 }
]

async function handleLoad() {
  await loadExams(examsTerm.value)
}

onMounted(async () => {
  if (isSessionReady.value && !payloads.exams?.data) {
    await loadExams()
  }
})
</script>

<template>
  <div class="module-page">
    <div class="section-head">
      <div>
        <NH2>考务</NH2>
        <NP class="desc">当前样本覆盖有限，若页面无数据会明确以 provisional 空态返回。</NP>
      </div>
      <NTag v-if="payloads.exams?.coverage" :type="payloads.exams.coverage === 'verified' ? 'success' : 'warning'" round>
        {{ payloads.exams.coverage }}
      </NTag>
    </div>

    <NSpace class="module-toolbar">
      <NInput v-model:value="examsTerm" placeholder="学期值，例如 2025-2026-2-2" style="width: 240px" />
      <NButton @click="handleLoad">按学期刷新</NButton>
    </NSpace>

    <NAlert v-if="moduleErrors.exams" type="error" :title="moduleErrors.exams" />
    <NAlert v-if="payloads.exams?.coverage === 'provisional'" type="warning" title="当前模块仍是 provisional：已完成页面级解析，但非空业务样本仍建议继续补录。" />

    <NDataTable
      :columns="columns"
      :data="payloads.exams?.data?.items || []"
      :bordered="true"
      size="small"
      :scroll-x="780"
      :row-key="(row) => `${row.term}-${row.course_name}`"
    >
      <template #empty>
        <span class="empty-text">当前没有可展示的考务记录。</span>
      </template>
    </NDataTable>
  </div>
</template>

<style scoped>
.module-page { display: grid; gap: 18px; }
.section-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.section-head h2 { margin: 0; }
.desc { margin: 6px 0 0; color: #776b5d; }
.empty-text { color: #776b5d; padding: 24px; display: block; text-align: center; }
</style>
