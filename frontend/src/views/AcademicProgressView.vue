<script setup>
import { computed, h, onMounted } from 'vue'
import {
  NAlert,
  NButton,
  NDataTable,
  NH2,
  NIcon,
  NP,
  NProgress,
  NSpace,
  NStatistic,
  NTag
} from 'naive-ui'
import { RefreshOutline } from '@vicons/ionicons5'
import { useModuleData } from '../composables/useModuleData'
import { useSession } from '../composables/useSession'

const { payloads, moduleErrors, loadAcademicProgress } = useModuleData()
const { isSessionReady } = useSession()

const progressPayload = computed(() => payloads.academicProgress || null)
const progressData = computed(() => progressPayload.value?.data || {})
const summary = computed(() => progressData.value.summary || {})
const mergedBuckets = computed(() => Array.isArray(progressData.value.merged_buckets) ? progressData.value.merged_buckets : [])
const detailBuckets = computed(() => Array.isArray(progressData.value.detail_buckets) ? progressData.value.detail_buckets : [])
const courses = computed(() => Array.isArray(progressData.value.courses) ? progressData.value.courses : [])
const coverageType = computed(() => progressPayload.value?.coverage === 'verified' ? 'success' : 'warning')
const completionRate = computed(() => Math.max(0, Math.min(Number(summary.value.completion_rate || 0), 100)))

function formatCredit(value) {
  if (value === null || value === undefined || value === '') return '-'
  const number = Number(value)
  if (Number.isNaN(number)) return `${value}`
  return Number.isInteger(number) ? String(number) : number.toFixed(1)
}

const bucketColumns = [
  { title: '平台', key: 'parent', width: 180, ellipsis: { tooltip: true } },
  { title: '课组', key: 'name', minWidth: 240, ellipsis: { tooltip: true } },
  {
    title: '要求',
    key: 'required_credits',
    width: 90,
    align: 'center',
    render(row) {
      return formatCredit(row.required_credits)
    }
  },
  {
    title: '已完成',
    key: 'earned_credits',
    width: 90,
    align: 'center',
    render(row) {
      return formatCredit(row.earned_credits)
    }
  },
  {
    title: '待完成',
    key: 'pending_credits',
    width: 90,
    align: 'center',
    render(row) {
      return formatCredit(row.pending_credits)
    }
  },
  {
    title: '完成度',
    key: 'completion_rate',
    width: 160,
    render(row) {
      const value = Math.max(0, Math.min(Number(row.completion_rate || 0), 100))
      return h(NProgress, {
        type: 'line',
        percentage: value,
        height: 8,
        indicatorPlacement: 'inside',
        processing: value < 100
      })
    }
  }
]

const courseColumns = [
  { title: '学期', key: 'term', width: 130, ellipsis: { tooltip: true } },
  { title: '课程号', key: 'course_code', width: 120, ellipsis: { tooltip: true } },
  { title: '课程', key: 'course_name', minWidth: 220, ellipsis: { tooltip: true } },
  {
    title: '学分',
    key: 'credit',
    width: 80,
    align: 'center',
    render(row) {
      return formatCredit(row.credit)
    }
  },
  { title: '成绩', key: 'score', width: 90, align: 'center' },
  { title: '考试时间', key: 'exam_date', width: 120 },
  { title: '课组信息', key: 'group_info', minWidth: 160, ellipsis: { tooltip: true } }
]

async function refreshProgress() {
  await loadAcademicProgress()
}

onMounted(async () => {
  if (isSessionReady.value && !payloads.academicProgress?.data) {
    await loadAcademicProgress()
  }
})
</script>

