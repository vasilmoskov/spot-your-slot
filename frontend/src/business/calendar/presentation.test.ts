import { afterEach, describe, expect, it } from 'vitest'
import {
  businessDateOf,
  formatDayHeading,
  formatTimeRange,
  formatTotalDuration,
  layoutLanes,
  percentOfDay,
} from './presentation'
import { visibleWindow } from './CalendarViews'
import { day } from './testFixtures'

const ORIGINAL_TZ = process.env['TZ']
afterEach(() => {
  if (ORIGINAL_TZ === undefined) delete process.env['TZ']
  else process.env['TZ'] = ORIGINAL_TZ
})

const BROWSER_ZONES = ['Pacific/Kiritimati', 'America/Los_Angeles', 'UTC', 'Pacific/Pago_Pago']

describe('Business-timezone presentation', () => {
  it('formats instants in the Business timezone whatever the browser timezone is', () => {
    for (const zone of BROWSER_ZONES) {
      process.env['TZ'] = zone
      expect(formatTimeRange('2026-10-14T10:00:00+03:00', '2026-10-14T10:45:00+03:00', 'Europe/Sofia')).toBe(
        '10:00–10:45',
      )
      // The same instant written with another offset is still shown in the Business timezone.
      expect(formatTimeRange('2026-10-14T07:00:00Z', '2026-10-14T07:45:00Z', 'Europe/Sofia')).toBe(
        '10:00–10:45',
      )
      expect(businessDateOf('2026-10-14T23:30:00Z', 'Europe/Sofia')).toBe('2026-10-15')
      expect(businessDateOf('2026-10-14T21:30:00Z', 'Europe/Sofia')).toBe('2026-10-15')
      expect(businessDateOf('2026-10-14T20:59:00Z', 'Europe/Sofia')).toBe('2026-10-14')
    }
  })

  it('keeps both occurrences of a repeated local time distinguishable', () => {
    for (const zone of BROWSER_ZONES) {
      process.env['TZ'] = zone
      // 2026-10-25: clocks go back from 04:00 EEST to 03:00 EET.
      expect(
        formatTimeRange('2026-10-25T03:30:00+03:00', '2026-10-25T03:45:00+03:00', 'Europe/Sofia'),
      ).toBe('03:30 (UTC+03:00)–03:45 (UTC+03:00)')
      expect(
        formatTimeRange('2026-10-25T03:30:00+02:00', '2026-10-25T03:45:00+02:00', 'Europe/Sofia'),
      ).toBe('03:30 (UTC+02:00)–03:45 (UTC+02:00)')
    }
  })

  it('formats a spring-gap day without a phantom hour', () => {
    // 2026-03-29: 03:00 EET jumps to 04:00 EEST; nothing exists between them.
    const spring = day('2026-03-29', '+02:00', '+03:00')
    const window = visibleWindow(spring, [])
    expect(window.hours).toBe(10)
    expect(formatTimeRange('2026-03-29T04:00:00+03:00', '2026-03-29T04:30:00+03:00', 'Europe/Sofia')).toBe(
      '04:00–04:30',
    )
    // 23 elapsed hours between the day's bounds.
    expect(
      (Date.parse(spring.end) - Date.parse(spring.start)) / 3_600_000,
    ).toBe(23)
  })

  it('labels dates from their numeric parts', () => {
    for (const zone of BROWSER_ZONES) {
      process.env['TZ'] = zone
      expect(formatDayHeading('2026-10-14')).toBe('Ср, 14 октомври 2026')
    }
  })

  it('formats durations', () => {
    expect(formatTotalDuration(45)).toBe('45 мин')
    expect(formatTotalDuration(120)).toBe('2 ч')
    expect(formatTotalDuration(75)).toBe('1 ч 15 мин')
  })
})

describe('visible day window', () => {
  it('covers work and bookings in whole hours and defaults to the day hours when empty', () => {
    const wednesday = day('2026-10-14')
    expect(visibleWindow(wednesday, []).hours).toBe(10)
    const window = visibleWindow(wednesday, [
      { start: '2026-10-14T09:15:00+03:00', end: '2026-10-14T12:00:00+03:00' },
    ])
    expect(window.hours).toBe(3)
    expect(Date.parse(window.start)).toBe(Date.parse('2026-10-14T09:00:00+03:00'))
  })

  it('keeps a 25-hour day within its real bounds', () => {
    const repeated = day('2026-10-25', '+03:00', '+02:00')
    const window = visibleWindow(repeated, [
      { start: '2026-10-25T02:00:00+03:00', end: '2026-10-25T05:00:00+02:00' },
    ])
    // 02:00 EEST to 05:00 EET is four elapsed hours.
    expect(window.hours).toBe(4)
    expect(percentOfDay('2026-10-25T05:00:00+02:00', window.start, window.end)).toBe(100)
  })
})

describe('overlapping entries', () => {
  const entry = (id: string, start: string, end: string) => ({ id, start, end })

  it('places overlapping entries in separate lanes and adjacent ones in the same lane', () => {
    const placed = layoutLanes([
      entry('a', '2026-10-14T10:00:00+03:00', '2026-10-14T11:00:00+03:00'),
      entry('b', '2026-10-14T10:30:00+03:00', '2026-10-14T11:30:00+03:00'),
      entry('c', '2026-10-14T11:00:00+03:00', '2026-10-14T11:15:00+03:00'),
      entry('d', '2026-10-14T13:00:00+03:00', '2026-10-14T14:00:00+03:00'),
    ])
    const byId = Object.fromEntries(placed.map((item) => [item.item.id, item]))
    expect(byId['a']).toMatchObject({ lane: 0, lanes: 2 })
    expect(byId['b']).toMatchObject({ lane: 1, lanes: 2 })
    expect(byId['c']).toMatchObject({ lane: 0, lanes: 2 })
    expect(byId['d']).toMatchObject({ lane: 0, lanes: 1 })
  })
})
