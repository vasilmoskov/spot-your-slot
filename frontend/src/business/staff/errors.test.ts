import { describe, expect, it } from 'vitest'
import { ApiError } from '../../identity/api'
import { isAuthenticationRequired, isConcurrentUpdate, safeStaffError } from './errors'

describe('staff error mapping', () => {
  it('surfaces the safe backend detail for approved codes', () => {
    const error = new ApiError(409, 'SERVICE_INACTIVE', 'Неактивна услуга не може да бъде добавена.')
    expect(safeStaffError(error, 'fallback')).toBe('Неактивна услуга не може да бъде добавена.')
  })

  it('falls back for unapproved codes and non-ApiError values', () => {
    expect(
      safeStaffError(new ApiError(500, 'UNEXPECTED', 'internal'), 'fallback'),
    ).toBe('fallback')
    expect(safeStaffError(new Error('network'), 'fallback')).toBe('fallback')
  })

  it('detects authentication-required and concurrent-update errors', () => {
    expect(isAuthenticationRequired(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))).toBe(true)
    expect(isAuthenticationRequired(new ApiError(403, 'ACCESS_DENIED', 'Отказан достъп.'))).toBe(false)
    expect(
      isConcurrentUpdate(new ApiError(409, 'STAFF_MEMBER_CONCURRENT_UPDATE', 'Обновете.')),
    ).toBe(true)
    expect(isConcurrentUpdate(new ApiError(409, 'SERVICE_INACTIVE', 'Конфликт.'))).toBe(false)
  })
})
