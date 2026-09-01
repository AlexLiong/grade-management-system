import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import type { Role } from '@/types/domain'
import { homeForUser } from '@/utils/permission'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    public?: boolean
    roles?: Role[]
    permission?: string | string[]
    anyPermission?: string[]
  }
}

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: () => import('@/layouts/AppShell.vue'),
    children: [
      { path: '', name: 'home', component: () => import('@/views/RoleHomeView.vue'), meta: { title: '工作台' } },
      {
        path: 'teacher/courses',
        name: 'teacher-courses',
        component: () => import('@/views/teacher/CourseListView.vue'),
        meta: { title: '我的课程', roles: ['TEACHER'], permission: 'COURSE_READ' },
      },
      {
        path: 'teacher/courses/:courseId/grades',
        name: 'teacher-gradebook',
        component: () => import('@/views/teacher/GradebookView.vue'),
        meta: { title: '成绩表', roles: ['TEACHER'], permission: ['COURSE_READ', 'GRADE_READ'] },
      },
      {
        path: 'teacher/courses/:courseId/analysis',
        name: 'teacher-analysis',
        component: () => import('@/views/teacher/AnalysisView.vue'),
        meta: { title: '统计与预警', roles: ['TEACHER'], permission: ['COURSE_READ', 'GRADE_READ', 'GRADE_ANALYTICS_READ'] },
      },
      {
        path: 'teacher/history/:offeringId?',
        name: 'teacher-history',
        component: () => import('@/views/teacher/HistoryView.vue'),
        meta: { title: '历年成绩', roles: ['TEACHER'], permission: 'GRADE_HISTORY_READ' },
      },
      { path: 'teacher/courses/:courseId/history', redirect: (to) => `/teacher/history/${String(to.params.courseId)}` },
      {
        path: 'student/grades',
        name: 'student-grades',
        component: () => import('@/views/student/StudentGradesView.vue'),
        meta: { title: '我的成绩', roles: ['STUDENT'], permission: 'GRADE_SELF_READ' },
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserManagementView.vue'),
        meta: { title: '用户与权限', roles: ['ADMIN'], anyPermission: ['USER_MANAGE', 'ORG_MANAGE', 'PERMISSION_MANAGE'] },
      },
      {
        path: 'admin/security',
        name: 'admin-security',
        component: () => import('@/views/admin/SecurityConsoleView.vue'),
        meta: { title: '安全审计', roles: ['ADMIN'], anyPermission: ['AUDIT_READ', 'INTEGRITY_VERIFY', 'ALERT_MANAGE', 'GRADE_READ', 'GRADE_REVERT_SMALL', 'GRADE_REVERT_REQUEST', 'GRADE_REVERT_APPROVE', 'GRADE_RESTORE_ORIGINAL'] },
      },
      { path: '403', name: 'forbidden', component: () => import('@/views/StatusView.vue'), props: { status: '403' }, meta: { title: '无权访问' } },
    ],
  },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/StatusView.vue'), props: { status: '404' }, meta: { public: true, title: '页面不存在' } },
]

const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.ready) await auth.bootstrap()

  if (to.meta.public) {
    if (to.name === 'login' && auth.user) return homeForUser(auth.user) || '/403'
    return true
  }
  if (!auth.user) return { name: 'login', query: { redirect: to.fullPath } }
  if (!auth.hasRole(to.meta.roles) || !auth.hasPermission(to.meta.permission) || !auth.hasAnyPermission(to.meta.anyPermission)) {
    return { name: 'forbidden' }
  }
  if (to.name === 'home') return homeForUser(auth.user) || '/403'
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 高校成绩管理` : '高校成绩管理工作台'
})

export default router
