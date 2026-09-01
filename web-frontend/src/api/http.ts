import axios, { AxiosError, AxiosHeaders, type AxiosRequestConfig, type Method } from 'axios'
import type { ApiEnvelope } from '@/types/domain'

export interface AppError {
  code: string
  message: string
  status?: number
  traceId?: string
  fieldErrors?: Record<string, string>
}

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 20_000,
  withCredentials: true,
  withXSRFToken: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: { 'X-Requested-With': 'XMLHttpRequest' },
})

interface CsrfMaterial {
  headerName: string
  token: string
}

let csrfMaterial: CsrfMaterial | null = null
let csrfPromise: Promise<CsrfMaterial> | null = null

export function initializeCsrf(force = false): Promise<CsrfMaterial> {
  if (!csrfPromise || force) {
    csrfPromise = client
      .get<ApiEnvelope<{ headerName: string; parameterName: string; token: string }>>('/auth/csrf')
      .then((response) => {
        const value = response.data?.data
        if (!response.data?.success || !value?.headerName || !value.token) {
          throw new Error('服务端未返回有效的 CSRF 令牌')
        }
        csrfMaterial = { headerName: value.headerName, token: value.token }
        return csrfMaterial
      })
      .catch((error) => {
        csrfPromise = null
        csrfMaterial = null
        throw toAppError(error)
      })
  }
  return csrfPromise
}

export function toAppError(error: unknown): AppError {
  if (axios.isAxiosError(error)) {
    const axiosError = error as AxiosError<ApiEnvelope<unknown>>
    const apiError = axiosError.response?.data?.error
    return {
      code: apiError?.code || (axiosError.code === 'ECONNABORTED' ? 'TIMEOUT' : 'NETWORK_ERROR'),
      message:
        apiError?.message ||
        (axiosError.response?.status === 403
          ? '当前账号无权执行此操作'
          : axiosError.response?.status === 401
            ? '登录状态已失效，请重新登录'
            : axiosError.message || '网络请求失败'),
      status: axiosError.response?.status,
      traceId: apiError?.traceId,
      fieldErrors: apiError?.fieldErrors,
    }
  }
  if (typeof error === 'object' && error !== null && 'message' in error) {
    const candidate = error as Partial<AppError> & { message: unknown }
    return {
      code: typeof candidate.code === 'string' ? candidate.code : 'API_ERROR',
      message: String(candidate.message),
      status: candidate.status,
      traceId: candidate.traceId,
      fieldErrors: candidate.fieldErrors,
    }
  }
  if (error instanceof Error) return { code: 'CLIENT_ERROR', message: error.message }
  return { code: 'UNKNOWN_ERROR', message: '发生未知错误' }
}

async function call<T>(method: Method, url: string, config?: AxiosRequestConfig): Promise<T> {
  const unsafe = !['get', 'head', 'options'].includes(method.toLowerCase())
  if (unsafe) await initializeCsrf()
  const request = () => {
    const headers = AxiosHeaders.from(config?.headers as AxiosHeaders | undefined)
    if (unsafe && csrfMaterial) headers.set(csrfMaterial.headerName, csrfMaterial.token)
    return client.request<ApiEnvelope<T>>({ ...config, headers, method, url })
  }
  try {
    let response
    try {
      response = await request()
    } catch (error) {
      const csrfRejected = unsafe && axios.isAxiosError<ApiEnvelope<unknown>>(error)
        && error.response?.status === 403 && error.response.data?.error?.code === 'CSRF_INVALID'
      if (!csrfRejected) throw error
      await initializeCsrf(true)
      response = await request()
    }
    const envelope = response.data
    if (typeof envelope === 'object' && envelope !== null && 'success' in envelope) {
      if (!envelope.success) throw envelope.error || new Error(envelope.message || '请求失败')
      return envelope.data
    }
    return response.data as T
  } catch (error) {
    if (typeof error === 'object' && error !== null && 'message' in error && !axios.isAxiosError(error)) {
      const candidate = error as { code?: string; message: string; traceId?: string }
      const appError = { code: candidate.code || 'API_ERROR', message: candidate.message, traceId: candidate.traceId } satisfies AppError
      throw appError
    }
    const appError = toAppError(error)
    if (appError.status === 401 && typeof window !== 'undefined' && !url.includes('/auth/login')) {
      window.dispatchEvent(new CustomEvent('auth-expired'))
    }
    throw appError
  }
}

export const http = {
  get: <T>(url: string, params?: unknown) => call<T>('get', url, { params }),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) => call<T>('post', url, { ...config, data }),
  put: <T>(url: string, data?: unknown) => call<T>('put', url, { data }),
  patch: <T>(url: string, data?: unknown) => call<T>('patch', url, { data }),
  delete: <T>(url: string, data?: unknown) => call<T>('delete', url, { data }),
}
