import { describe, expect, it } from 'vitest'
import { ApiError } from '../../identity/api'
import {
  customerErrorMessage,
  customerFieldErrors,
  customerLoadFailure,
  CUSTOMER_NOT_FOUND_MESSAGE,
} from './errors'

const problem = (status: number, code: string, fields?: Record<string, string>) =>
  new ApiError(status, code, 'RAW BACKEND TEXT org.postgresql SQLState 23505', fields)

describe('Customer error mapping', () => {
  it.each([
    ['VALIDATION_ERROR', 'Проверете въведените данни.'],
    ['CUSTOMER_CONTACT_CONFLICT', 'Телефонът или имейлът вече е записан за друг клиент.'],
    [
      'CUSTOMER_CONCURRENT_UPDATE',
      'Данните за клиента са променени. Обновете данните и опитайте отново.',
    ],
    ['CUSTOMER_CONCURRENT_CONFLICT', 'Операцията не можа да бъде завършена. Опитайте отново.'],
    ['BUSINESS_SUSPENDED', 'Спрян бизнес може само да преглежда данните си.'],
    ['CUSTOMER_NOT_FOUND', 'Клиентът не е намерен.'],
    ['ACCESS_DENIED', 'Нямате достъп до тази операция.'],
    ['ACTIVE_BUSINESS_REQUIRED', 'Изберете бизнес, за да продължите.'],
    ['INTERNAL_ERROR', 'Възникна неочаквана грешка.'],
  ])('maps %s to approved Bulgarian text and never shows the backend text', (code, text) => {
    expect(customerErrorMessage(problem(500, code), 'резервно')).toBe(text)
  })

  it('uses the caller fallback for an unknown code or a non-API error', () => {
    expect(customerErrorMessage(problem(500, 'SOMETHING_NEW'), 'резервно')).toBe('резервно')
    expect(customerErrorMessage(new Error('boom'), 'резервно')).toBe('резервно')
  })

  it('shows the same unavailable state for unknown, foreign and malformed IDs', () => {
    for (const error of [
      problem(404, 'CUSTOMER_NOT_FOUND'),
      problem(404, 'ROUTE_NOT_FOUND'),
      problem(400, 'VALIDATION_ERROR'),
    ]) {
      expect(customerLoadFailure(error, 'x')).toEqual({
        message: CUSTOMER_NOT_FOUND_MESSAGE,
        retryable: false,
      })
    }
    expect(customerLoadFailure(problem(500, 'INTERNAL_ERROR'), 'x').retryable).toBe(true)
  })

  it('maps validation field names to frontend text and ignores unknown names', () => {
    expect(
      customerFieldErrors(
        problem(400, 'VALIDATION_ERROR', { phone: 'x', contact: 'y', businessId: 'z' }),
      ),
    ).toEqual({
      phone: 'Въведеният телефонен номер не е валиден.',
      contact: 'Въведете телефон или имейл.',
    })
    expect(customerFieldErrors(problem(400, 'VALIDATION_ERROR'))).toBeUndefined()
    expect(customerFieldErrors(problem(400, 'VALIDATION_ERROR', { other: 'x' }))).toBeUndefined()
  })

  it('maps duplicate phone and email conflicts to their own fields', () => {
    expect(
      customerFieldErrors(problem(409, 'CUSTOMER_CONTACT_CONFLICT', { phone: 'a', email: 'b' })),
    ).toEqual({
      phone: 'Този телефонен номер вече е записан за друг клиент.',
      email: 'Този имейл адрес вече е записан за друг клиент.',
    })
    expect(customerFieldErrors(problem(409, 'CUSTOMER_CONCURRENT_UPDATE', { phone: 'a' }))).toBeUndefined()
  })
})
