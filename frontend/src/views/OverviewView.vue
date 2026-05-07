<script setup>
import { computed, onMounted } from 'vue'
import { NCard, NGrid, NGi, NStatistic, NTag, NDataTable, NH2, NP, NSpace } from 'naive-ui'
import { useRouter } from 'vue-router'
import { useModuleData } from '../composables/useModuleData'
import { useSync } from '../composables/useSync'
import { useSession } from '../composables/useSession'

const router = useRouter()
const { payloads, itemCount } = useModuleData()
const { syncStatus, refreshAll } = useSync()
const { isSessionReady, loadSessionStatus } = useSession()

const navigation = [
  { key: 'profile', label: '我的信息' },
  { key: 'academicProgress', label: '学业进度' },
  { key: 'historyScores', label: '历史成绩' },
  { key: 'timetable', label: '课表' },
  { key: 'exams', label: '考务' },
  { key: 'scores', label: '主修成绩' },
  { key: 'calendar', label: '学年日历' },
  { key: 'homework', label: '作业' },
  { key: 'courseResources', label: '课程资源' },
  { key: 'emptyRooms', label: '空教室' }
]

const coverageLabels = {
  verified: '已验证',
  provisional: '临时覆盖',
  pending: '待同步'
}

const coverageTypes = {
  verified: 'success',
  provisional: 'warning',
  pending: 'default'
}

const moduleCards = computed(() =>
  navigation.map((item) => ({
    ...item,
    coverage: payloads[item.key]?.coverage || 'pending',
    count: itemCount(payloads[item.key]),
    syncedAt: payloads[item.key]?.synced_at || '尚未同步'
  }))
)

const summaryColumns = [
  { title: '模块', key: 'module', width: 120 },
  { title: '状态', key: 'status', width: 100 },
  { title: '条数', key: 'items', width: 80 },
  { title: '覆盖级别', key: 'coverage', width: 120 }
]

const summaryData = computed(() =>
  Object.entries(syncStatus.module_summary || {}).map(([key, value]) => ({
    module: key,
    status: value.status,
    items: value.items ?? '-',
    coverage: value.coverage ?? '-',
    id: key
  }))
)

const moduleRoutes = {
  academicProgress: 'academic-progress',
  historyScores: 'history-scores',
  courseResources: 'course-resources',
  emptyRooms: 'empty-rooms'
}

function goToModule(key) {
  router.push(`/${moduleRoutes[key] || key}`)
}

onMounted(async () => {
  if (!isSessionReady.value) {
    await loadSessionStatus()
  }
  if (isSessionReady.value) {
    await refreshAll()
  }
})
</script>

<template>
  <div class="overview-page">
    <div class="section-head">
      <NH2>模块总览</NH2>
      <NP class="section-desc">点击模块卡片进入详情页</NP>
    </div>

    <NGrid cols="1 s:2 m:3" :x-gap="16" :y-gap="16" responsive="screen">
      <NGi v-for="module in moduleCards" :key="module.key">
        <NCard
          :bordered="true"
          hoverable
          class="module-card"
          @click="goToModule(module.key)"
        >
          <NSpace justify="space-between" align="start">
            <div>
              <h3 class="module-label">{{ module.label }}</h3>
              <NStatistic :value="module.count" />
              <NP class="module-sync-at">{{ module.syncedAt }}</NP>
            </div>
            <NTag :type="coverageTypes[module.coverage]" size="small" round>
              {{ coverageLabels[module.coverage] }}
            </NTag>
          </NSpace>
        </NCard>
      </NGi>
    </NGrid>

    <NCard v-if="summaryData.length" :bordered="true" class="summary-card" title="同步摘要">
      <NDataTable
        :columns="summaryColumns"
        :data="summaryData"
        :bordered="false"
        size="small"
        :scroll-x="420"
        :row-key="(row) => row.id"
      />
    </NCard>
  </div>
</template>

<style scoped>
.overview-page {
  display: grid;
  gap: 20px;
}

.section-head {
  margin: 0;
}

.section-head h2 {
  margin: 0 0 4px;
}

.section-desc {
  margin: 0;
  color: #776b5d;
}

.module-card {
  cursor: pointer;
  border-radius: 18px;
  transition: transform 0.16s ease, box-shadow 0.16s ease;
}

.module-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 32px rgba(88, 61, 20, 0.12);
}

.module-label {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 600;
}

.module-sync-at {
  margin: 4px 0 0;
  font-size: 12px;
  color: #776b5d;
}

.summary-card {
  border-radius: 18px;
}
</style>
