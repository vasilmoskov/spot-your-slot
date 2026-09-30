import { describe, expect, it } from 'vitest'
import { addressLines, dialableNumber, truncateAtWord } from './presentation'

describe('dialableNumber', () => {
  it('keeps digits and a leading plus only', () => {
    expect(dialableNumber('+359 88 000 0000')).toBe('+359880000000')
    expect(dialableNumber('088 (123) 45-67')).toBe('0881234567')
    expect(dialableNumber('  +359-2-000 0000 ')).toBe('+35920000000')
  })

  it('never lets arbitrary text reach the link', () => {
    expect(dialableNumber('javascript:alert(1) 123')).toBe('1123')
    expect(dialableNumber('123;ext=4"><b>')).toBe('1234')
    expect(dialableNumber('088 <script>')).toBe('088')
  })

  it('returns null when nothing dialable remains', () => {
    expect(dialableNumber('')).toBeNull()
    expect(dialableNumber('обадете се')).toBeNull()
    expect(dialableNumber('+')).toBeNull()
    expect(dialableNumber('12')).toBeNull()
  })
})

describe('addressLines', () => {
  const full = {
    city: 'София',
    postalCode: '1000',
    street: 'Примерна улица',
    streetNumber: '1',
    details: 'вход Б',
  }

  it('returns readable lines for a complete address', () => {
    expect(addressLines(full)).toEqual(['Примерна улица 1', '1000 София', 'вход Б'])
  })

  it('omits absent parts without separators or placeholders', () => {
    expect(addressLines({ ...full, street: null, streetNumber: null, details: null })).toEqual([
      '1000 София',
    ])
    expect(addressLines({ ...full, streetNumber: null, postalCode: null })).toEqual([
      'Примерна улица',
      'София',
      'вход Б',
    ])
    expect(addressLines({ city: null, postalCode: null, street: null, streetNumber: '5', details: null })).toEqual(['5'])
  })

  it('returns nothing for a null or blank address', () => {
    expect(addressLines(null)).toEqual([])
    expect(
      addressLines({ city: ' ', postalCode: '', street: null, streetNumber: null, details: null }),
    ).toEqual([])
  })
})

describe('truncateAtWord', () => {
  it('collapses whitespace and keeps short text whole', () => {
    expect(truncateAtWord('  Добро \n  място ')).toBe('Добро място')
  })

  it('cuts at a word boundary with an ellipsis within the limit', () => {
    const text = `${'дума '.repeat(60)}край`
    const result = truncateAtWord(text)
    expect(Array.from(result).length).toBeLessThanOrEqual(160)
    expect(result.endsWith('…')).toBe(true)
    expect(result).not.toMatch(/\sдум…$/)
    expect(result.slice(0, -1).trimEnd().endsWith('дума')).toBe(true)
  })

  it('hard-cuts one very long word by code points', () => {
    const result = truncateAtWord('я'.repeat(500))
    expect(Array.from(result)).toHaveLength(160)
    expect(result.endsWith('…')).toBe(true)
  })
})
