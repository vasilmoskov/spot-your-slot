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

  it('uses the structured authentication title when the filter response has no detail', async () => {
    fetchMock.mockResolvedValue(
      new Response(
        JSON.stringify({ code: 'AUTH_REQUIRED', title: 'Необходим е вход.' }),
        { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    )

    await expect(request('/api/platform/businesses')).rejects.toEqual(
      new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'),
    )
  })

  it('does not expose an unrecognized problem title', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ title: 'SQL internal failure' }), {
        status: 500,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    )

    await expect(request('/api/example')).rejects.toEqual(
      new ApiError(500, 'REQUEST_FAILED', 'Заявката не може да бъде изпълнена.'),
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

  it('accepts a successful empty response body without attempting JSON parsing', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 202 }))

    await expect(request('/api/example')).resolves.toBeUndefined()
  })

  it('accepts an HTTP 204 response without attempting JSON parsing', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))

    await expect(request('/api/example')).resolves.toBeUndefined()
  })

  it('returns parsed JSON from a successful non-empty response', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ status: 'accepted' }), { status: 202 }),
    )

    await expect(request('/api/example')).resolves.toEqual({ status: 'accepted' })
  })

  it('uses a safe client error when a successful response is not valid JSON', async () => {
    const malformedBody = '<html>private upstream diagnostic</html>'
    fetchMock.mockResolvedValue(new Response(malformedBody, { status: 202 }))

    const response = request('/api/example')

    await expect(response).rejects.toEqual(
      new ApiError(202, 'REQUEST_FAILED', 'Заявката не може да бъде изпълнена.'),
    )
    await expect(response).rejects.not.toThrow(malformedBody)
    await expect(response).rejects.not.toThrow('Unexpected token')
    await expect(response).rejects.not.toThrow('private upstream diagnostic')
  })

  it('keeps the Error message safe for identity feedback rendering', async () => {
    fetchMock.mockResolvedValue(
      new Response('<html>sensitive response fragment</html>', { status: 200 }),
    )

    const error = await request('/api/auth/session').catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as Error).message).toBe('Заявката не може да бъде изпълнена.')
    expect((error as Error).message).not.toContain('Unexpected token')
    expect((error as Error).message).not.toContain('sensitive response fragment')
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
