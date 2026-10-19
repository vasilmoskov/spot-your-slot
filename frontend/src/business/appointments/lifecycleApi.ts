import { businessRequest as request } from '../../identity/businessRequest'

// The contracts of the Phase 6 cancellation and internal-note routes. Each is one request with the
// version of the loaded Appointment; no reason, actor, status, or time can be named, and nothing is
// ever sent twice automatically.

/** The part of the complete private answer that the interface checks before it claims a success. */
export type LifecycleResult = {
  id: string
  status: string
  version: number
  internalNote: string | null
}

function base(appointmentId: string): string {
  return `/api/business/appointments/${encodeURIComponent(appointmentId)}`
}

/** `POST …/cancel` with exactly `{"expectedVersion": n}`. */
export function cancelAppointment(appointmentId: string, expectedVersion: number): Promise<LifecycleResult> {
  return request<LifecycleResult>(`${base(appointmentId)}/cancel`, {
    method: 'POST',
    body: JSON.stringify({ expectedVersion }),
  })
}

/** `PUT …/internal-note` with exactly `{"expectedVersion": n, "internalNote": "…"}`; a blank string clears it. */
export function saveInternalNote(
  appointmentId: string,
  expectedVersion: number,
  internalNote: string,
): Promise<LifecycleResult> {
  return request<LifecycleResult>(`${base(appointmentId)}/internal-note`, {
    method: 'PUT',
    body: JSON.stringify({ expectedVersion, internalNote }),
  })
}
