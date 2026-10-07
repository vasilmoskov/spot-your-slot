import { parseDateOnly, type DateOnly } from './dates'

// Pure calendar arithmetic on Business-local dates (`yyyy-MM-dd`). Every value is computed from the
// numeric parts in UTC, so no browser timezone can shift a day, and every comparison is a plain string
// comparison, which is correct for this fixed-width format.

export type Month = { year: number; month: number }

export function format(date: DateOnly): string {
  const year = String(date.year).padStart(4, '0')
  const month = String(date.month).padStart(2, '0')
  const day = String(date.day).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function fromUtc(value: Date): DateOnly {
  return { year: value.getUTCFullYear(), month: value.getUTCMonth() + 1, day: value.getUTCDate() }
}

function toUtc(date: DateOnly): Date {
  return new Date(Date.UTC(date.year, date.month - 1, date.day))
}

export function monthOf(value: string): Month | null {
  const date = parseDateOnly(value)
  return date ? { year: date.year, month: date.month } : null
}

export function sameMonth(first: Month, second: Month): boolean {
  return first.year === second.year && first.month === second.month
}

function monthIndex(month: Month): number {
  return month.year * 12 + (month.month - 1)
}

export function compareMonths(first: Month, second: Month): number {
  return monthIndex(first) - monthIndex(second)
}

export function addMonths(month: Month, count: number): Month {
  const index = monthIndex(month) + count
  return { year: Math.floor(index / 12), month: (index % 12) + 1 }
}

export function daysInMonth(month: Month): number {
  return new Date(Date.UTC(month.year, month.month, 0)).getUTCDate()
}

/** 0 for Monday through 6 for Sunday. */
export function weekdayIndex(date: DateOnly): number {
  return (toUtc(date).getUTCDay() + 6) % 7
}

/**
 * The weeks of a month, Monday first. A day outside the month is null, so the first and last rows are
 * padded and every row has seven entries.
 */
export function monthWeeks(month: Month): (string | null)[][] {
  const weeks: (string | null)[][] = []
  let week: (string | null)[] = new Array<string | null>(
    weekdayIndex({ ...month, day: 1 }),
  ).fill(null)
  for (let day = 1; day <= daysInMonth(month); day += 1) {
    week.push(format({ ...month, day }))
    if (week.length === 7) {
      weeks.push(week)
      week = []
    }
  }
  if (week.length > 0) {
    while (week.length < 7) week.push(null)
    weeks.push(week)
  }
  return weeks
}

export function addDays(value: string, count: number): string | null {
  const date = parseDateOnly(value)
  if (!date) return null
  const moved = toUtc(date)
  moved.setUTCDate(moved.getUTCDate() + count)
  return format(fromUtc(moved))
}

export function clampDate(value: string, first: string, last: string): string {
  if (value < first) return first
  if (value > last) return last
  return value
}

// The keys of the WAI-ARIA date picker grid pattern that move the focused day.
export const NAVIGATION_KEYS = [
  'ArrowLeft',
  'ArrowRight',
  'ArrowUp',
  'ArrowDown',
  'Home',
  'End',
  'PageUp',
  'PageDown',
] as const

/**
 * The day a navigation key moves to: a day left or right, a week up or down, the Monday or Sunday of
 * the week, and the same day of the previous or next month (limited to that month's last day). The
 * caller clamps the result to the booking horizon.
 */
export function moveDate(value: string, key: string): string | null {
  const date = parseDateOnly(value)
  if (!date) return null
  switch (key) {
    case 'ArrowLeft':
      return addDays(value, -1)
    case 'ArrowRight':
      return addDays(value, 1)
    case 'ArrowUp':
      return addDays(value, -7)
    case 'ArrowDown':
      return addDays(value, 7)
    case 'Home':
      return addDays(value, -weekdayIndex(date))
    case 'End':
      return addDays(value, 6 - weekdayIndex(date))
    case 'PageUp':
    case 'PageDown': {
      const target = addMonths({ year: date.year, month: date.month }, key === 'PageUp' ? -1 : 1)
      return format({ ...target, day: Math.min(date.day, daysInMonth(target)) })
    }
    default:
      return null
  }
}
