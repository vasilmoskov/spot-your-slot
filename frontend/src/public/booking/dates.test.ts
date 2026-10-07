import { describe, expect, it } from 'vitest'
import {
  elapsedMinutes,
  formatDateOnlyLong,
  formatTimeDistinct,
  formatTimeInZone,
  isRepeatedLocalTime,
  isValidTimeZone,
  offsetLabel,
  parseDateOnly,
  parseInstant,
} from './dates'

describe('date-only values', () => {
  it('accepts real calendar days only', () => {
    expect(parseDateOnly('2026-10-07')).toEqual({ year: 2026, month: 10, day: 7 })
    expect(parseDateOnly('2028-02-29')).not.toBeNull()
    for (const value of ['2026-02-29', '2026-13-01', '2026-00-10', '2026-10-7', '07.10.2026', '', '2026-10-07T10:00:00Z']) {
      expect(parseDateOnly(value)).toBeNull()
    }
  })

  // The browser timezone of the test run is irrelevant: the day comes from its own numbers.
  it('formats the named day itself, at the edges of a day and of the year', () => {
    expect(formatDateOnlyLong('2026-10-07')).toBe('сряда, 7 октомври 2026 г.')
    expect(formatDateOnlyLong('2026-01-01')).toBe('четвъртък, 1 януари 2026 г.')
    expect(formatDateOnlyLong('2026-12-31')).toBe('четвъртък, 31 декември 2026 г.')
    expect(formatDateOnlyLong('2026-10-25')).toBe('неделя, 25 октомври 2026 г.')
  })
})

describe('instants', () => {
  it('requires an offset date-time', () => {
    expect(parseInstant('2026-10-07T10:00:00+03:00')).toBe(Date.parse('2026-10-07T07:00:00Z'))
    expect(parseInstant('2026-10-07T07:00:00Z')).not.toBeNull()
    for (const value of ['2026-10-07', '2026-10-07T10:00:00', '10:00', 'x', '2026-10-07 10:00:00+03:00']) {
      expect(parseInstant(value)).toBeNull()
    }
  })

  it('formats the clock time in the Business timezone, whatever the browser uses', () => {
    expect(formatTimeInZone('2026-10-07T10:00:00+03:00', 'Europe/Sofia')).toBe('10:00')
    expect(formatTimeInZone('2026-10-07T07:00:00Z', 'Europe/Sofia')).toBe('10:00')
    expect(formatTimeInZone('2026-10-07T07:00:00Z', 'Europe/Berlin')).toBe('09:00')
    expect(formatTimeInZone('2026-10-07T07:00:00Z', 'UTC')).toBe('07:00')
  })

  it('validates timezone names', () => {
    expect(isValidTimeZone('Europe/Sofia')).toBe(true)
    expect(isValidTimeZone('Nowhere/Land')).toBe(false)
    expect(isValidTimeZone('')).toBe(false)
  })

  it('labels the offset the server attached', () => {
    expect(offsetLabel('2026-10-25T03:30:00+02:00')).toBe('UTC+02:00')
    expect(offsetLabel('2026-10-25T03:30:00-05:30')).toBe('UTC-05:30')
    expect(offsetLabel('2026-10-25T03:30:00Z')).toBe('UTC')
  })
})

describe('the repeated hour', () => {
  const zone = 'Europe/Sofia'

  it('marks both 03:30 starts of the day the clocks go back, and no ordinary time', () => {
    expect(isRepeatedLocalTime('2026-10-25T03:30:00+03:00', zone)).toBe(true)
    expect(isRepeatedLocalTime('2026-10-25T03:30:00+02:00', zone)).toBe(true)
    expect(isRepeatedLocalTime('2026-10-25T02:30:00+03:00', zone)).toBe(false)
    expect(isRepeatedLocalTime('2026-10-25T04:30:00+02:00', zone)).toBe(false)
    expect(isRepeatedLocalTime('2026-10-07T10:00:00+03:00', zone)).toBe(false)
  })

  it('does not mark the skipped hour of the spring change or either side of it', () => {
    for (const instant of ['2026-03-29T02:30:00+02:00', '2026-03-29T04:00:00+03:00', '2026-03-29T03:59:00+03:00']) {
      expect(isRepeatedLocalTime(instant, zone)).toBe(false)
    }
  })

  it('appends the offset only to a repeated time', () => {
    expect(formatTimeDistinct('2026-10-25T03:30:00+03:00', zone)).toBe('03:30 (UTC+03:00)')
    expect(formatTimeDistinct('2026-10-25T03:30:00+02:00', zone)).toBe('03:30 (UTC+02:00)')
    expect(formatTimeDistinct('2026-10-25T05:30:00+02:00', zone)).toBe('05:30')
  })

  it('measures elapsed time, not the difference of the wall clocks', () => {
    // 03:30 summer time to 03:15 winter time is 45 minutes, although the wall clock went back.
    expect(elapsedMinutes('2026-10-25T03:30:00+03:00', '2026-10-25T03:15:00+02:00')).toBe(45)
    expect(elapsedMinutes('2026-10-07T10:00:00+03:00', 'bad')).toBeNull()
  })
})
