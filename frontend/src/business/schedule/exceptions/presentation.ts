import {
  SCHEDULE_MAX_DATE,
  SCHEDULE_MAX_WINDOW_DATES,
  SCHEDULE_MIN_DATE,
  inclusiveDateCount,
  isCanonicalDate,
  type DateWindow,
  type ExceptionSortField,
  type ListSortDirection,
} from '../../../navigation'
import type { ExceptionKind, ExceptionPeriod, ScheduleExceptionItem } from './api'

export const EXCEPTION_KINDS: readonly ExceptionKind[] = [
  'BUSINESS_CLOSURE',
  'STAFF_TIME_OFF',
  'WORKING_DAY_OVERRIDE',
  'ADDITIONAL_WORKING_PERIODS',
]

// The only user-facing names of the four kinds; enum names never reach the UI.
export const KIND_LABELS: Record<ExceptionKind, string> = {
  BUSINESS_CLOSURE: 'Неработно време',
  STAFF_TIME_OFF: 'Отсъствие',
  WORKING_DAY_OVERRIDE: 'Променени работни часове',
  ADDITIONAL_WORKING_PERIODS: 'Допълнителни работни часове',
}

// Each entry is one sentence per line (rendered as separate paragraphs). The
// two working kinds say plainly that blocking kinds still win.
export const KIND_EXPLANATIONS: Record<ExceptionKind, readonly string[]> = {
  BUSINESS_CLOSURE: ['Блокира резервациите за всички членове на екипа през избрания период.'],
  STAFF_TIME_OFF: ['Блокира резервациите за избрания член на екипа през избрания период.'],
  WORKING_DAY_OVERRIDE: [
    'Заменя обичайните седмични часове за избраната дата.',
    'Неработното време и отсъствията продължават да блокират резервациите.',
  ],
  ADDITIONAL_WORKING_PERIODS: [
    'Добавя работни часове за избраната дата.',
    'Неработното време и отсъствията продължават да блокират резервациите.',
  ],
}

// Shown when the server reports that a record of the same kind already covers the
// chosen dates. The wording follows the kind the user is editing; the server's own
// text (which uses an internal term) is never displayed.
export const OVERLAP_MESSAGES: Record<ExceptionKind, readonly string[]> = {
  BUSINESS_CLOSURE: ['Избраният период се застъпва с вече добавено неработно време.'],
  STAFF_TIME_OFF: [
    'Избраният период се застъпва с вече добавено отсъствие за този член на екипа.',
  ],
  WORKING_DAY_OVERRIDE: [
    'За тази дата вече има променени работни часове за избрания член на екипа.',
    'Редактирайте съществуващия запис, за да промените периодите.',
  ],
  ADDITIONAL_WORKING_PERIODS: [
    'За тази дата вече има допълнителни работни часове за избрания член на екипа.',
    'Редактирайте съществуващия запис, за да добавите или промените периодите.',
  ],
}

// The choices are shown in two groups: what applies to the whole Business and
// what applies to one StaffMember.
export const KIND_GROUPS: ReadonlyArray<{ label: string; kinds: readonly ExceptionKind[] }> = [
  { label: 'За целия бизнес', kinds: ['BUSINESS_CLOSURE'] },
  {
    label: 'За член на екипа',
    kinds: ['STAFF_TIME_OFF', 'WORKING_DAY_OVERRIDE', 'ADDITIONAL_WORKING_PERIODS'],
  },
]

export function isStaffScoped(kind: ExceptionKind): boolean {
  return kind !== 'BUSINESS_CLOSURE'
}

/** Closures and time off can cover whole dates; the two working kinds never can. */
export function isBlockKind(kind: ExceptionKind): boolean {
  return kind === 'BUSINESS_CLOSURE' || kind === 'STAFF_TIME_OFF'
}

// Mirrors the backend's fixed technical limit (ScheduleExceptionInputValidator).
export const MAX_EXCEPTION_PERIODS = 24
export const MAX_FULL_DAY_SPAN_DATES = 366

// ---- dates ----

