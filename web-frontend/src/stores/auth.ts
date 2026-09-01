import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/services'
import { initializeCsrf, toAppError, type AppError } from '@/api/http'
import type { CurrentUser, Role } from '@/types/domain'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const ready = ref(false)
  const loading = ref(false)
  const error = ref<AppError | null>(null)

  const role = computed(() => user.value?.role)
  const authenticated = computed(() => Boolean(user.value))

  function hasRole(roles?: Role[]) {
    if (!roles?.length) return true
    const assigned = new Set([user.value?.role, ...(user.value?.roles || [])])
    return roles.some((item) => assigned.has(item))
  }

  function hasPermission(required?: string | string[]) {
    if (!required) return true
    const values = Array.isArray(required) ? required : [required]
    if (user.value?.permissions?.includes('*')) return true
    return values.every((item) => user.value?.permissions?.includes(item))
  }

  function hasAnyPermission(required?: string[]) {
    if (!required?.length) return true
    if (user.value?.permissions?.includes('*')) return true
    return required.some((item) => user.value?.permissions?.includes(item))
  }

  async function bootstrap() {
    if (ready.value) return
    try {
      await initializeCsrf()
      user.value = await authApi.me()
    } catch (cause) {
      const appError = toAppError(cause)
      if (appError.status !== 401) error.value = appError
      user.value = null
    } finally {
      ready.value = true
    }
  }

  async function login(username: string, password: string) {
    loading.value = true
    error.value = null
    try {
      const current = await authApi.login({ username, password })
      await initializeCsrf(true)
      user.value = current
      ready.value = true
      return user.value
    } catch (cause) {
      error.value = toAppError(cause)
      throw error.value
    } finally {
      loading.value = false
    }
  }

  async function logout() {
    try {
      await authApi.logout()
    } finally {
      user.value = null
      ready.value = true
    }
  }

  function clearSession() {
    user.value = null
    ready.value = true
  }

  return { user, role, ready, loading, error, authenticated, hasRole, hasPermission, hasAnyPermission, bootstrap, login, logout, clearSession }
})
