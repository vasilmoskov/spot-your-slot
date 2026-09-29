import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import { getWorkingSchedule, replaceWorkingSchedule } from './api'

vi.mock('../../identity/api', async (importOriginal) => {
  const original = await importOriginal<typeof import('../../identity/api')>()
  return { ...original, request: vi.fn() }
})

const mockedRequest = vi.mocked(request)

describe('schedule api client', () => {
  beforeEach(() => {
    mockedRequest.mockReset()
  })

  it('requests the working schedule for a StaffMember', () => {
    mockedRequest.mockResolvedValue({})
    void getWorkingSchedule('staff-a')
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/staff-members/staff-a/working-schedule',
      {},
    )
  })

  it('passes the abort signal through to the underlying request', () => {
    mockedRequest.mockResolvedValue({})
    const controller = new AbortController()
    void getWorkingSchedule('staff-a', controller.signal)
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/staff-members/staff-a/working-schedule',
      { signal: controller.signal },
    )
  })

  it('replaces the complete working schedule with expectedVersion and the full period set', () => {
    mockedRequest.mockResolvedValue({})
    void replaceWorkingSchedule('staff-a', {
      expectedVersion: 3,
      periods: [
        { weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' },
        { weekday: 'MONDAY', startTime: '14:00', endTime: '18:00' },
      ],
    })
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/staff-members/staff-a/working-schedule',
      {
        method: 'PUT',
        body: JSON.stringify({
          expectedVersion: 3,
          periods: [
            { weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' },
            { weekday: 'MONDAY', startTime: '14:00', endTime: '18:00' },
          ],
        }),
      },
    )
  })

  it('replaces with an empty period list to clear the schedule', () => {
    mockedRequest.mockResolvedValue({})
    void replaceWorkingSchedule('staff-a', { expectedVersion: 5, periods: [] })
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/staff-members/staff-a/working-schedule',
      { method: 'PUT', body: JSON.stringify({ expectedVersion: 5, periods: [] }) },
    )
  })

  it('URL-encodes the StaffMember id', () => {
    mockedRequest.mockResolvedValue({})
    void getWorkingSchedule('staff a/b')
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/staff-members/staff%20a%2Fb/working-schedule',
      {},
    )
  })
})
