import { describe, expect, it } from 'vitest'
import { ApiError } from '../../identity/api'
import { isAuthenticationRequired, isConcurrentUpdate, safeServiceError } from './errors'

describe('services error mapping', () => {
  it('surfaces the safe backend detail for approved codes', () => {
    const error = new ApiError(409, 'SERVICE_NAME_CONFLICT', 'Вече съществува услуга с това име.')
    expect(safeServiceError(error, 'fallback')).toBe('Вече съществува услуга с това име.')
  })

  it('falls back for unapproved codes and non-ApiError values', () => {
    expect(
      safeServiceError(new ApiError(500, 'UNEXPECTED', 'internal'), 'fallback'),
    ).toBe('fallback')
    expect(safeServiceError(new Error('network'), 'fallback')).toBe('fallback')
  })

  it('detects authentication-required and concurrent-update errors', () => {
    expect(isAuthenticationRequired(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))).toBe(true)
    expect(isAuthenticationRequired(new ApiError(403, 'ACCESS_DENIED', 'Отказан достъп.'))).toBe(false)
    expect(isConcurrentUpdate(new ApiError(409, 'SERVICE_CONCURRENT_UPDATE', 'Обновете.'))).toBe(true)
    expect(isConcurrentUpdate(new ApiError(409, 'SERVICE_NAME_CONFLICT', 'Конфликт.'))).toBe(false)
  })
})
