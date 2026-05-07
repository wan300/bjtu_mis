import { reactive } from 'vue'
import { api } from '../api'
import { useSession, SESSION_NOT_READY_MESSAGE } from './useSession'

const payloads = reactive({
  profile: null,
  academicProgress: null,
  historyScores: null,
  timetable: null,
  exams: null,
  scores: null,
  calendar: null,
  homework: null,
  emptyRooms: null,
  courseResources: null
})

const moduleErrors = reactive({
  profile: '',
  academicProgress: '',
  historyScores: '',
  timetable: '',
  exams: '',
  scores: '',
  calendar: '',
  homework: '',
  emptyRooms: '',
  courseResources: ''
})

function clearModuleError(key) {
  moduleErrors[key] = ''
}

function markModuleNeedsLogin(key) {
  moduleErrors[key] = SESSION_NOT_READY_MESSAGE
}

function itemCount(payload) {
  if (!payload?.data) return 0
  const data = payload.data
  if (Array.isArray(data.items)) return data.items.length
  if (Array.isArray(data.entries)) return data.entries.length
  if (Array.isArray(data.rooms)) return data.rooms.length
  if (Array.isArray(data.resources)) return data.resources.length
  if (Array.isArray(data.courses)) return data.courses.length
  if (Array.isArray(data.buckets)) return data.buckets.length
  if (Array.isArray(data.fields)) return data.fields.length
  return 0
}

async function ensureSessionReady(key) {
  const { isSessionReady, loadSessionStatus, captchaState, busy, loadCaptcha } = useSession()
  if (isSessionReady.value) return true

  await loadSessionStatus()
  if (!isSessionReady.value) {
    markModuleNeedsLogin(key)
    if (!captchaState.imageDataUrl && !busy.captcha) {
      await loadCaptcha(false)
    }
    return false
  }
  return true
}

async function loadProfile() {
  clearModuleError('profile')
  if (!(await ensureSessionReady('profile'))) return
  payloads.profile = await api.getProfile()
}

async function loadAcademicProgress() {
  clearModuleError('academicProgress')
  if (!(await ensureSessionReady('academicProgress'))) return
  payloads.academicProgress = await api.getAcademicProgress()
}

async function loadHistoryScores(term) {
  clearModuleError('historyScores')
  if (!(await ensureSessionReady('historyScores'))) return
  payloads.historyScores = await api.getHistoryScores(term)
}

async function loadTimetable() {
  clearModuleError('timetable')
  if (!(await ensureSessionReady('timetable'))) return
  payloads.timetable = await api.getTimetable()
}

async function loadExams(term) {
  clearModuleError('exams')
  if (!(await ensureSessionReady('exams'))) return
  payloads.exams = await api.getExams(term)
}

async function loadScores(term, ctype) {
  clearModuleError('scores')
  if (!(await ensureSessionReady('scores'))) return
  payloads.scores = await api.getScores(term, ctype)
}

async function loadCalendar(month) {
  clearModuleError('calendar')
  if (!(await ensureSessionReady('calendar'))) return
  payloads.calendar = await api.getCalendar(month)
}

async function loadHomework(status) {
  clearModuleError('homework')
  if (!(await ensureSessionReady('homework'))) return
  payloads.homework = await api.getHomework(status)
}

async function loadEmptyRooms(query) {
  clearModuleError('emptyRooms')
  if (!(await ensureSessionReady('emptyRooms'))) return
  payloads.emptyRooms = await api.getEmptyRooms(query)
}

async function loadCourseResources(query) {
  clearModuleError('courseResources')
  if (!(await ensureSessionReady('courseResources'))) return
  payloads.courseResources = await api.getCourseResources(query)
}

export function useModuleData() {
  return {
    payloads,
    moduleErrors,
    clearModuleError,
    markModuleNeedsLogin,
    itemCount,
    ensureSessionReady,
    loadProfile,
    loadAcademicProgress,
    loadHistoryScores,
    loadTimetable,
    loadExams,
    loadScores,
    loadCalendar,
    loadHomework,
    loadEmptyRooms,
    loadCourseResources
  }
}
