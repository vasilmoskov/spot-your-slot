// Date-only values (`yyyy-MM-dd`, a Business-local calendar day) and instants (an ISO-8601 date-time
// with its offset) are different things and never converted into one another through the browser's
// own timezone. A date-only value is formatted from its numeric parts in UTC, which cannot shift
// the day; an instant is formatted in the Business timezone the server returned.

const LOCALE = 'bg-BG'
const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|([+-]\d{2}):(\d{2}))$/

export type DateOnly = { year: number; month: number; day: number }

export function parseDateOnly(value: string): DateOnly | null {
  const match = DATE_ONLY.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const probe = new Date(Date.UTC(year, month - 1, day))
  if (
    probe.getUTCFullYear() !== year ||
    probe.getUTCMonth() !== month - 1 ||
    probe.getUTCDate() !== day
  ) {
    return null
  }
  return { year, month, day }
}

/** The instant in epoch milliseconds, or null when the text is not an offset date-time. */
export function parseInstant(value: string): number | null {
  if (!INSTANT.test(value)) return null
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? null : parsed
}

export function isValidTimeZone(timeZone: string): boolean {
  try {
    new Intl.DateTimeFormat(LOCALE, { timeZone })
    return timeZone.trim() !== ''
  } catch {
    return false
  }
}

function utcNoon(date: DateOnly): Date {
  return new Date(Date.UTC(date.year, date.month - 1, date.day, 12))
}

const longDate = new Intl.DateTimeFormat(LOCALE, {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
})

/** "сряда, 8 октомври 2026 г." for a Business-local date. */
export function formatDateOnlyLong(value: string): string {
  const date = parseDateOnly(value)
  return date ? longDate.format(utcNoon(date)) : value
}

function timeFormat(timeZone: string): Intl.DateTimeFormat {
  return new Intl.DateTimeFormat(LOCALE, {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone,
  })
}

/** "10:30" in the Business timezone. */
export function formatTimeInZone(instant: string, timeZone: string): string {
  const at = parseInstant(instant)
  return at === null ? instant : timeFormat(timeZone).format(new Date(at))
}

/** The long Business-local date of an instant. */
export function formatInstantDate(instant: string, timeZone: string): string {
  const at = parseInstant(instant)
  if (at === null) return instant
  return new Intl.DateTimeFormat(LOCALE, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone,
  }).format(new Date(at))
}

/** The offset the server attached to the instant, as "UTC+03:00". */
export function offsetLabel(instant: string): string {
  const match = INSTANT.exec(instant)
  if (!match) return ''
  if (match[1] === undefined || match[2] === undefined) return 'UTC'
  return `UTC${match[1]}:${match[2]}`
}

function wallClock(at: number, timeZone: string): string {
  return new Intl.DateTimeFormat('en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone,
  }).format(new Date(at))
}

const HOUR_MS = 3_600_000

/**
 * True when the same local date and clock time also occurs at another instant, which happens only in
 * the repeated hour when clocks go back. Checked by formatting the neighbouring hours in the Business
 * timezone, so no timezone database is duplicated here.
 */
export function isRepeatedLocalTime(instant: string, timeZone: string): boolean {
  const at = parseInstant(instant)
  if (at === null) return false
  const own = wallClock(at, timeZone)
  return wallClock(at - HOUR_MS, timeZone) === own || wallClock(at + HOUR_MS, timeZone) === own
}

/**
 * The clock time of an instant, with its offset appended only when the local time is repeated, so two
 * slots that read the same stay distinguishable: "03:30 (UTC+03:00)".
 */
export function formatTimeDistinct(instant: string, timeZone: string): string {
  const time = formatTimeInZone(instant, timeZone)
  return isRepeatedLocalTime(instant, timeZone) ? `${time} (${offsetLabel(instant)})` : time
}

/** The elapsed time between two instants in whole minutes (not the difference of wall-clock times). */
export function elapsedMinutes(start: string, end: string): number | null {
  const from = parseInstant(start)
  const to = parseInstant(end)
  return from === null || to === null ? null : Math.round((to - from) / 60_000)
}

// ---- calendar vocabulary and Business-local "today" ----

// Fixed names, so the calendar reads the same whatever the ICU data of the browser says. Monday is first.
export const MONTH_NAMES = [
  'Януари',
  'Февруари',
  'Март',
  'Април',
  'Май',
  'Юни',
  'Юли',
  'Август',
  'Септември',
  'Октомври',
  'Ноември',
  'Декември',
] as const

export const WEEKDAY_SHORT = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Нд'] as const
export const WEEKDAY_LONG = [
  'понеделник',
  'вторник',
  'сряда',
  'четвъртък',
  'петък',
  'събота',
  'неделя',
] as const

/** "Октомври 2026". */
export function formatMonthTitle(year: number, month: number): string {
  return `${MONTH_NAMES[month - 1]} ${year}`
}

/**
 * The Business-local calendar date of an instant, as `yyyy-MM-dd`. It only labels "today" in the
 * calendar; whether a date can be booked always comes from the availability answer.
 */
export function businessLocalDate(now: number, timeZone: string): string | null {
  if (!isValidTimeZone(timeZone)) return null
  const parts = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    timeZone,
  }).formatToParts(new Date(now))
  const part = (type: string) => parts.find((entry) => entry.type === type)?.value
  const year = part('year')
  const month = part('month')
  const day = part('day')
  return year && month && day ? `${year}-${month}-${day}` : null
}
