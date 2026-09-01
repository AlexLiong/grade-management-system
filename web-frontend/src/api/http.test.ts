import { beforeEach, describe, expect, it, vi } from 'vitest'

const axiosMocks = vi.hoisted(() => ({
  get: vi.fn(),
  request: vi.fn(),
}))

vi.mock('axios', async () => {
  const actual = await vi.importActual<typeof import('axios')>('axios')
  const mockedDefault = Object.assign(actual.default, {
    create: () => ({ get: axiosMocks.get, request: axiosMocks.request }),
  })
  return { ...actual, default: mockedDefault }
})

import { http, initializeCsrf } from './http'

describe('CSRF request handling', () => {
  beforeEach(() => {
    axiosMocks.get.mockReset()
    axiosMocks.request.mockReset()
  })

  it('injects the server-issued header into unsafe requests explicitly', async () => {
    axiosMocks.get.mockResolvedValue({
      data: { success: true, data: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-123' } },
    })
    axiosMocks.request.mockResolvedValue({ data: { success: true, data: { valid: true } } })

    await initializeCsrf(true)
    await http.post('/admin/integrity/verify')

    const config = axiosMocks.request.mock.calls[0]?.[0]
    expect(config.headers.get('X-XSRF-TOKEN')).toBe('csrf-123')
    expect(config.method).toBe('post')
  })
})
