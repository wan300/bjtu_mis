<script setup>
import { computed, h, onMounted, ref } from 'vue'
import { NH2, NP, NTag, NSelect, NButton, NDataTable, NAlert, NSpace, NDrawer, NDrawerContent, NSpin, NEmpty } from 'naive-ui'
import { api } from '../api'
import { useModuleData } from '../composables/useModuleData'
import { useSession } from '../composables/useSession'

const { payloads, moduleErrors, loadHistoryScores } = useModuleData()
const { isSessionReady } = useSession()

const ALL_TERMS_VALUE = 'all'
const term = ref(ALL_TERMS_VALUE)
const detailVisible = ref(false)
const selectedScore = ref(null)
const scoreDetail = ref(null)
const scoreDetailLoading = ref(false)
const scoreDetailError = ref('')
const termOptions = computed(() => {
  const terms = payloads.historyScores?.data?.available_terms || []
  return [
    { label: '全部学期', value: ALL_TERMS_VALUE },
    ...terms.map((item) => ({
      label: item.label || item.value,
      value: item.value
    }))
  ]
})

const columns = [
  { title: '学期', key: 'term', width: 140, ellipsis: { tooltip: true } },
  { title: '课程', key: 'course_name', minWidth: 220, ellipsis: { tooltip: true } },
  { title: '学分', key: 'credit', width: 80, align: 'center' },
  { title: '成绩', key: 'score', width: 100, align: 'center' },
  { title: '加分成绩', key: 'bonus_score', width: 100, align: 'center' },
  { title: '教师', key: 'teacher', width: 120, ellipsis: { tooltip: true } },
  {
    title: '详情',
    key: 'detail',
    minWidth: 160,
    render(row) {
      if (!row.detail_path) return row.detail || '-'
      return h(
        NButton,
        {
          size: 'tiny',
          tertiary: true,
          onClick: () => openScoreDetail(row)
        },
        { default: () => row.detail || '查看详情' }
      )
    }
  }
]

async function handleLoad() {
  await loadHistoryScores(term.value || ALL_TERMS_VALUE)
}

async function handleTermChange(value) {
  term.value = value || ALL_TERMS_VALUE
  await loadHistoryScores(term.value)
}

async function openScoreDetail(row) {
  selectedScore.value = row
  scoreDetail.value = null
  scoreDetailError.value = ''
  detailVisible.value = true

  if (!row.detail_path) {
    scoreDetailError.value = '这条成绩没有可用的分数明细链接。'
    return
  }

  scoreDetailLoading.value = true
  try {
    const payload = await api.getScoreDetail(row.detail_path)
    scoreDetail.value = payload?.data || null
  } catch (error) {
    scoreDetailError.value = error.message || '分数详情加载失败'
  } finally {
    scoreDetailLoading.value = false
  }
}

onMounted(async () => {
  const loadedTerm = payloads.historyScores?.source_params?.term
  if (isSessionReady.value && (!payloads.historyScores?.data || loadedTerm !== ALL_TERMS_VALUE)) {
    await loadHistoryScores(ALL_TERMS_VALUE)
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
      <NSelect
        v-model:value="term"
        :options="termOptions"
        placeholder="选择学期"
        style="width: 240px"
        @update:value="handleTermChange"
      />
      <NButton @click="handleLoad">刷新</NButton>
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

    <NDrawer v-model:show="detailVisible" placement="right" width="min(100vw, 560px)">
      <NDrawerContent title="分数详情" closable :native-scrollbar="false">
        <div v-if="selectedScore" class="score-detail">
          <div class="detail-head">
            <p>{{ selectedScore.term || '-' }}</p>
            <h3>{{ selectedScore.course_name }}</h3>
            <NSpace>
              <NTag round>成绩 {{ selectedScore.score || '-' }}</NTag>
              <NTag v-if="selectedScore.bonus_score" round>加分 {{ selectedScore.bonus_score }}</NTag>
              <NTag v-if="selectedScore.credit" round>{{ selectedScore.credit }} 学分</NTag>
            </NSpace>
          </div>

          <NSpin :show="scoreDetailLoading">
            <NAlert v-if="scoreDetailError" type="error" :title="scoreDetailError" />
            <div v-else-if="scoreDetail" class="detail-body">
              <dl v-if="scoreDetail.fields?.length" class="detail-fields">
                <div v-for="field in scoreDetail.fields" :key="`${field.label}-${field.value}`">
                  <dt>{{ field.label }}</dt>
                  <dd>{{ field.value }}</dd>
                </div>
              </dl>

              <div v-for="(table, tableIndex) in scoreDetail.tables || []" :key="tableIndex" class="detail-table-wrap">
                <p v-if="table.title" class="table-title">{{ table.title }}</p>
                <table class="detail-table">
                  <thead v-if="table.headers?.length">
                    <tr>
                      <th v-for="(header, headerIndex) in table.headers" :key="headerIndex">{{ header }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, rowIndex) in table.rows" :key="rowIndex">
                      <td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <NP v-if="!scoreDetail.fields?.length && !scoreDetail.tables?.length && scoreDetail.raw_text">
                {{ scoreDetail.raw_text }}
              </NP>
              <NEmpty v-else-if="!scoreDetail.fields?.length && !scoreDetail.tables?.length" description="这条成绩没有可展示的明细内容" />
            </div>
          </NSpin>
        </div>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.module-page { display: grid; gap: 18px; }
.section-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.section-head h2 { margin: 0; }
.desc { margin: 6px 0 0; color: #776b5d; }
.empty-text { color: #776b5d; padding: 24px; display: block; text-align: center; }
.score-detail { display: grid; gap: 18px; }
.detail-head { display: grid; gap: 8px; }
.detail-head p { margin: 0; color: #776b5d; }
.detail-head h3 { margin: 0; font-size: 20px; }
.detail-body { display: grid; gap: 16px; }
.detail-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin: 0; }
.detail-fields div { padding: 10px; border: 1px solid #eadfce; border-radius: 8px; background: #fffaf3; }
.detail-fields dt { color: #776b5d; font-size: 12px; }
.detail-fields dd { margin: 4px 0 0; font-weight: 600; }
.detail-table-wrap { overflow-x: auto; }
.table-title { margin: 0 0 8px; font-weight: 600; }
.detail-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.detail-table th,
.detail-table td { border: 1px solid #eadfce; padding: 8px; text-align: left; white-space: nowrap; }
.detail-table th { background: #fff4e4; color: #5f513f; }
</style>
