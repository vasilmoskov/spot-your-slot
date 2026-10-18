import { businessRequest as request } from '../../identity/businessRequest'
import { listServices, type ServiceSummary } from '../services/api'

export type StaffChoice = { id: string; displayName: string }

export type CreationOptions = {
  timezone: string
  durationMinutes: number
  staffMembers: StaffChoice[]
}

export type OfferedStart = { start: string; end: string }

export type CreationAvailability = {
  timezone: string
  date: string
  durationMinutes: number
  slots: OfferedStart[]
}

/** One reviewed Service claim of the create request, exactly the contract of Phase 4. */
export type ServiceClaim = {
  serviceId: string
  reviewedServiceName: string
  reviewedDurationMinutes: number
  reviewedPriceCents: number
}

export type CreateAppointmentBody = {
  customerId: string
  staffMemberId: string
  start: string
  services: ServiceClaim[]
  internalNote?: string
}

export type CreatedAppointment = {
  id: string
  reference: string
  status: 'CONFIRMED'
  source: 'MANUAL'
  start: string
  end: string
  timezone: string
  staffMemberId: string
  staffDisplayName: string
  customerId: string
  services: { position: number; name: string; durationMinutes: number; price: number }[]
  durationMinutes: number
  price: number
  internalNote: string | null
  version: number
  createdAt: string
}

const OPTIONS = '/api/business/appointment-options'

function selectionQuery(serviceIds: readonly string[]): URLSearchParams {
  const params = new URLSearchParams()
  for (const id of serviceIds) params.append('serviceId', id)
  return params
}

/** The StaffMembers assigned to every selected Service, with the authoritative summed duration. */
export function getCreationOptions(
  serviceIds: readonly string[],
  signal?: AbortSignal,
): Promise<CreationOptions> {
  return request<CreationOptions>(
    `${OPTIONS}?${selectionQuery(serviceIds).toString()}`,
    signal ? { signal } : {},
  )
}

/** The offered starts of one explicit StaffMember on one Business-local date. */
export function getCreationAvailability(
  serviceIds: readonly string[],
  staffMemberId: string,
  date: string,
  signal?: AbortSignal,
): Promise<CreationAvailability> {
  const params = selectionQuery(serviceIds)
  params.set('staffMemberId', staffMemberId)
  params.set('date', date)
  return request<CreationAvailability>(`${OPTIONS}/availability?${params.toString()}`, signal ? { signal } : {})
}

/** One POST for the whole visit; it is never repeated automatically. */
export function createAppointment(body: CreateAppointmentBody): Promise<CreatedAppointment> {
  return request<CreatedAppointment>('/api/business/appointments', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

const CATALOG_PAGE_SIZE = 50

/**
 * Every active Service of the Business in the catalog (name) order, all pages, so the form is never
 * silently limited to the first page. One signal cancels every page request at once.
 */
export async function loadEveryActiveService(signal: AbortSignal): Promise<ServiceSummary[]> {
  const first = await listServices(0, CATALOG_PAGE_SIZE, 'name', 'asc', signal)
  const collected = [...first.services]
  const pages = Math.ceil(first.totalElements / CATALOG_PAGE_SIZE)
  for (let page = 1; page < pages; page += 1) {
    collected.push(...(await listServices(page, CATALOG_PAGE_SIZE, 'name', 'asc', signal)).services)
  }
  return collected.filter((service) => service.active)
}
