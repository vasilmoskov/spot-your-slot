import { businessRequest as request } from '../../identity/businessRequest'

export type AppointmentStatus = 'CONFIRMED' | 'CANCELLED'
export type AppointmentSource = 'ONLINE' | 'MANUAL'
export type BusinessStatus = 'DRAFT' | 'ACTIVE' | 'SUSPENDED'

export type CalendarDay = { date: string; start: string; end: string }

export type WorkingInterval = { start: string; end: string }

export type CalendarClosure = { date: string; kind: 'BUSINESS_CLOSURE' | 'STAFF_TIME_OFF' }

export type CalendarStaffMember = {
  id: string
  displayName: string
  active: boolean
  workingIntervals: WorkingInterval[]
  closures: CalendarClosure[]
}

export type AppointmentService = { name: string; durationMinutes: number }

export type CalendarAppointment = {
  id: string
  status: AppointmentStatus
  source: AppointmentSource
  start: string
  end: string
  staffMemberId: string
  staffDisplayName: string
  customerId: string
  customerDisplayName: string
  durationMinutes: number
  services: AppointmentService[]
}

export type CalendarResponse = {
  businessStatus: BusinessStatus
  timezone: string
  from: string
  to: string
  days: CalendarDay[]
  staffMembers: CalendarStaffMember[]
  appointments: CalendarAppointment[]
}

export type AgendaResponse = {
  businessStatus: BusinessStatus
  timezone: string
  from: string
  to: string
  items: CalendarAppointment[]
  page: number
  size: number
  total: number
}

export type AppointmentDetailService = {
  position: number
  name: string
  durationMinutes: number
  price: number
}

export type AppointmentDetails = {
  businessStatus: BusinessStatus
  timezone: string
  id: string
  reference: string
  status: AppointmentStatus
  source: AppointmentSource
  start: string
  end: string
  staffMemberId: string
  staffDisplayName: string
  customer: { id: string; displayName: string; phone: string | null; email: string | null }
  services: AppointmentDetailService[]
  durationMinutes: number
  price: number
  customerNote: string | null
  internalNote: string | null
  cancellation: { cancelledAt: string | null; cancelledBy: 'BUSINESS' | 'UNKNOWN' } | null
  // Transport-only concurrency metadata: never displayed and never treated as history.
  version: number
  createdAt: string
  updatedAt: string
}

export type CustomerAppointment = {
  id: string
  status: AppointmentStatus
  source: AppointmentSource
  start: string
  end: string
  staffDisplayName: string
  serviceNames: string[]
}

export type CustomerAppointmentPage = {
  timezone: string
  items: CustomerAppointment[]
  page: number
  size: number
  total: number
}

// The backend accepts at most this many Business-local dates in one calendar or agenda request.
export const CALENDAR_MAX_DATES = 31

function rangeParams(from: string, to: string, staffMemberId: string | null): URLSearchParams {
  const params = new URLSearchParams({ from, to })
  if (staffMemberId !== null) params.set('staffMemberId', staffMemberId)
  return params
}

function options(signal: AbortSignal | undefined): RequestInit {
  return signal ? { signal } : {}
}

export function getCalendar(
  from: string,
  to: string,
  staffMemberId: string | null,
  signal?: AbortSignal,
): Promise<CalendarResponse> {
  return request<CalendarResponse>(
    `/api/business/calendar?${rangeParams(from, to, staffMemberId).toString()}`,
    options(signal),
  )
}

export function getAgenda(
  from: string,
  to: string,
  staffMemberId: string | null,
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<AgendaResponse> {
  const params = rangeParams(from, to, staffMemberId)
  params.set('page', String(page))
  params.set('size', String(size))
  return request<AgendaResponse>(`/api/business/appointments?${params.toString()}`, options(signal))
}

export function getAppointment(
  appointmentId: string,
  signal?: AbortSignal,
): Promise<AppointmentDetails> {
  return request<AppointmentDetails>(
    `/api/business/appointments/${encodeURIComponent(appointmentId)}`,
    options(signal),
  )
}

export type HistoryDirection = 'asc' | 'desc'

/**
 * One page of a Customer's history, ordered by start (`asc`: earlier first, the default) and optionally only the
 * Appointments that start on one Business-local date (`yyyy-MM-dd`). Both apply on the server before paging.
 */
export function getCustomerAppointments(
  customerId: string,
  page: number,
  size: number,
  signal?: AbortSignal,
  filter: { direction?: HistoryDirection; date?: string | null } = {},
): Promise<CustomerAppointmentPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (filter.direction) params.set('direction', filter.direction)
  if (filter.date) params.set('date', filter.date)
  return request<CustomerAppointmentPage>(
    `/api/business/customers/${encodeURIComponent(customerId)}/appointments?${params.toString()}`,
    options(signal),
  )
}
