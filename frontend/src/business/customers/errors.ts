import { ApiError } from '../../identity/api'
import type { FieldErrors } from '../../ui/formValidation'
import type { CustomerField } from './validation'

// Frontend-owned Bulgarian wording for the stable Customer problem codes. The backend text is
// never rendered for these codes; an unknown code uses the caller's generic fallback.
const MESSAGES: Record<string, string> = {
  VALIDATION_ERROR: 'Проверете въведените данни.',
  CUSTOMER_CONTACT_CONFLICT: 'Телефонът или имейлът вече е записан за друг клиент.',
  CUSTOMER_CONCURRENT_UPDATE:
    'Данните за клиента са променени. Обновете данните и опитайте отново.',
  CUSTOMER_CONCURRENT_CONFLICT: 'Операцията не можа да бъде завършена. Опитайте отново.',
  BUSINESS_SUSPENDED: 'Спрян бизнес може само да преглежда данните си.',
  CUSTOMER_NOT_FOUND: 'Клиентът не е намерен.',
  ACCESS_DENIED: 'Нямате достъп до тази операция.',
  ACTIVE_BUSINESS_REQUIRED: 'Изберете бизнес, за да продължите.',
  INTERNAL_ERROR: 'Възникна неочаквана грешка.',
}

export const CUSTOMER_NOT_FOUND_MESSAGE = MESSAGES['CUSTOMER_NOT_FOUND']!

export const CUSTOMER_FIELD_MESSAGES: Record<CustomerField, string> = {
  displayName: 'Въведете име на клиента до 200 знака.',
  phone: 'Въведеният телефонен номер не е валиден.',
  email: 'Въведеният имейл адрес не е валиден.',
  contact: 'Въведете телефон или имейл.',
}

const PHONE_CONFLICT = 'Този телефонен номер вече е записан за друг клиент.'
const EMAIL_CONFLICT = 'Този имейл адрес вече е записан за друг клиент.'

export function customerErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const message = MESSAGES[error.code]
    if (message) return message
  }
  return fallback
}

/** A load failure for one Customer: unknown, foreign and malformed IDs look identical. */
export function customerLoadFailure(
  error: unknown,
  fallback: string,
): { message: string; retryable: boolean } {
  if (
    error instanceof ApiError &&
    (error.status === 404 ||
      error.code === 'CUSTOMER_NOT_FOUND' ||
      (error.status === 400 && error.code === 'VALIDATION_ERROR'))
  ) {
    return { message: CUSTOMER_NOT_FOUND_MESSAGE, retryable: false }
  }
  return { message: customerErrorMessage(error, fallback), retryable: true }
}

/**
 * Usable backend field names of a validation or contact-conflict problem, shown inline with
 * frontend-owned text. Unknown names are ignored and the backend message is never used.
 */
export function customerFieldErrors(error: unknown): FieldErrors<CustomerField> | undefined {
  if (!(error instanceof ApiError) || !error.fieldErrors) return undefined
  const named = error.fieldErrors
  const result: FieldErrors<CustomerField> = {}
  if (error.code === 'VALIDATION_ERROR') {
    for (const field of ['displayName', 'phone', 'email', 'contact'] as const) {
      if (named[field]) result[field] = CUSTOMER_FIELD_MESSAGES[field]
    }
  } else if (error.code === 'CUSTOMER_CONTACT_CONFLICT') {
    if (named['phone']) result.phone = PHONE_CONFLICT
    if (named['email']) result.email = EMAIL_CONFLICT
  } else {
    return undefined
  }
  return Object.keys(result).length > 0 ? result : undefined
}

export function isAuthenticationRequired(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401
}

export function isConcurrentUpdate(error: unknown): error is ApiError {
  return error instanceof ApiError && error.code === 'CUSTOMER_CONCURRENT_UPDATE'
}

export function isBusinessSuspended(error: unknown): error is ApiError {
  return error instanceof ApiError && error.code === 'BUSINESS_SUSPENDED'
}
