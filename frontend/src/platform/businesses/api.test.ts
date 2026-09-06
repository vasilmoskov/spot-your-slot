import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import {
  changeBusinessStatus,
  createBusiness,
  getBusiness,
  inviteBusinessOwner,
  listBusinesses,
  updateBusiness,
} from './api'

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

  it('uses the approved create, detail and update contracts', async () => {
    mockedRequest.mockResolvedValue({ id: 'business-a' })
    const signal = new AbortController().signal

    await getBusiness('business/a', signal)
    await createBusiness({
      slug: 'studio-a',
      displayName: 'Студио А',
      businessType: 'BEAUTY_STUDIO',
    })
    await updateBusiness('business/a', {
      slug: 'studio-a',
      displayName: 'Студио А',
      businessType: 'BEAUTY_STUDIO',
      timezone: 'Europe/Sofia',
      expectedVersion: 4,
    })

    expect(mockedRequest).toHaveBeenNthCalledWith(
      1,
      '/api/platform/businesses/business%2Fa',
      { signal },
    )
    expect(JSON.parse(String(mockedRequest.mock.calls[1]?.[1]?.body))).toEqual({
      slug: 'studio-a',
      displayName: 'Студио А',
      businessType: 'BEAUTY_STUDIO',
    })
    expect(mockedRequest.mock.calls[1]?.[1]?.method).toBe('POST')
    expect(JSON.parse(String(mockedRequest.mock.calls[2]?.[1]?.body))).toEqual({
      slug: 'studio-a',
      displayName: 'Студио А',
      businessType: 'BEAUTY_STUDIO',
      timezone: 'Europe/Sofia',
      expectedVersion: 4,
    })
    expect(mockedRequest.mock.calls[2]?.[1]?.method).toBe('PUT')
  })

  it('sends only expectedVersion for lifecycle changes and only email for invitations', async () => {
    mockedRequest.mockResolvedValue(undefined)

    await changeBusinessStatus('business-a', 'activate', 2)
    await inviteBusinessOwner('business-a', 'owner@example.invalid')

    expect(mockedRequest).toHaveBeenNthCalledWith(
      1,
      '/api/platform/businesses/business-a/activate',
      {
        method: 'POST',
        body: JSON.stringify({ expectedVersion: 2 }),
      },
    )
    expect(mockedRequest).toHaveBeenNthCalledWith(
      2,
      '/api/platform/identity/businesses/business-a/owner-invitation',
      {
        method: 'POST',
        body: JSON.stringify({ email: 'owner@example.invalid' }),
      },
    )
  })
})
