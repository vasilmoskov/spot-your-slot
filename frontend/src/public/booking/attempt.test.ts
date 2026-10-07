import { describe, expect, it, vi } from 'vitest'
import type { ConfirmedBooking, SubmissionOutcome } from './api'
import {
  INITIAL_SUBMISSION,
  afterEdit,
  applyOutcome,
  attemptForSubmit,
  buildRequestBody,
  isFrozen,
  mayHaveBooking,
  newAttemptId,
  retryDelayMs,
  type Attempt,
  type BookingChoice,
  type BookingDetails,
  type Submission,
} from './attempt'

const choice: BookingChoice = {
  serviceId: '11111111-1111-4111-8111-111111111111',
  staffId: null,
  slot: { start: '2026-10-07T10:00:00+03:00', end: '2026-10-07T10:45:00+03:00' },
}
const details: BookingDetails = {
  displayName: '  Иван Петров ',
  phone: ' 0888 123 456 ',
  email: '  ',
  note: '  ',
}
const booking: ConfirmedBooking = {
  reference: 'K7M2Q9XW4B',
  status: 'CONFIRMED',
  service: { name: 'Подстригване', durationMinutes: 45, price: 25 },
  staff: { displayName: 'Мария' },
  start: choice.slot.start,
  end: choice.slot.end,
  timezone: 'Europe/Sofia',
}
const NOW = 1_000_000

function attempt(possiblyCommitted = false): Attempt {
  return { id: 'a', body: '{"x":1}', possiblyCommitted }
}

describe('the request body', () => {
  it('contains only the documented fields, trimmed, with blank optional values as null', () => {
    const body = JSON.parse(buildRequestBody('id-1', choice, details))
    expect(Object.keys(body)).toEqual(['attemptId', 'serviceId', 'staffMemberId', 'start', 'customer', 'note'])
    expect(body).toEqual({
      attemptId: 'id-1',
      serviceId: choice.serviceId,
      staffMemberId: null,
      start: choice.slot.start,
      customer: { displayName: 'Иван Петров', phone: '0888 123 456', email: null },
      note: null,
    })
  })

  it('is byte-identical for the same input', () => {
    expect(buildRequestBody('id-1', choice, details)).toBe(buildRequestBody('id-1', choice, details))
  })
})

