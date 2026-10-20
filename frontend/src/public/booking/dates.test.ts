import { describe, expect, it } from 'vitest'
import {
  elapsedMinutes,
  formatDateOnlyLong,
  formatTimeDistinct,
  formatTimeInZone,
  isRepeatedLocalTime,
  isValidTimeZone,
  crossesClockReturn,
  formatVisitRange,
  instantInZone,
  repeatedOccurrence,
  zoneOffsetMs,
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

  it('shows a repeated time plainly: no occurrence wording, no offset', () => {
    expect(formatTimeDistinct('2026-10-25T03:30:00+03:00', zone)).toBe('03:30')
    expect(formatTimeDistinct('2026-10-25T03:30:00+02:00', zone)).toBe('03:30')
    expect(formatTimeDistinct('2026-10-25T05:30:00+02:00', zone)).toBe('05:30')
  })

  it('measures elapsed time, not the difference of the wall clocks', () => {
    // 03:30 summer time to 03:15 winter time is 45 minutes, although the wall clock went back.
    expect(elapsedMinutes('2026-10-25T03:30:00+03:00', '2026-10-25T03:15:00+02:00')).toBe(45)
    expect(elapsedMinutes('2026-10-07T10:00:00+03:00', 'bad')).toBeNull()
  })
})

describe('repeated hours derived from the zone transitions (never a fixed date or a fixed shift)', () => {
  const zone = 'Europe/Sofia'
  const ZONES = ['Pacific/Kiritimati', 'America/Los_Angeles', 'UTC', 'Pacific/Pago_Pago']

  it('tells the first and the second occurrence of a repeated time apart, whatever the browser timezone', () => {
    const original = process.env['TZ']
    try {
      for (const browser of ZONES) {
        process.env['TZ'] = browser
        expect(repeatedOccurrence('2026-10-25T03:30:00+03:00', zone)).toBe('first')
        expect(repeatedOccurrence('2026-10-25T03:30:00+02:00', zone)).toBe('second')
        expect(repeatedOccurrence('2026-10-25T02:30:00+03:00', zone)).toBeNull()
        expect(repeatedOccurrence('2026-10-25T04:30:00+02:00', zone)).toBeNull()
        expect(repeatedOccurrence('2026-07-01T03:30:00+03:00', zone)).toBeNull()
      }
    } finally {
      if (original === undefined) delete process.env['TZ']
      else process.env['TZ'] = original
    }
  })

  it('finds the repeated hour of another zone and of the next year without any hard-coded date', () => {
    // New York goes back on 2026-11-01 at 02:00 local: 01:30 occurs twice.
    expect(repeatedOccurrence('2026-11-01T01:30:00-04:00', 'America/New_York')).toBe('first')
    expect(repeatedOccurrence('2026-11-01T01:30:00-05:00', 'America/New_York')).toBe('second')
    // Sofia, 2027: the last Sunday of October is the 31st.
    expect(repeatedOccurrence('2027-10-31T03:30:00+03:00', zone)).toBe('first')
    expect(repeatedOccurrence('2027-10-31T03:30:00+02:00', zone)).toBe('second')
  })

  it('has no repeated time in the spring gap or on a zone without daylight saving', () => {
    expect(repeatedOccurrence('2027-03-28T04:30:00+03:00', zone)).toBeNull()
    expect(repeatedOccurrence('2026-10-25T03:30:00+03:00', 'UTC')).toBeNull()
  })

  it('detects a visit that crosses the clock return and one that does not', () => {
    expect(crossesClockReturn('2026-10-25T03:30:00+03:00', '2026-10-25T03:15:00+02:00', zone)).toBe(true)
    expect(crossesClockReturn('2026-10-25T03:00:00+02:00', '2026-10-25T03:45:00+02:00', zone)).toBe(false)
    expect(crossesClockReturn('2026-10-24T10:00:00+03:00', '2026-10-24T11:00:00+03:00', zone)).toBe(false)
  })

  it('writes an ordinary range plainly and a crossing visit as its start and the elapsed time', () => {
    const crossing = formatVisitRange('2026-10-25T03:30:00+03:00', '2026-10-25T03:15:00+02:00', zone)
    expect(crossing).toBe('03:30 — 45\u00a0мин.')
    expect(formatVisitRange('2026-10-24T10:00:00+03:00', '2026-10-24T10:45:00+03:00', zone)).toBe('10:00 – 10:45')
    expect(formatVisitRange('2026-10-25T03:30:00+02:00', '2026-10-25T04:15:00+02:00', zone)).toBe('03:30 – 04:15')
    expect(crossing).not.toMatch(/UTC|Europe|[+-]\d\d:\d\d|път|часовника/)
  })

  it('builds the exact instant text of a clicked time from the instant, keeping both repeated times distinct', () => {
    const first = Date.parse('2026-10-25T03:30:00+03:00')
    const second = Date.parse('2026-10-25T03:30:00+02:00')
    expect(instantInZone(first, zone)).toBe('2026-10-25T03:30:00+03:00')
    expect(instantInZone(second, zone)).toBe('2026-10-25T03:30:00+02:00')
    expect(Date.parse(instantInZone(first, zone))).toBe(first)
    expect(Date.parse(instantInZone(second, zone))).toBe(second)
    expect(zoneOffsetMs(first, zone)).toBe(3 * 3_600_000)
    expect(zoneOffsetMs(second, zone)).toBe(2 * 3_600_000)
  })
})
