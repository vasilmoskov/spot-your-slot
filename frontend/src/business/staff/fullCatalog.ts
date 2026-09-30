import { listStaffMembers, type StaffMemberSummary } from './api'

// Every StaffMember page is loaded (not only the first), so a selector or a
// name lookup is never silently limited to the first page. One shared
// AbortSignal cancels every page request at once.
export const STAFF_CATALOG_PAGE_SIZE = 50

export async function loadEveryStaffMember(signal: AbortSignal): Promise<StaffMemberSummary[]> {
  const first = await listStaffMembers(0, STAFF_CATALOG_PAGE_SIZE, 'name', 'asc', signal)
  const collected = [...first.staffMembers]
  const totalPages = Math.ceil(first.totalElements / STAFF_CATALOG_PAGE_SIZE)
  for (let page = 1; page < totalPages; page += 1) {
    const next = await listStaffMembers(page, STAFF_CATALOG_PAGE_SIZE, 'name', 'asc', signal)
    collected.push(...next.staffMembers)
  }
  return collected
}
