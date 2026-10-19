import { ApiError } from '../../identity/api'

// Frontend-owned Bulgarian wording (Proposed until the Phase 11 review) for the stable problem codes of
// the cancellation and internal-note routes. The backend text is never rendered; an unknown code, an
// unexpected 5xx, a lost response, and an unreadable success are the unresolved kinds.
export type Operation = 'cancel' | 'note'

export type LifecycleFailure =
  | 'validation'
  | 'access'
  | 'business-not-active'
  | 'business-suspended'
  | 'not-found'
  | 'concurrent-update'
  | 'state-conflict'
  | 'temporarily-unavailable'
  | 'uncertain'
  | 'unexpected'

const CANCEL_TEXT: Record<LifecycleFailure, string> = {
  validation: 'Часът не беше отменен. Прочетете го отново и опитайте пак.',
  access: 'Нямате достъп до тази операция.',
  'business-not-active': 'Бизнесът не е активен. Часовете могат да се отменят само в активен бизнес.',
  'business-suspended': 'Спрян бизнес може само да преглежда данните си.',
  'not-found': 'Часът не е намерен.',
  'concurrent-update':
    'Часът е променен междувременно. Прегледайте актуалните данни и опитайте отново, ако все още е нужно.',
  'state-conflict': 'Часът не може да бъде отменен, защото вече е отменен или е започнал.',
  'temporarily-unavailable': 'Часът не беше отменен. Той е непроменен. Опитайте отново след малко.',
  uncertain: 'Не е ясно дали часът е отменен. Прочетете часа отново, преди да опитате пак.',
  unexpected:
    'Възникна неочаквана грешка и не е ясно дали часът е отменен. Прочетете часа отново, преди да опитате пак.',
}

const NOTE_TEXT: Record<LifecycleFailure, string> = {
  validation: 'Бележката не беше променена. Проверете я и опитайте отново.',
  access: 'Нямате достъп до тази операция.',
  'business-not-active': 'Бизнесът не е активен. Бележките могат да се променят само в активен бизнес.',
  'business-suspended': 'Спрян бизнес може само да преглежда данните си.',
  'not-found': 'Часът не е намерен.',
  'concurrent-update':
    'Часът е променен междувременно. Прегледайте актуалните данни и въведете промяната отново, ако все още е нужна.',
  'state-conflict': 'Бележката не може да бъде променена в сегашното състояние на часа.',
  'temporarily-unavailable': 'Бележката не беше променена. Опитайте отново след малко.',
  uncertain: 'Не е ясно дали бележката е променена. Прочетете часа отново, преди да опитате пак.',
  unexpected:
    'Възникна неочаквана грешка и не е ясно дали бележката е променена. Прочетете часа отново, преди да опитате пак.',
}

export function lifecycleText(operation: Operation, kind: LifecycleFailure): string {
  return (operation === 'cancel' ? CANCEL_TEXT : NOTE_TEXT)[kind]
}

/** A definite success whose authoritative re-read failed: success is claimed, the data is not shown. */
export function reloadText(operation: Operation): string {
  return operation === 'cancel'
    ? 'Часът е отменен, но актуалните данни не можаха да бъдат заредени. Прочетете часа отново.'
    : 'Бележката е запазена, но актуалните данни не можаха да бъдат заредени. Прочетете часа отново.'
}

const BY_CODE: Record<string, LifecycleFailure> = {
  VALIDATION_ERROR: 'validation',
  ACCESS_DENIED: 'access',
  ACTIVE_BUSINESS_REQUIRED: 'access',
  BUSINESS_NOT_ACTIVE: 'business-not-active',
  BUSINESS_SUSPENDED: 'business-suspended',
  APPOINTMENT_NOT_FOUND: 'not-found',
  APPOINTMENT_CONCURRENT_UPDATE: 'concurrent-update',
  APPOINTMENT_STATE_CONFLICT: 'state-conflict',
  APPOINTMENT_TEMPORARILY_UNAVAILABLE: 'temporarily-unavailable',
  APPOINTMENT_OUTCOME_UNCERTAIN: 'uncertain',
}

export function isAuthenticationRequired(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401
}

/**
 * The result of a failed mutation request. A response that never arrived (offline, reset) is
 * indistinguishable from a lost answer to a request that was applied, so it is the uncertain outcome,
 * never "nothing was changed"; so is a success status whose body could not be read.
 */
export function classifyLifecycleFailure(error: unknown): LifecycleFailure {
  if (error instanceof ApiError) {
    if (error.status >= 200 && error.status < 300) return 'uncertain'
    return BY_CODE[error.code] ?? 'unexpected'
  }
  return 'uncertain'
}

/** The results after which a committed change cannot be ruled out: they freeze the submitted operation. */
export function isUnresolved(kind: LifecycleFailure): boolean {
  return kind === 'uncertain' || kind === 'unexpected'
}

/** The results that mean the owner may not use the mutation at all from this screen. */
export function isBlocking(kind: LifecycleFailure): boolean {
  return kind === 'access' || kind === 'business-not-active' || kind === 'business-suspended'
}
