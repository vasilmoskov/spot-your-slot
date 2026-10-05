import { businessRequest as request } from '../../../identity/businessRequest'

export type ExceptionKind =
  | 'BUSINESS_CLOSURE'
  | 'STAFF_TIME_OFF'
  | 'WORKING_DAY_OVERRIDE'
  | 'ADDITIONAL_WORKING_PERIODS'

export type ExceptionPeriod = {
  startTime: string
  endTime: string
}

export type ScheduleExceptionItem = {
  id: string
  kind: ExceptionKind
  staffMemberId: string | null
  firstDate: string
  lastDate: string
  allDay: boolean
  periods: ExceptionPeriod[]
  version: number
  createdAt: string
  updatedAt: string
}

export type ScheduleExceptionDetails = ScheduleExceptionItem & { timezone: string }

export type ScheduleExceptionWindow = {
  from: string
  to: string
  timezone: string
  exceptions: ScheduleExceptionItem[]
}

export type CreateScheduleExceptionInput = {
  kind: ExceptionKind
  staffMemberId?: string
  firstDate: string
  lastDate: string
  allDay: boolean
  periods: ExceptionPeriod[]
}

export type ReplaceScheduleExceptionInput = {
  expectedVersion: number
  firstDate: string
  lastDate: string
  allDay: boolean
  periods: ExceptionPeriod[]
}

const BASE = '/api/business/schedule-exceptions'

export function listScheduleExceptions(
  from: string,
  to: string,
  signal?: AbortSignal,
): Promise<ScheduleExceptionWindow> {
  const params = new URLSearchParams({ from, to })
  return request<ScheduleExceptionWindow>(`${BASE}?${params.toString()}`, signal ? { signal } : {})
}

export function getScheduleException(
  exceptionId: string,
  signal?: AbortSignal,
): Promise<ScheduleExceptionDetails> {
  return request<ScheduleExceptionDetails>(
    `${BASE}/${encodeURIComponent(exceptionId)}`,
    signal ? { signal } : {},
  )
}

export function createScheduleException(
  input: CreateScheduleExceptionInput,
): Promise<ScheduleExceptionDetails> {
  return request<ScheduleExceptionDetails>(BASE, {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function replaceScheduleException(
  exceptionId: string,
  input: ReplaceScheduleExceptionInput,
): Promise<ScheduleExceptionDetails> {
  return request<ScheduleExceptionDetails>(`${BASE}/${encodeURIComponent(exceptionId)}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}

export function deleteScheduleException(
  exceptionId: string,
  expectedVersion: number,
): Promise<void> {
  const params = new URLSearchParams({ expectedVersion: String(expectedVersion) })
  return request<void>(`${BASE}/${encodeURIComponent(exceptionId)}?${params.toString()}`, {
    method: 'DELETE',
  })
}
