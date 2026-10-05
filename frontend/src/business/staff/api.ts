import { businessRequest as request } from '../../identity/businessRequest'

export type StaffMemberSummary = {
  id: string
  displayName: string
  contactEmail: string | null
  contactPhone: string | null
  active: boolean
  version: number
  createdAt: string
  updatedAt: string
}

export type StaffMemberDetails = StaffMemberSummary

export type StaffMemberPage = {
  staffMembers: StaffMemberSummary[]
  page: number
  size: number
  totalElements: number
}

export type CreateStaffMemberInput = {
  displayName: string
  contactEmail?: string | undefined
  contactPhone?: string | undefined
}

export type UpdateStaffMemberInput = CreateStaffMemberInput & { expectedVersion: number }

export type AssignedService = {
  id: string
  name: string
  active: boolean
}

export type StaffMemberAssignments = {
  staffMemberId: string
  version: number
  createdAt: string
  updatedAt: string
  services: AssignedService[]
}

export type ReplaceAssignmentsInput = {
  serviceIds: string[]
  expectedVersion: number
}

export function listStaffMembers(
  page = 0,
  size = 10,
  sort = 'name',
  direction: 'asc' | 'desc' = 'asc',
  signal?: AbortSignal,
): Promise<StaffMemberPage> {
  const options: RequestInit = signal ? { signal } : {}
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
    sort,
    direction,
  })
  return request<StaffMemberPage>(`/api/business/staff-members?${params.toString()}`, options)
}

export function getStaffMember(
  staffMemberId: string,
  signal?: AbortSignal,
): Promise<StaffMemberDetails> {
  return request<StaffMemberDetails>(
    `/api/business/staff-members/${encodeURIComponent(staffMemberId)}`,
    signal ? { signal } : {},
  )
}

export function createStaffMember(input: CreateStaffMemberInput): Promise<StaffMemberDetails> {
  return request<StaffMemberDetails>('/api/business/staff-members', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function updateStaffMember(
  staffMemberId: string,
  input: UpdateStaffMemberInput,
): Promise<StaffMemberDetails> {
  return request<StaffMemberDetails>(
    `/api/business/staff-members/${encodeURIComponent(staffMemberId)}`,
    {
      method: 'PUT',
      body: JSON.stringify(input),
    },
  )
}

export function deactivateStaffMember(
  staffMemberId: string,
  expectedVersion: number,
): Promise<StaffMemberDetails> {
  return request<StaffMemberDetails>(
    `/api/business/staff-members/${encodeURIComponent(staffMemberId)}/deactivate`,
    {
      method: 'POST',
      body: JSON.stringify({ expectedVersion }),
    },
  )
}

export function reactivateStaffMember(
  staffMemberId: string,
  expectedVersion: number,
): Promise<StaffMemberDetails> {
  return request<StaffMemberDetails>(
    `/api/business/staff-members/${encodeURIComponent(staffMemberId)}/reactivate`,
    {
      method: 'POST',
      body: JSON.stringify({ expectedVersion }),
    },
  )
}

export function listStaffMemberAssignments(
  staffMemberId: string,
  signal?: AbortSignal,
): Promise<StaffMemberAssignments> {
  return request<StaffMemberAssignments>(
    `/api/business/staff-members/${encodeURIComponent(staffMemberId)}/service-assignments`,
    signal ? { signal } : {},
  )
}

export function replaceStaffMemberAssignments(
  staffMemberId: string,
  input: ReplaceAssignmentsInput,
): Promise<StaffMemberAssignments> {
  return request<StaffMemberAssignments>(
    `/api/business/staff-members/${encodeURIComponent(staffMemberId)}/service-assignments`,
    {
      method: 'PUT',
      body: JSON.stringify(input),
    },
  )
}
