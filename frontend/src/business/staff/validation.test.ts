import { describe, expect, it } from 'vitest'
import {
  validateStaff,
  validateStaffField,
  type StaffFormValues,
} from './validation'

const valid: StaffFormValues = {
  displayName: 'Анна Иванова',
  contactEmail: '',
  contactPhone: '',
}

describe('validateStaff', () => {
  it('accepts a name alone and blank or whitespace-only optional contacts', () => {
    expect(validateStaff(valid)).toEqual({})
    expect(validateStaff({ ...valid, contactEmail: '   ', contactPhone: '   ' })).toEqual({})
  })

  it('rejects an empty and a whitespace-only name and over-long names', () => {
    expect(validateStaffField('displayName', { ...valid, displayName: '' })).toBe(
      'Въведете име на члена на екипа.',
    )
    expect(validateStaffField('displayName', { ...valid, displayName: ' 　 ' })).toBe(
      'Въведете име на члена на екипа.',
    )
    expect(validateStaffField('displayName', { ...valid, displayName: 'а'.repeat(200) })).toBeUndefined()
    expect(validateStaffField('displayName', { ...valid, displayName: 'а'.repeat(201) })).toBe(
      'Името може да съдържа най-много 200 знака.',
    )
  })

  it('accepts a blank email and validates a non-blank one', () => {
    const email = (value: string) => validateStaffField('contactEmail', { ...valid, contactEmail: value })
    expect(email('')).toBeUndefined()
    expect(email(`${'a'.repeat(300)}@x.bg`)).toBeUndefined()
    expect(email(`${'a'.repeat(320)}@x.bg`)).toBe('Имейлът може да съдържа най-много 320 знака.')
  })

  // The same examples are asserted by the backend StaffMemberInputValidatorTests.
  it.each([
    'a@a',
    'a@\u0430',
    'a@xn--80a',
    '@primer.bg',
    'a@',
    'a@.bg',
    'a@primer.',
    'a@primer..bg',
    'a@-primer.bg',
    'a@primer-.bg',
    'a@primer.-bg',
    'a@primer.bg-',
    'a b@primer.bg',
    'a@pri mer.bg',
    'a@@primer.bg',
    'a@b@primer.bg',
    'a..b@primer.bg',
    '.ab@primer.bg',
    'ab.@primer.bg',
    '\u0438\u0432\u0430\u043d@primer.bg',
    '\u0438\u0432\u0430\u043d@\u043f\u0440\u0438\u043c\u0435\u0440.\u0431\u0433',
    'ime@\u043f\u0440\u0438\u043c\u0435\u0440.\u0431\u0433',
    'anna',
  ])('rejects the email %s', (bad) => {
    expect(validateStaffField('contactEmail', { ...valid, contactEmail: bad })).toContain(
      'Въведете валиден имейл, например ime@primer.bg.',
    )
  })

  it.each([
    'ime@primer.bg',
    'ime.prezime@primer.bg',
    'a@ab.bg',
    'ime@mail.primer.bg',
    'ime.prezime+tag@sub.primer.co.uk',
  ])('accepts the email %s', (good) => {
    expect(validateStaffField('contactEmail', { ...valid, contactEmail: good })).toBeUndefined()
  })

  it('accepts real numbers in the forms the backend can canonicalize', () => {
    const phone = (value: string) => validateStaffField('contactPhone', { ...valid, contactPhone: value })
    for (const ok of [
      '+359 88 123 4567',
      '+359881234567',
      '0888 123 456',
      '(088) 812-34.56',
      '00359881234567',
      '+44 20 7946 0958',
      '02 123 4567',
    ]) {
      expect(phone(ok), ok).toBeUndefined()
    }
  })

  it('rejects numbers that only look like phone numbers', () => {
    const phone = (value: string) => validateStaffField('contactPhone', { ...valid, contactPhone: value })
    const invalid = 'Въведете валиден телефонен номер, например +359 88 123 4567.'
    for (const bad of ['+3598881234561', '0888 12', '+359 100 000 000', '+44 20 7946 095', '0800']) {
      expect(phone(bad), bad).toBe(invalid)
    }
  })

  it('rejects an ambiguous prefix, letters, and separators-only input', () => {
    const phone = (value: string) => validateStaffField('contactPhone', { ...valid, contactPhone: value })
    for (const bad of ['888123456', 'abc', '+359 88 abc', '++359881234567', '0', '+', '00']) {
      expect(phone(bad), bad).toBeTruthy()
    }
    expect(phone('888123456')).toContain('започва с +, 00 или 0')
  })
})
