import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import { listBusinesses } from './api'

vi.mock('../../identity/api', () => ({ request: vi.fn() }))

const mockedRequest = vi.mocked(request)

beforeEach(() => {
  mockedRequest.mockReset()
})

describe('Business API client', () => {
  it('requests the approved initial bounded page', async () => {
    mockedRequest.mockResolvedValue({ businesses: [], page: 0, size: 50, totalElements: 0 })

    await listBusinesses()

    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/platform/businesses?page=0&size=50',
      {},
    )
  })

  it('passes an explicit bounded page, size and cancellation signal', async () => {
    mockedRequest.mockResolvedValue({ businesses: [], page: 2, size: 25, totalElements: 0 })
    const controller = new AbortController()

    await listBusinesses(2, 25, controller.signal)

    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/platform/businesses?page=2&size=25',
      { signal: controller.signal },
    )
  })
})
