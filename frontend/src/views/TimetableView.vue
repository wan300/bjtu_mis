<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  NAlert,
  NButton,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NH2,
  NIcon,
  NP,
  NSpace,
  NSpin,
  NTabPane,
  NTable,
  NTabs,
  NTag
} from 'naive-ui'
import {
  DownloadOutline,
  OpenOutline,
  RefreshOutline
} from '@vicons/ionicons5'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { useModuleData } from '../composables/useModuleData'
import { useSession } from '../composables/useSession'

const router = useRouter()
const { payloads, moduleErrors, loadTimetable, loadHomework, loadCalendar } = useModuleData()
const { isSessionReady } = useSession()

const WEEKDAY_ORDER = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const WEEKDAY_ALIASES = {
  星期一: '周一', 星期二: '周二', 星期三: '周三', 星期四: '周四',
  星期五: '周五', 星期六: '周六', 星期日: '周日', 星期天: '周日', 周天: '周日'
}
const COVERAGE_LABELS = {
  verified: '已验证',
  provisional: '临时覆盖'
}

const detailVisible = ref(false)
const selectedCourse = ref(null)
const activeDetailTab = ref('homework')
const homeworkLoading = ref(false)
const detailResources = ref([])
const detailFolders = ref([])
const detailResourcesLoading = ref(false)
const detailResourcesError = ref('')
const detailResourceCoverage = ref('')
const detailSelectedCourseId = ref('')
let detailResourceSeq = 0

function normalizeWeekday(day) {
  const source = `${day || ''}`.trim()
  return WEEKDAY_ALIASES[source] || source
}

function normalizePeriodLabel(period) {
  const source = `${period || ''}`.trim()
  const matched = source.match(/第?\s*(\d+)\s*节/)
  if (matched) return `第${Number(matched[1])}节`
  if (/^\d+$/.test(source)) return `第${Number(source)}节`
  return source
}

function periodSortValue(label) {
  const matched = `${label || ''}`.match(/(\d+)/)
  return matched ? Number(matched[1]) : Number.MAX_SAFE_INTEGER
}

function parseCurrentWeekNumber(rawWeek) {
  const matched = `${rawWeek || ''}`.match(/(\d+)/)
  return matched ? Number(matched[1]) : null
}

function parseWeeksSpec(weeksText) {
  const source = `${weeksText || ''}`
  if (!source.trim()) return null
  const compact = source.replace(/\s+/g, '')
  const oddOnly = /单/.test(compact)
  const evenOnly = /双/.test(compact)
  const normalized = compact.replace(/第/g, '').replace(/周/g, '').replace(/[（(].*?[）)]/g, '')
  const tokens = normalized.split(/[，,、；;]+/).map(t => t.trim()).filter(Boolean)
  if (!tokens.length) return null
  const weeks = new Set()
  for (const token of tokens) {
    const rangeMatch = token.match(/^(\d+)-(\d+)$/)
    if (rangeMatch) {
      let start = Number(rangeMatch[1]), end = Number(rangeMatch[2])
      if (start > end) [start, end] = [end, start]
      for (let i = start; i <= end; i++) weeks.add(i)
      continue
    }
    const singleMatch = token.match(/^(\d+)$/)
    if (singleMatch) weeks.add(Number(singleMatch[1]))
  }
  if (!weeks.size) return null
  if (oddOnly) return new Set([...weeks].filter(w => w % 2 === 1))
  if (evenOnly) return new Set([...weeks].filter(w => w % 2 === 0))
  return weeks
}

function isCurrentWeekCourse(weeksText, currentWeekNumber) {
  if (!currentWeekNumber) return false
  const weeks = parseWeeksSpec(weeksText)
  if (!weeks) return false
  return weeks.has(currentWeekNumber)
}

function normalizeCourseName(value) {
  return `${value || ''}`
    .replace(/\[[^\]]*]/g, '')
    .replace(/【[^】]*】/g, '')
    .replace(/[（(][^）)]*[）)]/g, '')
    .replace(/\s+/g, '')
    .trim()
}

function courseLookupKey(entry) {
  return `${entry?.course_code || ''}`.trim() || normalizeCourseName(entry?.course_name)
}

function courseEntryKey(entry) {
  return [
    entry?.weekday,
    entry?.period,
    entry?.course_code,
    entry?.section || '',
    entry?.course_name
  ].join('::')
}

function isSameCourseEntry(entry, target) {
  if (!entry || !target) return false
  return courseEntryKey(entry) === courseEntryKey(target)
}

