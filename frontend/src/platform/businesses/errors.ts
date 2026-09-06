import { ApiError } from '../../identity/api'

const SAFE_BUSINESS_CODES = new Set([
  'VALIDATION_ERROR',
  'ACCESS_DENIED',
  'BUSINESS_NOT_FOUND',
  'BUSINESS_SLUG_CONFLICT',
  'BUSINESS_INVALID_LIFECYCLE',
  'BUSINESS_MISSING_ACTIVE_OWNER',
  'BUSINESS_CONCURRENT_UPDATE',
])

export function safeBusinessError(error: unknown, fallback: string): string {
  if (
    error instanceof ApiError &&
    error.code === 'BUSINESS_MISSING_ACTIVE_OWNER'
  ) {
    return 'За да активирате бизнеса, собственикът трябва първо да приеме поканата.'
  }
  if (error instanceof ApiError && SAFE_BUSINESS_CODES.has(error.code)) {
    return error.detail
  }
  return fallback
}

export function isAuthenticationRequired(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401
}

export function isConcurrentUpdate(error: unknown): error is ApiError {
  return error instanceof ApiError && error.code === 'BUSINESS_CONCURRENT_UPDATE'
}
