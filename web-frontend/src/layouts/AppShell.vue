<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  BellRing,
  BookOpenCheck,
  ChevronDown,
  GraduationCap,
  FileClock,
  LogOut,
  Menu,
  ShieldCheck,
  SlidersHorizontal,
  UsersRound,
} from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import { homeForUser } from '@/utils/permission'

interface NavItem {
  label: string
  to: string
  icon: typeof BookOpenCheck
  roles: Array<'TEACHER' | 'STUDENT' | 'ADMIN'>
  permission?: string
  anyPermission?: string[]
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const drawerOpen = ref(false)

const allNav: NavItem[] = [
  { label: '我的课程', to: '/teacher/courses', icon: BookOpenCheck, roles: ['TEACHER'], permission: 'COURSE_READ' },
  { label: '历年成绩', to: '/teacher/history', icon: FileClock, roles: ['TEACHER'], permission: 'GRADE_HISTORY_READ' },
  { label: '我的成绩', to: '/student/grades', icon: GraduationCap, roles: ['STUDENT'], permission: 'GRADE_SELF_READ' },
  { label: '用户与权限', to: '/admin/users', icon: UsersRound, roles: ['ADMIN'], anyPermission: ['USER_MANAGE', 'ORG_MANAGE', 'PERMISSION_MANAGE'] },
  { label: '安全审计', to: '/admin/security', icon: ShieldCheck, roles: ['ADMIN'], anyPermission: ['AUDIT_READ', 'INTEGRITY_VERIFY', 'ALERT_MANAGE', 'GRADE_READ', 'GRADE_REVERT_SMALL', 'GRADE_REVERT_REQUEST', 'GRADE_REVERT_APPROVE', 'GRADE_RESTORE_ORIGINAL'] },
]

const navItems = computed(() => allNav.filter((item) =>
  auth.hasRole(item.roles) && auth.hasPermission(item.permission) && auth.hasAnyPermission(item.anyPermission),
))
const roleLabel = computed(() => ({ TEACHER: '教师', STUDENT: '学生', ADMIN: '管理员' })[auth.user?.role || 'STUDENT'])

function activeNav(path: string) {
  return route.path === path || route.path.startsWith(`${path}/`) || (path === '/teacher/courses' && route.path.startsWith('/teacher/courses/'))
}

async function navigate(path: string) {
  drawerOpen.value = false
  await router.push(path)
}

function openNotifications() {
  const target = auth.user ? homeForUser(auth.user) || '/403' : '/login'
  navigate(target)
}

async function logout() {
  await ElMessageBox.confirm('确定退出当前账号吗？', '退出登录', { confirmButtonText: '退出', cancelButtonText: '取消' })
  await auth.logout()
  await router.replace('/login')
}

function handleExpiredSession() {
  const redirect = route.fullPath
  auth.clearSession()
  router.replace({ name: 'login', query: { redirect } })
}

onMounted(() => window.addEventListener('auth-expired', handleExpiredSession))
onBeforeUnmount(() => window.removeEventListener('auth-expired', handleExpiredSession))
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar desktop-sidebar">
      <div class="brand-block">
        <div class="brand-mark"><GraduationCap :size="22" /></div>
        <div>
          <strong>高校成绩管理</strong>
          <span>教学工作台</span>
        </div>
      </div>
      <nav class="side-nav" aria-label="主导航">
        <button
          v-for="item in navItems"
          :key="item.to"
          type="button"
          :class="['nav-item', { active: activeNav(item.to) }]"
          @click="navigate(item.to)"
        >
          <component :is="item.icon" :size="18" />
          <span>{{ item.label }}</span>
        </button>
      </nav>
      <div class="sidebar-foot">
        <ShieldCheck :size="16" />
        <span>受保护会话</span>
      </div>
    </aside>

    <el-drawer v-model="drawerOpen" direction="ltr" size="280px" :with-header="false" class="mobile-drawer">
      <div class="drawer-content">
        <div class="brand-block dark-text">
          <div class="brand-mark"><GraduationCap :size="22" /></div>
          <div><strong>高校成绩管理</strong><span>教学工作台</span></div>
        </div>
        <nav class="side-nav mobile-nav" aria-label="移动端主导航">
          <button
            v-for="item in navItems"
            :key="item.to"
            type="button"
            :class="['nav-item', { active: activeNav(item.to) }]"
            @click="navigate(item.to)"
          >
            <component :is="item.icon" :size="18" />
            <span>{{ item.label }}</span>
          </button>
        </nav>
      </div>
    </el-drawer>

    <div class="workspace">
      <header class="topbar">
        <el-tooltip content="打开导航" placement="bottom">
          <button class="icon-button mobile-menu" type="button" aria-label="打开导航" @click="drawerOpen = true">
            <Menu :size="20" />
          </button>
        </el-tooltip>
        <div class="page-context">
          <span>{{ route.meta.title || '工作台' }}</span>
        </div>
        <div class="topbar-actions">
          <el-tooltip content="查看提醒" placement="bottom">
            <button class="icon-button" type="button" aria-label="查看提醒" @click="openNotifications"><BellRing :size="18" /></button>
          </el-tooltip>
          <el-dropdown trigger="click" @command="(command: string) => command === 'logout' && logout()">
            <button class="account-button" type="button">
              <span class="avatar">{{ auth.user?.displayName?.slice(0, 1) || '用' }}</span>
              <span class="account-copy"><strong>{{ auth.user?.displayName }}</strong><small>{{ roleLabel }}</small></span>
              <ChevronDown :size="15" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled><SlidersHorizontal :size="15" /> {{ auth.user?.organization || '校级组织' }}</el-dropdown-item>
                <el-dropdown-item divided command="logout"><LogOut :size="15" /> 退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>
      <main class="main-content">
        <RouterView />
      </main>
    </div>
  </div>
</template>