export function formatDate(date: string): string {
  const [year, month, day] = date.split('-')
  return `${day}.${month}.${year}`
}

export function formatDateRange(firstDate: string, lastDate: string): string {
  return firstDate === lastDate
    ? formatDate(firstDate)
    : `${formatDate(firstDate)} – ${formatDate(lastDate)}`
}

export function addDays(date: string, days: number): string {
  const moved = new Date(Date.parse(`${date}T00:00:00Z`) + days * 86_400_000)
  return moved.toISOString().slice(0, 10)
}

/**
 * The calendar date of `instant` as `yyyy-MM-dd` in `timeZone`, or in the
 * browser's own zone when none is given. An unknown zone returns null so the
 * caller can keep its provisional value instead of guessing.
 */
export function localDateIn(instant: Date, timeZone?: string): string | null {
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      ...(timeZone ? { timeZone } : {}),
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(instant)
    const value = (type: string) => parts.find((part) => part.type === type)?.value
    const year = value('year')
    const month = value('month')
    const day = value('day')
    if (!year || !month || !day) return null
    return `${year.padStart(4, '0')}-${month}-${day}`
  } catch {
    return null
  }
}

/** Today through today + 29: the 30 Business-local dates customers can book. */
export function defaultWindowFrom(today: string): DateWindow {
  return { from: today, to: addDays(today, 29) }
}

export function sameWindow(left: DateWindow, right: DateWindow): boolean {
  return left.from === right.from && left.to === right.to
}

export type WindowField = 'from' | 'to'

export const WINDOW_FIELD_ORDER: readonly WindowField[] = ['from', 'to']

const BOUNDS_MESSAGE = 'Въведете дата между 01.01.2000 и 31.12.2100.'

export function validateWindow(values: {
  from: string
  to: string
}): Partial<Record<WindowField, string>> {
  const errors: Partial<Record<WindowField, string>> = {}
  if (!values.from) errors.from = 'Въведете начална дата.'
  else if (!isCanonicalDate(values.from) || values.from < SCHEDULE_MIN_DATE || values.from > SCHEDULE_MAX_DATE) {
    errors.from = BOUNDS_MESSAGE
  }
  if (!values.to) errors.to = 'Въведете крайна дата.'
  else if (!isCanonicalDate(values.to) || values.to < SCHEDULE_MIN_DATE || values.to > SCHEDULE_MAX_DATE) {
    errors.to = BOUNDS_MESSAGE
  }
  if (!errors.from && !errors.to) {
    if (values.from > values.to) {
      errors.to = 'Крайната дата не може да бъде преди началната.'
    } else if (inclusiveDateCount(values.from, values.to) > SCHEDULE_MAX_WINDOW_DATES) {
      errors.to = `Може да прегледате най-много ${SCHEDULE_MAX_WINDOW_DATES} дни наведнъж.`
    }
  }
  return errors
}

// ---- periods ----

const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/

export type DraftPeriod = { clientId: string; startTime: string; endTime: string }

export type PeriodError = 'MISSING_TIMES' | 'INVALID_FORMAT' | 'REVERSED_RANGE' | 'OVERLAP'

export function sortPeriods<T extends ExceptionPeriod>(periods: readonly T[]): T[] {
  return [...periods].sort((a, b) => {
    if (a.startTime !== b.startTime) return a.startTime.localeCompare(b.startTime)
    return a.endTime.localeCompare(b.endTime)
  })
}

export function formatPeriodRange(period: ExceptionPeriod): string {
  return `${period.startTime}–${period.endTime}`
}

export function formatPeriods(periods: readonly ExceptionPeriod[]): string {
  return sortPeriods(periods).map(formatPeriodRange).join(', ')
}

/**
 * Local mirror of the backend rules: both times present and canonical HH:mm,
 * start strictly before end, no duplicate or overlapping periods. Adjacent
 * periods stay valid and separate. The backend remains authoritative.
 */