function matchesSelectedCourse(item) {
  const entry = selectedCourse.value
  if (!entry) return false
  const entryCode = `${entry.course_code || ''}`.trim()
  const itemCode = `${item.course_code || ''}`.trim()
  if (entryCode && itemCode && entryCode === itemCode) return true

  const entryName = normalizeCourseName(entry.course_name)
  const itemName = normalizeCourseName(item.course)
  return Boolean(entryName && itemName && entryName === itemName)
}

function homeworkUrgency(item) {
  if (item.status === 'done') {
    return { label: '已完成', type: 'success' }
  }
  const dueTime = Date.parse(item.due_at || '')
  if (!Number.isFinite(dueTime)) {
    return { label: '待完成', type: 'warning' }
  }
  const hoursLeft = (dueTime - Date.now()) / 36e5
  if (hoursLeft < 0) return { label: '已截止', type: 'error' }
  if (hoursLeft <= 48) return { label: '即将截止', type: 'error' }
  return { label: '待完成', type: 'warning' }
}

function coverageLabel(value) {
  return COVERAGE_LABELS[value] || value || '-'
}

function formatResourceSize(size) {
  return size ? `${size} MB` : '未知大小'
}

function handleResourceDownload(resource) {
  if (!resource?.can_download || !resource.rp_id) return
  window.open(api.getCourseResourceDownloadUrl(resource.rp_id, resource.name), '_blank', 'noopener')
}

async function ensureHomeworkLoaded() {
  if (payloads.homework?.data || homeworkLoading.value) return
  homeworkLoading.value = true
  try {
    await loadHomework('all')
  } finally {
    homeworkLoading.value = false
  }
}

async function loadDetailResources(entry = selectedCourse.value) {
  if (!entry) return
  const requestSeq = ++detailResourceSeq
  detailResourcesLoading.value = true
  detailResourcesError.value = ''
  detailResources.value = []
  detailFolders.value = []
  detailResourceCoverage.value = ''
  detailSelectedCourseId.value = ''

  try {
    const payload = await api.getCourseResources({
      course_id: courseLookupKey(entry),
      folder_id: '0'
    })
    if (requestSeq !== detailResourceSeq) return
    const data = payload?.data || {}
    detailResources.value = Array.isArray(data.resources) ? data.resources : []
    detailFolders.value = Array.isArray(data.folders) ? data.folders : []
    detailResourceCoverage.value = payload?.coverage || ''
    detailSelectedCourseId.value = data.selected_course_id != null ? String(data.selected_course_id) : ''
  } catch (error) {
    if (requestSeq !== detailResourceSeq) return
    detailResourcesError.value = error.message
  } finally {
    if (requestSeq === detailResourceSeq) {
      detailResourcesLoading.value = false
    }
  }
}

function openCourseDetail(entry) {
  selectedCourse.value = entry
  detailVisible.value = true
  activeDetailTab.value = 'homework'
  void ensureHomeworkLoaded()
  void loadDetailResources(entry)
}

function openFullResourcePage() {
  const courseId = detailSelectedCourseId.value || courseLookupKey(selectedCourse.value)
  router.push({
    name: 'CourseResources',
    query: courseId ? { course_id: courseId } : {}
  })
}

const currentCalendarWeek = computed(() =>
  parseCurrentWeekNumber(payloads.calendar?.data?.current_week)
)

const todayWeekday = computed(() => WEEKDAY_ORDER[(new Date().getDay() + 6) % 7])

const selectedHomeworkItems = computed(() => {
  const items = Array.isArray(payloads.homework?.data?.items) ? payloads.homework.data.items : []
  return items
    .filter(matchesSelectedCourse)
    .sort((a, b) => {
      const dueA = Date.parse(a.due_at || '') || Number.MAX_SAFE_INTEGER
      const dueB = Date.parse(b.due_at || '') || Number.MAX_SAFE_INTEGER
      if (dueA !== dueB) return dueA - dueB
      return `${a.title || ''}`.localeCompare(`${b.title || ''}`, 'zh-Hans-CN')
    })
})

const selectedOpenHomeworkCount = computed(() =>
  selectedHomeworkItems.value.filter(item => item.status === 'open').length
)

