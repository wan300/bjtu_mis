<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from './api'

const navigation = [
  { key: 'overview', label: '总览' },
  { key: 'timetable', label: '课表' },
  { key: 'exams', label: '考务' },
  { key: 'scores', label: '主修成绩' },
  { key: 'calendar', label: '学年日历' },
  { key: 'homework', label: '作业' },
  { key: 'emptyRooms', label: '空教室' }
]

const activeView = ref('overview')
const busy = reactive({
  sync: false,
  login: false,
  captcha: false
})
const alerts = ref([])
const sessionStatus = ref({ state: 'waiting_for_login', detail: '尚未检测会话。' })
const syncStatus = ref({
  status: 'idle',
  started_at: null,
  finished_at: null,
  module_summary: {},
  error_text: null
})
const payloads = reactive({
  timetable: null,
  exams: null,
  scores: null,
  calendar: null,
  homework: null,
  emptyRooms: null
})
const moduleErrors = reactive({
  timetable: '',
  exams: '',
  scores: '',
  calendar: '',
  homework: '',
  emptyRooms: ''
})

const loginForm = reactive({
  loginname: '',
  password: '',
  captcha: ''
})

const captchaState = reactive({
  imageDataUrl: '',
  fetchedAt: ''
})

const SESSION_NOT_READY_MESSAGE = '会话未就绪，请先在本页输入账号、密码和验证码完成登录。'

const filters = reactive({
  examsTerm: '',
  scoresTerm: '',
  scoresCtype: 'lr',
  calendarMonth: new Date().toISOString().slice(0, 7),
  homeworkStatus: 'all',
  emptyRooms: {
    term: '',
    week: '',
    building: '',
    room: ''
  }
})

function pushAlert(message, tone = 'info') {
  alerts.value = [{ message, tone }, ...alerts.value].slice(0, 3)
}

function clearModuleError(key) {
  moduleErrors[key] = ''
}

function itemCount(payload) {
  if (!payload?.data) return 0
  const data = payload.data
  if (Array.isArray(data.items)) return data.items.length
  if (Array.isArray(data.entries)) return data.entries.length
  if (Array.isArray(data.rooms)) return data.rooms.length
  return 0
}

const moduleCards = computed(() =>
  navigation
    .filter((item) => item.key !== 'overview')
    .map((item) => ({
      ...item,
      coverage:
        item.key === 'emptyRooms'
          ? payloads.emptyRooms?.coverage
          : payloads[item.key]?.coverage,
      count:
        item.key === 'emptyRooms'
          ? itemCount(payloads.emptyRooms)
          : itemCount(payloads[item.key]),
      syncedAt:
        item.key === 'emptyRooms'
          ? payloads.emptyRooms?.synced_at
          : payloads[item.key]?.synced_at
    }))
)

const isSessionReady = computed(() => sessionStatus.value?.state === 'ready')

const WEEKDAY_ORDER = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const WEEKDAY_ALIASES = {
  星期一: '周一',
  星期二: '周二',
  星期三: '周三',
  星期四: '周四',
  星期五: '周五',
  星期六: '周六',
  星期日: '周日',
  星期天: '周日',
  周天: '周日'
}

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

function periodSortValue(periodLabel) {
  const matched = `${periodLabel || ''}`.match(/(\d+)/)
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
  const normalized = compact
    .replace(/第/g, '')
    .replace(/周/g, '')
    .replace(/[（(].*?[）)]/g, '')
  const tokens = normalized
    .split(/[，,、；;]+/)
    .map((token) => token.trim())
    .filter(Boolean)

  if (!tokens.length) return null

  const weeks = new Set()
  for (const token of tokens) {
    const rangeMatch = token.match(/^(\d+)-(\d+)$/)
    if (rangeMatch) {
      let start = Number(rangeMatch[1])
      let end = Number(rangeMatch[2])
      if (start > end) [start, end] = [end, start]
      for (let index = start; index <= end; index += 1) {
        weeks.add(index)
      }
      continue
    }

    const singleMatch = token.match(/^(\d+)$/)
    if (singleMatch) {
      weeks.add(Number(singleMatch[1]))
    }
  }

  if (!weeks.size) return null

  if (oddOnly) {
    return new Set([...weeks].filter((week) => week % 2 === 1))
  }
  if (evenOnly) {
    return new Set([...weeks].filter((week) => week % 2 === 0))
  }
  return weeks
}