export function validatePeriods(periods: readonly DraftPeriod[]): {
  errors: Map<string, PeriodError>
  tooMany: boolean
} {
  const errors = new Map<string, PeriodError>()
  for (const period of periods) {
    if (!period.startTime || !period.endTime) {
      errors.set(period.clientId, 'MISSING_TIMES')
    } else if (!TIME_PATTERN.test(period.startTime) || !TIME_PATTERN.test(period.endTime)) {
      errors.set(period.clientId, 'INVALID_FORMAT')
    } else if (period.startTime >= period.endTime) {
      errors.set(period.clientId, 'REVERSED_RANGE')
    }
  }
  const valid = sortPeriods(periods.filter((period) => !errors.has(period.clientId)))
  for (let i = 0; i < valid.length; i += 1) {
    for (let j = i + 1; j < valid.length; j += 1) {
      const earlier = valid[i]!
      const later = valid[j]!
      if (earlier.startTime < later.endTime && later.startTime < earlier.endTime) {
        errors.set(earlier.clientId, 'OVERLAP')
        errors.set(later.clientId, 'OVERLAP')
      }
    }
  }
  return { errors, tooMany: periods.length > MAX_EXCEPTION_PERIODS }
}

/**
 * The exact conflict between a proposed period and the periods already in the
 * form: a duplicate names the repeated hours, an overlap names both ranges. Only
 * values present in the form are ever used. Adjacent periods do not conflict.
 */
export function periodConflictMessage(
  candidate: ExceptionPeriod,
  existing: readonly ExceptionPeriod[],
): string | null {
  for (const other of sortPeriods(existing)) {
    if (candidate.startTime === other.startTime && candidate.endTime === other.endTime) {
      return `Периодът ${formatPeriodRange(candidate)} вече е добавен.`
    }
    if (candidate.startTime < other.endTime && other.startTime < candidate.endTime) {
      return `Периодът ${formatPeriodRange(candidate)} се застъпва със съществуващия период ${formatPeriodRange(other)}.`
    }
  }
  return null
}

export function periodErrorMessage(error: PeriodError): string {
  switch (error) {
    case 'MISSING_TIMES':
      return 'Въведете начален и краен час.'
    case 'INVALID_FORMAT':
      return 'Часът трябва да бъде във формат ЧЧ:ММ.'
    case 'REVERSED_RANGE':
      return 'Началният час трябва да бъде преди крайния.'
    case 'OVERLAP':
      return 'Периодът се припокрива с друг период.'
  }
}

export const TOO_MANY_PERIODS_MESSAGE = `Може да добавите най-много ${MAX_EXCEPTION_PERIODS} периода.`

// ---- list/detail presentation ----

export function hoursSummary(item: {
  kind: ExceptionKind
  allDay: boolean
  periods: readonly ExceptionPeriod[]
}): string {
  if (item.allDay) return 'Цял ден'
  if (item.periods.length === 0) {
    return item.kind === 'WORKING_DAY_OVERRIDE' ? 'Неработен ден' : '—'
  }
  return formatPeriods(item.periods)
}

export function staffNameOf(
  item: Pick<ScheduleExceptionItem, 'staffMemberId'>,
  names: ReadonlyMap<string, string>,
): string {
  if (!item.staffMemberId) return '—'
  return names.get(item.staffMemberId) ?? 'Член на екипа'
}

// ---- derived schedule-change status ----

export type ScheduleChangeStatus = 'IN_EFFECT' | 'UPCOMING' | 'PAST'

// Derived on every render from the Business-local date; never stored, never
// read from the API, and unrelated to Business/Service/StaffMember lifecycle.
export const SCHEDULE_CHANGE_STATUS_LABELS: Record<ScheduleChangeStatus, string> = {
  UPCOMING: 'Предстояща',
  IN_EFFECT: 'В сила',
  PAST: 'Минала',
}

const SCHEDULE_CHANGE_STATUS_TONES: Record<ScheduleChangeStatus, string> = {
  UPCOMING: 'info',
  IN_EFFECT: 'success',
  PAST: 'neutral',
}

/** Both bounds inclusive; `businessToday` is the Business-local `yyyy-MM-dd`. */
export function scheduleChangeStatus(
  item: Pick<ScheduleExceptionItem, 'firstDate' | 'lastDate'>,
  businessToday: string,
): ScheduleChangeStatus {
  if (item.firstDate > businessToday) return 'UPCOMING'
  if (item.lastDate < businessToday) return 'PAST'
  return 'IN_EFFECT'
}

