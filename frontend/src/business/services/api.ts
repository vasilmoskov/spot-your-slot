import { request } from '../../identity/api'

export type ServiceSummary = {
  id: string
  name: string
  description: string | null
  durationMinutes: number
  price: number
  active: boolean
  version: number
  createdAt: string
  updatedAt: string
}

export type ServiceDetails = ServiceSummary

export type ServicePage = {
  services: ServiceSummary[]
  page: number
  size: number
  totalElements: number
}

export type CreateServiceInput = {
  name: string
  description?: string | undefined
  durationMinutes: number
  price: string
}

export type UpdateServiceInput = CreateServiceInput & { expectedVersion: number }

export function listServices(
  page = 0,
  size = 10,
  sort = 'name',
  direction: 'asc' | 'desc' = 'asc',
  signal?: AbortSignal,
): Promise<ServicePage> {
  const options: RequestInit = signal ? { signal } : {}
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
    sort,
    direction,
  })
  return request<ServicePage>(`/api/business/services?${params.toString()}`, options)
}

export function getService(
  serviceId: string,
  signal?: AbortSignal,
): Promise<ServiceDetails> {
  return request<ServiceDetails>(
    `/api/business/services/${encodeURIComponent(serviceId)}`,
    signal ? { signal } : {},
  )
}

export function createService(input: CreateServiceInput): Promise<ServiceDetails> {
  return request<ServiceDetails>('/api/business/services', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function updateService(
  serviceId: string,
  input: UpdateServiceInput,
): Promise<ServiceDetails> {
  return request<ServiceDetails>(
    `/api/business/services/${encodeURIComponent(serviceId)}`,
    {
      method: 'PUT',
      body: JSON.stringify(input),
    },
  )
}

export function deactivateService(
  serviceId: string,
  expectedVersion: number,
): Promise<ServiceDetails> {
  return request<ServiceDetails>(
    `/api/business/services/${encodeURIComponent(serviceId)}/deactivate`,
    {
      method: 'POST',
      body: JSON.stringify({ expectedVersion }),
    },
  )
}

export function reactivateService(
  serviceId: string,
  expectedVersion: number,
): Promise<ServiceDetails> {
  return request<ServiceDetails>(
    `/api/business/services/${encodeURIComponent(serviceId)}/reactivate`,
    {
      method: 'POST',
      body: JSON.stringify({ expectedVersion }),
    },
  )
}
