import { formatServicePrice } from '../services/presentation'
import {
  AFTER_CLOCK_RETURN,
  BEFORE_CLOCK_RETURN,
  FIRST_TIME_LABEL,
  SECOND_TIME_LABEL,
  clockReturnPlace,
  crossesClockReturn,
  elapsedMinutes,
  formatDateOnlyLong,
  formatTimeInZone,
  repeatedOccurrence,
  MONTH_NAMES,
  parseInstant,
  WEEKDAY_LONG,
} from '../../public/booking/dates'
import { localDateIn } from '../schedule/exceptions/presentation'
import { weekdayIndex } from './dates'
import type {
  AppointmentSource,
  AppointmentStatus,
  CalendarAppointment,
  CalendarClosure,
  CustomerAppointment,
} from './api'
import type { CalendarView } from '../../navigation'

export const VIEW_LABELS: Record<CalendarView, string> = {
  day: 'Ден',
  week: 'Седмица',
  // The route value stays `agenda` (stable URLs); the month-scoped list is presented as the period it shows.
  agenda: 'Месец',
}

export function statusLabel(status: AppointmentStatus): string {
  return status === 'CANCELLED' ? 'Отменен' : 'Потвърден'
}

export function sourceLabel(source: AppointmentSource): string {
  return source === 'ONLINE' ? 'Онлайн резервация' : 'Въведен от бизнеса'
}

export function closureLabel(kind: CalendarClosure['kind']): string {
  return kind === 'BUSINESS_CLOSURE' ? 'Бизнесът е затворен' : 'Отсъствие'
}

/** "1 ч 30 мин", "45 мин", "2 ч". */
export function formatTotalDuration(minutes: number): string {
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours === 0) return `${rest} мин`
  return rest === 0 ? `${hours} ч` : `${hours} ч ${rest} мин`
}

export const formatPrice = formatServicePrice

/**
 * The written Bulgarian date that begins a heading or a card title: "Четвъртък, 29 октомври 2026 г."
 * One comma after the weekday, no ordinal suffix, no comma before the year, a capital weekday. Embedded in
 * a sentence the lower-case `formatDateOnlyLong` is used instead.
 */
export function formatDateHeading(date: string): string {
  const text = formatDateOnlyLong(date)
  return text.charAt(0).toLocaleUpperCase('bg-BG') + text.slice(1)
}

/** The heading of one day in the week view. */
export function formatDayHeading(date: string): string {
  return formatDateHeading(date)
}

/**
 * The short heading of a week: "19–25 октомври 2026 г." inside one month, both months when the week crosses a
 * month ("26 октомври – 1 ноември 2026 г."), both years when it crosses a year. No weekday names.
 */
export function formatWeekRange(from: string, to: string): string {
  const part = (date: string) => ({
    year: Number(date.slice(0, 4)),
    day: Number(date.slice(8, 10)),
    month: MONTH_NAMES[Number(date.slice(5, 7)) - 1]?.toLowerCase() ?? '',
  })
  const a = part(from)
  const b = part(to)
  if (a.year !== b.year) return `${a.day} ${a.month} ${a.year} г. – ${b.day} ${b.month} ${b.year} г.`
  if (a.month !== b.month) return `${a.day} ${a.month} – ${b.day} ${b.month} ${b.year} г.`
  return `${a.day}–${b.day} ${b.month} ${b.year} г.`
}

/** The three lines of a week-column heading: weekday, day and month, year. */
export function weekColumnLines(date: string): { weekday: string; dayMonth: string } {
  const full = formatDateHeading(date)
  const [weekday = '', rest = ''] = full.split(', ')
  const parts = rest.split(' ')
  return { weekday, dayMonth: `${parts[0]} ${parts[1]}` }
}

export function formatMonthHeading(date: string): string {
  const month = Number(date.slice(5, 7))
  return `${MONTH_NAMES[month - 1]} ${date.slice(0, 4)}`
}

export function formatWeekdayLong(date: string): string {
  return WEEKDAY_LONG[weekdayIndex(date)] ?? ''
}

export { formatDateOnlyLong }

/**
 * The time span of an Appointment in the Business timezone, never with a timezone identifier or a UTC offset.
 * A visit that crosses the backward clock change would read "03:30–03:15", so it is written as the start and the
 * duration ("03:30 — 45 мин"); a visit that starts in a repeated hour says which occurrence it is
 * ("03:00–03:45 — втори път"); every other span is the plain "10:00–11:15".
 */
export function formatTimeRange(start: string, end: string, timeZone: string): string {
  const from = formatTimeInZone(start, timeZone)
  const to = formatTimeInZone(end, timeZone)
  if (crossesClockReturn(start, end, timeZone)) {
    return `${from} — ${formatTotalDuration(elapsedMinutes(start, end) ?? 0)}`
  }
  const occurrence = repeatedOccurrence(start, timeZone)
  if (occurrence === null) return `${from}–${to}`
  return `${from}–${to} — ${occurrence === 'first' ? FIRST_TIME_LABEL : SECOND_TIME_LABEL}`
}

