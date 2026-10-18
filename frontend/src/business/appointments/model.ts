import { codePointLength, trimApproved } from '../text'
import { formatCents, priceToCents } from '../../public/booking/money'
import type { ServiceSummary } from '../services/api'
import type { CreateAppointmentBody, OfferedStart, ServiceClaim } from './api'

// The pure model of the manual-create flow. Nothing here touches the network, the DOM, the URL, or
// storage: the whole form lives in the memory of one mounted component.

export const MAX_SERVICES = 5
export const MAX_VISIT_MINUTES = 480
export const NOTE_MAX_CODE_POINTS = 500

export type Step = 'customer' | 'services' | 'staff' | 'datetime' | 'note' | 'review'

export const STEPS: readonly Step[] = ['customer', 'services', 'staff', 'datetime', 'note', 'review']

export type ChosenCustomer = { id: string; name: string }

export type FormState = {
  step: Step
  // True while the owner edits one section from the review: the next step is the first incomplete one,
  // or the review again, instead of the next step in the sequence.
  fromReview: boolean
  customer: ChosenCustomer | null
  // The chosen Service ids, in the order they were ticked; the visible order is the catalog's.
  serviceIds: string[]
  staffMemberId: string | null
  // A Business-local `yyyy-MM-dd`, never converted through the browser timezone.
  date: string
  start: OfferedStart | null
  note: string
}

export const INITIAL_STATE: FormState = {
  step: 'customer',
  fromReview: false,
  customer: null,
  serviceIds: [],
  staffMemberId: null,
  date: '',
  start: null,
  note: '',
}

export type Action =
  | { type: 'customer'; customer: ChosenCustomer | null }
  | { type: 'services'; ids: string[] }
  | { type: 'staff'; staffMemberId: string | null }
  | { type: 'date'; date: string }
  | { type: 'start'; start: OfferedStart | null }
  | { type: 'note'; note: string }
  | { type: 'go'; step: Step; fromReview?: boolean }
  | { type: 'advance' }
  | { type: 'back' }

export function isComplete(state: FormState, step: Step): boolean {
  switch (step) {
    case 'customer':
      return state.customer !== null
    case 'services':
      return state.serviceIds.length > 0
    case 'staff':
      return state.staffMemberId !== null
    case 'datetime':
      return state.start !== null
    default:
      return true
  }
}

export function firstIncomplete(state: FormState): Step {
  return STEPS.find((step) => step !== 'review' && !isComplete(state, step)) ?? 'review'
}

function sameSet(first: readonly string[], second: readonly string[]): boolean {
  return first.length === second.length && first.every((id) => second.includes(id))
}

/**
 * The one state transition function. Upstream choices invalidate every dependent choice: a changed
 * Service set clears the StaffMember, the date, and the start; a changed StaffMember or date clears the
 * start. The Customer and the note depend on nothing.
 */
export function reduce(state: FormState, action: Action): FormState {
  switch (action.type) {
    case 'customer':
      return { ...state, customer: action.customer }
    case 'services':
      if (sameSet(state.serviceIds, action.ids)) return { ...state, serviceIds: action.ids }
      return { ...state, serviceIds: action.ids, staffMemberId: null, date: '', start: null }
    case 'staff':
      if (state.staffMemberId === action.staffMemberId) return state
      return { ...state, staffMemberId: action.staffMemberId, start: null }
    case 'date':
      if (state.date === action.date) return state
      return { ...state, date: action.date, start: null }
    case 'start':
      return { ...state, start: action.start }
    case 'note':
      return { ...state, note: action.note }
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
      const previous = STEPS[Math.max(0, STEPS.indexOf(state.step) - 1)] ?? 'customer'
      return { ...state, step: previous, fromReview: false }
    }
  }
}

/** True once the owner has entered anything that would be lost by leaving. */
export function isDirty(state: FormState): boolean {
  return (
    state.customer !== null ||
    state.serviceIds.length > 0 ||
    state.staffMemberId !== null ||
    state.date !== '' ||
    state.start !== null ||
    trimApproved(state.note) !== ''
  )
}