function isCurrentWeekCourse(weeksText, currentWeekNumber) {
  if (!currentWeekNumber) return false
  const weeks = parseWeeksSpec(weeksText)
  if (!weeks) return false
  return weeks.has(currentWeekNumber)
}

const currentCalendarWeek = computed(() =>
  parseCurrentWeekNumber(payloads.calendar?.data?.current_week)
)

const timetableMatrix = computed(() => {
  const data = payloads.timetable?.data
  const entries = Array.isArray(data?.entries) ? data.entries : []

  const observedDays = []
  entries.forEach((entry) => {
    const normalizedDay = normalizeWeekday(entry.weekday)
    if (normalizedDay && !observedDays.includes(normalizedDay)) {
      observedDays.push(normalizedDay)
    }
  })

  const sourceDays = Array.isArray(data?.days) ? data.days.map((day) => normalizeWeekday(day)) : []
  const days = WEEKDAY_ORDER.filter((day) => sourceDays.includes(day) || observedDays.includes(day))
  if (!days.length) days.push(...WEEKDAY_ORDER)

  const periodSet = new Set()
  ;(Array.isArray(data?.periods) ? data.periods : []).forEach((period) => {
    const label = normalizePeriodLabel(period)
    if (label) periodSet.add(label)
  })
  entries.forEach((entry) => {
    const label = normalizePeriodLabel(entry.period)
    if (label) periodSet.add(label)
  })
  const periods = [...periodSet].sort((a, b) => periodSortValue(a) - periodSortValue(b))

  const matrix = {}
  periods.forEach((period) => {
    matrix[period] = {}
    days.forEach((day) => {
      matrix[period][day] = []
    })
  })

  entries.forEach((entry) => {
    const day = normalizeWeekday(entry.weekday)
    const period = normalizePeriodLabel(entry.period)
    if (!days.includes(day) || !periods.includes(period)) return
    matrix[period][day].push(entry)
  })

  return {
    days,
    periods,
    matrix
  }
})

function markModuleNeedsLogin(key) {
  moduleErrors[key] = SESSION_NOT_READY_MESSAGE
}

async function ensureSessionReady(key) {
  if (isSessionReady.value) {
    return true
  }
  try {
    await loadOverview()
  } catch (error) {
    moduleErrors[key] = error.message
    return false
  }
  if (!isSessionReady.value) {
    markModuleNeedsLogin(key)
    if (!captchaState.imageDataUrl && !busy.captcha) {
      await loadCaptcha(true)
    }
    return false
  }
  return true
}

async function loadOverview() {
  sessionStatus.value = await api.getSessionStatus()
  syncStatus.value = await api.getSyncStatus()
}

async function loadCaptcha(notify = true) {
  busy.captcha = true
  try {
    const payload = await api.getLoginCaptcha()
    captchaState.imageDataUrl = payload.image_data_url
    captchaState.fetchedAt = payload.fetched_at
    loginForm.captcha = ''
    if (notify) {
      pushAlert('验证码已刷新。', 'info')
    }
  } catch (error) {
    captchaState.imageDataUrl = ''
    if (notify) {
      pushAlert(error.message, 'error')
    }
  } finally {
    busy.captcha = false
  }
}

async function submitInlineLogin() {
  if (!loginForm.loginname.trim() || !loginForm.password || !loginForm.captcha.trim()) {
    pushAlert('请完整输入账号、密码和验证码。', 'error')
    return
  }

  busy.login = true
  try {
    await api.loginInline({
      loginname: loginForm.loginname.trim(),
      password: loginForm.password,
      captcha: loginForm.captcha.trim()
    })
    pushAlert('登录成功，会话已更新。', 'success')
    loginForm.password = ''
    loginForm.captcha = ''
    await refreshAll()
  } catch (error) {
    pushAlert(error.message, 'error')
    loginForm.captcha = ''
    await loadCaptcha(false)
    await loadOverview()
  } finally {
    busy.login = false
  }
}

async function loadTimetable() {
  clearModuleError('timetable')
  if (!(await ensureSessionReady('timetable'))) {
    return
  }
  payloads.timetable = await api.getTimetable()
}

async function loadExams() {
  clearModuleError('exams')
  if (!(await ensureSessionReady('exams'))) {
    return
  }
  payloads.exams = await api.getExams(filters.examsTerm)
}

