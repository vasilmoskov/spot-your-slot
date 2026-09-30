import { expect, type Page } from '@playwright/test'
import { apiRequest } from './browser'

// Schedule changes (backend kind names stay internal to this support module).
export type ChangeKind =
  | 'BUSINESS_CLOSURE'
  | 'STAFF_TIME_OFF'
  | 'WORKING_DAY_OVERRIDE'
  | 'ADDITIONAL_WORKING_PERIODS'

export type SeedChange = {
  kind: ChangeKind
  staffMemberId?: string
  firstDate: string
  lastDate?: string
  allDay: boolean
  periods?: { startTime: string, endTime: string }[]
}

const BUSINESS_TIME_ZONE = 'Europe/Sofia'
const CHANGES_PATH = '/api/business/schedule-exceptions'

/** The Business-local (Europe/Sofia) calendar date, independent of the machine zone. */
export function businessToday(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: BUSINESS_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const value = (type: string) => parts.find((part) => part.type === type)?.value
  return `${value('year')}-${value('month')}-${value('day')}`
}

export function addDays(date: string, days: number): string {
  const moved = new Date(Date.parse(`${date}T00:00:00Z`) + days * 86_400_000)
  return moved.toISOString().slice(0, 10)
}

/** `yyyy-MM-dd` as the interface shows it: `dd.MM.yyyy`. */
export function shown(date: string): string {
  const [year, month, day] = date.split('-')
  return `${day}.${month}.${year}`
}

export function shownRange(firstDate: string, lastDate: string): string {
  return firstDate === lastDate ? shown(firstDate) : `${shown(firstDate)} – ${shown(lastDate)}`
}

/** The canonical list query string the route carries for a window and state. */
export function listQuery(
  from: string,
  to: string,
  state: { page?: number, size?: number, sort?: string, direction?: string } = {},
): string {
  return new URLSearchParams({
    from,
    to,
    page: String(state.page ?? 0),
    size: String(state.size ?? 10),
    sort: state.sort ?? 'dates',
    direction: state.direction ?? 'asc',
  }).toString()
}

export type CreatedChange = { id: string, version: number }

/** Creates a record through the private API and returns its identifier and version. */
export async function seedChange(page: Page, change: SeedChange): Promise<CreatedChange> {
  const result = await apiRequest(page, 'POST', CHANGES_PATH, {
    kind: change.kind,
    ...(change.staffMemberId ? { staffMemberId: change.staffMemberId } : {}),
    firstDate: change.firstDate,
    lastDate: change.lastDate ?? change.firstDate,
    allDay: change.allDay,
    periods: change.periods ?? [],
  })
  expect(result.status, result.text).toBe(201)
  const body = JSON.parse(result.text) as { id: string, version: number }
  return { id: body.id, version: body.version }
}

export async function seedClosure(
  page: Page,
  firstDate: string,
  lastDate: string = firstDate,
): Promise<CreatedChange> {
  return seedChange(page, { kind: 'BUSINESS_CLOSURE', firstDate, lastDate, allDay: true })
}

export async function seedTimeOff(
  page: Page,
  staffMemberId: string,
  firstDate: string,
  lastDate: string = firstDate,
): Promise<CreatedChange> {
  return seedChange(page, {
    kind: 'STAFF_TIME_OFF',
    staffMemberId,
    firstDate,
    lastDate,
    allDay: true,
  })
}

export async function createStaffMember(page: Page, displayName: string): Promise<string> {
  const result = await apiRequest(page, 'POST', '/api/business/staff-members', { displayName })
  expect(result.status, result.text).toBe(201)
  return (JSON.parse(result.text) as { id: string }).id
}

/**
 * Collects the identifiers of records a test creates so cleanup can remove them
 * even after a partial failure. Cleanup reads the current version first, ignores
 * records that are already gone, and never throws.
 */
export class ChangeTracker {
  private readonly ids = new Set<string>()

  track(id: string): void {
    this.ids.add(id)
  }

  trackFromUrl(page: Page): string {
    const id = decodeURIComponent(new URL(page.url()).hash.split('?')[0]!.split('/').pop() ?? '')
    if (!id) throw new Error('The current route does not end in a record id')
    this.ids.add(id)
    return id
  }

  async cleanup(page: Page): Promise<void> {
    for (const id of this.ids) {
      try {
        const current = await apiRequest(page, 'GET', `${CHANGES_PATH}/${encodeURIComponent(id)}`)
        if (current.status !== 200) continue
        const { version } = JSON.parse(current.text) as { version: number }
        await apiRequest(
          page,
          'DELETE',
          `${CHANGES_PATH}/${encodeURIComponent(id)}?expectedVersion=${version}`,
        )
      } catch {
        // Best effort: the disposable environment is removed after the run anyway.
      }
    }
    this.ids.clear()
  }
}
