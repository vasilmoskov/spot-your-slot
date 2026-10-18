import { ApiError } from '../../identity/api'

// Frontend-owned Bulgarian wording for the stable reschedule problem codes (Phase 5 and the Phase 9
// option reads). The backend text is never rendered; an unknown code is the unresolved 'unexpected'.
export type RescheduleFailure =
  | 'validation'
  | 'access'
  | 'business-not-active'
  | 'business-suspended'
  | 'not-found'
  | 'concurrent-update'
  | 'state-conflict'
  | 'staff-unavailable'
  | 'slot-unavailable'
  | 'temporarily-unavailable'
  | 'uncertain'
  | 'unexpected'

export const RESCHEDULE_TEXT: Record<RescheduleFailure, string> = {
  validation: 'Проверете въведените данни.',
  access: 'Нямате достъп до тази операция.',
  'business-not-active': 'Бизнесът не е активен. Часовете могат да се променят само в активен бизнес.',
  'business-suspended': 'Спрян бизнес може само да преглежда данните си.',
  'not-found': 'Часът не е намерен.',
  'concurrent-update':
    'Часът е променен междувременно. Прегледайте актуалните данни и изберете промяната отново.',
  'state-conflict': 'Часът не може да бъде променен, защото е отменен или вече е започнал.',
  'staff-unavailable': 'Избраният член на екипа не е наличен за тази промяна. Изберете отново.',
  'slot-unavailable': 'Избраният час вече не е свободен. Изберете друг час.',
  'temporarily-unavailable':
    'Промяната не беше направена. Часът е непроменен. Опитайте отново след малко.',
  uncertain:
    'Не е ясно дали промяната е направена. Проверете часа в графика, преди да опитате отново.',
  unexpected:
    'Възникна неочаквана грешка. Проверете часа в графика, преди да опитате отново.',
}

const BY_CODE: Record<string, RescheduleFailure> = {
  VALIDATION_ERROR: 'validation',
  ACCESS_DENIED: 'access',
  ACTIVE_BUSINESS_REQUIRED: 'access',
  BUSINESS_NOT_ACTIVE: 'business-not-active',
  BUSINESS_SUSPENDED: 'business-suspended',
  APPOINTMENT_NOT_FOUND: 'not-found',
  APPOINTMENT_CONCURRENT_UPDATE: 'concurrent-update',
  APPOINTMENT_STATE_CONFLICT: 'state-conflict',
  APPOINTMENT_STAFF_UNAVAILABLE: 'staff-unavailable',
  APPOINTMENT_SLOT_UNAVAILABLE: 'slot-unavailable',
  APPOINTMENT_TEMPORARILY_UNAVAILABLE: 'temporarily-unavailable',
  APPOINTMENT_OUTCOME_UNCERTAIN: 'uncertain',
}

export function isAuthenticationRequired(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401
}

/**
 * The result of a failed reschedule POST. A response that never arrived (offline, reset) is
 * indistinguishable from a lost answer to a request that was applied, so it is the uncertain outcome:
 * never "nothing was changed". A success status whose body could not be read is uncertain as well.
 */
export function classifyRescheduleFailure(error: unknown): RescheduleFailure {
  if (error instanceof ApiError) {
    if (error.status >= 200 && error.status < 300) return 'uncertain'
    return BY_CODE[error.code] ?? 'unexpected'
  }
  return 'uncertain'
}

/**
 * The results after which a committed change cannot be ruled out. They freeze the submitted review:
 * another POST before the Appointment was checked could apply the same intent to a state the owner
 * never saw. A deterministic rejection and a temporary refusal are known rollbacks and are not unresolved.
 */
export function isUnresolved(kind: RescheduleFailure): boolean {
  return kind === 'uncertain' || kind === 'unexpected'
}

/** The kind of a failed READ of the Appointment or of its options; null for a plain, retryable failure. */
export function readFailureOf(error: unknown): RescheduleFailure | null {
  if (!(error instanceof ApiError)) return null
  const kind = BY_CODE[error.code]
  // A malformed identifier is a validation error: for a read it is as good as a missing Appointment.
  if (kind === 'validation') return 'not-found'
  return kind ?? null
}
