import type { CalendarState, CalendarView } from '../../navigation'
import { SCHEDULE_MAX_DATE, SCHEDULE_MIN_DATE } from '../../navigation'

// Business-local date arithmetic on `yyyy-MM-dd` text. Every value is handled as numeric parts in UTC,
// which cannot shift a day; the browser timezone never takes part (it is only a bootstrap for "today"
// until the first response names the Business timezone).

const DAY_MS = 86_400_000

function toUtc(date: string): number {
  return Date.parse(`${date}T00:00:00Z`)
}

function fromUtc(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}

export function addDays(date: string, days: number): string {
  return fromUtc(toUtc(date) + days * DAY_MS)
}

/** 0 for Monday … 6 for Sunday. */
export function weekdayIndex(date: string): number {
  return (new Date(toUtc(date)).getUTCDay() + 6) % 7
}

export function weekStart(date: string): string {
  return addDays(date, -weekdayIndex(date))
}

export function monthStart(date: string): string {
  return `${date.slice(0, 7)}-01`
}

export function monthEnd(date: string): string {
  const [year, month] = [Number(date.slice(0, 4)), Number(date.slice(5, 7))]
  return fromUtc(Date.UTC(year, month, 0))
}

export function dateRange(from: string, to: string): string[] {
  const dates: string[] = []
  for (let current = from; current <= to; current = addDays(current, 1)) dates.push(current)
  return dates
}

export type DateWindowRange = { from: string; to: string }

function clamp(range: DateWindowRange): DateWindowRange {
  return {
    from: range.from < SCHEDULE_MIN_DATE ? SCHEDULE_MIN_DATE : range.from,
    to: range.to > SCHEDULE_MAX_DATE ? SCHEDULE_MAX_DATE : range.to,
  }
}

/**
 * The inclusive Business-local dates one view needs: the day, the Monday-to-Sunday week, or the
 * calendar month of the agenda. Never more than 31 dates, and always inside the API's date bounds.
 */
export function rangeFor(view: CalendarView, date: string): DateWindowRange {
  if (view === 'day') return { from: date, to: date }
  if (view === 'week') {
    const from = weekStart(date)
    return clamp({ from, to: addDays(from, 6) })
  }
  return clamp({ from: monthStart(date), to: monthEnd(date) })
}

/** The anchor date after moving one period; null when it would leave the supported dates. */
export function shiftDate(view: CalendarView, date: string, direction: -1 | 1): string | null {
  let next: string
  if (view === 'day') next = addDays(date, direction)
  else if (view === 'week') next = addDays(date, 7 * direction)
  else next = direction < 0 ? monthStart(addDays(monthStart(date), -1)) : addDays(monthEnd(date), 1)
  return next < SCHEDULE_MIN_DATE || next > SCHEDULE_MAX_DATE ? null : next
}

export function sameCalendarRequest(a: CalendarState, b: CalendarState): boolean {
  return (
    a.view === b.view &&
    a.date === b.date &&
    a.staffMemberId === b.staffMemberId &&
    a.page === b.page &&
    a.size === b.size
  )
}