// ---- Services --------------------------------------------------------------------------------

export type Totals = { count: number; minutes: number; cents: number }

/** The selected Services of the catalog, always in the catalog order. */
export function selectedServices(
  catalog: readonly ServiceSummary[],
  ids: readonly string[],
): ServiceSummary[] {
  return catalog.filter((service) => ids.includes(service.id))
}

/** Exact totals: money in integer cents, minutes as an integer sum. */
export function totalsOf(services: readonly ServiceSummary[]): Totals {
  let minutes = 0
  let cents = 0
  for (const service of services) {
    minutes += service.durationMinutes
    cents += priceToCents(service.price)
  }
  return { count: services.length, minutes, cents }
}

/** «42.00 € — 75 мин.»: the price first, then the duration. */
export function summaryText(cents: number, minutes: number): string {
  return `${formatCents(cents)} — ${minutes} мин.`
}

export function serviceSummary(service: ServiceSummary): string {
  return summaryText(priceToCents(service.price), service.durationMinutes)
}

export type DisabledReason = 'count' | 'duration'

/** Why an unselected Service cannot be added; a selected Service is never disabled (it must be removable). */
export function disabledReason(
  selected: readonly ServiceSummary[],
  candidate: ServiceSummary,
): DisabledReason | null {
  if (selected.some((service) => service.id === candidate.id)) return null
  if (selected.length >= MAX_SERVICES) return 'count'
  const minutes = selected.reduce((sum, service) => sum + service.durationMinutes, 0)
  return minutes + candidate.durationMinutes > MAX_VISIT_MINUTES ? 'duration' : null
}

function compareText(first: string, second: string): number {
  return first < second ? -1 : first > second ? 1 : 0
}

/** The canonical identifier order (lowercase canonical UUID text): the order of the request. */
export function canonicalIds(ids: readonly string[]): string[] {
  return ids.map((id) => id.toLowerCase()).sort(compareText)
}

export function selectionKey(ids: readonly string[]): string {
  return canonicalIds(ids).join(',')
}

export function claimsOf(services: readonly ServiceSummary[]): ServiceClaim[] {
  return [...services]
    .map((service) => ({
      serviceId: service.id.toLowerCase(),
      reviewedServiceName: service.name,
      reviewedDurationMinutes: service.durationMinutes,
      reviewedPriceCents: priceToCents(service.price),
    }))
    .sort((first, second) => compareText(first.serviceId, second.serviceId))
}

// ---- the internal note -------------------------------------------------------------------------

// The backend rejects NUL and control characters other than tab and line feed, and unpaired surrogates.
function hasForbiddenControl(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index)
    if (code <= 8 || (code >= 11 && code <= 31) || code === 127) return true
  }
  return false
}
const UNPAIRED_SURROGATE = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/

export const NOTE_TOO_LONG = 'Бележката е твърде дълга. Съкратете я.'
export const NOTE_INVALID = 'Бележката съдържа непозволени знаци.'

/** Line breaks to LF and surrounding white space removed; blank is absent (null). Never truncates. */
export function normalizeNote(value: string): string | null {
  const unified = trimApproved(value.replace(/\r\n?/g, '\n'))
  return unified === '' ? null : unified
}

export function noteError(value: string): string | undefined {
  const note = normalizeNote(value)
  if (note === null) return undefined
  if (hasForbiddenControl(note) || UNPAIRED_SURROGATE.test(note)) return NOTE_INVALID
  return codePointLength(note) > NOTE_MAX_CODE_POINTS ? NOTE_TOO_LONG : undefined
}

// ---- the request ---------------------------------------------------------------------------------

/** The exact body of the create contract; a blank note is omitted. Nothing else is ever sent. */
export function buildBody(
  customerId: string,
  staffMemberId: string,
  start: string,
  services: readonly ServiceSummary[],
  note: string,
): CreateAppointmentBody {
  const body: CreateAppointmentBody = {
    customerId,
    staffMemberId,
    start,
    services: claimsOf(services),
  }
  const normalized = normalizeNote(note)
  if (normalized !== null) body.internalNote = normalized
  return body
}
