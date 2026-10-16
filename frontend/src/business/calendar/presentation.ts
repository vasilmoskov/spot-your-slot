import { formatServicePrice } from '../services/presentation'
import {
  formatDateOnlyLong,
  formatTimeDistinct,
  MONTH_NAMES,
  parseInstant,
  WEEKDAY_LONG,
  WEEKDAY_SHORT,
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
  agenda: 'Списък',
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

/** "dd.MM" style short label of a Business-local date, e.g. "Пн, 12 октомври". */
export function formatDayHeading(date: string): string {
  const year = Number(date.slice(0, 4))
  const month = Number(date.slice(5, 7))
  const day = Number(date.slice(8, 10))
  const index = weekdayIndex(date)
  return `${WEEKDAY_SHORT[index]}, ${day} ${MONTH_NAMES[month - 1]?.toLowerCase()} ${year}`
}

export function formatMonthHeading(date: string): string {
  const month = Number(date.slice(5, 7))
  return `${MONTH_NAMES[month - 1]} ${date.slice(0, 4)}`
}

export function formatWeekdayLong(date: string): string {
  return WEEKDAY_LONG[weekdayIndex(date)] ?? ''
}

export { formatDateOnlyLong }

/** The start–end of an Appointment in the Business timezone; a repeated hour keeps its offset. */
export function formatTimeRange(start: string, end: string, timeZone: string): string {
  return `${formatTimeDistinct(start, timeZone)}–${formatTimeDistinct(end, timeZone)}`
}

/** The Business-local date of an instant as `yyyy-MM-dd`; falls back to the text when unparsable. */
export function businessDateOf(instant: string, timeZone: string): string {
  const at = parseInstant(instant)
  return at === null ? instant : (localDateIn(new Date(at), timeZone) ?? instant)
}

/** Date plus time range, for lists that are not grouped by date. */
export function formatDateAndRange(start: string, end: string, timeZone: string): string {
  const date = businessDateOf(start, timeZone)
  return `${formatDateOnlyLong(date)}, ${formatTimeRange(start, end, timeZone)}`
}

export function serviceNames(appointment: Pick<CalendarAppointment, 'services'>): string {
  return appointment.services.map((service) => service.name).join(', ')
}

export function customerHistoryServiceNames(entry: CustomerAppointment): string {
  return entry.serviceNames.join(', ')
}

// ---- overlapping entries ----

export type Positioned<T> = { item: T; lane: number; lanes: number }

/**
 * Side-by-side lanes for entries whose time ranges overlap within one column. A cluster is a chain of
 * overlapping entries; each entry takes the first free lane and the cluster width is shared. Overlap
 * here is only visual: a cancelled entry may legitimately overlap a confirmed one.
 */
export function layoutLanes<T extends { start: string; end: string }>(items: readonly T[]): Positioned<T>[] {
  const sorted = [...items]
    .map((item) => ({ item, from: parseInstant(item.start) ?? 0, to: parseInstant(item.end) ?? 0 }))
    .sort((a, b) => a.from - b.from || a.to - b.to)
  const result: Positioned<T>[] = []
  let cluster: { item: T; lane: number; to: number }[] = []
  let laneEnds: number[] = []
  let clusterEnd = -Infinity

  const flush = () => {
    const lanes = Math.max(1, laneEnds.length)
    for (const entry of cluster) result.push({ item: entry.item, lane: entry.lane, lanes })
    cluster = []
    laneEnds = []
    clusterEnd = -Infinity
  }

  for (const entry of sorted) {
    if (entry.from >= clusterEnd) flush()
    let lane = laneEnds.findIndex((end) => end <= entry.from)
    if (lane === -1) {
      lane = laneEnds.length
      laneEnds.push(entry.to)
    } else {
      laneEnds[lane] = entry.to
    }
    cluster.push({ item: entry.item, lane, to: entry.to })
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
