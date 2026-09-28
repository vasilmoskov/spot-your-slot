import { describe, expect, it } from 'vitest'
import { formatStaffPhone, staffStatusPresentation } from './presentation'

describe('staff presentation', () => {
  it('labels active and inactive StaffMembers', () => {
    expect(staffStatusPresentation(true)).toEqual({ label: 'Активен', tone: 'success' })
    expect(staffStatusPresentation(false)).toEqual({ label: 'Неактивен', tone: 'neutral' })
  })

  it('groups a canonical Bulgarian mobile number into readable triplets', () => {
    expect(formatStaffPhone('+359895555777')).toBe('+359 895 555 777')
  })

  it('retains the explicit country code for other international numbers', () => {
    expect(formatStaffPhone('+4915123456789')).toBe('+4915123456789')
  })

  it('shows the compact canonical value for a Bulgarian number without nine national digits', () => {
    expect(formatStaffPhone('+35921234567')).toBe('+35921234567')
  })

  it('shows an em dash for a missing telephone', () => {
    expect(formatStaffPhone(null)).toBe('—')
    expect(formatStaffPhone(undefined)).toBe('—')
    expect(formatStaffPhone('')).toBe('—')
  })
})
