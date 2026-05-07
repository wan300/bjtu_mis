import { createRouter, createWebHashHistory } from 'vue-router'
import { AUTH_REQUIRED_EVENT } from '../api'
import { useSession } from '../composables/useSession'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
    meta: { guest: true }
  },
  {
    path: '/',
    component: () => import('../components/AppLayout.vue'),
    children: [
      {
        path: '',
        redirect: { name: 'Overview' }
      },
      {
        path: 'overview',
        name: 'Overview',
        component: () => import('../views/OverviewView.vue'),
        meta: { requiresSession: true }
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('../views/ProfileView.vue'),
        meta: { requiresSession: true }
      },
      {
        path: 'academic-progress',
        name: 'AcademicProgress',
        component: () => import('../views/AcademicProgressView.vue'),
        meta: { requiresSession: true }
      },
      {
        path: 'history-scores',
        name: 'HistoryScores',
        component: () => import('../views/HistoryScoresView.vue'),
        meta: { requiresSession: true }
      },
      {
        path: 'timetable',
        name: 'Timetable',
        component: () => import('../views/TimetableView.vue'),
        meta: { requiresSession: true }
      },
      {
        path: 'exams',
        name: 'Exams',
        component: () => import('../views/ExamsView.vue'),
        meta: { requiresSession: true }
      },
      {
        path: 'scores',
        name: 'Scores',
        component: () => import('../views/ScoresView.vue'),
        meta: { requiresSession: true }
      },
      {
        path: 'calendar',
        name: 'Calendar',
        component: () => import('../views/CalendarView.vue'),
        meta: { requiresSession: true }
      },
      {
        path: 'homework',
        name: 'Homework',
        component: () => import('../views/HomeworkView.vue'),
        meta: { requiresSession: true }
      },
      {
        path: 'course-resources',
        name: 'CourseResources',
        component: () => import('../views/CourseResourcesView.vue'),
        meta: { requiresSession: true }
      },
      {
        path: 'empty-rooms',
        name: 'EmptyRooms',
        component: () => import('../views/EmptyRoomsView.vue'),
        meta: { requiresSession: true }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

function getSafeRedirect(value) {
  const redirect = Array.isArray(value) ? value[0] : value
  if (!redirect || typeof redirect !== 'string') return '/overview'
  if (!redirect.startsWith('/') || redirect.startsWith('/login')) return '/overview'
  return redirect
}

function loginLocation(redirect) {
  return {
    path: '/login',
    query: {
      redirect: getSafeRedirect(redirect)
    }
  }
}

let authRedirectPending = false

function handleAuthRequired() {
  if (authRedirectPending) return

  const currentRoute = router.currentRoute.value
  if (currentRoute.path === '/login') return

  authRedirectPending = true
  router
    .replace(loginLocation(currentRoute.fullPath))
    .finally(() => {
      authRedirectPending = false
    })
}

if (typeof window !== 'undefined') {
  window.addEventListener(AUTH_REQUIRED_EVENT, handleAuthRequired)
}

router.beforeEach(async (to, from) => {
  const { isSessionReady, loadSessionStatus } = useSession()

  if (to.path === '/login' && from.path !== '/login') {
    await loadSessionStatus({ redirectOnInvalid: false })
    if (isSessionReady.value) {
      return getSafeRedirect(to.query.redirect)
    }
  }

  if (to.meta.requiresSession || to.matched.some(r => r.meta.requiresSession)) {
    await loadSessionStatus({ redirectOnInvalid: false })
    if (!isSessionReady.value) {
      return loginLocation(to.fullPath)
    }
  }

  return true
})

export default router
