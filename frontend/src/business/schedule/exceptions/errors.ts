import { ApiError } from '../../../identity/api'
import { backendFieldErrors, type FieldErrors } from '../../../ui/formValidation'
import type { ExceptionKind } from './api'
import { EXCEPTION_BACKEND_FIELDS, type ExceptionField } from './formModel'
import { OVERLAP_MESSAGES } from './presentation'

// The interface owns every message it shows. The server's texts use the internal
// term for these records and are never displayed; only the stable code is used.
// Each entry is one sentence per line.
const CODE_MESSAGES: Record<string, readonly string[]> = {
  VALIDATION_ERROR: ['Проверете въведените данни.'],
  ACCESS_DENIED: ['Нямате достъп до тази операция.'],
  ACTIVE_BUSINESS_REQUIRED: ['Изберете бизнес, за да продължите.'],
  STAFF_MEMBER_NOT_FOUND: ['Членът на екипа не е намерен.'],
  SCHEDULE_EXCEPTION_NOT_FOUND: ['Промяната в графика не е намерена.'],
  STAFF_MEMBER_INACTIVE: [
    'Промените в графика на неактивен член на екипа не могат да бъдат променяни.',
  ],
  SCHEDULE_EXCEPTION_CONCURRENT_UPDATE: [
    'Промяната в графика е променена от друга операция.',
    'Обновете данните и опитайте отново.',
  ],
  BUSINESS_SUSPENDED: ['Спрян бизнес може само да преглежда данните си.'],
}

const GENERIC_OVERLAP = ['Избраният период се застъпва с вече добавена промяна в графика.']

// Lines joined by a newline; components render each line as its own paragraph.
export function safeExceptionError(
  error: unknown,
  fallback: string,
  kind?: ExceptionKind,
): string {
  if (error instanceof ApiError) {
    if (error.code === 'SCHEDULE_EXCEPTION_OVERLAP') {
      return (kind ? OVERLAP_MESSAGES[kind] : GENERIC_OVERLAP).join('\n')
    }
    const lines = CODE_MESSAGES[error.code]
    if (lines) return lines.join('\n')
  }
  return fallback
}

const FIELD_MESSAGES: Record<ExceptionField, string> = {
  kind: 'Изберете вид промяна.',
  staffMemberId: 'Изберете член на екипа.',
  firstDate: 'Въведете валидна дата между 01.01.2000 и 31.12.2100.',
  lastDate: 'Крайната дата не може да е преди началната и периодът е до 366 дни.',
  periods: 'Проверете периодите: до 24, начало преди край, без припокриване.',
}

// Which fields the server rejected, with the interface's own wording.
export function exceptionFieldErrors(error: unknown): FieldErrors<ExceptionField> | undefined {
  const named = backendFieldErrors(error, EXCEPTION_BACKEND_FIELDS)
  if (!named) return undefined
  const result: FieldErrors<ExceptionField> = {}
  for (const field of EXCEPTION_BACKEND_FIELDS) {
    if (named[field]) result[field] = FIELD_MESSAGES[field]
  }
  return result
}

export function isAuthenticationRequired(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401
}

export function isConcurrentUpdate(error: unknown): error is ApiError {
  return error instanceof ApiError && error.code === 'SCHEDULE_EXCEPTION_CONCURRENT_UPDATE'
}

export function isOverlapConflict(error: unknown): error is ApiError {
  return error instanceof ApiError && error.code === 'SCHEDULE_EXCEPTION_OVERLAP'
}

export function isNotFound(error: unknown): error is ApiError {
  return error instanceof ApiError && error.code === 'SCHEDULE_EXCEPTION_NOT_FOUND'
}
