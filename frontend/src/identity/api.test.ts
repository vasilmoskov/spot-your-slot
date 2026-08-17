import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from './api'

const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('identity API client', () => {
  it('preserves safe problem status, code and detail', async () => {
    fetchMock.mockResolvedValue(
      new Response(
        JSON.stringify({ code: 'ACCESS_DENIED', detail: 'Нямате достъп до тази операция.' }),
        { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    )

    await expect(request('/api/example')).rejects.toEqual(
      new ApiError(403, 'ACCESS_DENIED', 'Нямате достъп до тази операция.'),
    )
  })

  it('uses a safe fallback for malformed and non-JSON errors', async () => {
    fetchMock.mockResolvedValue(new Response('<html>internal details</html>', { status: 500 }))

    await expect(request('/api/example')).rejects.toEqual(
      new ApiError(500, 'REQUEST_FAILED', 'Заявката не може да бъде изпълнена.'),
    )
  })

  it('uses credentialed cookies for reads', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }))

    await request('/api/example')

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/example',
      expect.objectContaining({ credentials: 'include' }),
    )
  })

  it('keeps the CSRF token in memory and sends credentialed writes', async () => {
    fetchMock
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' })),
      )
      .mockResolvedValue(new Response(null, { status: 204 }))

    await request('/api/first', { method: 'POST', body: '{}' })
    await request('/api/second', { method: 'POST', body: '{}' })

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls[0]![1]).toEqual({ credentials: 'include' })
    const firstWrite = fetchMock.mock.calls[1]![1]
    const secondWrite = fetchMock.mock.calls[2]![1]
    expect(firstWrite?.credentials).toBe('include')
    expect(secondWrite?.credentials).toBe('include')
    expect(new Headers(firstWrite?.headers).get('X-XSRF-TOKEN')).toBe('csrf-token')
    expect(new Headers(secondWrite?.headers).get('X-XSRF-TOKEN')).toBe('csrf-token')
  })
})
