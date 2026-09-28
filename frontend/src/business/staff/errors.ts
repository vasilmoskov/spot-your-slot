import { ApiError } from '../../identity/api'

const SAFE_STAFF_CODES = new Set([
  'VALIDATION_ERROR',
  'ACCESS_DENIED',
  'ACTIVE_BUSINESS_REQUIRED',
  'STAFF_MEMBER_NOT_FOUND',
  'SERVICE_NOT_FOUND',
  'STAFF_MEMBER_INVALID_LIFECYCLE',
  'STAFF_MEMBER_CONCURRENT_UPDATE',
  'SERVICE_INACTIVE',
  'BUSINESS_SUSPENDED',
])

export function safeStaffError(error: unknown, fallback: string): string {
  if (error instanceof ApiError && SAFE_STAFF_CODES.has(error.code)) {
    return error.detail
  }
  return fallback
}

export function isAuthenticationRequired(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401
}

export function isConcurrentUpdate(error: unknown): error is ApiError {
  return error instanceof ApiError && error.code === 'STAFF_MEMBER_CONCURRENT_UPDATE'
}
