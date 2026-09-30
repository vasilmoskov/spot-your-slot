import { describe, expect, it } from 'vitest'
import {
  BUSINESS_TYPE_LABELS,
  BUSINESS_TYPE_OPTIONS,
  businessTypeLabel,
} from './businessType'
import * as platformPresentation from '../platform/businesses/presentation'

describe('shared Business-type labels', () => {
  it('labels every known type in Bulgarian', () => {
    expect(businessTypeLabel('HAIR_SALON')).toBe('Фризьорски салон')
    expect(BUSINESS_TYPE_OPTIONS.map((option) => option.value)).toEqual(
      Object.keys(BUSINESS_TYPE_LABELS),
    )
    for (const label of Object.values(BUSINESS_TYPE_LABELS)) {
      expect(label).not.toMatch(/[A-Z_]{4,}/)
    }
  })

  it('degrades an unknown value to the generic label without exposing it', () => {
    expect(businessTypeLabel('SPACE_STATION')).toBe('Друг')
    expect(businessTypeLabel('toString')).toBe('Друг')
    expect(businessTypeLabel('')).toBe('Друг')
  })

  it('is the very mapping the platform administration uses', () => {
    expect(platformPresentation.BUSINESS_TYPE_LABELS).toBe(BUSINESS_TYPE_LABELS)
    expect(platformPresentation.businessTypeLabel).toBe(businessTypeLabel)
  })
})
