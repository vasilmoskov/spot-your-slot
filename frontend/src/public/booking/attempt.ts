import { trimApproved } from '../../business/text'
import type {
  ConfirmedBooking,
  Slot,
  SubmissionOutcome,
  SubmissionRejection,
  BookingFieldErrors,
} from './api'

// The submission state machine of the guest-booking journey (ADR-0024, ADR-0026). It is pure: it
// holds no timer, request or storage, so every transition is a plain function of the previous state.
//
// Attempt lifetime. One attempt is one attempt ID together with the exact request body it was first
// sent with. The ID is created when the guest submits a review that has no live attempt, and it
// lives only in memory. It is kept, unchanged and with the same frozen body, for every resend.
//
// `possiblyCommitted` is set when a send ends UNCERTAIN (a transport failure, an unreadable or
// malformed success, an unknown 5xx, a 408, `BOOKING_OUTCOME_UNCERTAIN`, or any answer that cannot
// safely establish the result). Once set it is sticky: a LATER request's rollback, 429, unavailable
// answer, validation error, mismatch or any other rejection says nothing about the EARLIER send, so
// none of them clears it. The attempt then stays frozen (same ID, same bytes, no edits, no new
// attempt) until a valid successful response (201, or a 200 replay including CANCELLED) resolves it,
// or the guest abandons the journey through the warned restart or leave flow.
//
// Only a first send, which no earlier send can contradict, may prove a failure:
//   - a documented 4xx rejection (slot, Service, StaffMember, identity conflict, validation, Business
//     unavailable, an oversized or unsupported body) proves that nothing was created: the attempt is
//     dropped and the next submit creates a NEW attempt ID;
//   - `BOOKING_TEMPORARILY_UNAVAILABLE` is a proven rollback: the attempt may be sent again, or the
//     guest may change the choices, which drops it;
//   - a `429` was refused before any booking work: the attempt is kept and sent again later;
//   - `BOOKING_ATTEMPT_MISMATCH` is NOT such a proof: it means an Appointment already exists for this
//     attempt ID with other data, so it is treated as unresolved: frozen and marked possibly committed.
// A new ID is therefore never created while the result of any send of the previous attempt may be a booking.

export type BookingDetails = {
  displayName: string
  phone: string
  email: string
  note: string
}

export type BookingChoice = {
  serviceId: string
  // null is "no preference"; it is sent as null, never as an assigned member.
  staffId: string | null
  slot: Slot
}

export type Attempt = {
  id: string
  // The exact JSON text of the first send; every resend posts this very string.
  body: string
  // True once any send of this attempt ended uncertain, until the server gives a verdict for it.
  possiblyCommitted: boolean
}

export type RejectionNotice = { reason: SubmissionRejection; fieldErrors?: BookingFieldErrors }

// What the guest is told about a frozen attempt. Every cause keeps the attempt unresolved.
export type UncertainCause =
  | 'unknown' // a send ended without a verdict, or a later answer proved nothing
  | 'rate-limited' // the latest send was refused by the limiter
  | 'mismatch' // the server reports a different request under this attempt ID
  | 'business-unavailable' // the Business page became unavailable after the earlier uncertain send

export type Submission =
  | { phase: 'idle'; notice: RejectionNotice | null }
  | { phase: 'sending'; attempt: Attempt }
  | { phase: 'retryable'; attempt: Attempt; cause: 'rolled-back' | 'rate-limited'; retryAt: number | null }
  | { phase: 'uncertain'; attempt: Attempt; retryAt: number | null; cause: UncertainCause }
  | { phase: 'confirmed'; booking: ConfirmedBooking; replayed: boolean }

export const INITIAL_SUBMISSION: Submission = { phase: 'idle', notice: null }

/** The one new field of the contract: an optional note of at most 500 code points. */
export const NOTE_MAX_CODE_POINTS = 500

function blankToNull(value: string): string | null {
  const trimmed = trimApproved(value)
  return trimmed === '' ? null : trimmed
}

/**
 * The exact request body for a new attempt. Only the documented fields exist: no Business, price,
 * duration, end, status or timezone. Text values are edge-trimmed, blank optional values are null.
 */
export function buildRequestBody(
  attemptId: string,
  choice: BookingChoice,
  details: BookingDetails,
): string {
  return JSON.stringify({
    attemptId,
    serviceId: choice.serviceId,
    staffMemberId: choice.staffId,
    start: choice.slot.start,
    customer: {
      displayName: trimApproved(details.displayName),
      phone: blankToNull(details.phone),
      email: blankToNull(details.email),
    },
    note: blankToNull(details.note),
  })
}

