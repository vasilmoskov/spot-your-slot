import { describe, expect, it } from 'vitest'
import {
  BUSINESS_STATUS_PRESENTATION,
  businessTypeLabel,
  formatBusinessUpdatedAt,
} from './presentation'

describe('Business presentation', () => {
  it.each([
    ['HAIR_SALON', 'Фризьорски салон'],
    ['BARBERSHOP', 'Бръснарски салон'],
    ['NAIL_STUDIO', 'Студио за маникюр'],
    ['MASSAGE_STUDIO', 'Масажно студио'],
    ['MAKEUP_STUDIO', 'Студио за грим'],
    ['BEAUTY_STUDIO', 'Козметично студио'],
    ['OTHER', 'Друг'],
  ] as const)('presents %s as %s', (type, label) => {
    expect(businessTypeLabel(type)).toBe(label)
  })

  it('presents every Business status with the approved Bulgarian label and tone', () => {
    expect(BUSINESS_STATUS_PRESENTATION).toEqual({
      DRAFT: { label: 'Чернова', tone: 'neutral' },
      ACTIVE: { label: 'Активен', tone: 'success' },
      SUSPENDED: { label: 'Временно спрян', tone: 'warning' },
    })
  })

  it('formats the updated instant in the Business timezone and Bulgarian locale', () => {
    expect(formatBusinessUpdatedAt('2026-08-20T12:30:00Z', 'Europe/Sofia')).toBe(
      '20.08.2026 г., 15:30',
    )
  })
})