describe('the attempt identifier', () => {
  it('is a lowercase UUID version 4 from the secure generator', () => {
    for (let index = 0; index < 20; index += 1) {
      expect(newAttemptId()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    }
  })

  it('falls back to getRandomValues without randomUUID, and never to Math.random', () => {
    const random = vi.spyOn(Math, 'random')
    vi.stubGlobal('crypto', { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) })
    expect(newAttemptId()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    vi.stubGlobal('crypto', {})
    expect(() => newAttemptId()).toThrow()
    expect(random).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
    random.mockRestore()
  })
})

describe('choosing the attempt of a submit', () => {
  const make = () => 'new-id'

  it('draws a new attempt only from an idle state', () => {
    const created = attemptForSubmit(INITIAL_SUBMISSION, choice, details, make)
    expect(created).toMatchObject({ id: 'new-id', possiblyCommitted: false })
    expect(JSON.parse(created!.body).attemptId).toBe('new-id')
  })

  it('reuses a retryable or uncertain attempt unchanged, whatever the form now holds', () => {
    const retryable: Submission = { phase: 'retryable', attempt: attempt(), cause: 'rolled-back', retryAt: null }
    const uncertain: Submission = { phase: 'uncertain', attempt: attempt(true), retryAt: null, cause: 'unknown' }
    const generator = vi.fn(make)
    expect(attemptForSubmit(retryable, choice, details, generator)).toBe(retryable.attempt)
    expect(attemptForSubmit(uncertain, { ...choice, staffId: 'other' }, details, generator)).toBe(uncertain.attempt)
    expect(generator).not.toHaveBeenCalled()
  })

  it('sends nothing while a send is in flight or after a confirmation', () => {
    expect(attemptForSubmit({ phase: 'sending', attempt: attempt() }, choice, details, make)).toBeNull()
    expect(
      attemptForSubmit({ phase: 'confirmed', booking, replayed: false }, choice, details, make),
    ).toBeNull()
  })
})

describe('what each outcome proves', () => {
  const apply = (outcome: SubmissionOutcome, source = attempt()) => applyOutcome(source, outcome, NOW)

  it('an uncertain result freezes the attempt and marks it possibly committed', () => {
    expect(apply({ kind: 'uncertain', retryAfterSeconds: 2 })).toEqual({
      phase: 'uncertain',
      attempt: { ...attempt(), possiblyCommitted: true },
      retryAt: NOW + 2000,
      cause: 'unknown',
    })
  })

  it('a first-send proven rollback keeps the clean attempt retryable', () => {
    expect(apply({ kind: 'rolled-back', retryAfterSeconds: null })).toEqual({
      phase: 'retryable',
      attempt: attempt(false),
      cause: 'rolled-back',
      retryAt: null,
    })
  })

  it('a first-send rate limit keeps the clean attempt retryable', () => {
    expect(apply({ kind: 'rate-limited', retryAfterSeconds: 30 })).toMatchObject({
      phase: 'retryable',
      cause: 'rate-limited',
      retryAt: NOW + 30_000,
    })
  })

  it('a first-send documented rejection drops the attempt, so the next submit draws a new one', () => {
    const next = apply({ kind: 'rejected', reason: 'slot-unavailable' })
    expect(next).toEqual({ phase: 'idle', notice: { reason: 'slot-unavailable' } })
    expect(attemptForSubmit(next, choice, details, () => 'fresh')?.id).toBe('fresh')
  })

  it('keeps the field errors of a first-send validation rejection', () => {
    expect(apply({ kind: 'rejected', reason: 'validation', fieldErrors: { phone: 'x' } })).toEqual({
      phase: 'idle',
      notice: { reason: 'validation', fieldErrors: { phone: 'x' } },
    })
  })

  it('a mismatch is never an ordinary rejection: it freezes the attempt, even as the first answer', () => {
    expect(apply({ kind: 'rejected', reason: 'attempt-mismatch' })).toEqual({
      phase: 'uncertain',
      attempt: attempt(true),
      retryAt: null,
      cause: 'mismatch',
    })
    expect(apply({ kind: 'rejected', reason: 'attempt-mismatch' }, attempt(true))).toMatchObject({
      phase: 'uncertain',
      cause: 'mismatch',
      attempt: { possiblyCommitted: true },
    })
  })

  // After an uncertain send no later answer except a success may resolve the attempt.
  const later: [string, SubmissionOutcome][] = [
    ['a proven rollback', { kind: 'rolled-back', retryAfterSeconds: null }],
    ['a rate limit', { kind: 'rate-limited', retryAfterSeconds: 5 }],
    ['an uncertain result', { kind: 'uncertain', retryAfterSeconds: null }],
    ['a slot rejection', { kind: 'rejected', reason: 'slot-unavailable' }],
    ['a Service rejection', { kind: 'rejected', reason: 'service-unavailable' }],
    ['a StaffMember rejection', { kind: 'rejected', reason: 'staff-unavailable' }],
    ['an identity conflict', { kind: 'rejected', reason: 'identity-conflict' }],
    ['a validation rejection', { kind: 'rejected', reason: 'validation', fieldErrors: { phone: 'x' } }],
    ['an unexpected rejection', { kind: 'rejected', reason: 'unexpected' }],
    ['an unavailable Business', { kind: 'rejected', reason: 'business-unavailable' }],
    ['a mismatch', { kind: 'rejected', reason: 'attempt-mismatch' }],
  ]

  it.each(later)('%s after an uncertain send keeps the same frozen, possibly committed attempt', (_name, outcome) => {
    const unresolved = attempt(true)
    const next = apply(outcome, unresolved)
    expect(next.phase).toBe('uncertain')
    if (next.phase !== 'uncertain') return
    expect(next.attempt).toBe(unresolved)
    expect(next.attempt.possiblyCommitted).toBe(true)
    expect(mayHaveBooking(next)).toBe(true)
    expect(isFrozen(next)).toBe(true)
    // An edit changes nothing and a submit reuses the very same attempt.
    expect(afterEdit(next)).toBe(next)
    expect(attemptForSubmit(next, choice, details, () => 'never')).toBe(unresolved)
  })

  // The sequences that start with a first mismatch: it must itself carry the unresolved mark.
  describe('after a first mismatch', () => {
    const first = () => apply({ kind: 'rejected', reason: 'attempt-mismatch' })
    const frozenAttempt = () => {
      const state = first()
      if (state.phase !== 'uncertain') throw new Error('expected a frozen attempt')
      return state.attempt
    }

    it('marks the same ID and bytes possibly committed', () => {
      expect(frozenAttempt()).toEqual({ ...attempt(), possiblyCommitted: true })
      expect(mayHaveBooking(first())).toBe(true)
    })

    it.each([
      ['a rollback', { kind: 'rolled-back', retryAfterSeconds: null }],
      ['a rate limit', { kind: 'rate-limited', retryAfterSeconds: 5 }],
      ['an ordinary rejection', { kind: 'rejected', reason: 'slot-unavailable' }],
      ['an unavailable Business', { kind: 'rejected', reason: 'business-unavailable' }],
      ['an unknown response', { kind: 'uncertain', retryAfterSeconds: null }],
    ] as [string, SubmissionOutcome][])('then %s keeps it frozen, uneditable and undiscarded', (_name, outcome) => {
      const marked = frozenAttempt()
      const next = apply(outcome, marked)
      expect(next.phase).toBe('uncertain')
      if (next.phase !== 'uncertain') return
      expect(next.attempt).toBe(marked)
      expect(next.attempt.possiblyCommitted).toBe(true)
      expect(isFrozen(next)).toBe(true)
      expect(afterEdit(next)).toBe(next)
      expect(attemptForSubmit(next, choice, details, () => 'never')).toBe(marked)
    })

    it.each([
      ['a valid replay', { kind: 'success', replayed: true, booking }],
      ['a cancelled replay', { kind: 'success', replayed: true, booking: { ...booking, status: 'CANCELLED' } }],
    ] as [string, SubmissionOutcome][])('then %s resolves it', (_name, outcome) => {
      expect(apply(outcome, frozenAttempt()).phase).toBe('confirmed')
    })
  })

  it.each([
    ['a created booking', { kind: 'success', replayed: false, booking }],
    ['a replay', { kind: 'success', replayed: true, booking }],
    ['a cancelled replay', { kind: 'success', replayed: true, booking: { ...booking, status: 'CANCELLED' } }],
  ] as [string, SubmissionOutcome][])('%s resolves an uncertain attempt', (_name, outcome) => {
    const next = apply(outcome, attempt(true))
    expect(next.phase).toBe('confirmed')
  })

  it('a success is a confirmation with its replay flag', () => {
    expect(apply({ kind: 'success', replayed: true, booking })).toEqual({
      phase: 'confirmed',
      booking,
      replayed: true,
    })
  })

  it('an unreadable Retry-After imposes no wait and a past one is clamped to zero', () => {
    expect(retryDelayMs(apply({ kind: 'uncertain', retryAfterSeconds: null }), NOW)).toBe(0)
    const waiting = apply({ kind: 'uncertain', retryAfterSeconds: 5 })
    expect(retryDelayMs(waiting, NOW)).toBe(5000)
    expect(retryDelayMs(waiting, NOW + 9000)).toBe(0)
  })
})

describe('freezing and editing', () => {
  it('freezes while sending, uncertain or confirmed and warns only while a booking may exist', () => {
    const sending: Submission = { phase: 'sending', attempt: attempt() }
    const uncertain: Submission = { phase: 'uncertain', attempt: attempt(true), retryAt: null, cause: 'unknown' }
    const confirmed: Submission = { phase: 'confirmed', booking, replayed: false }
    const retryable: Submission = { phase: 'retryable', attempt: attempt(), cause: 'rolled-back', retryAt: null }
    expect([sending, uncertain, confirmed].map(isFrozen)).toEqual([true, true, true])
    expect([retryable, INITIAL_SUBMISSION].map(isFrozen)).toEqual([false, false])
    expect([sending, uncertain].map(mayHaveBooking)).toEqual([true, true])
    expect([confirmed, retryable, INITIAL_SUBMISSION].map(mayHaveBooking)).toEqual([false, false, false])
  })

  it('an edit drops a retryable attempt and a stale notice, and leaves a frozen state alone', () => {
    const retryable: Submission = { phase: 'retryable', attempt: attempt(), cause: 'rolled-back', retryAt: null }
    const uncertain: Submission = { phase: 'uncertain', attempt: attempt(true), retryAt: null, cause: 'unknown' }
    expect(afterEdit(retryable)).toBe(INITIAL_SUBMISSION)
    expect(afterEdit({ phase: 'idle', notice: { reason: 'validation' } })).toBe(INITIAL_SUBMISSION)
    expect(afterEdit(uncertain)).toBe(uncertain)
  })
})
