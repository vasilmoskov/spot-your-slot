import { describe, expect, it } from 'vitest'
import { ApiError } from '../../identity/api'
import { isAuthenticationRequired, isConcurrentUpdate, safeScheduleError } from './errors'

describe('schedule error mapping', () => {
  it('surfaces the safe backend detail for approved codes', () => {
    const error = new ApiError(
      409,
      'STAFF_MEMBER_INACTIVE',
      'Неактивен член на екипа не може да получи работен график.',
    )
    expect(safeScheduleError(error, 'fallback')).toBe(
      'Неактивен член на екипа не може да получи работен график.',
    )
  })

  it('falls back for unapproved codes and non-ApiError values', () => {
    expect(safeScheduleError(new ApiError(500, 'UNEXPECTED', 'internal'), 'fallback')).toBe(
      'fallback',
    )
    expect(safeScheduleError(new Error('network'), 'fallback')).toBe('fallback')
  })

  it('detects authentication-required errors', () => {
    expect(isAuthenticationRequired(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))).toBe(
      true,
    )
    expect(isAuthenticationRequired(new ApiError(403, 'ACCESS_DENIED', 'Отказан достъп.'))).toBe(
      false,
    )
  })

  it('detects the schedule-specific concurrent-update code only', () => {
    expect(
      isConcurrentUpdate(
        new ApiError(409, 'WORKING_SCHEDULE_CONCURRENT_UPDATE', 'Обновете данните.'),
      ),
    ).toBe(true)
    expect(
      isConcurrentUpdate(new ApiError(409, 'STAFF_MEMBER_CONCURRENT_UPDATE', 'Обновете.')),
    ).toBe(false)
    expect(isConcurrentUpdate(new ApiError(409, 'BUSINESS_SUSPENDED', 'Конфликт.'))).toBe(false)
  })
})