const timetableMatrix = computed(() => {
  const data = payloads.timetable?.data
  const entries = Array.isArray(data?.entries) ? data.entries : []
  const observedDays = []
  entries.forEach(entry => {
    const d = normalizeWeekday(entry.weekday)
    if (d && !observedDays.includes(d)) observedDays.push(d)
  })
  const sourceDays = Array.isArray(data?.days) ? data.days.map(d => normalizeWeekday(d)) : []
  const days = WEEKDAY_ORDER.filter(d => sourceDays.includes(d) || observedDays.includes(d))
  if (!days.length) days.push(...WEEKDAY_ORDER)

  const periodSet = new Set()
  ;(Array.isArray(data?.periods) ? data.periods : []).forEach(p => {
    const label = normalizePeriodLabel(p)
    if (label) periodSet.add(label)
  })
  entries.forEach(entry => {
    const label = normalizePeriodLabel(entry.period)
    if (label) periodSet.add(label)
  })
  const periods = [...periodSet].sort((a, b) => periodSortValue(a) - periodSortValue(b))

  const matrix = {}
  periods.forEach(p => {
    matrix[p] = {}
    days.forEach(d => { matrix[p][d] = [] })
  })
  entries.forEach(entry => {
    const day = normalizeWeekday(entry.weekday)
    const period = normalizePeriodLabel(entry.period)
    if (!days.includes(day) || !periods.includes(period)) return
    matrix[period][day].push(entry)
  })
  return { days, periods, matrix }
})

onMounted(async () => {
  if (isSessionReady.value) {
    if (!payloads.timetable?.data) {
      await loadTimetable()
    }
    if (!payloads.calendar?.data) {
      await loadCalendar()
    }
  }
})
</script>

