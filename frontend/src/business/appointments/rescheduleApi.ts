import { businessRequest as request } from '../../identity/businessRequest'
import type { OfferedStart, StaffChoice } from './api'

// The contracts of the reschedule and reassignment flow: the two Appointment-scoped option reads of the
// Phase 9 backend prerequisite and the Phase 5 reschedule POST. The Appointment is the only thing a
// request names; no duration, price, Service, or ignored Appointment is ever sent.

export type CurrentStaffChoice = { id: string; displayName: string; available: boolean }

export type RescheduleOptions = {
  timezone: string
  // The stored duration of the Appointment, never the current Services'.
  durationMinutes: number
  currentStaffMember: CurrentStaffChoice
  // The other eligible StaffMembers only; the current one is never listed.
  staffMembers: StaffChoice[]
}

export type RescheduleAvailability = {
  timezone: string
  date: string
  durationMinutes: number
  slots: OfferedStart[]
}

/** The exact documented body: `expectedVersion` always, `start` and `staffMemberId` only when they change. */
export type RescheduleBody = {
  expectedVersion: number
  start?: string
  staffMemberId?: string
}

/** The part of the answer the flow checks before it claims a success. */
export type RescheduledAppointment = {
  id: string
  status: string
  start: string
  end: string
  staffMemberId: string
  version: number
}

function base(appointmentId: string): string {
  return `/api/business/appointments/${encodeURIComponent(appointmentId)}`
}

export function getRescheduleOptions(
  appointmentId: string,
  signal?: AbortSignal,
): Promise<RescheduleOptions> {
  return request<RescheduleOptions>(`${base(appointmentId)}/reschedule-options`, signal ? { signal } : {})
}

export function getRescheduleAvailability(
  appointmentId: string,
  staffMemberId: string,
  date: string,
  signal?: AbortSignal,
): Promise<RescheduleAvailability> {
  const params = new URLSearchParams({ staffMemberId, date })
  return request<RescheduleAvailability>(
    `${base(appointmentId)}/reschedule-options/availability?${params.toString()}`,
    signal ? { signal } : {},
  )
}

/** One POST for the whole change; it is never repeated automatically. */
export function rescheduleAppointment(
  appointmentId: string,
  body: RescheduleBody,
): Promise<RescheduledAppointment> {
  return request<RescheduledAppointment>(`${base(appointmentId)}/reschedule`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}
