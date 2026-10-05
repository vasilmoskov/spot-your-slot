import { businessRequest as request } from '../../identity/businessRequest'
import type { ListQueryState } from '../../navigation'

export type CustomerSummary = {
  id: string
  displayName: string
  phone: string | null
  email: string | null
}

// `version` is transport-only: it is sent back as `expectedVersion` and never displayed.
export type CustomerDetails = CustomerSummary & {
  version: number
  createdAt: string
  updatedAt: string
}

export type CustomerPage = {
  items: CustomerSummary[]
  page: number
  size: number
  total: number
}

export type CustomerInput = {
  displayName: string
  phone?: string | undefined
  email?: string | undefined
}

export type UpdateCustomerInput = CustomerInput & { expectedVersion: number }

const BASE = '/api/business/customers'

/**
 * The ordinary list (a blank term) is a GET with paging only. A search sends its term in the
 * POST body and nowhere else: never in a URL, query string, header or browser storage.
 */
export function listCustomers(
  query: ListQueryState,
  search: string,
  signal?: AbortSignal,
): Promise<CustomerPage> {
  const options: RequestInit = signal ? { signal } : {}
  if (search === '') {
    const params = new URLSearchParams({
      page: String(query.page),
      size: String(query.size),
      sort: query.sort,
      direction: query.direction,
    })
    return request<CustomerPage>(`${BASE}?${params.toString()}`, options)
  }
  return request<CustomerPage>(`${BASE}/search`, {
    ...options,
    method: 'POST',
    body: JSON.stringify({
      search,
      page: query.page,
      size: query.size,
      sort: query.sort,
      direction: query.direction,
    }),
  })
}

export function getCustomer(customerId: string, signal?: AbortSignal): Promise<CustomerDetails> {
  return request<CustomerDetails>(`${BASE}/${encodeURIComponent(customerId)}`, signal ? { signal } : {})
}

export function createCustomer(input: CustomerInput): Promise<CustomerDetails> {
  return request<CustomerDetails>(BASE, { method: 'POST', body: JSON.stringify(input) })
}

export function updateCustomer(
  customerId: string,
  input: UpdateCustomerInput,
): Promise<CustomerDetails> {
  return request<CustomerDetails>(`${BASE}/${encodeURIComponent(customerId)}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}