<template>
  <div class="module-page">
    <div class="section-head">
      <div>
        <NH2>课表</NH2>
        <NP class="desc">
          基于 AA 课表页面直接解析，按"星期 x 节次"矩阵展示。
          <span v-if="currentCalendarWeek">当前教学周：第{{ currentCalendarWeek }}周（已高亮）</span>
        </NP>
      </div>
      <NTag v-if="payloads.timetable?.coverage" :type="payloads.timetable.coverage === 'verified' ? 'success' : 'warning'" round>
        {{ coverageLabel(payloads.timetable.coverage) }}
      </NTag>
    </div>

    <NAlert v-if="moduleErrors.timetable" type="error" :title="moduleErrors.timetable" />

    <div class="table-wrap">
      <NTable :bordered="true" :single-line="false" size="small">
        <thead>
          <tr>
            <th>节次</th>
            <th
              v-for="day in timetableMatrix.days"
              :key="day"
              :class="{ 'today-head': day === todayWeekday }"
            >
              <span>{{ day }}</span>
              <NTag v-if="day === todayWeekday" size="tiny" type="success" round>今天</NTag>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="period in timetableMatrix.periods" :key="period">
            <td class="period-col">{{ period }}</td>
            <td v-for="day in timetableMatrix.days" :key="`${period}-${day}`" class="cell-col">
              <div v-if="timetableMatrix.matrix[period][day]?.length" class="cell-courses">
                <article
                  v-for="entry in timetableMatrix.matrix[period][day]"
                  :key="`${entry.weekday}-${entry.period}-${entry.course_code}-${entry.section || 'base'}`"
                  :class="[
                    'course-chip',
                    {
                      current: isCurrentWeekCourse(entry.weeks, currentCalendarWeek),
                      selected: isSameCourseEntry(entry, selectedCourse)
                    }
                  ]"
                  role="button"
                  tabindex="0"
                  :aria-label="`查看 ${entry.course_name} 课程详情`"
                  @click="openCourseDetail(entry)"
                  @keydown.enter.prevent="openCourseDetail(entry)"
                  @keydown.space.prevent="openCourseDetail(entry)"
                >
                  <p class="course-title">{{ entry.course_code }} {{ entry.course_name }}</p>
                  <p>教师：{{ entry.teacher || '-' }}</p>
                  <p>周次：{{ entry.weeks || '-' }}</p>
                  <p>地点：{{ entry.location_text || '-' }}</p>
                </article>
              </div>
              <span v-else class="empty-cell">-</span>
            </td>
          </tr>
          <tr v-if="!timetableMatrix.periods.length">
            <td :colspan="1 + timetableMatrix.days.length" class="empty-row">当前没有可展示的课表记录。</td>
          </tr>
        </tbody>
      </NTable>
    </div>

    <NDrawer v-model:show="detailVisible" placement="right" width="min(100vw, 560px)">
      <NDrawerContent title="课程详情" closable :native-scrollbar="false">
        <div v-if="selectedCourse" class="course-detail">
          <div class="detail-hero">
            <div>
              <p class="detail-code">{{ selectedCourse.course_code || '未记录课程号' }}</p>
              <h3>{{ selectedCourse.course_name }}</h3>
            </div>
            <NTag
              :type="isCurrentWeekCourse(selectedCourse.weeks, currentCalendarWeek) ? 'success' : 'default'"
              round
            >
              {{ isCurrentWeekCourse(selectedCourse.weeks, currentCalendarWeek) ? '本周上课' : '非本周' }}
            </NTag>
          </div>

          <dl class="detail-grid">
            <div>
              <dt>教师</dt>
              <dd>{{ selectedCourse.teacher || '-' }}</dd>
            </div>
            <div>
              <dt>时间</dt>
              <dd>{{ normalizeWeekday(selectedCourse.weekday) }} {{ normalizePeriodLabel(selectedCourse.period) }} {{ selectedCourse.time_range || '' }}</dd>
            </div>
            <div>
              <dt>地点</dt>
              <dd>{{ selectedCourse.location_text || '-' }}</dd>
            </div>
            <div>
              <dt>周次</dt>
              <dd>{{ selectedCourse.weeks || '-' }}</dd>
            </div>
          </dl>

          <NSpace class="detail-actions">
            <NButton secondary type="primary" @click="openFullResourcePage">
              <template #icon>
                <NIcon><OpenOutline /></NIcon>
              </template>
              打开完整资源页
            </NButton>
            <NButton :loading="detailResourcesLoading" @click="loadDetailResources()">
              <template #icon>
                <NIcon><RefreshOutline /></NIcon>
              </template>
              刷新资源
            </NButton>
          </NSpace>

          <NTabs v-model:value="activeDetailTab" type="line" animated>
            <NTabPane name="homework" :tab="`作业 ${selectedHomeworkItems.length}`">
              <NSpin :show="homeworkLoading">
                <div class="tab-panel">
                  <div class="panel-summary">
                    <NTag round :type="selectedOpenHomeworkCount ? 'warning' : 'success'">
                      待完成 {{ selectedOpenHomeworkCount }} 条
                    </NTag>
                    <NTag round>共 {{ selectedHomeworkItems.length }} 条</NTag>
                  </div>
                  <div v-if="selectedHomeworkItems.length" class="detail-list">
                    <article
                      v-for="item in selectedHomeworkItems"
                      :key="`${item.homework_id || 'noid'}-${item.course_id}-${item.sub_type}-${item.title}`"
                      class="detail-item"
                    >
                      <div class="detail-item-head">
                        <strong>{{ item.title }}</strong>
                        <NTag size="small" round :type="homeworkUrgency(item).type">
                          {{ homeworkUrgency(item).label }}
                        </NTag>
                      </div>
                      <p class="detail-meta">开始：{{ item.opened_at || '-' }} | 截止：{{ item.due_at || '-' }}</p>
                      <p class="detail-excerpt">{{ item.content_excerpt || '暂无作业说明' }}</p>
                    </article>
                  </div>
                  <NEmpty v-else description="这门课当前没有可展示的作业" />
                </div>
              </NSpin>
            </NTabPane>

            <NTabPane name="resources" :tab="`资源 ${detailResources.length}`">
              <NSpin :show="detailResourcesLoading">
                <div class="tab-panel">
                  <NAlert
                    v-if="detailResourcesError"
                    type="error"
                    :title="detailResourcesError"
                    class="detail-alert"
                  >
                    <template #action>
                      <NButton size="small" @click="loadDetailResources()">重试</NButton>
                    </template>
                  </NAlert>
                  <div class="panel-summary">
                    <NTag round>{{ detailResources.length }} 个文件</NTag>
                    <NTag round>{{ detailFolders.length }} 个目录</NTag>
                    <NTag v-if="detailResourceCoverage" round :type="detailResourceCoverage === 'verified' ? 'success' : 'warning'">
                      {{ coverageLabel(detailResourceCoverage) }}
                    </NTag>
                  </div>
                  <div v-if="detailFolders.length" class="folder-hints">
                    <NTag v-for="folder in detailFolders" :key="folder.folder_id" round>
                      {{ folder.name }}
                    </NTag>
                  </div>
                  <div v-if="detailResources.length" class="detail-list">
                    <article
                      v-for="resource in detailResources"
                      :key="resource.rp_id || resource.resource_id"
                      class="detail-item"
                    >
                      <div class="detail-item-head">
                        <strong>{{ resource.name }}</strong>
                        <NTag v-if="resource.extension" size="small" round>
                          {{ resource.extension.toUpperCase() }}
                        </NTag>
                      </div>
                      <p class="detail-meta">
                        {{ formatResourceSize(resource.size) }} · {{ resource.uploaded_at || '未知上传时间' }}
                      </p>
                      <NSpace justify="space-between" align="center" class="resource-actions">
                        <span class="detail-meta">下载 {{ resource.download_count ?? '-' }} 次</span>
                        <NButton
                          size="small"
                          type="primary"
                          tertiary
                          :disabled="!resource.can_download || !resource.rp_id"
                          @click="handleResourceDownload(resource)"
                        >
                          <template #icon>
                            <NIcon><DownloadOutline /></NIcon>
                          </template>
                          下载
                        </NButton>
                      </NSpace>
                    </article>
                  </div>
                  <NEmpty v-else-if="!detailResourcesError" description="这门课当前没有可展示的课程资源" />
                </div>
              </NSpin>
            </NTabPane>
          </NTabs>
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
.table-wrap { overflow-x: auto; border-radius: 16px; }

