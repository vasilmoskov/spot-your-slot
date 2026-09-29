import { request } from '../../identity/api'

export type Weekday =
  | 'MONDAY'
  | 'TUESDAY'
  | 'WEDNESDAY'
  | 'THURSDAY'
  | 'FRIDAY'
  | 'SATURDAY'
  | 'SUNDAY'

export type WorkingPeriod = {
  weekday: Weekday
  startTime: string
  endTime: string
}

export type WorkingSchedule = {
  staffMemberId: string
  timezone: string
  periods: WorkingPeriod[]
  version: number
  createdAt: string
  updatedAt: string
}

export type ReplaceWorkingScheduleInput = {
  expectedVersion: number
  periods: WorkingPeriod[]
}

export function getWorkingSchedule(
  staffMemberId: string,
  signal?: AbortSignal,
): Promise<WorkingSchedule> {
  return request<WorkingSchedule>(
    `/api/business/staff-members/${encodeURIComponent(staffMemberId)}/working-schedule`,
    signal ? { signal } : {},
  )
}

export function replaceWorkingSchedule(
  staffMemberId: string,
  input: ReplaceWorkingScheduleInput,
): Promise<WorkingSchedule> {
  return request<WorkingSchedule>(
    `/api/business/staff-members/${encodeURIComponent(staffMemberId)}/working-schedule`,
    {
      method: 'PUT',
      body: JSON.stringify(input),
    },
  )
}
