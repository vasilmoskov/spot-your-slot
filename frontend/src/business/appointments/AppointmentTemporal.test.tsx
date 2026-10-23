import '@testing-library/jest-dom/vitest'
import { cleanup, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import { confirmed, cancelled, type FakeLifecycleBackend } from './lifecycleTestFixtures'
import { installBackend, ready, renderDetail } from './lifecycleTestHarness'

vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
let backend: FakeLifecycleBackend

const iso = (ms: number) => new Date(ms).toISOString().replace('Z', '+00:00')
const badges = () => document.querySelector('.calendar-badges')!

beforeEach(() => {
  backend = installBackend(mockedRequest)
})

afterEach(cleanup)

describe('Appointment detail: booking status and temporal position are separate badges', () => {
  it('shows «Потвърден» and «Предстоящ» for a confirmed Appointment that has not started', async () => {
    renderDetail()
    await ready()
    expect([...badges().querySelectorAll('.status-badge')].map((badge) => badge.textContent)).toEqual(['Потвърден', 'Предстоящ'])
    expect(badges().querySelector('.calendar-temporal')).toHaveClass('status-badge-info')
  })

  it('shows «В момента» between the start and the end, and «Минал» after the end', async () => {
    const now = Date.now()
    backend.current = confirmed({ start: iso(now - 60_000), end: iso(now + 3_600_000) })
    renderDetail({ now: new Date(now) })
    await ready()
    expect(badges()).toHaveTextContent('В момента')
    expect(badges().querySelector('.calendar-temporal')).toHaveClass('status-badge-warning')
    cleanup()
    backend.current = confirmed({ start: iso(now - 7_200_000), end: iso(now - 3_600_000) })
    renderDetail({ now: new Date(now) })
    await ready()
    expect(badges()).toHaveTextContent('Минал')
    expect(badges().querySelector('.calendar-temporal')).toHaveClass('status-badge-neutral')
    expect(document.body.textContent).not.toMatch(/Завършен|Проведен|Неявил/)
  })

  it('gives a cancelled Appointment «Отменен» and no temporal badge', async () => {
    backend.current = cancelled()
    renderDetail()
    await ready()
    expect([...badges().querySelectorAll('.status-badge')].map((badge) => badge.textContent)).toEqual(['Отменен'])
  })

  it('follows the clock on an open screen: when the start passes the badge changes and the change actions go, without a new read', async () => {
    const start = Date.now() + 700
    backend.current = confirmed({ start: iso(start), end: iso(start + 3_600_000) })
    renderDetail({ now: () => new Date() })
    await ready()
    // Not started: the badge says so and the owner may change or cancel the visit.
    expect(badges()).toHaveTextContent('Предстоящ')
    expect(screen.getByRole('button', { name: 'Отмени часа' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Промени часа' })).toBeInTheDocument()
    const reads = mockedRequest.mock.calls.length
    await waitFor(() => expect(badges()).toHaveTextContent('В момента'), { timeout: 4000 })
    // A visit that has started can be neither rescheduled nor cancelled (the server decides again), so the actions are gone.
    expect(screen.queryByRole('button', { name: 'Отмени часа' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Промени часа' })).toBeNull()
    expect(mockedRequest.mock.calls.length).toBe(reads)
  })

  it('mirrors the server on reschedule eligibility: owner, ACTIVE Business, confirmed, strictly before the start', async () => {
    const start = Date.parse('2026-11-10T09:00:00+02:00')
    const open = async (now: number, extra: Partial<Parameters<typeof renderDetail>[0]> = {}, detail = confirmed({ start: iso(start), end: iso(start + 2_700_000) })) => {
      cleanup()
      backend.current = detail
      renderDetail({ now: new Date(now), ...extra })
      await ready()
      return screen.queryByRole('button', { name: 'Промени часа' })
    }
    expect(await open(start - 1)).toBeInTheDocument()
    // The server treats an Appointment starting exactly at its clock instant as started.
    expect(await open(start)).toBeNull()
    expect(await open(start + 1)).toBeNull()
    // Not the owner (MANAGER and STAFF never change it), not ACTIVE, not CONFIRMED: absent, never disabled.
    expect(await open(start - 1, { canChange: false })).toBeNull()
    expect(await open(start - 1, {}, confirmed({ start: iso(start), end: iso(start + 2_700_000), businessStatus: 'SUSPENDED' }))).toBeNull()
    expect(await open(start - 1, {}, confirmed({ start: iso(start), end: iso(start + 2_700_000), businessStatus: 'DRAFT' }))).toBeNull()
    expect(await open(start - 1, {}, cancelled({ start: iso(start), end: iso(start + 2_700_000) }))).toBeNull()
  })
})
