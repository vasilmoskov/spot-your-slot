import { ApiError } from '../../identity/api'
import type { AppointmentDetails } from '../calendar/api'
import { APPOINTMENT_ONE, detailResponse } from '../calendar/testFixtures'
import type { LifecycleResult } from './lifecycleApi'

// Synthetic data only: invented names and reserved example domains.
export { APPOINTMENT_ONE }
export const NOW = new Date('2026-10-02T08:00:00Z')
export const PAST_NOW = new Date('2026-10-14T08:00:00Z')

/** A future, confirmed 75-minute visit at version 7 with no internal note. */
export function confirmed(overrides: Partial<AppointmentDetails> = {}): AppointmentDetails {
  return detailResponse(overrides)
}

export function cancelled(overrides: Partial<AppointmentDetails> = {}): AppointmentDetails {
  return detailResponse({
    status: 'CANCELLED',
    cancellation: { cancelledAt: '2026-10-02T08:00:00Z', cancelledBy: 'BUSINESS' },
    version: 8,
    ...overrides,
  })
}

export type Call = { path: string; method: string; body: unknown }

type Pending<T> = T | Promise<T>

/**
 * A scriptable stand-in for the Business API behind the network helper. By default it behaves like the
 * server: a cancellation and a note edit change the stored row and advance the version; everything can be
 * replaced per test to script a rejection, a lost response, or a deferred answer.
 */
export class FakeLifecycleBackend {
  calls: Call[] = []
  current: AppointmentDetails = confirmed()
  read: () => Pending<AppointmentDetails> = () => structuredClone(this.current)
  cancel: (body: { expectedVersion: number }) => Pending<LifecycleResult> = () => {
    this.current = cancelled({ ...this.current, status: 'CANCELLED', version: this.current.version + 1 })
    return this.result()
  }
  note: (body: { expectedVersion: number; internalNote: string }) => Pending<LifecycleResult> = (body) => {
    const next = body.internalNote === '' ? null : body.internalNote
    if (next !== this.current.internalNote) {
      this.current = { ...this.current, internalNote: next, version: this.current.version + 1 }
    }
    return this.result()
  }

  result(): LifecycleResult {
    return {
      id: this.current.id,
      status: this.current.status,
      version: this.current.version,
      internalNote: this.current.internalNote,
    }
  }

  problem(status: number, code: string, detail = 'backend text that must never be shown'): ApiError {
    return new ApiError(status, code, detail)
  }

  posts(): Call[] {
    return this.calls.filter((call) => call.method !== 'GET')
  }

  reads(): Call[] {
    return this.calls.filter((call) => call.method === 'GET')
  }

  async handle(path: string, options: RequestInit | undefined): Promise<unknown> {
    const method = options?.method ?? 'GET'
    const body = options?.body ? JSON.parse(String(options.body)) : undefined
    this.calls.push({ path, method, body })
    const base = `/api/business/appointments/${APPOINTMENT_ONE}`
    if (path === base && method === 'GET') return this.read()
    if (path === `${base}/cancel` && method === 'POST') return this.cancel(body)
    if (path === `${base}/internal-note` && method === 'PUT') return this.note(body)
    throw new Error(`unexpected ${method} ${path}`)
  }
}
