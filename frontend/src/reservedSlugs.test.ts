import { describe, expect, it } from 'vitest'
import {
  RESERVED_BUSINESS_SLUGS,
  canonicalBusinessSlug,
  isReservedBusinessSlug,
} from './reservedSlugs'

// Must equal the backend's ReservedBusinessSlugsTests.APPROVED (ADR-0018).
const APPROVED = [
  'forgot-password',
  'password-reset',
  'invitation',
  'login',
  'logout',
  'profile',
  'platform',
  'business',
  'api',
  'actuator',
  'assets',
  'admin',
  'b',
  'book',
  'booking',
  'cancel',
  'cancellation',
  'confirmation',
  'appointments',
]

describe('reserved business slugs', () => {
  it('contains exactly the approved values', () => {
    expect([...RESERVED_BUSINESS_SLUGS].sort()).toEqual([...APPROVED].sort())
    expect(RESERVED_BUSINESS_SLUGS.size).toBe(19)
  })

  it.each(APPROVED)('reserves %s', (slug) => {
    expect(isReservedBusinessSlug(slug)).toBe(true)
  })

  it.each(['LOGIN', '  Booking  ', 'Forgot-Password', 'B'])(
    'matches the canonical lowercase form of %j',
    (slug) => {
      expect(isReservedBusinessSlug(slug)).toBe(true)
    },
  )

  it.each(['booking-studio', 'my-book', 'appointments-bg', 'salon-invitation', 'a', ''])(
    'does not reserve %j',
    (slug) => {
      expect(isReservedBusinessSlug(slug)).toBe(false)
    },
  )

  it('canonicalizes by trimming and lowercasing', () => {
    expect(canonicalBusinessSlug('  My-Slug ')).toBe('my-slug')
  })
})