.today-head {
  color: #9b421a;
}

.today-head span {
  margin-right: 6px;
}

.period-col { min-width: 72px; white-space: nowrap; font-weight: 600; }
.cell-col { min-width: 190px; vertical-align: top; }
.empty-cell { color: #8a94a6; }
.empty-row { text-align: center; color: #776b5d; padding: 32px !important; }

.cell-courses { display: grid; gap: 8px; }
.course-chip {
  border: 1px solid #d7dbe3;
  border-radius: 10px;
  padding: 8px 10px;
  background: #f8fafc;
  cursor: pointer;
  line-height: 1.35;
  outline: none;
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}
.course-chip p { margin: 3px 0; }
.course-chip .course-title { margin-top: 0; font-weight: 600; }
.course-chip:hover,
.course-chip:focus-visible,
.course-chip.selected {
  border-color: #b34f1f;
  box-shadow: 0 8px 20px rgba(88, 61, 20, 0.12);
  transform: translateY(-1px);
}
.course-chip.current {
  border-color: #b34f1f;
  background: #fef4ea;
  box-shadow: inset 3px 0 0 #b34f1f;
}
.course-chip.current:hover,
.course-chip.current:focus-visible,
.course-chip.current.selected {
  box-shadow: inset 3px 0 0 #b34f1f, 0 8px 20px rgba(88, 61, 20, 0.12);
}

.course-detail {
  display: grid;
  gap: 18px;
}

.detail-hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(78, 62, 43, 0.12);
}

.detail-code {
  margin: 0 0 4px;
  color: #776b5d;
  font-size: 13px;
}

.detail-hero h3 {
  margin: 0;
  font-size: 22px;
  line-height: 1.25;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 0;
}

.detail-grid div,
.detail-item {
  border: 1px solid rgba(140, 116, 81, 0.2);
  border-radius: 8px;
  background: rgba(255, 252, 247, 0.86);
}

.detail-grid div {
  padding: 10px 12px;
}

.detail-grid dt {
  margin: 0 0 4px;
  color: #776b5d;
  font-size: 12px;
}

.detail-grid dd {
  margin: 0;
  color: #2c261f;
  line-height: 1.5;
}

.detail-actions {
  width: 100%;
}

.tab-panel {
  min-height: 180px;
  display: grid;
  gap: 12px;
}

.panel-summary,
.folder-hints {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.detail-alert {
  margin-bottom: 2px;
}

.detail-list {
  display: grid;
  gap: 10px;
}

.detail-item {
  padding: 12px;
}

.detail-item-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.detail-item-head strong {
  min-width: 0;
  overflow-wrap: anywhere;
}

.detail-meta,
.detail-excerpt {
  margin: 8px 0 0;
  color: #776b5d;
  font-size: 12px;
}

.detail-excerpt {
  color: #4f4336;
  line-height: 1.55;
}

.resource-actions {
  margin-top: 10px;
  width: 100%;
}

@media (max-width: 768px) {
  .cell-col {
    min-width: 160px;
  }

  .course-chip {
    padding: 8px;
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }

  .detail-actions :deep(.n-button) {
    width: 100%;
  }

  .detail-item-head,
  .resource-actions {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
