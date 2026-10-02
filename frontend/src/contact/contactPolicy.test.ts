import { describe, expect, it } from 'vitest'
import vectors from '../../../shared-test-data/contact-policy-vectors.json'
import { canonicalOptional } from '../business/text'
import { canonicalEmail, canonicalPhone, checkPhone } from './contactPolicy'

// The same file is run by the backend ContactPolicyVectorTests. There is one expected result
// per vector for both implementations; the backend stays authoritative.
describe('shared contact-policy vectors: phone', () => {
  it.each(vectors.phone.accepted)('accepts $id', ({ input, canonical }) => {
    expect(canonicalPhone(input)).toBe(canonical)
  })

  it.each(vectors.phone.rejected)('rejects $id', ({ input }) => {
    expect(canonicalPhone(input)).toBeNull()
  })

  it.each(vectors.phone.blank.map((input, index) => ({ index, input })))(
    'treats blank input #$index as absent',
    ({ input }) => {
      expect(canonicalOptional(input)).toBe('')
    },
  )
})

describe('shared contact-policy vectors: email', () => {
  it.each(vectors.email.accepted)('accepts $id', ({ input, canonical }) => {
    expect(canonicalEmail(input)).toBe(canonical)
  })

  it.each(vectors.email.rejected)('rejects $id', ({ input }) => {
    expect(canonicalEmail(input)).toBeNull()
  })

  it.each(vectors.email.blank.map((input, index) => ({ index, input })))(
    'treats blank input #$index as absent',
    ({ input }) => {
      expect(canonicalOptional(input)).toBe('')
    },
  )
})

describe('phone check reasons', () => {
  it('separates a malformed shape from a well formed but invalid number', () => {
    expect(checkPhone('888123456')).toEqual({ ok: false, reason: 'shape' })
    expect(checkPhone('+359895555777 ext 5')).toEqual({ ok: false, reason: 'shape' })
    expect(checkPhone('+3598881234561')).toEqual({ ok: false, reason: 'invalid' })
    expect(checkPhone('0895555777')).toEqual({ ok: true, canonical: '+359895555777' })
  })
})
