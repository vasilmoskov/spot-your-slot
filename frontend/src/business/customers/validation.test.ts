import { describe, expect, it } from 'vitest'
import vectors from '../../../../shared-test-data/contact-policy-vectors.json'
import {
  SEARCH_MAX_LENGTH,
  normalizeSearchTerm,
  searchTermError,
  validateCustomer,
  validateCustomerField,
  type CustomerFormValues,
} from './validation'

const base: CustomerFormValues = {
  displayName: 'Мария Тестова',
  phone: '',
  email: 'maria@example.test',
  contact: '',
}

describe('Customer validation uses the shared contact vectors', () => {
  it.each(vectors.phone.accepted)('accepts the phone vector $id', ({ input }) => {
    expect(validateCustomerField('phone', { ...base, phone: input })).toBeUndefined()
  })

  it.each(vectors.phone.rejected)('rejects the phone vector $id', ({ input }) => {
    expect(validateCustomerField('phone', { ...base, phone: input })).toBe(
      'Въведеният телефонен номер не е валиден.',
    )
  })

  it.each(vectors.email.accepted)('accepts the email vector $id', ({ input }) => {
    expect(validateCustomerField('email', { ...base, email: input, phone: '' })).toBeUndefined()
  })

  it.each(vectors.email.rejected)('rejects the email vector $id', ({ input }) => {
    expect(validateCustomerField('email', { ...base, email: input })).toBe(
      'Въведеният имейл адрес не е валиден.',
    )
  })
})

describe('Customer form rules', () => {
  it('accepts phone only, email only and both', () => {
    expect(validateCustomer({ ...base, phone: '0895555777', email: '' })).toEqual({})
    expect(validateCustomer({ ...base, phone: '', email: 'maria@example.test' })).toEqual({})
    expect(
      validateCustomer({ ...base, phone: '+359 895 555 777', email: 'maria@example.test' }),
    ).toEqual({})
  })

  it('requires a name and at least one contact, treating whitespace as empty', () => {
    expect(validateCustomer({ ...base, displayName: '   ', phone: '  ', email: ' ' })).toEqual({
      displayName: 'Въведете име на клиента до 200 знака.',
      contact: 'Въведете телефон или имейл.',
    })
  })

  it('counts the name length in code points', () => {
    expect(validateCustomerField('displayName', { ...base, displayName: '😀'.repeat(200) })).toBe(
      undefined,
    )
    expect(validateCustomerField('displayName', { ...base, displayName: '😀'.repeat(201) })).toBe(
      'Въведете име на клиента до 200 знака.',
    )
  })

  it('does not report the contact rule when a phone is present but invalid', () => {
    expect(validateCustomer({ ...base, phone: 'abc', email: '' })).toEqual({
      phone: 'Въведеният телефонен номер не е валиден.',
    })
  })

  it('uses ASCII-only email like the backend', () => {
    expect(validateCustomerField('email', { ...base, email: 'мария@example.test' })).toBeDefined()
  })
})

describe('search term rules', () => {
  it('trims only the approved whitespace and keeps the inner text unchanged', () => {
    expect(normalizeSearchTerm('   Анна  Иванова \t')).toBe('Анна  Иванова')
    expect(normalizeSearchTerm('   ')).toBe('')
  })

  it('accepts exactly 100 code points and rejects 101', () => {
    expect(searchTermError('😀'.repeat(SEARCH_MAX_LENGTH))).toBeUndefined()
    expect(searchTermError('😀'.repeat(SEARCH_MAX_LENGTH + 1))).toBe(
      'Търсенето може да съдържа най-много 100 знака.',
    )
  })
})
