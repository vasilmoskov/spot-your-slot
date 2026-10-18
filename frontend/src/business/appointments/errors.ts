import { ApiError } from '../../identity/api'

// Frontend-owned Bulgarian wording for the stable create problem codes (Phase 4). The backend text is
// never rendered; an unknown code uses the generic unexpected-failure text.
export type FailureKind =
  | 'validation'
  | 'access'
  | 'business-not-active'
  | 'business-suspended'
  | 'customer-missing'
  | 'service-unavailable'
  | 'staff-unavailable'
  | 'review-changed'
  | 'slot-unavailable'
  | 'temporarily-unavailable'
  | 'uncertain'
  | 'unexpected'

export const FAILURE_TEXT: Record<FailureKind, string> = {
  validation: 'Проверете въведените данни.',
  access: 'Нямате достъп до тази операция.',
  'business-not-active': 'Бизнесът не е активен. Часове могат да се създават само в активен бизнес.',
  'business-suspended': 'Спрян бизнес може само да преглежда данните си.',
  'customer-missing': 'Клиентът не е намерен. Изберете клиент отново.',
  'service-unavailable': 'Някоя от избраните услуги вече не е налична. Прегледайте избора на услуги.',
  'staff-unavailable': 'Избраният член на екипа не е наличен за тези услуги. Изберете отново.',
  'review-changed': 'Услугите са променени. Прегледайте ги отново и опитайте пак.',
  'slot-unavailable': 'Избраният час вече не е свободен. Изберете друг час.',
  'temporarily-unavailable': 'Часът не беше създаден. Опитайте отново след малко.',
  uncertain:
    'Не е ясно дали часът е създаден. Проверете графика, преди да опитате отново, за да не създадете втори час.',
  unexpected: 'Възникна неочаквана грешка. Проверете графика, преди да опитате отново.',
}

const BY_CODE: Record<string, FailureKind> = {
  VALIDATION_ERROR: 'validation',
  ACCESS_DENIED: 'access',
  ACTIVE_BUSINESS_REQUIRED: 'access',
  BUSINESS_NOT_ACTIVE: 'business-not-active',
  BUSINESS_SUSPENDED: 'business-suspended',
  CUSTOMER_NOT_FOUND: 'customer-missing',
  APPOINTMENT_SERVICE_UNAVAILABLE: 'service-unavailable',
  APPOINTMENT_STAFF_UNAVAILABLE: 'staff-unavailable',
  APPOINTMENT_REVIEW_CHANGED: 'review-changed',
  APPOINTMENT_SLOT_UNAVAILABLE: 'slot-unavailable',
  APPOINTMENT_TEMPORARILY_UNAVAILABLE: 'temporarily-unavailable',
  APPOINTMENT_OUTCOME_UNCERTAIN: 'uncertain',
}

export function isAuthenticationRequired(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401
}

/**
 * The result of a failed create POST. A response that never arrived (offline, reset) is
 * indistinguishable from a lost answer to a request that was applied, so it is the uncertain outcome:
 * never "nothing was created".
 */
export function classifyCreateFailure(error: unknown): FailureKind {
  if (error instanceof ApiError) {
    // A success status whose body could not be read: the Appointment may well exist.
    if (error.status >= 200 && error.status < 300) return 'uncertain'
    return BY_CODE[error.code] ?? 'unexpected'
  }
  return 'uncertain'
}

/**
 * The results after which the creation cannot be ruled out. They freeze the submitted form: the
 * manual create has no idempotency, so another POST before the calendar was checked could duplicate
 * the visit. A known rollback (a deterministic rejection, or a temporary refusal) is not one of them.
 */
export function isUnresolved(kind: FailureKind): boolean {
  return kind === 'uncertain' || kind === 'unexpected'
}

/** A failed read of the choices (never the create): safe text, no backend wording. */
export function readFailureText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'BUSINESS_NOT_ACTIVE') return FAILURE_TEXT['business-not-active']
    if (error.code === 'BUSINESS_SUSPENDED') return FAILURE_TEXT['business-suspended']
    if (error.code === 'ACCESS_DENIED') return FAILURE_TEXT.access
    return fallback
  }
  return 'Няма връзка със сървъра. Проверете връзката си и опитайте отново.'
}

export function readFailureKind(error: unknown): FailureKind | null {
  if (!(error instanceof ApiError)) return null
  const kind = BY_CODE[error.code]
  return kind === 'business-not-active' || kind === 'business-suspended' || kind === 'access' ? kind : null
}
