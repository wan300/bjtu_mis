import { reactive } from 'vue'
import { api } from '../api'
import { useSession, SESSION_NOT_READY_MESSAGE } from './useSession'
import { useAlerts } from './useAlerts'
import { useModuleData } from './useModuleData'

const syncStatus = reactive({
  status: 'idle',
  started_at: null,
  finished_at: null,
  module_summary: {},
  error_text: null
})

const busy = reactive({ sync: false })

async function loadSyncStatus() {
  try {
    const data = await api.getSyncStatus()
    Object.assign(syncStatus, data)
  } catch (e) {
    syncStatus.status = 'error'
    syncStatus.error_text = e.message
  }
}

async function runSync() {
  const { isSessionReady, loadSessionStatus } = useSession()
  busy.sync = true
  try {
    await loadSessionStatus()
    if (!isSessionReady.value) {
      useAlerts().pushAlert(SESSION_NOT_READY_MESSAGE, 'error')
      return
    }
    await api.runSync()
    useAlerts().pushAlert('同步已完成，页面数据已刷新。', 'success')
    await refreshAll()
  } catch (error) {
    useAlerts().pushAlert(error.message, 'error')
    await loadSyncStatus()
  } finally {
    busy.sync = false
  }
}

async function startSync({ notify = false } = {}) {
  const { isSessionReady, loadSessionStatus } = useSession()
  busy.sync = true
  try {
    await loadSessionStatus()
    if (!isSessionReady.value) return { started: false }
    const result = await api.startSync()
    if (notify) {
      useAlerts().pushAlert('后台同步已启动。', 'info')
    }
    await loadSyncStatus()
    return result
  } catch (error) {
    if (error.status !== 409) {
      useAlerts().pushAlert(error.message, 'error')
    }
    await loadSyncStatus()
    return { started: false, error }
  } finally {
    busy.sync = false
  }
}

async function refreshAll() {
  const { isSessionReady, loadSessionStatus, captchaState, busy: sessionBusy, loadCaptcha } = useSession()
  const { loadProfile, loadAcademicProgress, loadHistoryScores, loadTimetable, loadCourseSelection, loadExams, loadScores, loadCalendar, loadHomework, loadEmptyRooms, loadCourseResources,
          markModuleNeedsLogin, moduleErrors, loadSnapshots } = useModuleData()

  await loadSessionStatus()
  await loadSyncStatus()
  await loadSnapshots().catch(() => {})

  if (!isSessionReady.value) {
    if (!captchaState.imageDataUrl && !sessionBusy.captcha) {
      await loadCaptcha(false)
    }
    markModuleNeedsLogin('profile')
    markModuleNeedsLogin('academicProgress')
    markModuleNeedsLogin('historyScores')
    markModuleNeedsLogin('timetable')
    markModuleNeedsLogin('courseSelection')
    markModuleNeedsLogin('exams')
    markModuleNeedsLogin('scores')
    markModuleNeedsLogin('calendar')
    markModuleNeedsLogin('homework')
    markModuleNeedsLogin('emptyRooms')
    markModuleNeedsLogin('courseResources')
    return
  }

  await Promise.all([
    loadProfile().catch((error) => { moduleErrors.profile = error.message }),
    loadAcademicProgress().catch((error) => { moduleErrors.academicProgress = error.message }),
    loadHistoryScores().catch((error) => { moduleErrors.historyScores = error.message }),
    loadTimetable().catch((error) => { moduleErrors.timetable = error.message }),
    loadCourseSelection().catch((error) => { moduleErrors.courseSelection = error.message }),
    loadExams().catch((error) => { moduleErrors.exams = error.message }),
    loadScores().catch((error) => { moduleErrors.scores = error.message }),
    loadCalendar().catch((error) => { moduleErrors.calendar = error.message }),
    loadHomework().catch((error) => { moduleErrors.homework = error.message }),
    loadEmptyRooms().catch((error) => { moduleErrors.emptyRooms = error.message }),
    loadCourseResources().catch((error) => { moduleErrors.courseResources = error.message })
  ])
  await loadSyncStatus()
}

export function useSync() {
  return {
    syncStatus,
    busy,
    loadSyncStatus,
    runSync,
    startSync,
    refreshAll
  }
}