async function loadScores() {
  clearModuleError('scores')
  if (!(await ensureSessionReady('scores'))) {
    return
  }
  payloads.scores = await api.getScores(filters.scoresTerm, filters.scoresCtype)
}

async function loadCalendar() {
  clearModuleError('calendar')
  if (!(await ensureSessionReady('calendar'))) {
    return
  }
  payloads.calendar = await api.getCalendar(filters.calendarMonth)
}

async function loadHomework() {
  clearModuleError('homework')
  if (!(await ensureSessionReady('homework'))) {
    return
  }
  payloads.homework = await api.getHomework(filters.homeworkStatus)
}

async function loadEmptyRooms() {
  clearModuleError('emptyRooms')
  if (!(await ensureSessionReady('emptyRooms'))) {
    return
  }
  payloads.emptyRooms = await api.getEmptyRooms(filters.emptyRooms)
}

async function refreshAll() {
  await loadOverview()
  if (!isSessionReady.value) {
    if (!captchaState.imageDataUrl && !busy.captcha) {
      await loadCaptcha(true)
    }
    markModuleNeedsLogin('timetable')
    markModuleNeedsLogin('exams')
    markModuleNeedsLogin('scores')
    markModuleNeedsLogin('calendar')
    markModuleNeedsLogin('homework')
    markModuleNeedsLogin('emptyRooms')
    return
  }

  const tasks = [
    loadTimetable().catch((error) => (moduleErrors.timetable = error.message)),
    loadExams().catch((error) => (moduleErrors.exams = error.message)),
    loadScores().catch((error) => (moduleErrors.scores = error.message)),
    loadCalendar().catch((error) => (moduleErrors.calendar = error.message)),
    loadHomework().catch((error) => (moduleErrors.homework = error.message)),
    loadEmptyRooms().catch((error) => (moduleErrors.emptyRooms = error.message))
  ]
  await Promise.all(tasks)
}

async function runSync() {
  busy.sync = true
  try {
    await loadOverview()
    if (!isSessionReady.value) {
      pushAlert(SESSION_NOT_READY_MESSAGE, 'error')
      return
    }
    await api.runSync()
    pushAlert('同步已完成，页面数据已刷新。', 'success')
    await refreshAll()
  } catch (error) {
    pushAlert(error.message, 'error')
    await loadOverview()
  } finally {
    busy.sync = false
  }
}

async function openBrowser() {
  busy.login = true
  try {
    const result = await api.openBrowser()
    if (result.already_running) {
      pushAlert('登录浏览器已经在运行。若未看到窗口，请结束后端进程后重试。', 'info')
    } else if (!result.launched) {
      pushAlert(result.message || '登录浏览器启动失败，请查看后端日志。', 'error')
    } else if (result.recovered_stale_lock) {
      pushAlert('检测到旧的登录锁并已自动恢复，登录浏览器已重新打开。', 'success')
    } else {
      pushAlert('已打开登录浏览器（MIS 与教务平台跳转页），请先完成 MIS 登录，再通过跳转页自动登录教务平台。', 'info')
    }
    await loadOverview()
  } catch (error) {
    pushAlert(error.message, 'error')
  } finally {
    busy.login = false
  }
}

function toneClass(tone) {
  return {
    info: 'notice',
    success: 'notice success',
    error: 'notice error'
  }[tone] || 'notice'
}

onMounted(async () => {
  await refreshAll()
  if (!isSessionReady.value && !captchaState.imageDataUrl) {
    await loadCaptcha(false)
  }
})
</script>

