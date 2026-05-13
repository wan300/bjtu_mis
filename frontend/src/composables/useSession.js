import { computed, reactive, ref } from 'vue'
import { api, notifyAuthRequired } from '../api'
import { useAlerts } from './useAlerts'

const sessionStatus = ref({ state: 'waiting_for_login', detail: '尚未检测会话。' })
const busy = reactive({ login: false, captcha: false })
const loginForm = reactive({ loginname: '', password: '', captcha: '' })
const captchaState = reactive({ imageDataUrl: '', fetchedAt: '' })
const autoReloginFailure = reactive({ visible: false, message: '', attempts: 0 })

const isSessionReady = computed(() => sessionStatus.value?.state === 'ready')

export const SESSION_NOT_READY_MESSAGE = '会话未就绪，请先登录。'

function isLoginRequiredStatus(status) {
  return status?.state === 'waiting_for_login' || status?.state === 'expired'
}

async function loadSessionStatus({ redirectOnInvalid = true } = {}) {
  try {
    const status = await api.getSessionStatus()
    sessionStatus.value = status
    if (redirectOnInvalid && isLoginRequiredStatus(status)) {
      notifyAuthRequired({
        code: status.state,
        message: status.detail || SESSION_NOT_READY_MESSAGE,
        path: '/api/session/status'
      })
    }
  } catch (e) {
    sessionStatus.value = { state: 'error', detail: e.message }
  }
}

async function loadCaptcha(notify = true) {
  busy.captcha = true
  try {
    const payload = await api.getLoginCaptcha()
    captchaState.imageDataUrl = payload.image_data_url
    captchaState.fetchedAt = payload.fetched_at
    loginForm.captcha = ''
    if (notify) {
      useAlerts().pushAlert('验证码已刷新。', 'info')
    }
  } catch (error) {
    captchaState.imageDataUrl = ''
    if (notify) {
      useAlerts().pushAlert(error.message, 'error')
    }
  } finally {
    busy.captcha = false
  }
}

async function submitInlineLogin() {
  if (!loginForm.loginname.trim() || !loginForm.password || !loginForm.captcha.trim()) {
    useAlerts().pushAlert('请完整输入账号、密码和验证码。', 'warning')
    return { success: false }
  }

  busy.login = true
  try {
    await api.loginInline({
      loginname: loginForm.loginname.trim(),
      password: loginForm.password,
      captcha: loginForm.captcha.trim()
    })
    useAlerts().pushAlert('登录成功，会话已更新。', 'success')
    loginForm.password = ''
    loginForm.captcha = ''
    await loadSessionStatus()
    return { success: true }
  } catch (error) {
    useAlerts().pushAlert(error.message, 'error')
    loginForm.captcha = ''
    await loadCaptcha(false)
    await loadSessionStatus()
    return { success: false }
  } finally {
    busy.login = false
  }
}

function applyManualCaptcha(captcha) {
  captchaState.imageDataUrl = captcha?.image_data_url || ''
  captchaState.fetchedAt = captcha?.fetched_at || ''
  loginForm.captcha = ''
}

async function submitAutoLogin({ loginname, password } = {}) {
  const explicitLoginName = loginname ?? loginForm.loginname
  const explicitPassword = password ?? loginForm.password
  if (!explicitLoginName?.trim() || !explicitPassword) {
    useAlerts().pushAlert('请先输入学号和密码。', 'warning')
    return { success: false, manualRequired: false }
  }

  busy.login = true
  try {
    const result = await api.loginAuto({
      loginname: explicitLoginName.trim(),
      password: explicitPassword
    })
    if (result.status === 'ready') {
      sessionStatus.value = result.session || { state: 'ready', detail: result.message }
      autoReloginFailure.visible = false
      loginForm.password = ''
      loginForm.captcha = ''
      useAlerts().pushAlert(result.message || '自动登录成功。', 'success')
      return { success: true, manualRequired: false }
    }
    if (result.status === 'manual_required') {
      applyManualCaptcha(result.captcha)
      useAlerts().pushAlert(result.message || '自动识别失败，请手动输入验证码。', 'warning')
      return { success: false, manualRequired: true }
    }
    useAlerts().pushAlert(result.message || '自动登录失败。', 'error')
    return { success: false, manualRequired: false }
  } catch (error) {
    useAlerts().pushAlert(error.message, 'error')
    return { success: false, manualRequired: false }
  } finally {
    busy.login = false
  }
}

async function attemptSavedAutoLogin({ showFailureDialog = false } = {}) {
  if (busy.login) return { success: false, status: 'busy' }
  busy.login = true
  try {
    const result = await api.loginAuto({})
    if (result.status === 'ready') {
      sessionStatus.value = result.session || { state: 'ready', detail: result.message }
      autoReloginFailure.visible = false
      return { success: true, status: result.status, result }
    }
    if (result.status === 'manual_required') {
      applyManualCaptcha(result.captcha)
      return { success: false, status: result.status, result }
    }
    if (showFailureDialog && (result.attempts || 0) > 0) {
      autoReloginFailure.visible = true
      autoReloginFailure.message = result.message || '自动重新登录失败。'
      autoReloginFailure.attempts = result.attempts || 0
    }
    return { success: false, status: result.status, result }
  } catch (error) {
    if (showFailureDialog) {
      autoReloginFailure.visible = true
      autoReloginFailure.message = error.message
      autoReloginFailure.attempts = 0
    }
    return { success: false, status: 'error', error }
  } finally {
    busy.login = false
  }
}

async function continueSavedAutoLogin() {
  return attemptSavedAutoLogin({ showFailureDialog: true })
}

function dismissAutoReloginFailure() {
  autoReloginFailure.visible = false
}

async function openBrowser() {
  busy.login = true
  try {
    const result = await api.openBrowser()
    if (result.already_running) {
      useAlerts().pushAlert('登录浏览器已经在运行。若未看到窗口，请结束后端进程后重试。', 'info')
    } else if (!result.launched) {
      useAlerts().pushAlert(result.message || '登录浏览器启动失败，请查看后端日志。', 'error')
    } else if (result.recovered_stale_lock) {
      useAlerts().pushAlert('检测到旧的登录锁并已自动恢复，登录浏览器已重新打开。', 'success')
    } else {
      useAlerts().pushAlert('已打开登录浏览器（MIS 与教务平台跳转页），请先完成 MIS 登录，再通过跳转页自动登录教务平台。', 'info')
    }
    await loadSessionStatus()
  } catch (error) {
    useAlerts().pushAlert(error.message, 'error')
  } finally {
    busy.login = false
  }
}

export function useSession() {
  return {
    sessionStatus,
    isSessionReady,
    busy,
    loginForm,
    captchaState,
    autoReloginFailure,
    loadSessionStatus,
    loadCaptcha,
    submitInlineLogin,
    submitAutoLogin,
    attemptSavedAutoLogin,
    continueSavedAutoLogin,
    dismissAutoReloginFailure,
    openBrowser
  }
}
