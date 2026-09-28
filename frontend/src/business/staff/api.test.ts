import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import {
  createStaffMember,
  deactivateStaffMember,
  getStaffMember,
  listStaffMemberAssignments,
  listStaffMembers,
  reactivateStaffMember,
  replaceStaffMemberAssignments,
  updateStaffMember,
} from './api'

vi.mock('../../identity/api', async (importOriginal) => {
  const original = await importOriginal<typeof import('../../identity/api')>()
  return { ...original, request: vi.fn() }
})

const mockedRequest = vi.mocked(request)

describe('staff api client', () => {
  beforeEach(() => {
    mockedRequest.mockReset()
  })

  it('requests the paginated StaffMember list using the default sort and direction', () => {
    mockedRequest.mockResolvedValue({ staffMembers: [], page: 0, size: 10, totalElements: 0 })
    void listStaffMembers(1, 20)
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/staff-members?page=1&size=20&sort=name&direction=asc',
      {},
    )
  })

  it('requests an explicit sort field and direction', () => {
    mockedRequest.mockResolvedValue({ staffMembers: [], page: 0, size: 25, totalElements: 0 })
    const controller = new AbortController()
    void listStaffMembers(2, 25, 'status', 'desc', controller.signal)
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/staff-members?page=2&size=25&sort=status&direction=desc',
      { signal: controller.signal },
    )
  })

  it('requests a single StaffMember by id', () => {
    mockedRequest.mockResolvedValue({})
    void getStaffMember('staff-a')
    expect(mockedRequest).toHaveBeenCalledWith('/api/business/staff-members/staff-a', {})
  })

  it('creates a StaffMember with only the current backend fields', () => {
    mockedRequest.mockResolvedValue({})
    void createStaffMember({
      displayName: 'Анна Иванова',
      contactEmail: 'anna@example.invalid',
      contactPhone: undefined,
    })
    expect(mockedRequest).toHaveBeenCalledWith('/api/business/staff-members', {
      method: 'POST',
      body: JSON.stringify({
        displayName: 'Анна Иванова',
        contactEmail: 'anna@example.invalid',
      }),
    })
  })

  it('preserves expectedVersion on update and allows clearing optional contact fields', () => {
    mockedRequest.mockResolvedValue({})
    void updateStaffMember('staff-a', {
      displayName: 'Анна Петрова',
      contactEmail: undefined,
      contactPhone: '+359 888 123 456',
      expectedVersion: 3,
    })
    expect(mockedRequest).toHaveBeenCalledWith('/api/business/staff-members/staff-a', {
      method: 'PUT',
      body: JSON.stringify({
        displayName: 'Анна Петрова',
        contactPhone: '+359 888 123 456',
        expectedVersion: 3,
      }),
    })
  })

  it('sends expectedVersion for deactivate and reactivate', () => {
    mockedRequest.mockResolvedValue({})
    void deactivateStaffMember('staff-a', 2)
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/staff-members/staff-a/deactivate',
      { method: 'POST', body: JSON.stringify({ expectedVersion: 2 }) },
    )

    void reactivateStaffMember('staff-a', 3)
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/staff-members/staff-a/reactivate',
      { method: 'POST', body: JSON.stringify({ expectedVersion: 3 }) },
    )
  })

  it('requests the current assigned Services', () => {
    mockedRequest.mockResolvedValue({})
    void listStaffMemberAssignments('staff-a')
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/staff-members/staff-a/service-assignments',
      {},
    )
  })

  it('replaces assignments with the complete desired set and expectedVersion', () => {
    mockedRequest.mockResolvedValue({})
    void replaceStaffMemberAssignments('staff-a', {
      serviceIds: ['service-a', 'service-b'],
      expectedVersion: 4,
    })
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/business/staff-members/staff-a/service-assignments',
      {
        method: 'PUT',
        body: JSON.stringify({
          serviceIds: ['service-a', 'service-b'],
          expectedVersion: 4,
        }),
      },
    )
  })
})
