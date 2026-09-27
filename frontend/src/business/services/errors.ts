import { ApiError } from '../../identity/api'

const SAFE_SERVICE_CODES = new Set([
  'VALIDATION_ERROR',
  'ACCESS_DENIED',
  'ACTIVE_BUSINESS_REQUIRED',
  'SERVICE_NOT_FOUND',
  'SERVICE_NAME_CONFLICT',
  'SERVICE_INVALID_LIFECYCLE',
  'SERVICE_CONCURRENT_UPDATE',
  'BUSINESS_SUSPENDED',
])

export function safeServiceError(error: unknown, fallback: string): string {
  if (error instanceof ApiError && SAFE_SERVICE_CODES.has(error.code)) {
    return error.detail
  }
  return fallback
}

export function isAuthenticationRequired(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401
}

export function isConcurrentUpdate(error: unknown): error is ApiError {
  return error instanceof ApiError && error.code === 'SERVICE_CONCURRENT_UPDATE'
}
