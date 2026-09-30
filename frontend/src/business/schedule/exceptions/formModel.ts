import {
  SCHEDULE_MAX_DATE,
  SCHEDULE_MIN_DATE,
  inclusiveDateCount,
  isCanonicalDate,
} from '../../../navigation'
import type { ExceptionKind, ScheduleExceptionItem } from './api'
import {
  MAX_EXCEPTION_PERIODS,
  MAX_FULL_DAY_SPAN_DATES,
  TOO_MANY_PERIODS_MESSAGE,
  isBlockKind,
  isStaffScoped,
  sortPeriods,
  type DraftPeriod,
} from './presentation'

// `WHOLE` is whole dates for closures and time off, and "no working hours"
// for a working-day override; `PART` is one date with local time periods.
// Additional working periods are always `PART`.
export type ExceptionMode = 'WHOLE' | 'PART'

export type ExceptionFormValues = {
  kind: ExceptionKind | ''
  staffMemberId: string
  mode: ExceptionMode
  firstDate: string
  lastDate: string
  periods: DraftPeriod[]
}

export type ExceptionField = 'kind' | 'staffMemberId' | 'firstDate' | 'lastDate' | 'periods'

export const EXCEPTION_FIELD_ORDER: readonly ExceptionField[] = [
  'kind',
  'staffMemberId',
  'firstDate',
  'lastDate',
  'periods',
]

// Backend names these body fields in `fieldErrors`; `allDay` has no control of
// its own, so its message belongs under the mode choice, which is `periods`.
export const EXCEPTION_BACKEND_FIELDS: readonly ExceptionField[] = [
  'kind',
  'staffMemberId',
  'firstDate',
  'lastDate',
  'periods',
]

export const EXCEPTION_REJECTED_MESSAGE = 'Проверете въведените данни и опитайте отново.'

export function emptyValues(): ExceptionFormValues {
  return { kind: '', staffMemberId: '', mode: 'WHOLE', firstDate: '', lastDate: '', periods: [] }
}

let clientIdCounter = 0
export function nextClientId(): string {
  clientIdCounter += 1
  return `exception-period-${clientIdCounter}`
}

export function valuesFromItem(item: ScheduleExceptionItem): ExceptionFormValues {
  const whole =
    item.kind === 'WORKING_DAY_OVERRIDE' ? item.periods.length === 0 : item.allDay
  return {
    kind: item.kind,
    staffMemberId: item.staffMemberId ?? '',
    mode: whole ? 'WHOLE' : 'PART',
    firstDate: item.firstDate,
    lastDate: item.lastDate,
    periods: item.periods.map((period) => ({ clientId: nextClientId(), ...period })),
  }
}

/** Whether the current mode/kind uses a date range (whole dates of a block). */
export function usesDateRange(values: ExceptionFormValues): boolean {
  return values.kind !== '' && isBlockKind(values.kind) && values.mode === 'WHOLE'
}

/** Whether the current mode/kind asks for time periods. */
export function usesPeriods(values: ExceptionFormValues): boolean {
  if (values.kind === '') return false
  if (values.kind === 'ADDITIONAL_WORKING_PERIODS') return true
  return values.mode === 'PART'
}

// "Работни часове" always needs at least one period; a working-day override
// with no periods is expressed by choosing "Неработен ден" instead.
export function periodsRequired(values: ExceptionFormValues): boolean {
  return usesPeriods(values)
}

function effectiveMode(values: ExceptionFormValues): ExceptionMode {
  return values.kind === 'ADDITIONAL_WORKING_PERIODS' ? 'PART' : values.mode
}

const DATE_MESSAGE = 'Въведете валидна дата между 01.01.2000 и 31.12.2100.'

function validDate(value: string): boolean {
  return isCanonicalDate(value) && value >= SCHEDULE_MIN_DATE && value <= SCHEDULE_MAX_DATE
}

export function validateException(
  values: ExceptionFormValues,
  requireStaffSelection: boolean,
): Partial<Record<ExceptionField, string>> {
  const errors: Partial<Record<ExceptionField, string>> = {}
  if (values.kind === '') {
    errors.kind = 'Изберете вид промяна.'
    return errors
  }
  if (requireStaffSelection && isStaffScoped(values.kind) && !values.staffMemberId) {
    errors.staffMemberId = 'Изберете член на екипа.'
  }
  const range = usesDateRange(values)
  if (!values.firstDate) errors.firstDate = range ? 'Въведете начална дата.' : 'Въведете дата.'
  else if (!validDate(values.firstDate)) errors.firstDate = DATE_MESSAGE
  if (range) {
    if (!values.lastDate) errors.lastDate = 'Въведете крайна дата.'
    else if (!validDate(values.lastDate)) errors.lastDate = DATE_MESSAGE
    else if (!errors.firstDate) {
      if (values.lastDate < values.firstDate) {
        errors.lastDate = 'Крайната дата не може да бъде преди началната.'
      } else if (inclusiveDateCount(values.firstDate, values.lastDate) > MAX_FULL_DAY_SPAN_DATES) {
        errors.lastDate = `Периодът може да обхваща най-много ${MAX_FULL_DAY_SPAN_DATES} дни.`
      }
    }
  }
  if (periodsRequired(values) && values.periods.length === 0) {
    errors.periods = 'Добавете поне един период.'
  } else if (usesPeriods(values) && values.periods.length > MAX_EXCEPTION_PERIODS) {
    errors.periods = TOO_MANY_PERIODS_MESSAGE
  }
  return errors
}

export type ExceptionPayload = {
  firstDate: string
  lastDate: string
  allDay: boolean
  periods: { startTime: string; endTime: string }[]
}

/** The kind-independent body fields sent on create and replace. */
export function toPayload(values: ExceptionFormValues): ExceptionPayload {
  const kind = values.kind as ExceptionKind
  const mode = effectiveMode(values)
  const allDay = isBlockKind(kind) && mode === 'WHOLE'
  const periods =
    !allDay && mode === 'PART'
      ? sortPeriods(values.periods).map(({ startTime, endTime }) => ({ startTime, endTime }))
      : []
  return {
    firstDate: values.firstDate,
    lastDate: allDay ? values.lastDate : values.firstDate,
    allDay,
    periods,
  }
}

/** A stable comparison key: a form is dirty only when this differs from the saved one. */
export function dirtyKey(values: ExceptionFormValues): string {
  if (values.kind === '') return JSON.stringify({ kind: '', staff: values.staffMemberId })
  return JSON.stringify({
    kind: values.kind,
    staff: isStaffScoped(values.kind) ? values.staffMemberId : '',
    mode: effectiveMode(values),
    ...toPayload(values),
  })
}
