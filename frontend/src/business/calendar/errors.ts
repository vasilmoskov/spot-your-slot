import { ApiError } from '../../identity/api'

// Frontend-owned Bulgarian wording for the stable calendar problem codes. The backend text is never
// rendered; an unknown code uses the caller's generic fallback.
const MESSAGES: Record<string, string> = {
  VALIDATION_ERROR: 'Проверете въведените данни.',
  APPOINTMENT_NOT_FOUND: 'Часът не е намерен.',
  CUSTOMER_NOT_FOUND: 'Клиентът не е намерен.',
  STAFF_MEMBER_NOT_FOUND: 'Членът на екипа не е намерен.',
  ACCESS_DENIED: 'Нямате достъп до тази операция.',
  ACTIVE_BUSINESS_REQUIRED: 'Изберете бизнес, за да продължите.',
  INTERNAL_ERROR: 'Възникна неочаквана грешка.',
}

export const APPOINTMENT_NOT_FOUND_MESSAGE = MESSAGES['APPOINTMENT_NOT_FOUND']!
export const CALENDAR_LOAD_ERROR = 'Графикът не може да бъде зареден.'
export const NETWORK_ERROR = 'Няма връзка със сървъра. Проверете връзката си и опитайте отново.'

export function isAuthenticationRequired(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401
}

export function isStaffMemberMissing(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'STAFF_MEMBER_NOT_FOUND'
}

/** A fetch that never produced an HTTP response (offline, DNS, reset). */
export function isNetworkFailure(error: unknown): boolean {
  return !(error instanceof ApiError) && !(error instanceof DOMException && error.name === 'AbortError')
}

export function isMissingAppointment(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    (error.status === 404 ||
      error.code === 'APPOINTMENT_NOT_FOUND' ||
      (error.status === 400 && error.code === 'VALIDATION_ERROR'))
  )
}

/** Safe text for a failed calendar-family read; never the backend text. */
export function calendarFailureMessage(error: unknown, fallback = CALENDAR_LOAD_ERROR): string {
  if (isNetworkFailure(error)) return NETWORK_ERROR
  if (error instanceof ApiError) {
    if (error.code === 'ACCESS_DENIED') return MESSAGES['ACCESS_DENIED']!
    if (error.status >= 500) return MESSAGES['INTERNAL_ERROR']!
  }
  return fallback
}
