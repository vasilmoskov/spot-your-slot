import { ApiError } from '../../identity/api'

const SAFE_SCHEDULE_CODES = new Set([
  'VALIDATION_ERROR',
  'ACCESS_DENIED',
  'ACTIVE_BUSINESS_REQUIRED',
  'STAFF_MEMBER_NOT_FOUND',
  'STAFF_MEMBER_INACTIVE',
  'WORKING_SCHEDULE_CONCURRENT_UPDATE',
  'BUSINESS_SUSPENDED',
])

export function safeScheduleError(error: unknown, fallback: string): string {
  if (error instanceof ApiError && SAFE_SCHEDULE_CODES.has(error.code)) {
    return error.detail
  }
  return fallback
}

export function isAuthenticationRequired(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401
}

export function isConcurrentUpdate(error: unknown): error is ApiError {
  return error instanceof ApiError && error.code === 'WORKING_SCHEDULE_CONCURRENT_UPDATE'
}