<template>
  <div class="shell">
    <header class="hero">
      <div class="hero-copy">
        <p class="eyebrow">BJTU MIS v1</p>
        <h1>本页登录复用会话的采集与展示控制台</h1>
        <p class="subtitle">
          在当前页面输入账号、密码与验证码即可完成 MIS 登录，无需跳转登录页。
          课表、学年日历、作业与空教室优先验证；考务与主修成绩按 <code>provisional</code> 覆盖展示，避免误报。
        </p>
      </div>
      <div class="hero-actions">
        <button class="primary" :disabled="busy.sync" @click="runSync">
          {{ busy.sync ? '同步中...' : '立即同步' }}
        </button>
      </div>
    </header>

    <section class="status-strip">
      <article class="status-card">
        <span class="status-label">会话状态</span>
        <strong>{{ sessionStatus.state }}</strong>
        <p>{{ sessionStatus.detail }}</p>
      </article>
      <article class="status-card">
        <span class="status-label">最近同步</span>
        <strong>{{ syncStatus.status }}</strong>
        <p>{{ syncStatus.finished_at || syncStatus.started_at || '尚未运行' }}</p>
      </article>
      <article class="status-card">
        <span class="status-label">最近错误</span>
        <strong>{{ syncStatus.error_text ? '需要关注' : '无' }}</strong>
        <p>{{ syncStatus.error_text || '最近一次同步没有记录错误。' }}</p>
      </article>
    </section>

    <section v-if="!isSessionReady" class="login-card">
      <div class="section-head">
        <div>
          <h2>本页登录 MIS</h2>
          <p>输入账号、密码和验证码后直接登录，登录成功后将自动复用会话进行同步。</p>
        </div>
      </div>
      <div class="login-grid">
        <input v-model="loginForm.loginname" autocomplete="username" placeholder="学号 / 工号" />
        <input v-model="loginForm.password" autocomplete="current-password" placeholder="密码" type="password" />
        <input
          v-model="loginForm.captcha"
          autocomplete="off"
          maxlength="8"
          placeholder="验证码"
          @keyup.enter="submitInlineLogin"
        />
      </div>
      <div class="captcha-row">
        <img v-if="captchaState.imageDataUrl" :src="captchaState.imageDataUrl" alt="验证码" class="captcha-image" />
        <div v-else class="captcha-placeholder">验证码加载中...</div>
        <button class="secondary" :disabled="busy.captcha || busy.login" @click="loadCaptcha(false)">
          {{ busy.captcha ? '刷新中...' : '刷新验证码' }}
        </button>
      </div>
      <div class="inline-login-actions">
        <button class="primary" :disabled="busy.login || busy.captcha" @click="submitInlineLogin">
          {{ busy.login ? '登录中...' : '提交登录' }}
        </button>
        <button class="secondary" :disabled="busy.login || busy.captcha" @click="openBrowser">
          备用：打开浏览器登录
        </button>
      </div>
      <p v-if="captchaState.fetchedAt" class="meta">验证码更新时间：{{ captchaState.fetchedAt }}</p>
    </section>

    <section v-if="alerts.length" class="alerts">
      <div v-for="item in alerts" :key="item.message" :class="toneClass(item.tone)">
        {{ item.message }}
      </div>
    </section>

    <nav class="tabs">
      <button
        v-for="item in navigation"
        :key="item.key"
        :class="['tab', { active: activeView === item.key }]"
        @click="activeView = item.key"
      >
        {{ item.label }}
      </button>
    </nav>

    <main class="panel">
      <section v-if="activeView === 'overview'" class="stack">
        <div class="module-grid">
          <article v-for="module in moduleCards" :key="module.key" class="module-card">
            <div class="module-head">
              <h3>{{ module.label }}</h3>
              <span class="coverage" :class="module.coverage">{{ module.coverage || 'pending' }}</span>
            </div>
            <p class="metric">{{ module.count }}</p>
            <p class="meta">{{ module.syncedAt || '尚未同步' }}</p>
          </article>
        </div>
        <div class="summary-card">
          <h3>同步摘要</h3>
          <table class="summary-table">
            <thead>
              <tr>
                <th>模块</th>
                <th>状态</th>
                <th>条数</th>
                <th>覆盖级别</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(value, key) in syncStatus.module_summary" :key="key">
                <td>{{ key }}</td>
                <td>{{ value.status }}</td>
                <td>{{ value.items ?? '-' }}</td>
                <td>{{ value.coverage ?? '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-else-if="activeView === 'timetable'" class="stack">
        <div class="section-head">
          <div>
            <h2>课表</h2>
            <p>
              基于 AA 课表页面直接解析，按“星期 x 节次”矩阵展示。
              <span v-if="currentCalendarWeek">当前教学周：第{{ currentCalendarWeek }}周（已高亮）</span>
            </p>
          </div>
          <span class="coverage" :class="payloads.timetable?.coverage">{{ payloads.timetable?.coverage }}</span>
        </div>
        <p v-if="moduleErrors.timetable" class="error-text">{{ moduleErrors.timetable }}</p>
        <div class="room-table-wrap timetable-wrap">
          <table class="data-table compact timetable-matrix">
            <thead>
              <tr>
                <th>节次</th>
                <th v-for="day in timetableMatrix.days" :key="day">{{ day }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="period in timetableMatrix.periods" :key="period">
                <td class="period-col">{{ period }}</td>
                <td v-for="day in timetableMatrix.days" :key="`${period}-${day}`">
                  <div v-if="timetableMatrix.matrix[period][day]?.length" class="cell-courses">
                    <article
                      v-for="entry in timetableMatrix.matrix[period][day]"
                      :key="`${entry.weekday}-${entry.period}-${entry.course_code}-${entry.section || 'base'}`"
                      :class="['course-chip', { current: isCurrentWeekCourse(entry.weeks, currentCalendarWeek) }]"
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
                <td :colspan="1 + timetableMatrix.days.length" class="empty">当前没有可展示的课表记录。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-else-if="activeView === 'exams'" class="stack">
        <div class="section-head">
          <div>
            <h2>考务</h2>
            <p>当前样本覆盖有限，若页面无数据会明确以 provisional 空态返回。</p>
          </div>
          <span class="coverage" :class="payloads.exams?.coverage">{{ payloads.exams?.coverage }}</span>
        </div>
        <div class="filter-row">
          <input v-model="filters.examsTerm" placeholder="学期值，例如 2025-2026-2-2" />
          <button class="secondary" @click="loadExams">按学期刷新</button>
        </div>
        <p v-if="moduleErrors.exams" class="error-text">{{ moduleErrors.exams }}</p>
        <p v-if="payloads.exams?.coverage === 'provisional'" class="hint">
          当前模块仍是 provisional：已完成页面级解析，但非空业务样本仍建议继续补录。
        </p>
        <table class="data-table">
          <thead>
            <tr>
              <th>课程</th>
              <th>时间地点</th>
              <th>考试方式</th>
              <th>报名信息</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in payloads.exams?.data?.items || []" :key="`${item.term}-${item.course_name}`">
              <td>{{ item.course_name }}</td>
              <td>{{ item.schedule || '-' }}</td>
              <td>{{ item.exam_mode || '-' }}</td>
              <td>{{ item.registration || '-' }}</td>
              <td>{{ item.status || '-' }}</td>
            </tr>
            <tr v-if="!(payloads.exams?.data?.items || []).length">
              <td colspan="5" class="empty">当前没有可展示的考务记录。</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-else-if="activeView === 'scores'" class="stack">
        <div class="section-head">
          <div>
            <h2>主修成绩</h2>
            <p>首版仅覆盖主修成绩主页面，不包含成绩卡和替代课程。</p>
          </div>
          <span class="coverage" :class="payloads.scores?.coverage">{{ payloads.scores?.coverage }}</span>
        </div>
        <div class="filter-row">
          <input v-model="filters.scoresTerm" placeholder="学期值，例如 2025-2026-2-2" />
          <select v-model="filters.scoresCtype">
            <option value="lr">本学期成绩</option>
            <option value="ln">历年成绩</option>
            <option value="en">英语认定成绩</option>
            <option value="rm">留级库成绩</option>
          </select>
          <button class="secondary" @click="loadScores">按学期刷新</button>
        </div>
        <p v-if="moduleErrors.scores" class="error-text">{{ moduleErrors.scores }}</p>
        <p v-if="payloads.scores?.coverage === 'provisional'" class="hint">
          当前模块仍是 provisional：接口已打通，但真实非空样本不足，建议继续补录。
        </p>
        <table class="data-table">
          <thead>
            <tr>
              <th>学期</th>
              <th>课程</th>
              <th>学分</th>
              <th>成绩</th>
              <th>教师</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in payloads.scores?.data?.items || []" :key="`${item.term}-${item.course_name}`">
              <td>{{ item.term || '-' }}</td>
              <td>{{ item.course_name }}</td>
              <td>{{ item.credit || '-' }}</td>
              <td>{{ item.score || '-' }}</td>
              <td>{{ item.teacher || '-' }}</td>
            </tr>
            <tr v-if="!(payloads.scores?.data?.items || []).length">
              <td colspan="5" class="empty">当前没有可展示的主修成绩记录。</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-else-if="activeView === 'calendar'" class="stack">
        <div class="section-head">
          <div>
            <h2>学年日历</h2>
            <p>默认展示当前月份，可按月刷新 VE 日历接口。</p>
          </div>
          <span class="coverage" :class="payloads.calendar?.coverage">{{ payloads.calendar?.coverage }}</span>
        </div>
        <div class="filter-row">
          <input v-model="filters.calendarMonth" type="month" />
          <button class="secondary" @click="loadCalendar">刷新月份</button>
        </div>
        <p v-if="moduleErrors.calendar" class="error-text">{{ moduleErrors.calendar }}</p>
        <div class="calendar-grid">
          <article v-for="item in payloads.calendar?.data?.items || []" :key="item.date" class="calendar-card">
            <strong>{{ item.date }}</strong>
            <span>教学周 {{ item.week || '-' }}</span>
          </article>
        </div>
      </section>

      <section v-else-if="activeView === 'homework'" class="stack">
        <div class="section-head">
          <div>
            <h2>作业</h2>
            <p>先聚合课程列表，再按课程抓取待完成与已完成作业。</p>
          </div>
          <span class="coverage" :class="payloads.homework?.coverage">{{ payloads.homework?.coverage }}</span>
        </div>
        <div class="filter-row">
          <select v-model="filters.homeworkStatus">
            <option value="all">全部</option>
            <option value="open">待完成</option>
            <option value="done">已完成</option>
          </select>
          <button class="secondary" @click="loadHomework">应用筛选</button>
        </div>
        <p v-if="moduleErrors.homework" class="error-text">{{ moduleErrors.homework }}</p>
        <div class="homework-list">
          <article v-for="item in payloads.homework?.data?.items || []" :key="`${item.homework_id || 'noid'}-${item.course_id}-${item.sub_type}-${item.title}`" class="homework-card">
            <div class="module-head">
              <h3>{{ item.title }}</h3>
              <span class="badge">{{ item.status }}</span>
            </div>
            <p>{{ item.course }}</p>
            <p class="meta">开始：{{ item.opened_at || '-' }} | 截止：{{ item.due_at || '-' }}</p>
            <p>{{ item.content_excerpt }}</p>
          </article>
        </div>
      </section>

      <section v-else-if="activeView === 'emptyRooms'" class="stack">
        <div class="section-head">
          <div>
            <h2>空教室</h2>
            <p>支持按学期、周次、教学楼和教室号查询整周节次矩阵。</p>
          </div>
          <span class="coverage" :class="payloads.emptyRooms?.coverage">{{ payloads.emptyRooms?.coverage }}</span>
        </div>
        <div class="filter-grid">
          <input v-model="filters.emptyRooms.term" placeholder="学期，可留空" />
          <input v-model="filters.emptyRooms.week" placeholder="周次，例如 8" />
          <input v-model="filters.emptyRooms.building" placeholder="教学楼代码，例如 2" />
          <input v-model="filters.emptyRooms.room" placeholder="教室号，例如 SX101" />
          <button class="secondary wide" @click="loadEmptyRooms">刷新空教室</button>
        </div>
        <p v-if="moduleErrors.emptyRooms" class="error-text">{{ moduleErrors.emptyRooms }}</p>
        <div class="room-table-wrap">
          <table class="data-table compact">
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
                <td :colspan="1 + (payloads.emptyRooms?.data?.slots || []).length" class="empty">
                  当前查询没有返回空教室矩阵数据。
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.timetable-wrap {
  overflow-x: auto;
}

.timetable-matrix .period-col {
  min-width: 72px;
  white-space: nowrap;
}

.timetable-matrix td {
  min-width: 190px;
  vertical-align: top;
}

.cell-courses {
  display: grid;
  gap: 0.5rem;
}

.course-chip {
  border: 1px solid #d7dbe3;
  border-radius: 10px;
  padding: 0.55rem 0.6rem;
  background: #f8fafc;
  line-height: 1.35;
}

.course-chip p {
  margin: 0.15rem 0;
}

.course-chip .course-title {
  margin-top: 0;
  font-weight: 600;
}

.course-chip.current {
  border-color: #3b82f6;
  background: #eaf2ff;
  box-shadow: inset 3px 0 0 #3b82f6;
}

.empty-cell {
  color: #8a94a6;
}
</style>