/** "Преди връщането на часовника" for a card or a detail whose visit starts in a repeated hour or crosses the change. */
export function clockReturnNote(start: string, end: string, timeZone: string): string | null {
  const place = clockReturnPlace(start, timeZone) ?? (crossesClockReturn(start, end, timeZone) ? BEFORE_CLOCK_RETURN : null)
  if (place === null) return null
  return place.charAt(0).toLocaleUpperCase('bg-BG') + place.slice(1)
}

/** Start and end as two lines for the detail of a visit that crosses the backward clock change; otherwise null. */
export function crossingBounds(start: string, end: string, timeZone: string): { start: string; end: string } | null {
  if (!crossesClockReturn(start, end, timeZone)) return null
  return {
    start: `${formatTimeInZone(start, timeZone)} — ${BEFORE_CLOCK_RETURN}`,
    end: `${formatTimeInZone(end, timeZone)} — ${AFTER_CLOCK_RETURN}`,
  }
}

/** The Business-local date of an instant as `yyyy-MM-dd`; falls back to the text when unparsable. */
export function businessDateOf(instant: string, timeZone: string): string {
  const at = parseInstant(instant)
  return at === null ? instant : (localDateIn(new Date(at), timeZone) ?? instant)
}

/** Date plus time range, for lists that are not grouped by date. */
export function formatDateAndRange(start: string, end: string, timeZone: string): string {
  const date = businessDateOf(start, timeZone)
  return `${formatDateHeading(date)}, ${formatTimeRange(start, end, timeZone)}`
}

export function serviceNames(appointment: Pick<CalendarAppointment, 'services'>): string {
  return appointment.services.map((service) => service.name).join(', ')
}

/** "Дамско подстригване" for one Service, "5 услуги" for several: the full list lives in the detail. */
export function serviceSummary(appointment: Pick<CalendarAppointment, 'services'>): string {
  const count = appointment.services.length
  return count === 1 ? (appointment.services[0]?.name ?? '') : `${count} услуги`
}

export function customerHistoryServiceNames(entry: CustomerAppointment): string {
  return entry.serviceNames.join(', ')
}

// ---- overlapping entries ----

export type Positioned<T> = {
  item: T
  lane: number
  lanes: number
  // The chain of overlapping entries this one belongs to (in time order).
  cluster: number
  // True for an entry that is listed in the cluster's «Още N» disclosure instead of having a lane of its own.
  hidden: boolean
}

/** The most side-by-side columns a cluster shows: wider groups keep MAX_LANES - 1 cards and one «Още N» tile. */
export const MAX_LANES = 3

/**
 * Side-by-side lanes for entries whose time ranges overlap within one column (an interval layout). A cluster is a
 * chain of overlapping entries; each entry takes the first free lane and the cluster width is shared equally, so a
 * confirmed and a cancelled entry of one StaffMember sit next to each other and never on top of each other. When a
 * cluster would need more than MAX_LANES lanes the last visible column becomes one disclosure that holds every
 * entry from that lane on, so card widths never shrink into unreadable strips. Deterministic: ties on the start are
 * ordered by the end, then by the original order.
 */
export function layoutLanes<T extends { start: string; end: string }>(items: readonly T[]): Positioned<T>[] {
  const sorted = [...items]
    .map((item, order) => ({ item, order, from: parseInstant(item.start) ?? 0, to: parseInstant(item.end) ?? 0 }))
    .sort((a, b) => a.from - b.from || a.to - b.to || a.order - b.order)
  const result: Positioned<T>[] = []
  let cluster: { item: T; lane: number }[] = []
  let laneEnds: number[] = []
  let clusterEnd = -Infinity
  let clusterIndex = -1

  const flush = () => {
    const needed = Math.max(1, laneEnds.length)
    const lanes = Math.min(needed, MAX_LANES)
    for (const entry of cluster) {
      const hidden = needed > MAX_LANES && entry.lane >= MAX_LANES - 1
      result.push({ item: entry.item, lane: hidden ? MAX_LANES - 1 : entry.lane, lanes, cluster: clusterIndex, hidden })
    }
    cluster = []
    laneEnds = []
    clusterEnd = -Infinity
  }

  for (const entry of sorted) {
    if (entry.from >= clusterEnd) {
      flush()
      clusterIndex += 1
    }
    let lane = laneEnds.findIndex((end) => end <= entry.from)
    if (lane === -1) {
      lane = laneEnds.length
      laneEnds.push(entry.to)
    } else {
      laneEnds[lane] = entry.to
    }
    cluster.push({ item: entry.item, lane })
    clusterEnd = Math.max(clusterEnd, entry.to)
  }
  flush()
  return result
}

/** Position of an instant inside [dayStart, dayEnd) as a percentage, clamped to the day. */
export function percentOfDay(instant: string, dayStart: string, dayEnd: string): number {
  const at = parseInstant(instant)
  const from = parseInstant(dayStart)
  const to = parseInstant(dayEnd)
  if (at === null || from === null || to === null || to <= from) return 0
  return Math.min(100, Math.max(0, ((at - from) / (to - from)) * 100))
}