/**
 * A cryptographically secure lowercase UUID version 4. `randomUUID` needs a secure context, so the
 * fallback builds the same value from `getRandomValues`; there is deliberately no `Math.random`
 * fallback, and a browser without either cannot submit.
 */
export function newAttemptId(): string {
  const source = globalThis.crypto
  if (typeof source?.randomUUID === 'function') return source.randomUUID()
  if (typeof source?.getRandomValues !== 'function') {
    throw new Error('A secure random number generator is required.')
  }
  const bytes = source.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function retryAt(seconds: number | null, now: number): number | null {
  return seconds !== null && seconds > 0 ? now + seconds * 1000 : null
}

/** The attempt for a submit: the live one when it is the same request, otherwise a new one. */
export function attemptForSubmit(
  submission: Submission,
  choice: BookingChoice,
  details: BookingDetails,
  makeId: () => string,
): Attempt | null {
  if (submission.phase === 'sending' || submission.phase === 'confirmed') return null
  // A frozen or retryable attempt is sent again exactly as it was, whatever the form now holds.
  if (submission.phase === 'uncertain' || submission.phase === 'retryable') return submission.attempt
  const id = makeId()
  return { id, body: buildRequestBody(id, choice, details), possiblyCommitted: false }
}

/**
 * The next state when one send of `attempt` produced `outcome`. Once `attempt.possiblyCommitted` is
 * true only a success can resolve it; every other answer keeps the same attempt frozen.
 */
export function applyOutcome(attempt: Attempt, outcome: SubmissionOutcome, now: number): Submission {
  if (outcome.kind === 'success') {
    return { phase: 'confirmed', booking: outcome.booking, replayed: outcome.replayed }
  }
  const frozen = (cause: UncertainCause, marked: Attempt = attempt, seconds: number | null = null): Submission => ({
    phase: 'uncertain',
    attempt: marked,
    retryAt: retryAt(seconds, now),
    cause,
  })
  const wait = outcome.kind === 'rejected' ? null : outcome.retryAfterSeconds

  if (outcome.kind === 'uncertain') {
    return frozen('unknown', attempt.possiblyCommitted ? attempt : { ...attempt, possiblyCommitted: true }, wait)
  }
  if (attempt.possiblyCommitted) {
    // A later refusal, rollback or rejection cannot undo an earlier uncertain send.
    if (outcome.kind === 'rate-limited') return frozen('rate-limited', attempt, wait)
    if (outcome.kind === 'rejected') {
      if (outcome.reason === 'attempt-mismatch') return frozen('mismatch')
      if (outcome.reason === 'business-unavailable') return frozen('business-unavailable')
    }
    return frozen('unknown', attempt, wait)
  }

  switch (outcome.kind) {
    case 'rate-limited':
      // Refused before any booking work, and no earlier send exists.
      return { phase: 'retryable', attempt, cause: 'rate-limited', retryAt: retryAt(wait, now) }
    case 'rolled-back':
      return { phase: 'retryable', attempt, cause: 'rolled-back', retryAt: retryAt(wait, now) }
    case 'rejected':
      // A mismatch does not prove that no Appointment exists, so it is never an ordinary rejection:
      // it marks the attempt possibly committed, which only a success or a warned abandonment clears.
      if (outcome.reason === 'attempt-mismatch') {
        return frozen('mismatch', { ...attempt, possiblyCommitted: true })
      }
      return {
        phase: 'idle',
        notice: outcome.fieldErrors
          ? { reason: outcome.reason, fieldErrors: outcome.fieldErrors }
          : { reason: outcome.reason },
      }
  }
}

/** True while nothing may be edited and no step may be left (the review is frozen). */
export function isFrozen(submission: Submission): boolean {
  return (
    submission.phase === 'sending' ||
    submission.phase === 'uncertain' ||
    submission.phase === 'confirmed'
  )
}

/** True when leaving must be confirmed because a booking may exist or be created. */
export function mayHaveBooking(submission: Submission): boolean {
  return submission.phase === 'sending' || submission.phase === 'uncertain'
}

/**
 * A change to any choice or detail drops a retryable attempt (a proven rollback or an unprocessed
 * send) and a stale rejection notice; a frozen state ignores the change before it gets here.
 */
export function afterEdit(submission: Submission): Submission {
  return submission.phase === 'retryable' || submission.phase === 'idle'
    ? INITIAL_SUBMISSION
    : submission
}

export function retryDelayMs(submission: Submission, now: number): number {
  if (submission.phase !== 'retryable' && submission.phase !== 'uncertain') return 0
  return submission.retryAt === null ? 0 : Math.max(0, submission.retryAt - now)
}
