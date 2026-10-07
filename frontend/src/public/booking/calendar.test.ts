import { describe, expect, it } from 'vitest'
import {
  addDays,
  addMonths,
  clampDate,
  compareMonths,
  daysInMonth,
  monthOf,
  monthWeeks,
  moveDate,
  weekdayIndex,
} from './calendar'
import { businessLocalDate, formatMonthTitle, MONTH_NAMES, WEEKDAY_LONG, WEEKDAY_SHORT } from './dates'

describe('the month grid', () => {
  it('starts on Monday and pads the first and last week', () => {
    // 1 October 2026 is a Thursday.
    const weeks = monthWeeks({ year: 2026, month: 10 })
    expect(weeks[0]).toEqual([null, null, null, '2026-10-01', '2026-10-02', '2026-10-03', '2026-10-04'])
    expect(weeks.every((week) => week.length === 7)).toBe(true)
    expect(weeks.flat().filter((day) => day !== null)).toHaveLength(31)
    expect(weeks.at(-1)).toEqual(['2026-10-26', '2026-10-27', '2026-10-28', '2026-10-29', '2026-10-30', '2026-10-31', null])
  })

  it('has no padding when a month starts on Monday and ends on Sunday', () => {
    // February 2027 starts on Monday and has 28 days.
    const weeks = monthWeeks({ year: 2027, month: 2 })
    expect(weeks).toHaveLength(4)
    expect(weeks.flat().every((day) => day !== null)).toBe(true)
  })

  it('knows leap years and month lengths', () => {
    expect(daysInMonth({ year: 2028, month: 2 })).toBe(29)
    expect(daysInMonth({ year: 2026, month: 2 })).toBe(28)
    expect(daysInMonth({ year: 2026, month: 10 })).toBe(31)
  })

  it('numbers the weekdays from Monday', () => {
    expect(weekdayIndex({ year: 2026, month: 10, day: 5 })).toBe(0)
    expect(weekdayIndex({ year: 2026, month: 10, day: 25 })).toBe(6)
  })

  it('uses Bulgarian, Monday-first vocabulary', () => {
    expect(WEEKDAY_SHORT).toEqual(['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Нд'])
    expect(WEEKDAY_LONG[0]).toBe('понеделник')
    expect(MONTH_NAMES).toHaveLength(12)
    expect(formatMonthTitle(2026, 10)).toBe('Октомври 2026')
  })
})

describe('month arithmetic', () => {
  it('moves across a year boundary and compares months', () => {
    expect(addMonths({ year: 2026, month: 12 }, 1)).toEqual({ year: 2027, month: 1 })
    expect(addMonths({ year: 2026, month: 1 }, -1)).toEqual({ year: 2025, month: 12 })
    expect(compareMonths({ year: 2026, month: 10 }, { year: 2026, month: 11 })).toBeLessThan(0)
    expect(monthOf('2026-10-07')).toEqual({ year: 2026, month: 10 })
    expect(monthOf('2026-13-07')).toBeNull()
  })
})

describe('keyboard movement of the focused day', () => {
  it('moves by day and by week', () => {
    expect(moveDate('2026-10-07', 'ArrowRight')).toBe('2026-10-08')
    expect(moveDate('2026-10-07', 'ArrowLeft')).toBe('2026-10-06')
    expect(moveDate('2026-10-07', 'ArrowDown')).toBe('2026-10-14')
    expect(moveDate('2026-10-07', 'ArrowUp')).toBe('2026-09-30')
  })

  it('goes to the Monday and the Sunday of the week', () => {
    // 7 October 2026 is a Wednesday.
    expect(moveDate('2026-10-07', 'Home')).toBe('2026-10-05')
    expect(moveDate('2026-10-07', 'End')).toBe('2026-10-11')
    expect(moveDate('2026-10-05', 'Home')).toBe('2026-10-05')
    expect(moveDate('2026-10-11', 'End')).toBe('2026-10-11')
  })

  it('moves by month and keeps the day within the target month', () => {
    expect(moveDate('2026-10-07', 'PageDown')).toBe('2026-11-07')
    expect(moveDate('2026-10-31', 'PageDown')).toBe('2026-11-30')
    expect(moveDate('2026-03-31', 'PageUp')).toBe('2026-02-28')
  })

  it('ignores every other key and any malformed date', () => {
    expect(moveDate('2026-10-07', 'a')).toBeNull()
    expect(moveDate('nonsense', 'ArrowRight')).toBeNull()
  })

  it('adds days across month ends and clamps to the horizon', () => {
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01')
    expect(clampDate('2026-10-01', '2026-10-07', '2026-11-05')).toBe('2026-10-07')
    expect(clampDate('2026-12-01', '2026-10-07', '2026-11-05')).toBe('2026-11-05')
    expect(clampDate('2026-10-20', '2026-10-07', '2026-11-05')).toBe('2026-10-20')
  })
})

describe('the Business-local current date', () => {
  it('is the date in the Business timezone, not the browser or UTC date', () => {
    // 21:30 UTC on 6 October is already 7 October in Sofia (UTC+3), but still the 6th in UTC.
    const instant = Date.parse('2026-10-06T21:30:00Z')
    expect(businessLocalDate(instant, 'Europe/Sofia')).toBe('2026-10-07')
    expect(businessLocalDate(instant, 'UTC')).toBe('2026-10-06')
    expect(businessLocalDate(instant, 'Pacific/Kiritimati')).toBe('2026-10-07')
  })

  it('is null for a timezone that is not valid', () => {
    expect(businessLocalDate(Date.now(), 'Not/AZone')).toBeNull()
  })
})
