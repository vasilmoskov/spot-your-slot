import { afterEach, describe, expect, it } from 'vitest'
import { formatDateOnlyLong } from '../../public/booking/dates'
import {
  businessDateOf,
  formatDateHeading,
  formatDayHeading,
  formatTimeRange,
  formatTotalDuration,
  layoutLanes,
  MAX_LANES,
  percentOfDay,
  serviceSummary,
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
      ).toBe('03:30–03:45 — първи път')
      expect(
        formatTimeRange('2026-10-25T03:30:00+02:00', '2026-10-25T03:45:00+02:00', 'Europe/Sofia'),
      ).toBe('03:30–03:45 — втори път')
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
      expect(formatDayHeading('2026-10-14')).toBe('Сряда, 14 октомври 2026 г.')
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

describe('dense overlapping entries', () => {
  const at = (id: string, from: string, to: string) => ({ id, start: `2026-10-14T${from}:00+03:00`, end: `2026-10-14T${to}:00+03:00` })

  it('lays a cancelled and a confirmed entry side by side at half width each', () => {
    const placed = layoutLanes([at('cancelled', '14:30', '15:15'), at('confirmed', '14:45', '15:15')])
    expect(placed.map((entry) => [entry.item.id, entry.lane, entry.lanes, entry.hidden])).toEqual([
      ['cancelled', 0, 2, false],
      ['confirmed', 1, 2, false],
    ])
  })

  it('keeps up to MAX_LANES columns, then one «Още N» column that holds every remaining entry', () => {
    const three = layoutLanes([at('a', '10:00', '11:00'), at('b', '10:00', '11:00'), at('c', '10:00', '11:00')])
    expect(three.every((entry) => entry.lanes === 3 && !entry.hidden)).toBe(true)
    const five = layoutLanes(['a', 'b', 'c', 'd', 'e'].map((id) => at(id, '10:00', '11:00')))
    expect(five.every((entry) => entry.lanes === MAX_LANES)).toBe(true)
    expect(five.filter((entry) => entry.hidden).map((entry) => entry.item.id)).toEqual(['c', 'd', 'e'])
    expect(five.filter((entry) => !entry.hidden).map((entry) => entry.lane)).toEqual([0, 1])
  })

  it('is deterministic for equal starts and does not mix separate clusters', () => {
    const items = [at('x', '10:00', '11:00'), at('y', '10:00', '11:00'), at('z', '12:00', '13:00')]
    expect(layoutLanes(items)).toEqual(layoutLanes([...items]))
    const placed = layoutLanes(items)
    expect(placed.find((entry) => entry.item.id === 'z')).toMatchObject({ lane: 0, lanes: 1, cluster: 1 })
  })
})

describe('Bulgarian written dates (Phase 11 Part 1)', () => {
  const SUFFIX = /-ви\b|-ри\b|-ти\b|-и\b|-ми\b|-ен\b/

  it('writes 1, 2 and 29 of a month without an ordinal suffix and with one comma after the weekday', () => {
    expect(formatDateHeading('2026-10-01')).toBe('Четвъртък, 1 октомври 2026 г.')
    expect(formatDateHeading('2026-10-02')).toBe('Петък, 2 октомври 2026 г.')
    expect(formatDateHeading('2026-10-29')).toBe('Четвъртък, 29 октомври 2026 г.')
    for (const date of ['2026-10-01', '2026-10-02', '2026-10-29']) {
      const text = formatDateHeading(date)
      expect(text).not.toMatch(SUFFIX)
      // No comma between the month and the year, exactly one comma overall.
      expect(text).not.toMatch(/[а-я], 20\d\d/)
      expect(text.match(/,/g)).toHaveLength(1)
    }
  })

  it('capitalizes the weekday of day and week headings and keeps it lower case inside a sentence', () => {
    expect(formatDayHeading('2026-10-12')).toBe('Понеделник, 12 октомври 2026 г.')
    expect(formatDayHeading('2026-10-18')).toBe('Неделя, 18 октомври 2026 г.')
    expect(formatDateOnlyLong('2026-10-14')).toBe('сряда, 14 октомври 2026 г.')
  })

  it('does not pass a date-only value through the browser timezone', () => {
    for (const zone of BROWSER_ZONES) {
      process.env['TZ'] = zone
      expect(formatDateHeading('2026-10-29')).toBe('Четвъртък, 29 октомври 2026 г.')
    }
  })
})

describe('no technical timezone text (Phase 11 Part 1)', () => {
  it('names the occurrence of a repeated hour in words and never prints an IANA identifier or an offset', () => {
    const first = formatTimeRange('2026-10-25T03:30:00+03:00', '2026-10-25T03:45:00+03:00', 'Europe/Sofia')
    const second = formatTimeRange('2026-10-25T03:30:00+02:00', '2026-10-25T03:45:00+02:00', 'Europe/Sofia')
    expect(first).toContain('първи път')
    expect(second).toContain('втори път')
    expect(first + second).not.toMatch(/Europe|Sofia|Часова зона/)
  })

  it('summarizes Services compactly', () => {
    expect(serviceSummary({ services: [{ name: 'Маникюр', durationMinutes: 60 }] })).toBe('Маникюр')
    expect(
      serviceSummary({ services: Array.from({ length: 5 }, (_, i) => ({ name: `Дълго име ${i}`, durationMinutes: 30 })) }),
    ).toBe('5 услуги')
  })
})