<template>
  <div class="module-page">
    <div class="section-head">
      <div>
        <NH2>学业进度</NH2>
        <NP class="desc">AA 教学支撑平台 · {{ progressPayload?.synced_at || '尚未同步' }}</NP>
      </div>
      <NSpace align="center">
        <NTag v-if="progressPayload?.coverage" :type="coverageType" round>
          {{ progressPayload.coverage }}
        </NTag>
        <NButton secondary @click="refreshProgress">
          <template #icon>
            <NIcon><RefreshOutline /></NIcon>
          </template>
          刷新
        </NButton>
      </NSpace>
    </div>

    <NAlert v-if="moduleErrors.academicProgress" type="error" :title="moduleErrors.academicProgress" />

    <section class="progress-panel">
      <div class="summary-grid">
        <div class="summary-main">
          <p class="panel-kicker">课组完成率</p>
          <strong>{{ completionRate.toFixed(1) }}%</strong>
          <NProgress
            type="line"
            :percentage="completionRate"
            :height="10"
            :show-indicator="false"
            processing
          />
        </div>
        <NStatistic label="已完成学分" :value="formatCredit(summary.passed_credits)" />
        <NStatistic label="要求学分" :value="formatCredit(summary.target_credits)" />
        <NStatistic label="未完成课组" :value="summary.failed_course_count || 0" />
        <NStatistic label="已列课程" :value="summary.course_count || 0" />
      </div>
    </section>

    <section v-if="mergedBuckets.length" class="bucket-panel">
      <div
        v-for="bucket in mergedBuckets"
        :key="bucket.name"
        class="bucket-row"
      >
        <div class="bucket-head">
          <span>{{ bucket.name }}</span>
          <strong>{{ formatCredit(bucket.earned_credits) }} / {{ formatCredit(bucket.required_credits) }}</strong>
        </div>
        <NProgress
          type="line"
          :percentage="Math.max(0, Math.min(Number(bucket.completion_rate || 0), 100))"
          :height="8"
          :show-indicator="false"
        />
      </div>
    </section>

    <section class="table-panel">
      <div class="panel-head">
        <h3>课组明细</h3>
        <NTag size="small" round>{{ detailBuckets.length }} 项</NTag>
      </div>
      <NDataTable
        :columns="bucketColumns"
        :data="detailBuckets"
        :bordered="true"
        size="small"
        :scroll-x="850"
        :row-key="(row) => `${row.parent || ''}-${row.name}`"
      >
        <template #empty>
          <span class="empty-text">当前没有可展示的课组进度。</span>
        </template>
      </NDataTable>
    </section>

    <section class="table-panel">
      <div class="panel-head">
        <h3>已计入课程</h3>
        <NTag size="small" round>{{ courses.length }} 门</NTag>
      </div>
      <NDataTable
        :columns="courseColumns"
        :data="courses"
        :bordered="true"
        size="small"
        :scroll-x="920"
        :row-key="(row) => `${row.term}-${row.course_code || row.course_name}`"
      >
        <template #empty>
          <span class="empty-text">当前没有可展示的已计入课程。</span>
        </template>
      </NDataTable>
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

.progress-panel,
.bucket-panel,
.table-panel {
  border: 1px solid rgba(140, 116, 81, 0.2);
  border-radius: 8px;
  background: rgba(255, 252, 247, 0.86);
}

.progress-panel {
  padding: 18px;
}

.summary-grid {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) repeat(4, minmax(110px, 160px));
  gap: 16px;
  align-items: end;
}

.summary-main {
  display: grid;
  gap: 8px;
}

.panel-kicker {
  margin: 0;
  color: #776b5d;
  font-size: 13px;
}

.summary-main strong {
  font-size: 34px;
  line-height: 1;
  color: #b34f1f;
}

.bucket-panel {
  display: grid;
  gap: 12px;
  padding: 14px;
}

.bucket-row {
  display: grid;
  gap: 8px;
}

.bucket-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: #4f4336;
  font-size: 13px;
}

.table-panel {
  overflow: hidden;
}

.panel-head {
  min-height: 48px;
  padding: 10px 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid rgba(140, 116, 81, 0.16);
}

.panel-head h3 {
  margin: 0;
  font-size: 15px;
}

.empty-text {
  color: #776b5d;
  padding: 24px;
  display: block;
  text-align: center;
}

@media (max-width: 1120px) {
  .summary-grid {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 620px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }

  .summary-main strong {
    font-size: 30px;
  }
}
</style>
