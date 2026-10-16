import { afterEach, describe, expect, it } from 'vitest'
import { CALENDAR_MAX_DATES } from './api'
import { addDays, dateRange, monthEnd, rangeFor, shiftDate, weekStart, weekdayIndex } from './dates'

const ORIGINAL_TZ = process.env['TZ']
afterEach(() => {
  if (ORIGINAL_TZ === undefined) delete process.env['TZ']
  else process.env['TZ'] = ORIGINAL_TZ
})

describe('Business-local date arithmetic', () => {
  it('adds days across month, year and leap-day boundaries', () => {
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01')
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01')
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29')
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28')
  })

  it('starts the week on Monday and ends it on Sunday', () => {
    expect(weekdayIndex('2026-10-14')).toBe(2) // Wednesday
    expect(weekStart('2026-10-14')).toBe('2026-10-12')
    expect(weekStart('2026-10-18')).toBe('2026-10-12') // Sunday
    expect(weekStart('2026-10-12')).toBe('2026-10-12')
    expect(rangeFor('week', '2026-10-18')).toEqual({ from: '2026-10-12', to: '2026-10-18' })
  })

  it('never shifts a date through the browser timezone', () => {
    for (const zone of ['Pacific/Kiritimati', 'America/Los_Angeles', 'UTC', 'Pacific/Pago_Pago']) {
      process.env['TZ'] = zone
      expect(addDays('2026-03-29', 1)).toBe('2026-03-30')
      expect(addDays('2026-10-25', 1)).toBe('2026-10-26')
      expect(weekStart('2026-10-14')).toBe('2026-10-12')
      expect(rangeFor('day', '2026-10-25')).toEqual({ from: '2026-10-25', to: '2026-10-25' })
      expect(monthEnd('2026-02-10')).toBe('2026-02-28')
    }
  })
})

describe('bounded ranges', () => {
  it('requests the day, the week or the calendar month and never more than 31 dates', () => {
    expect(rangeFor('day', '2026-10-14')).toEqual({ from: '2026-10-14', to: '2026-10-14' })
    expect(rangeFor('agenda', '2026-10-14')).toEqual({ from: '2026-10-01', to: '2026-10-31' })
    for (let month = 1; month <= 12; month += 1) {
      for (const year of [2026, 2028]) {
        const date = `${year}-${String(month).padStart(2, '0')}-15`
        for (const view of ['day', 'week', 'agenda'] as const) {
          const { from, to } = rangeFor(view, date)
          expect(dateRange(from, to).length).toBeLessThanOrEqual(CALENDAR_MAX_DATES)
        }
      }
    }
    expect(dateRange('2026-10-01', '2026-10-31')).toHaveLength(31)
  })

  it('clamps a range to the supported dates', () => {
    expect(rangeFor('week', '2000-01-01')).toEqual({ from: '2000-01-01', to: '2000-01-02' })
    expect(rangeFor('week', '2100-12-31')).toEqual({ from: '2100-12-27', to: '2100-12-31' })
  })

  it('moves one period at a time and stops at the supported dates', () => {
    expect(shiftDate('day', '2026-10-14', 1)).toBe('2026-10-15')
    expect(shiftDate('week', '2026-10-14', -1)).toBe('2026-10-07')
    expect(shiftDate('agenda', '2026-10-14', 1)).toBe('2026-11-01')
    expect(shiftDate('agenda', '2026-10-14', -1)).toBe('2026-09-01')
    expect(shiftDate('day', '2000-01-01', -1)).toBeNull()
    expect(shiftDate('agenda', '2100-12-15', 1)).toBeNull()
  })
})