export function scheduleChangeStatusPresentation(status: ScheduleChangeStatus): {
  label: string
  tone: string
} {
  return { label: SCHEDULE_CHANGE_STATUS_LABELS[status], tone: SCHEDULE_CHANGE_STATUS_TONES[status] }
}

/**
 * The current date in the Business timezone. An unusable timezone falls back to
 * the browser-local date, which only affects the badge, never stored data.
 */
export function businessToday(now: Date, timezone: string): string {
  return localDateIn(now, timezone) ?? localDateIn(now) ?? now.toISOString().slice(0, 10)
}

// ---- sorting ----

const collator = new Intl.Collator('bg')
// Semantic ascending order of the status column.
const STATUS_RANK: Record<ScheduleChangeStatus, number> = { IN_EFFECT: 0, UPCOMING: 1, PAST: 2 }

type Sortable = ScheduleExceptionItem
type Compare = (left: Sortable, right: Sortable) => number

const byId: Compare = (left, right) => (left.id < right.id ? -1 : left.id > right.id ? 1 : 0)
const byFirstDate: Compare = (left, right) =>
  left.firstDate < right.firstDate ? -1 : left.firstDate > right.firstDate ? 1 : 0
const byLastDate: Compare = (left, right) =>
  left.lastDate < right.lastDate ? -1 : left.lastDate > right.lastDate ? 1 : 0
const byKind: Compare = (left, right) =>
  collator.compare(KIND_LABELS[left.kind], KIND_LABELS[right.kind])

function chain(...compares: Compare[]): Compare {
  return (left, right) => {
    for (const compare of compares) {
      const result = compare(left, right)
      if (result !== 0) return result
    }
    return 0
  }
}

// Business-wide records have no StaffMember. They always sort after every named
// StaffMember, in both directions, so the direction only reorders real names.
function byStaffName(names: ReadonlyMap<string, string>, direction: ListSortDirection): Compare {
  return (left, right) => {
    if (!left.staffMemberId || !right.staffMemberId) {
      if (!left.staffMemberId && !right.staffMemberId) return 0
      return left.staffMemberId ? -1 : 1
    }
    const result = collator.compare(staffNameOf(left, names), staffNameOf(right, names))
    return direction === 'asc' ? result : -result
  }
}

/**
 * A sorted copy of the complete window. `sort` undefined is the canonical
 * default. The direction reverses only each column's primary key; tie-breakers
 * keep their own fixed direction so ties stay deterministic.
 */
export function sortScheduleExceptions(
  items: readonly ScheduleExceptionItem[],
  names: ReadonlyMap<string, string>,
  businessTodayDate: string,
  sort: ExceptionSortField | undefined,
  direction: ListSortDirection = 'asc',
): ScheduleExceptionItem[] {
  const sign = direction === 'asc' ? 1 : -1
  const primary = (compare: Compare): Compare => (left, right) => sign * compare(left, right)
  const rank: Compare = (left, right) =>
    STATUS_RANK[scheduleChangeStatus(left, businessTodayDate)] -
    STATUS_RANK[scheduleChangeStatus(right, businessTodayDate)]
  let compare: Compare
  switch (sort) {
    case 'kind':
      compare = chain(primary(byKind), byFirstDate, byLastDate, byStaffName(names, 'asc'), byId)
      break
    case 'dates':
    default:
      // The canonical order: first date, last date, then kind, StaffMember name
      // and finally the record ID so equal records never reorder.
      compare = chain(
        primary(chain(byFirstDate, byLastDate)),
        byKind,
        byStaffName(names, 'asc'),
        byId,
      )
      break
    case 'staff':
      compare = chain(byStaffName(names, direction), byFirstDate, byLastDate, byKind, byId)
      break
    case 'status':
      compare = chain(primary(rank), byFirstDate, byLastDate, byId)
      break
  }
  return [...items].sort(compare)
}
