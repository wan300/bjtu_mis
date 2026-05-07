<script setup>
import { onMounted, ref } from 'vue'
import { NH2, NP, NTag, NInput, NSelect, NButton, NDataTable, NAlert, NSpace } from 'naive-ui'
import { useModuleData } from '../composables/useModuleData'
import { useSession } from '../composables/useSession'

const { payloads, moduleErrors, loadScores } = useModuleData()
const { isSessionReady } = useSession()

const scoresTerm = ref('')
const scoresCtype = ref('lr')

const ctypeOptions = [
  { label: '本学期成绩', value: 'lr' },
  { label: '历年成绩', value: 'ln' },
  { label: '英语认定成绩', value: 'en' },
  { label: '留级库成绩', value: 'rm' }
]

const columns = [
  { title: '学期', key: 'term', width: 140, ellipsis: { tooltip: true } },
  { title: '课程', key: 'course_name', width: 220, ellipsis: { tooltip: true } },
  { title: '学分', key: 'credit', width: 80, align: 'center' },
  { title: '成绩', key: 'score', width: 100, align: 'center' },
  { title: '教师', key: 'teacher', width: 120 }
]

async function handleLoad() {
  await loadScores(scoresTerm.value, scoresCtype.value)
}

onMounted(async () => {
  if (isSessionReady.value && !payloads.scores?.data) {
    await loadScores()
  }
})
</script>

<template>
  <div class="module-page">
    <div class="section-head">
      <div>
        <NH2>主修成绩</NH2>
        <NP class="desc">首版仅覆盖主修成绩主页面，不包含成绩卡和替代课程。</NP>
      </div>
      <NTag v-if="payloads.scores?.coverage" :type="payloads.scores.coverage === 'verified' ? 'success' : 'warning'" round>
        {{ payloads.scores.coverage }}
      </NTag>
    </div>

    <NSpace class="module-toolbar">
      <NInput v-model:value="scoresTerm" placeholder="学期值，例如 2025-2026-2-2" style="width: 240px" />
      <NSelect v-model:value="scoresCtype" :options="ctypeOptions" style="width: 160px" />
      <NButton @click="handleLoad">按学期刷新</NButton>
    </NSpace>

    <NAlert v-if="moduleErrors.scores" type="error" :title="moduleErrors.scores" />
    <NAlert v-if="payloads.scores?.coverage === 'provisional'" type="warning" title="当前模块仍是 provisional：接口已打通，但真实非空样本不足，建议继续补录。" />

    <NDataTable
      :columns="columns"
      :data="payloads.scores?.data?.items || []"
      :bordered="true"
      size="small"
      :scroll-x="660"
      :row-key="(row) => `${row.term}-${row.course_name}`"
    >
      <template #empty>
        <span class="empty-text">当前没有可展示的主修成绩记录。</span>
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
