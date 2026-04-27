async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    },
    ...options
  })

  if (!response.ok) {
    let detail = `Request failed: ${response.status}`
    try {
      const payload = await response.json()
      detail =
        payload?.detail?.message ||
        payload?.detail?.code ||
        payload?.message ||
        detail
    } catch {
      detail = `${detail}`
    }
    throw new Error(detail)
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
  openBrowser() {
    return request('/api/session/open-browser', { method: 'POST' })
  },
  getSyncStatus() {
    return request('/api/sync/status')
  },
  runSync() {
    return request('/api/sync/run', { method: 'POST' })
  },
  getTimetable() {
    return request('/api/modules/timetable')
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
  getCalendar(month) {
    const query = month ? `?month=${encodeURIComponent(month)}` : ''
    return request(`/api/modules/calendar${query}`)
  },
  getHomework(status = 'all') {
    return request(`/api/modules/homework?status=${encodeURIComponent(status)}`)
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
  }
}
