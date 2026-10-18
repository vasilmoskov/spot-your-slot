import { parseInstant } from '../../public/booking/dates'
import type { OfferedStart } from './api'
import type { RescheduleBody } from './rescheduleApi'

// The pure model of the reschedule flow. Nothing here touches the network, the DOM, the URL, or
// storage: the whole proposal lives in the memory of one mounted component.

export type Step = 'staff' | 'datetime' | 'review'
export const STEPS: readonly Step[] = ['staff', 'datetime', 'review']

export type Proposal = {
  step: Step
  // True while the owner edits one section from the review: the next step is the first incomplete one,
  // or the review again, instead of the next step in the sequence.
  fromReview: boolean
  // The StaffMember the Appointment would have (the current one is a choice like any other).
  staffMemberId: string | null
  // A Business-local `yyyy-MM-dd`, never converted through the browser timezone.
  date: string
  start: OfferedStart | null
}

/** The facts of the stored Appointment the proposal is judged against. */
export type Original = {
  staffMemberId: string
  start: string
  date: string
  version: number
}

export type Action =
  | { type: 'staff'; staffMemberId: string | null }
  | { type: 'date'; date: string }
  | { type: 'start'; start: OfferedStart | null }
  | { type: 'go'; step: Step; fromReview?: boolean }
  | { type: 'advance' }
  | { type: 'back' }

/** The proposal the form starts with: the current StaffMember when it can be kept, the original date. */
export function initialProposal(original: Original, currentAvailable: boolean): Proposal {
  return {
    step: 'staff',
    fromReview: false,
    staffMemberId: currentAvailable ? original.staffMemberId : null,
    date: original.date,
    start: null,
  }
}

export function isComplete(state: Proposal, step: Step): boolean {
  switch (step) {
    case 'staff':
      return state.staffMemberId !== null
    case 'datetime':
      return state.start !== null
    default:
      return true
  }
}

export function firstIncomplete(state: Proposal): Step {
  return STEPS.find((step) => step !== 'review' && !isComplete(state, step)) ?? 'review'
}

/**
 * The one state transition function. A changed StaffMember or date clears the chosen start: the offered
 * starts belong to exactly one StaffMember and one date, so a start can never outlive either.
 */
export function reduce(state: Proposal, action: Action): Proposal {
  switch (action.type) {
    case 'staff':
      if (state.staffMemberId === action.staffMemberId) return state
      return { ...state, staffMemberId: action.staffMemberId, start: null }
    case 'date':
      if (state.date === action.date) return state
      return { ...state, date: action.date, start: null }
    case 'start':
      return { ...state, start: action.start }
    case 'go':
      return { ...state, step: action.step, fromReview: action.fromReview ?? false }
    case 'advance': {
      if (state.fromReview) {
        const target = firstIncomplete(state)
        return { ...state, step: target, fromReview: target !== 'review' }
      }
      const next = STEPS[STEPS.indexOf(state.step) + 1] ?? 'review'
      return { ...state, step: next }
    }
    case 'back': {
      const previous = STEPS[Math.max(0, STEPS.indexOf(state.step) - 1)] ?? 'staff'
      return { ...state, step: previous, fromReview: false }
    }
  }
}

function sameInstant(first: string, second: string): boolean {
  const a = parseInstant(first)
  const b = parseInstant(second)
  return a !== null && b !== null ? a === b : first === second
}

export function staffChanged(state: Proposal, original: Original): boolean {
  return state.staffMemberId !== null && state.staffMemberId !== original.staffMemberId
}

export function startChanged(state: Proposal, original: Original): boolean {
  return state.start !== null && !sameInstant(state.start.start, original.start)
}

/** A real, effective change: another StaffMember or another start. A no-op is never submitted. */
export function isEffective(state: Proposal, original: Original): boolean {
  return (
    state.staffMemberId !== null &&
    state.start !== null &&
    (staffChanged(state, original) || startChanged(state, original))
  )
}

/**
 * True while the form retains any value the owner selected that differs from the authoritative original
 * state (`baseline`): another StaffMember, another date, or a chosen start. It is defined against the
 * original Appointment and never against whether the proposal is complete or submittable, so an
 * incomplete proposal (a start cleared by a conflict, a StaffMember cleared by a refusal) that still
 * holds another changed choice is dirty. A cleared value (no StaffMember, no date) is not a retained
 * choice; the form that holds only the original values, or nothing selected, is clean.
 */
export function isDirty(state: Proposal, baseline: Proposal): boolean {
  return (
    (state.staffMemberId !== null && state.staffMemberId !== baseline.staffMemberId) ||
    (state.date !== '' && state.date !== baseline.date) ||
    state.start !== null
  )
}

/**
 * The exact body of the Phase 5 contract. `expectedVersion` is the version of the loaded Appointment
 * and is the only place it is used; `start` and `staffMemberId` are sent only when they differ from the
 * stored values, so the body is never a no-op. Nothing else is ever sent.
 */
export function buildBody(state: Proposal, original: Original): RescheduleBody {
  const body: RescheduleBody = { expectedVersion: original.version }
  if (state.staffMemberId !== null && staffChanged(state, original)) {
    body.staffMemberId = state.staffMemberId
  }
  if (state.start !== null && startChanged(state, original)) {
    body.start = state.start.start
  }
  return body
}
