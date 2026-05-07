<script setup>
import { onMounted, ref } from 'vue'
import { NH2, NP, NTag, NInput, NButton, NGrid, NGi, NCard, NAlert, NSpace } from 'naive-ui'
import { useModuleData } from '../composables/useModuleData'
import { useSession } from '../composables/useSession'

const { payloads, moduleErrors, loadCalendar } = useModuleData()
const { isSessionReady } = useSession()

const calendarMonth = ref(new Date().toISOString().slice(0, 7))

async function handleLoad() {
  await loadCalendar(calendarMonth.value)
}

onMounted(async () => {
  if (isSessionReady.value && !payloads.calendar?.data) {
    await loadCalendar()
  }
})
</script>

<template>
  <div class="module-page">
    <div class="section-head">
      <div>
        <NH2>学年日历</NH2>
        <NP class="desc">默认展示当前月份，可按月刷新 VE 日历接口。</NP>
      </div>
      <NTag v-if="payloads.calendar?.coverage" :type="payloads.calendar.coverage === 'verified' ? 'success' : 'warning'" round>
        {{ payloads.calendar.coverage }}
      </NTag>
    </div>

    <NSpace class="module-toolbar">
      <NInput v-model:value="calendarMonth" type="month" style="width: 200px" />
      <NButton @click="handleLoad">刷新月份</NButton>
    </NSpace>

    <NAlert v-if="moduleErrors.calendar" type="error" :title="moduleErrors.calendar" />

    <NGrid cols="1 s:2 m:4" :x-gap="14" :y-gap="14" responsive="screen">
      <NGi v-for="item in payloads.calendar?.data?.items || []" :key="item.date">
        <NCard :bordered="true" size="small" class="calendar-card">
          <strong>{{ item.date }}</strong>
          <span class="calendar-week">教学周 {{ item.week || '-' }}</span>
        </NCard>
      </NGi>
    </NGrid>

    <NP v-if="!(payloads.calendar?.data?.items || []).length" class="empty-text">当前没有可展示的日历记录。</NP>
  </div>
</template>

<style scoped>
.module-page { display: grid; gap: 18px; }
.section-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.section-head h2 { margin: 0; }
.desc { margin: 6px 0 0; color: #776b5d; }
.calendar-card { display: flex; flex-direction: column; gap: 6px; }
.calendar-week { color: #776b5d; font-size: 13px; }
.empty-text { color: #776b5d; text-align: center; }
</style>
