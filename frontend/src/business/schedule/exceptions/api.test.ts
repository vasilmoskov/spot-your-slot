import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../../identity/api'
import {
  createScheduleException,
  deleteScheduleException,
  getScheduleException,
  listScheduleExceptions,
  replaceScheduleException,
} from './api'

vi.mock('../../../identity/api', async (importOriginal) => {
  const original = await importOriginal<typeof import('../../../identity/api')>()
  return { ...original, request: vi.fn() }
})

const mockedRequest = vi.mocked(request)

describe('schedule change api client', () => {
  beforeEach(() => {
    mockedRequest.mockReset()
    mockedRequest.mockResolvedValue({})
  })

  it('lists an inclusive window with the abort signal', () => {
    const controller = new AbortController()
    void listScheduleExceptions('2026-10-01', '2026-10-30', controller.signal)
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/schedule-exceptions?from=2026-10-01&to=2026-10-30',
      { signal: controller.signal },
    )
  })

  it('reads one record and encodes the identifier', () => {
    void getScheduleException('a/b')
    expect(mockedRequest).toHaveBeenCalledWith('/api/business/schedule-exceptions/a%2Fb', {})
  })

  it('creates with the exact body and never sends a Business identity', () => {
    const input = {
      kind: 'STAFF_TIME_OFF' as const,
      staffMemberId: 'staff-a',
      firstDate: '2026-10-01',
      lastDate: '2026-10-03',
      allDay: true,
      periods: [],
    }
    void createScheduleException(input)
    expect(mockedRequest).toHaveBeenCalledWith('/api/business/schedule-exceptions', {
      method: 'POST',
      body: JSON.stringify(input),
    })
    expect(JSON.stringify(input)).not.toContain('business')
  })

  it('replaces with expectedVersion and without kind or StaffMember', () => {
    const input = {
      expectedVersion: 4,
      firstDate: '2026-10-01',
      lastDate: '2026-10-01',
      allDay: false,
      periods: [{ startTime: '09:00', endTime: '12:00' }],
    }
    void replaceScheduleException('exception-a', input)
    expect(mockedRequest).toHaveBeenCalledWith('/api/business/schedule-exceptions/exception-a', {
      method: 'PUT',
      body: JSON.stringify(input),
    })
    const body = JSON.parse((mockedRequest.mock.calls[0]![1] as RequestInit).body as string)
    expect(Object.keys(body)).not.toContain('kind')
    expect(Object.keys(body)).not.toContain('staffMemberId')
  })

  it('deletes with expectedVersion as a query parameter', () => {
    void deleteScheduleException('exception-a', 7)
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/schedule-exceptions/exception-a?expectedVersion=7',
      { method: 'DELETE' },
    )
  })
})
