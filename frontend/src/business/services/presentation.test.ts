import { describe, expect, it } from 'vitest'
import {
  formatServiceDuration,
  formatServicePrice,
  serviceStatusPresentation,
} from './presentation'

describe('services presentation', () => {
  it('labels active and inactive Services', () => {
    expect(serviceStatusPresentation(true)).toEqual({ label: 'Активна', tone: 'success' })
    expect(serviceStatusPresentation(false)).toEqual({ label: 'Неактивна', tone: 'neutral' })
  })

  it('formats duration in minutes', () => {
    expect(formatServiceDuration(10)).toBe('10 мин.')
  })

  it('formats price with two decimal places without floating-point drift', () => {
    expect(formatServicePrice(19.9)).toBe('19.90 €')
    expect(formatServicePrice(10)).toBe('10.00 €')
  })
})
