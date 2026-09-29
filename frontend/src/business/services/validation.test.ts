import { describe, expect, it } from 'vitest'
import { validateService, validateServiceField, type ServiceFormValues } from './validation'

const valid: ServiceFormValues = {
  name: 'Подстригване',
  description: '',
  durationMinutes: '30',
  price: '25.50',
}

describe('validateService', () => {
  it('accepts a complete valid service and a blank optional description', () => {
    expect(validateService(valid)).toEqual({})
  })

  it('rejects an empty and a whitespace-only name, including non-breaking spaces', () => {
    expect(validateServiceField('name', { ...valid, name: '' })).toBe('Въведете име на услугата.')
    expect(validateServiceField('name', { ...valid, name: '    　 ' })).toBe(
      'Въведете име на услугата.',
    )
  })

  it('counts name length in code points after collapsing whitespace', () => {
    expect(validateServiceField('name', { ...valid, name: 'а'.repeat(200) })).toBeUndefined()
    expect(validateServiceField('name', { ...valid, name: 'а'.repeat(201) })).toBe(
      'Името може да съдържа най-много 200 знака.',
    )
    expect(validateServiceField('name', { ...valid, name: '😀'.repeat(200) })).toBeUndefined()
    expect(validateServiceField('name', { ...valid, name: `${'а'.repeat(198)}    ${'б'}` })).toBeUndefined()
  })

  it('limits the description to 2000 characters after trimming', () => {
    expect(
      validateServiceField('description', { ...valid, description: `  ${'а'.repeat(2000)}  ` }),
    ).toBeUndefined()
    expect(
      validateServiceField('description', { ...valid, description: 'а'.repeat(2001) }),
    ).toBe('Описанието може да съдържа най-много 2000 знака.')
  })

  it('enforces the 1–480 minute duration boundaries', () => {
    const duration = (value: string) =>
      validateServiceField('durationMinutes', { ...valid, durationMinutes: value })
    expect(duration('1')).toBeUndefined()
    expect(duration('480')).toBeUndefined()
    expect(duration('0')).toBe('Продължителността трябва да бъде между 1 и 480 минути.')
    expect(duration('481')).toBe('Продължителността трябва да бъде между 1 и 480 минути.')
    expect(duration('')).toBe('Въведете продължителност в минути.')
    expect(duration('-5')).toBe('Продължителността трябва да бъде между 1 и 480 минути.')
    expect(duration('1.5')).toBe('Въведете продължителността като цяло число минути.')
    expect(duration('1e2')).toBe('Въведете продължителността като цяло число минути.')
  })

  it('treats unparseable number-input text as invalid rather than blank', () => {
    expect(
      validateServiceField('durationMinutes', {
        ...valid,
        durationMinutes: '',
        durationBadInput: true,
      }),
    ).toBe('Въведете продължителността като цяло число минути.')
  })

  it('validates the EUR price in a deterministic order', () => {
    const price = (value: string) => validateServiceField('price', { ...valid, price: value })
    const format =
      'Въведете цената като число с най-много 2 знака след десетичната точка, например 25.50.'
    for (const ok of ['0', '0.00', '19.9', '19.90', '9999999999.99']) {
      expect(price(ok), ok).toBeUndefined()
    }
    expect(price('')).toBe('Въведете цена.')
    expect(price('   ')).toBe('Въведете цена.')
    for (const bad of ['19.999', '12,50', 'abc', '.5', '5.', '-1.999']) {
      expect(price(bad), bad).toBe(format)
    }
    for (const negative of ['-1', '-0.5', '-0']) {
      expect(price(negative), negative).toBe('Цената не може да бъде отрицателна.')
    }
    expect(price('10000000000')).toBe('Цената може да има най-много 10 цифри преди десетичната точка.')
  })

  it('reports every invalid field at once', () => {
    expect(Object.keys(validateService({ name: ' ', description: '', durationMinutes: '', price: '' })))
      .toEqual(['name', 'durationMinutes', 'price'])
  })
})
