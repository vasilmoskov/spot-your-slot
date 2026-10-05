import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import { CUSTOMERS_DEFAULT_LIST } from '../../navigation'
import { createCustomer, getCustomer, listCustomers, updateCustomer } from './api'

vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)

beforeEach(() => {
  mockedRequest.mockReset()
  mockedRequest.mockResolvedValue({ items: [], page: 0, size: 10, total: 0 })
})

afterEach(() => vi.restoreAllMocks())

describe('Customer API', () => {
  it('loads the ordinary list with a GET carrying only paging and sorting', async () => {
    await listCustomers({ page: 2, size: 25, sort: 'email', direction: 'desc' }, '')
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/customers?page=2&size=25&sort=email&direction=desc',
      {},
    )
  })

  it('sends a search term only in the JSON body of a POST', async () => {
    const controller = new AbortController()
    await listCustomers(
      { page: 1, size: 10, sort: 'name', direction: 'asc' },
      'Анна & +359',
      controller.signal,
    )
    const [url, options] = mockedRequest.mock.calls[0]!
    expect(url).toBe('/api/business/customers/search')
    expect(String(url)).not.toContain('Анна')
    expect(String(url)).not.toContain('?')
    expect(options).toMatchObject({ method: 'POST', signal: controller.signal })
    expect(JSON.parse(options!.body as string)).toEqual({
      search: 'Анна & +359',
      page: 1,
      size: 10,
      sort: 'name',
      direction: 'asc',
    })
  })

  it('reads, creates and updates by ID without placing contact data in a URL', async () => {
    await getCustomer('a b/c')
    expect(mockedRequest).toHaveBeenLastCalledWith('/api/business/customers/a%20b%2Fc', {})
    await createCustomer({ displayName: 'Мария', phone: '0895555777' })
    expect(mockedRequest).toHaveBeenLastCalledWith('/api/business/customers', {
      method: 'POST',
      body: JSON.stringify({ displayName: 'Мария', phone: '0895555777' }),
    })
    await updateCustomer('id-1', { displayName: 'Мария', email: 'm@example.test', expectedVersion: 4 })
    expect(mockedRequest).toHaveBeenLastCalledWith('/api/business/customers/id-1', {
      method: 'PUT',
      body: JSON.stringify({ displayName: 'Мария', email: 'm@example.test', expectedVersion: 4 }),
    })
  })

  it('uses the canonical default list state', () => {
    expect(CUSTOMERS_DEFAULT_LIST).toEqual({ page: 0, size: 10, sort: 'name', direction: 'asc' })
  })
})
