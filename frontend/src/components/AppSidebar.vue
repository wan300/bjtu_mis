<script setup>
import { h, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { NMenu, NIcon } from 'naive-ui'
import {
  GridOutline,
  PersonCircleOutline,
  StatsChartOutline,
  TimeOutline,
  CalendarNumberOutline,
  DocumentTextOutline,
  TrophyOutline,
  CalendarOutline,
  BookOutline,
  FolderOpenOutline,
  HomeOutline
} from '@vicons/ionicons5'

const router = useRouter()
const route = useRoute()

const menuOptions = [
  { label: '总览', key: '/overview', icon: () => h(NIcon, null, { default: () => h(GridOutline) }) },
  { label: '我的信息', key: '/profile', icon: () => h(NIcon, null, { default: () => h(PersonCircleOutline) }) },
  { label: '学业进度', key: '/academic-progress', icon: () => h(NIcon, null, { default: () => h(StatsChartOutline) }) },
  { label: '历史成绩', key: '/history-scores', icon: () => h(NIcon, null, { default: () => h(TimeOutline) }) },
  { label: '课表', key: '/timetable', icon: () => h(NIcon, null, { default: () => h(CalendarNumberOutline) }) },
  { label: '抢课', key: '/course-selection', icon: () => h(NIcon, null, { default: () => h(BookOutline) }) },
  { label: '考务', key: '/exams', icon: () => h(NIcon, null, { default: () => h(DocumentTextOutline) }) },
  { label: '主修成绩', key: '/scores', icon: () => h(NIcon, null, { default: () => h(TrophyOutline) }) },
  { label: '学年日历', key: '/calendar', icon: () => h(NIcon, null, { default: () => h(CalendarOutline) }) },
  { label: '作业', key: '/homework', icon: () => h(NIcon, null, { default: () => h(BookOutline) }) },
  { label: '课程资源', key: '/course-resources', icon: () => h(NIcon, null, { default: () => h(FolderOpenOutline) }) },
  { label: '空教室', key: '/empty-rooms', icon: () => h(NIcon, null, { default: () => h(HomeOutline) }) }
]

const activeKey = computed(() => route.path)

function handleMenuUpdate(key) {
  router.push(key)
}
</script>

<template>
  <div class="sidebar-header">
    <h2 class="sidebar-logo">BJTU MIS</h2>
    <p class="sidebar-subtitle">校园信息采集控制台</p>
  </div>
  <NMenu
    :value="activeKey"
    :options="menuOptions"
    :indent="24"
    @update:value="handleMenuUpdate"
  />
</template>

<style scoped>
.sidebar-header {
  padding: 20px 18px 12px;
  text-align: center;
}

.sidebar-logo {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: #b34f1f;
  letter-spacing: 0.05em;
}

.sidebar-subtitle {
  margin: 4px 0 0;
  font-size: 12px;
  color: #776b5d;
}

:deep(.n-menu) {
  margin-top: 8px;
}
</style>
