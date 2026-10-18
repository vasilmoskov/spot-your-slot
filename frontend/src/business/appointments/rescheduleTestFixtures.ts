import { ApiError } from '../../identity/api'
import type { AppointmentDetails } from '../calendar/api'
import { APPOINTMENT_ONE, CUSTOMER_ONE, STAFF_ONE, STAFF_TWO, detailResponse } from '../calendar/testFixtures'
import type { OfferedStart } from './api'
import type { RescheduleAvailability, RescheduleBody, RescheduleOptions, RescheduledAppointment } from './rescheduleApi'
import { slot } from './testFixtures'

// Synthetic data only: invented names, reserved example domains and documented sample numbers.
export { APPOINTMENT_ONE, CUSTOMER_ONE, STAFF_ONE, STAFF_TWO }
export const STAFF_THREE = '33333333-3333-4333-8333-333333333333'
export const ZONE = 'Europe/Sofia'
export const NOW = new Date('2026-10-02T08:00:00Z')
export const DAY = '2026-11-10'
export const ORIGINAL_START = `${DAY}T09:00:00+02:00`

/** A future, confirmed 75-minute visit of two Services with the first StaffMember (version 7). */
export function appointmentDetail(overrides: Partial<AppointmentDetails> = {}): AppointmentDetails {
  return detailResponse({
    start: ORIGINAL_START,
    end: `${DAY}T10:15:00+02:00`,
    ...overrides,
  })
}

export function optionsResponse(overrides: Partial<RescheduleOptions> = {}): RescheduleOptions {
  return {
    timezone: ZONE,
    durationMinutes: 75,
    currentStaffMember: { id: STAFF_ONE, displayName: 'Мила Работникова', available: true },
    staffMembers: [{ id: STAFF_TWO, displayName: 'Георги Стилистов' }],
    ...overrides,
  }
}

export type Call = { path: string; method: string; body: unknown; signal: AbortSignal | undefined }

/** A scriptable stand-in for the Business API behind the network helper. */
export class FakeRescheduleBackend {
  calls: Call[] = []
  detail: () => AppointmentDetails | Promise<AppointmentDetails> = () => appointmentDetail()
  options: () => RescheduleOptions | Promise<RescheduleOptions> = () => optionsResponse()
  // Like the server: the unchanged start is not offered to the current StaffMember.
  availability: (staff: string, date: string) => RescheduleAvailability | Promise<RescheduleAvailability> = (
    staff,
    date,
  ) => {
    const starts = [`${date}T09:00:00+02:00`, `${date}T09:15:00+02:00`, `${date}T10:00:00+02:00`]
    const offered = starts.filter((start) => !(staff === STAFF_ONE && start === ORIGINAL_START))
    return { timezone: ZONE, date, durationMinutes: 75, slots: offered.map((start) => slot(start, 75)) }
  }
  reschedule: (body: RescheduleBody) => RescheduledAppointment | Promise<RescheduledAppointment> = (body) => ({
    id: APPOINTMENT_ONE,
    status: 'CONFIRMED',
    start: body.start ?? ORIGINAL_START,
    end: `${DAY}T10:15:00+02:00`,
    staffMemberId: body.staffMemberId ?? STAFF_ONE,
    version: 8,
  })

  problem(status: number, code: string, detail = 'backend text that must never be shown'): ApiError {
    return new ApiError(status, code, detail)
  }

  count(suffix: string, method = 'GET'): number {
    return this.calls.filter((call) => call.path.split('?')[0]!.endsWith(suffix) && call.method === method).length
  }

  posts(): Call[] {
    return this.calls.filter((call) => call.method === 'POST')
  }

  async handle(path: string, options: RequestInit | undefined): Promise<unknown> {
    const method = options?.method ?? 'GET'
    const body = options?.body ? JSON.parse(String(options.body)) : undefined
    this.calls.push({ path, method, body, signal: options?.signal ?? undefined })
    const url = new URL(path, 'http://x')
    const base = `/api/business/appointments/${APPOINTMENT_ONE}`
    if (url.pathname === base && method === 'GET') return this.detail()
    if (url.pathname === `${base}/reschedule-options`) return this.options()
    if (url.pathname === `${base}/reschedule-options/availability`) {
      return this.availability(url.searchParams.get('staffMemberId') as string, url.searchParams.get('date') as string)
    }
    if (url.pathname === `${base}/reschedule` && method === 'POST') return this.reschedule(body)
    throw new Error(`unexpected ${method} ${path}`)
  }
}

export function offered(...starts: string[]): OfferedStart[] {
  return starts.map((start) => slot(start, 75))
}
