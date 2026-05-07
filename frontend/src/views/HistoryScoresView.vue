<script setup>
import { onMounted, ref } from 'vue'
import { NH2, NP, NTag, NInput, NButton, NDataTable, NAlert, NSpace } from 'naive-ui'
import { useModuleData } from '../composables/useModuleData'
import { useSession } from '../composables/useSession'

const { payloads, moduleErrors, loadHistoryScores } = useModuleData()
const { isSessionReady } = useSession()

const term = ref('')

const columns = [
  { title: '学期', key: 'term', width: 140, ellipsis: { tooltip: true } },
  { title: '课程', key: 'course_name', minWidth: 220, ellipsis: { tooltip: true } },
  { title: '学分', key: 'credit', width: 80, align: 'center' },
  { title: '成绩', key: 'score', width: 100, align: 'center' },
  { title: '加分成绩', key: 'bonus_score', width: 100, align: 'center' },
  { title: '教师', key: 'teacher', width: 120, ellipsis: { tooltip: true } },
  { title: '说明', key: 'detail', minWidth: 160, ellipsis: { tooltip: true } }
]

async function handleLoad() {
  await loadHistoryScores(term.value)
}

onMounted(async () => {
  if (isSessionReady.value && !payloads.historyScores?.data) {
    await loadHistoryScores()
  }
})
</script>

<template>
  <div class="module-page">
    <div class="section-head">
      <div>
        <NH2>历史成绩</NH2>
        <NP class="desc">AA 教学支撑平台 · {{ payloads.historyScores?.synced_at || '尚未同步' }}</NP>
      </div>
      <NTag v-if="payloads.historyScores?.coverage" :type="payloads.historyScores.coverage === 'verified' ? 'success' : 'warning'" round>
        {{ payloads.historyScores.coverage }}
      </NTag>
    </div>

    <NSpace class="module-toolbar">
      <NInput v-model:value="term" placeholder="学期值，例如 2025-2026-2-2" style="width: 240px" />
      <NButton @click="handleLoad">按学期刷新</NButton>
    </NSpace>

    <NAlert v-if="moduleErrors.historyScores" type="error" :title="moduleErrors.historyScores" />

    <NDataTable
      :columns="columns"
      :data="payloads.historyScores?.data?.items || []"
      :bordered="true"
      size="small"
      :scroll-x="920"
      :row-key="(row) => `${row.term}-${row.course_name}-${row.score}`"
    >
      <template #empty>
        <span class="empty-text">当前没有可展示的历史成绩记录。</span>
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
