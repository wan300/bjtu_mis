<script setup>
import { onMounted, reactive } from 'vue'
import { NH2, NP, NTag, NInput, NButton, NTable, NAlert, NSpace } from 'naive-ui'
import { useModuleData } from '../composables/useModuleData'
import { useSession } from '../composables/useSession'

const { payloads, moduleErrors, loadCalendar, loadEmptyRooms } = useModuleData()
const { isSessionReady } = useSession()

const filters = reactive({
  term: '',
  week: '',
  building: '',
  room: ''
})

function textValue(value) {
  return value == null ? '' : `${value}`.trim()
}

function emptyRoomsPayloadWeek() {
  return textValue(payloads.emptyRooms?.source_params?.week || payloads.emptyRooms?.data?.query?.week)
}

function currentCalendarWeek() {
  return textValue(payloads.calendar?.data?.current_week)
}

function applyDefaultWeek() {
  if (!textValue(filters.week)) {
    filters.week = currentCalendarWeek() || emptyRoomsPayloadWeek()
  }
}

function syncWeekFromPayload() {
  const week = emptyRoomsPayloadWeek()
  if (week) {
    filters.week = week
  }
}

async function prepareDefaultWeek() {
  if (!payloads.calendar?.data) {
    try {
      await loadCalendar()
    } catch {
      // The empty-rooms API resolves the current teaching week server-side too.
    }
  }
  applyDefaultWeek()
}

async function handleLoad() {
  await prepareDefaultWeek()
  await loadEmptyRooms(filters)
  syncWeekFromPayload()
}

onMounted(async () => {
  if (isSessionReady.value) {
    await prepareDefaultWeek()
    const payloadWeek = emptyRoomsPayloadWeek()
    const targetWeek = textValue(filters.week)
    if (!payloads.emptyRooms?.data || !payloadWeek || (targetWeek && payloadWeek !== targetWeek)) {
      await loadEmptyRooms(filters)
    }
    syncWeekFromPayload()
  }
})
</script>

<template>
  <div class="module-page">
    <div class="section-head">
      <div>
        <NH2>空教室</NH2>
        <NP class="desc">支持按学期、周次、教学楼和教室号查询整周节次矩阵。</NP>
      </div>
      <NTag v-if="payloads.emptyRooms?.coverage" :type="payloads.emptyRooms.coverage === 'verified' ? 'success' : 'warning'" round>
        {{ payloads.emptyRooms.coverage }}
      </NTag>
    </div>

    <div class="filter-grid">
      <NInput v-model:value="filters.term" placeholder="学期，可留空" />
      <NInput v-model:value="filters.week" placeholder="周次，例如 8" />
      <NInput v-model:value="filters.building" placeholder="教学楼代码，例如 2" />
      <NInput v-model:value="filters.room" placeholder="教室号，例如 SX101" />
      <NButton @click="handleLoad" class="filter-btn">刷新空教室</NButton>
    </div>

    <NAlert v-if="moduleErrors.emptyRooms" type="error" :title="moduleErrors.emptyRooms" />

    <div class="table-wrap">
      <NTable :bordered="true" size="small">
        <thead>
          <tr>
            <th>教室</th>
            <th v-for="slot in payloads.emptyRooms?.data?.slots || []" :key="`${slot.day}-${slot.period}`">
              {{ slot.day }} {{ slot.date || '' }} / {{ slot.period }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="room in payloads.emptyRooms?.data?.rooms || []" :key="room.room">
            <td>{{ room.room }}</td>
            <td
              v-for="(available, index) in room.availability"
              :key="`${room.room}-${index}`"
              :class="available ? 'free-cell' : 'busy-cell'"
            >
              {{ available ? '空闲' : '占用' }}
            </td>
          </tr>
          <tr v-if="!(payloads.emptyRooms?.data?.rooms || []).length">
            <td :colspan="1 + (payloads.emptyRooms?.data?.slots || []).length" class="empty-row">
              当前查询没有返回空教室矩阵数据。
            </td>
          </tr>
        </tbody>
      </NTable>
    </div>
  </div>
</template>

<style scoped>
.module-page { display: grid; gap: 18px; }
.section-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.section-head h2 { margin: 0; }
.desc { margin: 6px 0 0; color: #776b5d; }
.filter-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)) auto; gap: 12px; align-items: end; }
.filter-btn { min-width: 160px; }
.table-wrap { overflow-x: auto; border-radius: 16px; }
.table-wrap :deep(th),
.table-wrap :deep(td) { white-space: nowrap; }

:deep(.busy-cell) { background: rgba(181, 61, 61, 0.12); color: #b53d3d; }
:deep(.free-cell) { background: rgba(47, 125, 90, 0.08); color: #2f7d5a; }

.empty-row { text-align: center; color: #776b5d; padding: 32px !important; }

@media (max-width: 960px) {
  .filter-grid { grid-template-columns: 1fr; }
  .filter-btn { width: 100%; min-width: 0; }
}
</style>
