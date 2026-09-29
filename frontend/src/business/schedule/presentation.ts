import type { Weekday } from './api'

export const WEEKDAY_ORDER: readonly Weekday[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
]

// Capitalized standalone form: weekday column headings and other
// standalone labels (e.g. copy-target checkbox labels), never mid-sentence.
export const WEEKDAY_LABELS: Record<Weekday, string> = {
  MONDAY: 'Понеделник',
  TUESDAY: 'Вторник',
  WEDNESDAY: 'Сряда',
  THURSDAY: 'Четвъртък',
  FRIDAY: 'Петък',
  SATURDAY: 'Събота',
  SUNDAY: 'Неделя',
}

// Lowercase sentence-form: for every place a weekday name is embedded in
// the middle of a Bulgarian sentence or phrase (dialog titles, body text,
// accessible names) rather than standing alone as a heading.
export const WEEKDAY_SENTENCE_LABELS: Record<Weekday, string> = {
  MONDAY: 'понеделник',
  TUESDAY: 'вторник',
  WEDNESDAY: 'сряда',
  THURSDAY: 'четвъртък',
  FRIDAY: 'петък',
  SATURDAY: 'събота',
  SUNDAY: 'неделя',
}

// Matches the backend's exact contract limit (StaffWorkingScheduleInputValidator.MAX_PERIODS).
export const MAX_WORKING_PERIODS = 100

const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/

export function formatPeriodRange(startTime: string, endTime: string): string {
  return `${startTime}–${endTime}`
}

// The single deterministic schedule order — Monday→Sunday, then start time
// ascending, then end time ascending as a tie-breaker — used everywhere a
// period list is shown or sent to the backend: the read-only view, the
// editable draft (re-applied after every local operation: add, edit, copy,
// remove, clear, or discard), and the final atomic PUT payload. Generic so
// it works on both the plain WorkingPeriod shape and the editable
// DraftPeriod shape (which carries an extra clientId the sort never reads
// or reassigns).
export function sortPeriodsForDisplay<
  T extends { weekday: Weekday; startTime: string; endTime: string },
>(periods: readonly T[]): T[] {
  return [...periods].sort((a, b) => {
    const weekdayDiff = WEEKDAY_ORDER.indexOf(a.weekday) - WEEKDAY_ORDER.indexOf(b.weekday)
    if (weekdayDiff !== 0) return weekdayDiff
    if (a.startTime !== b.startTime) return a.startTime.localeCompare(b.startTime)
    return a.endTime.localeCompare(b.endTime)
  })
}

export function groupPeriodsByWeekday<T extends { weekday: Weekday }>(
  periods: readonly T[],
): Record<Weekday, T[]> {
  const grouped = Object.fromEntries(WEEKDAY_ORDER.map((weekday) => [weekday, [] as T[]])) as Record<
    Weekday,
    T[]
  >
  for (const period of periods) {
    grouped[period.weekday].push(period)
  }
  return grouped
}

export type DraftPeriod = {
  clientId: string
  weekday: Weekday
  startTime: string
  endTime: string
}

export type PeriodValidationError = 'MISSING_TIMES' | 'INVALID_FORMAT' | 'REVERSED_RANGE' | 'OVERLAP'

export type DraftValidationResult = {
  errorsByPeriod: Map<string, PeriodValidationError>
  tooManyPeriods: boolean
  valid: boolean
}

function isValidTime(value: string): boolean {
  return TIME_PATTERN.test(value)
}

// Pure, synchronous client-side validation mirroring the backend's own rules
// (StaffWorkingScheduleInputValidator / WorkingPeriodOverlapValidator): both
// times required and canonical HH:mm, start strictly before end, no two
// periods on the same weekday overlapping. Adjacent periods (one period's
// endTime equal to another's startTime) are intentionally accepted, matching
// the backend's half-open interval semantics. Duplicate periods are caught
// by the same overlap check, since an identical range always overlaps
// itself. The backend remains authoritative; this only improves UX by
// surfacing the same failures immediately, next to the affected period.
export function validateDraftPeriods(periods: readonly DraftPeriod[]): DraftValidationResult {
  const errorsByPeriod = new Map<string, PeriodValidationError>()
  const tooManyPeriods = periods.length > MAX_WORKING_PERIODS

  for (const period of periods) {
    if (!period.startTime || !period.endTime) {
      errorsByPeriod.set(period.clientId, 'MISSING_TIMES')
      continue
    }
    if (!isValidTime(period.startTime) || !isValidTime(period.endTime)) {
      errorsByPeriod.set(period.clientId, 'INVALID_FORMAT')
      continue
    }
    if (period.startTime >= period.endTime) {
      errorsByPeriod.set(period.clientId, 'REVERSED_RANGE')
    }
  }

  const byWeekday = new Map<Weekday, DraftPeriod[]>()
  for (const period of periods) {
    if (errorsByPeriod.has(period.clientId)) continue
    const list = byWeekday.get(period.weekday) ?? []
    list.push(period)
    byWeekday.set(period.weekday, list)
  }

  for (const dayPeriods of byWeekday.values()) {
    const sorted = [...dayPeriods].sort((a, b) => a.startTime.localeCompare(b.startTime))
    for (let i = 0; i < sorted.length; i += 1) {
      const earlier = sorted[i]
      if (!earlier) continue
      for (let j = i + 1; j < sorted.length; j += 1) {
        const later = sorted[j]
        if (!later) continue
        if (earlier.startTime < later.endTime && later.startTime < earlier.endTime) {
          errorsByPeriod.set(earlier.clientId, 'OVERLAP')
          errorsByPeriod.set(later.clientId, 'OVERLAP')
        }
      }
    }
  }

  return {
    errorsByPeriod,
    tooManyPeriods,
    valid: errorsByPeriod.size === 0 && !tooManyPeriods,
  }
}

export function periodValidationMessage(error: PeriodValidationError): string {
  switch (error) {
    case 'MISSING_TIMES':
      return 'Въведете начален и краен час.'
    case 'INVALID_FORMAT':
      return 'Часът трябва да бъде във формат ЧЧ:ММ.'
    case 'REVERSED_RANGE':
      return 'Началният час трябва да бъде преди крайния.'
    case 'OVERLAP':
      return 'Периодът се припокрива с друг период за същия ден.'
  }
}
