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

const HOUR_MS = 3_600_000
const FOUR_HOURS_MS = 4 * HOUR_MS

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

/** The zone's offset from UTC at an instant, in milliseconds, derived from the zone's own wall clock. */
export function zoneOffsetMs(at: number, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
    timeZone,
  }).formatToParts(new Date(at))
  const value = (type: string) => Number(parts.find((part) => part.type === type)?.value)
  const wall = Date.UTC(value('year'), value('month') - 1, value('day'), value('hour'), value('minute'), value('second'))
  return wall - Math.floor(at / 1000) * 1000
}

/**
 * Which occurrence of its local clock time an instant is, when the local time occurs twice because the clocks
 * went back: "first" (before the change) or "second" (after it); null for every ordinary time. Derived from the
 * actual offsets of the zone around the instant, not from a fixed date or a fixed one-hour shift.
 */
export function repeatedOccurrence(instant: string, timeZone: string): 'first' | 'second' | null {
  const at = parseInstant(instant)
  if (at === null) return null
  const own = zoneOffsetMs(at, timeZone)
  const later = zoneOffsetMs(at + FOUR_HOURS_MS, timeZone)
  const earlier = zoneOffsetMs(at - FOUR_HOURS_MS, timeZone)
  if (later < own) {
    // Clocks go back after this instant: the same wall time recurs `own - later` later.
    const other = at + (own - later)
    if (zoneOffsetMs(other, timeZone) === later && wallClock(other, timeZone) === wallClock(at, timeZone)) return 'first'
  }
  if (earlier > own) {
    const other = at - (earlier - own)
    if (zoneOffsetMs(other, timeZone) === earlier && wallClock(other, timeZone) === wallClock(at, timeZone)) return 'second'
  }
  return null
}

/** True when the same local date and clock time also occurs at another instant (the clocks went back). */
export function isRepeatedLocalTime(instant: string, timeZone: string): boolean {
  return repeatedOccurrence(instant, timeZone) !== null
}

/** True when the clocks went back between the two instants, so a compact "start–end" would mislead. */
export function crossesClockReturn(start: string, end: string, timeZone: string): boolean {
  const from = parseInstant(start)
  const to = parseInstant(end)
  if (from === null || to === null) return false
  return zoneOffsetMs(to, timeZone) < zoneOffsetMs(from, timeZone)
}

/**
 * The clock time of an instant in the Business timezone, plain "03:30". A local time that occurs twice (the clocks
 * went back) is deliberately not annotated: the choices stay distinct by their exact instants, never by the label.
 */
export function formatTimeDistinct(instant: string, timeZone: string): string {
  return formatTimeInZone(instant, timeZone)
}

/** "45 мин." for a short elapsed time, "1 ч 30 мин." and "2 ч." otherwise: the duration of a visit across the clock change. */
export function formatElapsed(minutes: number): string {
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  // Non-breaking spaces keep a number with its unit when a narrow card wraps the line after the dash.
  if (hours === 0) return `${rest}\u00a0мин.`
  return rest === 0 ? `${hours}\u00a0ч.` : `${hours}\u00a0ч ${rest}\u00a0мин.`
}

/**
 * The offset-bearing ISO text of an instant in the Business timezone (for example "2026-10-25T03:30:00+03:00"),
 * kept exact for a repeated hour because it is built from the instant, never from an ambiguous local string.
 */
export function instantInZone(at: number, timeZone: string): string {
  const offset = zoneOffsetMs(at, timeZone)
  const local = new Date(Math.floor(at / 1000) * 1000 + offset).toISOString().slice(0, 19)
  const minutes = Math.round(offset / 60_000)
  const sign = minutes < 0 ? '-' : '+'
  const abs = Math.abs(minutes)
  return `${local}${sign}${String(Math.floor(abs / 60)).padStart(2, '0')}:${String(abs % 60).padStart(2, '0')}`
}

/**
 * A visit as the public journey states it: "03:30 – 04:15". A visit across the backward clock change would read
 * "03:30 – 03:15", so it is the start and the actual elapsed time: "03:30 — 45 мин.".
 */
export function formatVisitRange(start: string, end: string, timeZone: string): string {
  if (crossesClockReturn(start, end, timeZone)) {
    const from = parseInstant(start)
    const to = parseInstant(end)
    const minutes = from === null || to === null ? 0 : Math.round((to - from) / 60_000)
    return `${formatTimeDistinct(start, timeZone)} — ${formatElapsed(minutes)}`
  }
  return `${formatTimeDistinct(start, timeZone)} – ${formatTimeDistinct(end, timeZone)}`
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
