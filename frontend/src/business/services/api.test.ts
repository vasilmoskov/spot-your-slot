import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import {
  createService,
  deactivateService,
  getService,
  listServices,
  reactivateService,
  updateService,
} from './api'

vi.mock('../../identity/api', async (importOriginal) => {
  const original = await importOriginal<typeof import('../../identity/api')>()
  return { ...original, request: vi.fn() }
})

const mockedRequest = vi.mocked(request)

describe('services api client', () => {
  beforeEach(() => {
    mockedRequest.mockReset()
  })

  it('requests the paginated Service list using the default sort and direction', () => {
    mockedRequest.mockResolvedValue({ services: [], page: 0, size: 10, totalElements: 0 })
    void listServices(1, 20)
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/services?page=1&size=20&sort=name&direction=asc',
      {},
    )
  })

  it('requests an explicit sort field and direction', () => {
    mockedRequest.mockResolvedValue({ services: [], page: 0, size: 25, totalElements: 0 })
    const controller = new AbortController()
    void listServices(2, 25, 'price', 'desc', controller.signal)
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/services?page=2&size=25&sort=price&direction=desc',
      { signal: controller.signal },
    )
  })

  it('requests a single Service by id', () => {
    mockedRequest.mockResolvedValue({})
    void getService('service-a')
    expect(mockedRequest).toHaveBeenCalledWith('/api/business/services/service-a', {})
  })

  it('sends the exact decimal price string on create, never a parsed number', () => {
    mockedRequest.mockResolvedValue({})
    void createService({
      name: 'Подстригване',
      description: undefined,
      durationMinutes: 10,
      price: '19.90',
    })
    expect(mockedRequest).toHaveBeenCalledWith('/api/business/services', {
      method: 'POST',
      body: JSON.stringify({
        name: 'Подстригване',
        durationMinutes: 10,
        price: '19.90',
      }),
    })
    const [, options] = mockedRequest.mock.calls[0]!
    expect(String(options?.body)).toContain('"19.90"')
  })

  it('preserves expectedVersion and the exact price string on update', () => {
    mockedRequest.mockResolvedValue({})
    void updateService('service-a', {
      name: 'Брада',
      description: 'Оформяне на брада',
      durationMinutes: 5,
      price: '10.00',
      expectedVersion: 3,
    })
    expect(mockedRequest).toHaveBeenCalledWith('/api/business/services/service-a', {
      method: 'PUT',
      body: JSON.stringify({
        name: 'Брада',
        description: 'Оформяне на брада',
        durationMinutes: 5,
        price: '10.00',
        expectedVersion: 3,
      }),
    })
  })

  it('sends expectedVersion for deactivate and reactivate', () => {
    mockedRequest.mockResolvedValue({})
    void deactivateService('service-a', 2)
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/services/service-a/deactivate',
      { method: 'POST', body: JSON.stringify({ expectedVersion: 2 }) },
    )

    void reactivateService('service-a', 3)
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/services/service-a/reactivate',
      { method: 'POST', body: JSON.stringify({ expectedVersion: 3 }) },
    )
  })
})
