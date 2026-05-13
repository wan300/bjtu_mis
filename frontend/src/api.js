export const AUTH_REQUIRED_EVENT = 'bjtu:auth-required'

export class ApiError extends Error {
  constructor(message, { status, code, payload, path } = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.payload = payload
    this.path = path
  }
}

export function notifyAuthRequired(detail = {}) {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent(AUTH_REQUIRED_EVENT, { detail }))
}

function getErrorDetail(payload, fallback) {
  const detail = payload?.detail
  const message =
    detail?.message ||
    detail?.code ||
    payload?.message ||
    fallback
  const code = detail?.code || payload?.code || null
  return { message, code }
}

function isAuthFailure(status, code) {
  return status === 401 || code === 'SESSION_EXPIRED'
}

async function request(path, options = {}) {
  const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData
  const response = await fetch(path, {
    headers: {
      ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
      ...(options.headers || {})
    },
    ...options
  })

  if (!response.ok) {
    let detail = `Request failed: ${response.status}`
    let code = null
    let payload = null
    try {
      payload = await response.json()
      const errorDetail = getErrorDetail(payload, detail)
      detail = errorDetail.message
      code = errorDetail.code
    } catch {
      detail = `${detail}`
    }
    if (isAuthFailure(response.status, code)) {
      notifyAuthRequired({ status: response.status, code, message: detail, path })
    }
    throw new ApiError(detail, {
      status: response.status,
      code,
      payload,
      path
    })
  }

  if (response.status === 204) {
    return null
  }
  return response.json()
}

export const api = {
  getSessionStatus() {
    return request('/api/session/status')
  },
  getLoginCaptcha() {
    return request('/api/session/captcha')
  },
  loginInline(payload) {
    return request('/api/session/login-inline', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },
  loginAuto(payload = {}) {
    return request('/api/session/login-auto', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },
  openBrowser() {
    return request('/api/session/open-browser', { method: 'POST' })
  },
  getSyncStatus() {
    return request('/api/sync/status')
  },
  runSync() {
    return request('/api/sync/run', { method: 'POST' })
  },
  startSync() {
    return request('/api/sync/start', { method: 'POST' })
  },
  getSnapshots() {
    return request('/api/modules/snapshots')
  },
  getProfile() {
    return request('/api/modules/profile')
  },
  getAcademicProgress() {
    return request('/api/modules/academic-progress')
  },
  getHistoryScores(term) {
    const query = term ? `?term=${encodeURIComponent(term)}` : ''
    return request(`/api/modules/history-scores${query}`)
  },
  getTimetable() {
    return request('/api/modules/timetable')
  },
  getCourseSelection() {
    return request('/api/modules/course-selection')
  },
  selectCourse(payload) {
    return request('/api/modules/course-selection/select', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },
  dropCourse(payload) {
    return request('/api/modules/course-selection/drop', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },
  replaceCourse(payload) {
    return request('/api/modules/course-selection/replace', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },
  submitCourseSelectionCaptcha(payload) {
    return request('/api/modules/course-selection/captcha', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },
  getExams(term) {
    const query = term ? `?term=${encodeURIComponent(term)}` : ''
    return request(`/api/modules/exams${query}`)
  },
  getScores(term, ctype) {
    const search = new URLSearchParams()
    if (term) search.set('term', term)
    if (ctype) search.set('ctype', ctype)
    const query = search.toString() ? `?${search.toString()}` : ''
    return request(`/api/modules/scores${query}`)
  },
  getScoreDetail(path) {
    const query = `?path=${encodeURIComponent(path)}`
    return request(`/api/modules/score-detail${query}`)
  },
  getCalendar(month) {
    const query = month ? `?month=${encodeURIComponent(month)}` : ''
    return request(`/api/modules/calendar${query}`)
  },
  getHomework(status = 'all') {
    return request(`/api/modules/homework?status=${encodeURIComponent(status)}`)
  },
  submitHomework(homeworkId, { courseId, content = '', files = [] }) {
    const form = new FormData()
    form.set('course_id', String(courseId))
    form.set('content', content || '')
    files.forEach((file) => {
      if (file) {
        form.append('files', file)
      }
    })
    return request(`/api/modules/homework/${encodeURIComponent(homeworkId)}/submit`, {
      method: 'POST',
      body: form
    })
  },
  getEmptyRooms(query) {
    const search = new URLSearchParams()
    Object.entries(query || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && `${value}`.trim() !== '') {
        search.set(key, value)
      }
    })
    const suffix = search.toString() ? `?${search.toString()}` : ''
    return request(`/api/modules/empty-rooms${suffix}`)
  },
  getCourseResources(query) {
    const search = new URLSearchParams()
    Object.entries(query || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && `${value}`.trim() !== '') {
        search.set(key, value)
      }
    })
    const suffix = search.toString() ? `?${search.toString()}` : ''
    return request(`/api/modules/course-resources${suffix}`)
  },
  getCourseResourceDownloadUrl(rpId, filename) {
    const search = new URLSearchParams()
    if (filename) search.set('filename', filename)
    const suffix = search.toString() ? `?${search.toString()}` : ''
    return `/api/modules/course-resources/download/${encodeURIComponent(rpId)}${suffix}`
  },
  getMailFolders() {
    return request('/api/modules/mail/folders')
  },
  getMailMessages(query) {
    const search = new URLSearchParams()
    Object.entries(query || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && `${value}`.trim() !== '') {
        search.set(key, value)
      }
    })
    const suffix = search.toString() ? `?${search.toString()}` : ''
    return request(`/api/modules/mail/messages${suffix}`)
  },
  getMailMessageDetail(query) {
    const search = new URLSearchParams()
    Object.entries(query || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && `${value}`.trim() !== '') {
        search.set(key, value)
      }
    })
    const suffix = search.toString() ? `?${search.toString()}` : ''
    return request(`/api/modules/mail/message${suffix}`)
  },
  getMailAttachmentDownloadUrl(query) {
    const search = new URLSearchParams()
    Object.entries(query || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && `${value}`.trim() !== '') {
        search.set(key, value)
      }
    })
    const suffix = search.toString() ? `?${search.toString()}` : ''
    return `/api/modules/mail/attachment${suffix}`
  },
  deleteMailMessages(payload) {
    return request('/api/modules/mail/messages/delete', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },
  sendMailMessage(payload) {
    return request('/api/modules/mail/messages/send', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },
  saveMailDraft(payload) {
    return request('/api/modules/mail/drafts/save', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },
  autocompleteMailContacts(query) {
    const search = new URLSearchParams()
    Object.entries(query || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && `${value}`.trim() !== '') {
        search.set(key, value)
      }
    })
    const suffix = search.toString() ? `?${search.toString()}` : ''
    return request(`/api/modules/mail/contacts/autocomplete${suffix}`)
  },
  uploadMailAttachment({ composeId, file }) {
    const form = new FormData()
    if (composeId) form.set('compose_id', composeId)
    form.set('file', file)
    return request('/api/modules/mail/attachments/upload', {
      method: 'POST',
      body: form
    })
  }
}
