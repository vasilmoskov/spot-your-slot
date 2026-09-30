import '@testing-library/jest-dom/vitest'
import { useState } from 'react'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App, AuthenticatedApplication } from './App'
import { ApiError, request, type Session } from './identity/api'
import { UnsavedChangesGuardProvider } from './ui/UnsavedChangesGuard'
import { useFeedback } from './ui/useFeedback'
import { listStaffMembers, type StaffMemberPage, type StaffMemberSummary } from './business/staff/api'
import { getWorkingSchedule } from './business/schedule/api'
import {
  createScheduleException,
  deleteScheduleException,
  replaceScheduleException,
  getScheduleException,
  listScheduleExceptions,
  type ScheduleExceptionDetails,
  type ScheduleExceptionItem,
  type ScheduleExceptionWindow,
} from './business/schedule/exceptions/api'
import { addDays, localDateIn } from './business/schedule/exceptions/presentation'

vi.mock('./identity/api', async (importOriginal) => {
  const original = await importOriginal<typeof import('./identity/api')>()
  return { ...original, request: vi.fn() }
})
vi.mock('./business/staff/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./business/staff/api')>()),
  listStaffMembers: vi.fn(),
}))
vi.mock('./business/schedule/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./business/schedule/api')>()),
  getWorkingSchedule: vi.fn(),
}))
vi.mock('./business/schedule/exceptions/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./business/schedule/exceptions/api')>()),
  listScheduleExceptions: vi.fn(),
  getScheduleException: vi.fn(),
  createScheduleException: vi.fn(),
  replaceScheduleException: vi.fn(),
  deleteScheduleException: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
const mockedStaff = vi.mocked(listStaffMembers)
const mockedWeekly = vi.mocked(getWorkingSchedule)
const mockedList = vi.mocked(listScheduleExceptions)
const mockedGet = vi.mocked(getScheduleException)
const mockedCreate = vi.mocked(createScheduleException)
const mockedDelete = vi.mocked(deleteScheduleException)
const mockedReplace = vi.mocked(replaceScheduleException)

const owner: Session = {
  email: 'ivan@example.invalid',
  displayName: 'Иван',
  platformAdmin: false,
  businesses: [{ id: 'a', displayName: 'Бизнес А', role: 'BUSINESS_OWNER', status: 'ACTIVE' }],
  activeBusinessId: 'a',
}

function member(id: string, displayName: string, active = true): StaffMemberSummary {
  return {
    id,
    displayName,
    contactEmail: null,
    contactPhone: null,
    active,
    version: 0,
    createdAt: '2026-08-19T09:00:00Z',
    updatedAt: '2026-08-19T09:00:00Z',
  }
}

const staffPage: StaffMemberPage = {
  staffMembers: [member('staff-a', 'Анна Иванова')],
  page: 0,
  size: 50,
  totalElements: 1,
}

function record(overrides: Partial<ScheduleExceptionItem> = {}): ScheduleExceptionItem {
  return {
    id: 'exception-1',
    kind: 'BUSINESS_CLOSURE',
    staffMemberId: null,
    firstDate: '2026-12-24',
    lastDate: '2026-12-26',
    allDay: true,
    periods: [],
    version: 0,
    createdAt: '2026-09-29T08:00:00Z',
    updatedAt: '2026-09-29T08:00:00Z',
    ...overrides,
  }
}

function windowOf(from: string, to: string, exceptions: ScheduleExceptionItem[] = []) {
  return { from, to, timezone: 'Europe/Sofia', exceptions } satisfies ScheduleExceptionWindow
}

beforeEach(() => {
  history.replaceState({}, '', '/')
  mockedRequest.mockReset()
  mockedRequest.mockResolvedValue(owner)
  mockedStaff.mockReset()
  mockedStaff.mockResolvedValue(staffPage)
  mockedWeekly.mockReset()
  mockedList.mockReset()
  mockedList.mockImplementation((from, to) => Promise.resolve(windowOf(from, to)))
  mockedGet.mockReset()
  mockedCreate.mockReset()
  mockedDelete.mockReset()
  mockedReplace.mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('schedule navigation', () => {
  it('keeps one sidebar item and offers the two destinations as separate tabs', async () => {
    history.replaceState({}, '', '/#/business/schedule')
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Работно време' })).toBeInTheDocument()
    const sidebarLinks = screen.getAllByRole('link', { name: 'Работно време' })
    expect(sidebarLinks.length).toBeGreaterThan(0)
    const tabs = screen.getByRole('navigation', { name: 'Работно време' })
    expect(within(tabs).getByRole('link', { name: 'Седмични графици' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(tabs).getByRole('link', { name: 'Промени в графика' })).not.toHaveAttribute(
      'aria-current',
    )

    fireEvent.click(within(tabs).getByRole('link', { name: 'Промени в графика' }))

    await waitFor(() =>
      expect(window.location.hash).toMatch(/^#\/business\/schedule\/exceptions/),
    )
    // One shared heading; the active tab names the subsection, never a second heading.
    expect(screen.getAllByRole('heading', { name: 'Работно време' })).toHaveLength(1)
    expect(screen.queryByRole('heading', { name: 'Промени в графика' })).not.toBeInTheDocument()
    const exceptionTabs = screen.getByRole('navigation', { name: 'Работно време' })
    expect(within(exceptionTabs).getByRole('link', { name: 'Промени в графика' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(screen.getAllByRole('link', { name: 'Работно време' })[0]).toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  it('shows neither a lifecycle badge next to the eyebrow nor the Business timezone', async () => {
    history.replaceState({}, '', '/#/business/schedule/exceptions')
    render(<App />)

    await screen.findByRole('heading', { name: 'Работно време' })
    expect(screen.getByText('УПРАВЛЕНИЕ НА БИЗНЕСА')).toBeInTheDocument()
    expect(screen.queryByText('Активен')).not.toBeInTheDocument()
    expect(screen.queryByText('Предстои активиране')).not.toBeInTheDocument()
    expect(screen.queryByText(/Europe\/Sofia/)).not.toBeInTheDocument()
  })

  it('canonicalizes a bare hash by replacing history, not pushing', async () => {
    history.replaceState({}, '', '/#/business/schedule/exceptions')
    const lengthBefore = history.length
    const push = vi.spyOn(history, 'pushState')
    render(<App />)

    await screen.findByText('Няма промени за избрания период.')

    const today = localDateIn(new Date(), 'Europe/Sofia')!
    expect(window.location.hash).toBe(
      `#/business/schedule/exceptions?from=${today}&to=${addDays(today, 29)}&page=0&size=10&sort=dates&direction=asc`,
    )
    expect(push).not.toHaveBeenCalled()
    expect(history.length).toBe(lengthBefore)
  })

  it('pushes a manual filter change and Back restores the previous window', async () => {
    history.replaceState(
      {},
      '',
      '/#/business/schedule/exceptions?from=2026-10-01&to=2026-10-30&page=0&size=10&sort=dates&direction=asc',
    )
    render(<App />)
    await screen.findByText('Няма промени за избрания период.')

    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-11-01' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Покажи' }))
    await waitFor(() =>
      expect(window.location.hash).toBe(
        '#/business/schedule/exceptions?from=2026-11-01&to=2026-11-30&page=0&size=10&sort=dates&direction=asc',
      ),
    )
    await waitFor(() => expect(mockedList).toHaveBeenLastCalledWith('2026-11-01', '2026-11-30', expect.anything()))

    await act(async () => {
      history.pushState({}, '', '/#/business/schedule/exceptions?from=2026-10-01&to=2026-10-30&page=0&size=10&sort=dates&direction=asc')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })

    await waitFor(() => expect(screen.getByLabelText('От')).toHaveValue('2026-10-01'))
    expect(screen.getByLabelText('До')).toHaveValue('2026-10-30')
  })

  it('normalizes an unusable window in the URL to the canonical default', async () => {
    history.replaceState(
      {},
      '',
      '/#/business/schedule/exceptions?from=2026-10-30&to=2026-10-01',
    )
    render(<App />)

    await screen.findByText('Няма промени за избрания период.')
    expect(window.location.hash).toMatch(/\?from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}&page=0&size=10&sort=dates&direction=asc$/)
    expect(mockedList.mock.calls[0]![0] <= mockedList.mock.calls[0]![1]).toBe(true)
  })

  it.each(['MANAGER', 'STAFF'] as const)(
    'does not open schedule changes for the %s role',
    async (role) => {
      history.replaceState({}, '', '/#/business/schedule/exceptions')
      mockedRequest.mockResolvedValue({
        ...owner,
        businesses: [{ ...owner.businesses[0]!, role }],
      })
      render(<App />)

      expect(await screen.findByRole('heading', { name: 'Профил' })).toBeInTheDocument()
      expect(window.location.hash).toBe('#/profile')
      expect(mockedList).not.toHaveBeenCalled()
    },
  )

  it('does not give a PLATFORM_ADMIN without a Business Membership access', async () => {
    history.replaceState({}, '', '/#/business/schedule/exceptions')
    mockedRequest.mockResolvedValue({
      ...owner,
      platformAdmin: true,
      businesses: [],
      activeBusinessId: undefined as never,
    })
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Профил' })).toBeInTheDocument()
    expect(mockedList).not.toHaveBeenCalled()
    expect(screen.queryByRole('link', { name: 'Работно време' })).not.toBeInTheDocument()
  })

  it('shows the SUSPENDED banner once and hides mutation actions', async () => {
    history.replaceState(
      {},
      '',
      '/#/business/schedule/exceptions?from=2026-12-01&to=2026-12-31&page=0&size=10&sort=dates&direction=asc',
    )
    mockedRequest.mockResolvedValue({
      ...owner,
      businesses: [{ ...owner.businesses[0]!, status: 'SUSPENDED' }],
    })
    mockedList.mockResolvedValue(windowOf('2026-12-01', '2026-12-31', [record()]))
    render(<App />)

    await screen.findByRole('table')
    expect(screen.getAllByText(/Бизнесът е временно спрян/)).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Добави промяна' })).not.toBeInTheDocument()
  })
})

describe('full journeys', () => {
  it('creates a closure, lands on its detail with a success message, deletes it and returns to the list', async () => {
    history.replaceState(
      {},
      '',
      '/#/business/schedule/exceptions?from=2026-12-01&to=2026-12-31&page=0&size=10&sort=dates&direction=asc',
    )
    const created: ScheduleExceptionDetails = { ...record(), timezone: 'Europe/Sofia' }
    mockedCreate.mockResolvedValue(created)
    mockedGet.mockResolvedValue(created)
    mockedDelete.mockResolvedValue(undefined)
    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Добави промяна' }))
    expect(await screen.findByRole('heading', { name: 'Нова промяна в графика' })).toBeInTheDocument()
    fireEvent.click(await screen.findByRole('radio', { name: 'Неработно време' }))
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-12-24' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-12-26' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    expect(await screen.findByRole('heading', { name: 'Промяна в графика' })).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(await screen.findByText('Промяната е добавена.')).toBeInTheDocument()
    expect(window.location.hash).toBe(
      '#/business/schedule/exceptions/exception-1?from=2026-12-01&to=2026-12-31&page=0&size=10&sort=dates&direction=asc',
    )

    fireEvent.click(await screen.findByRole('button', { name: 'Изтрий' }))
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий промяната' }))

    expect(await screen.findByRole('heading', { name: 'Работно време' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Промени в графика' })).toHaveAttribute('aria-current', 'page')
    expect(mockedDelete).toHaveBeenCalledWith('exception-1', 0)
    expect(await screen.findByText('Промяната е изтрита.')).toBeInTheDocument()
  })

  it('does not write anything to browser storage during the whole journey', async () => {
    const storageWrite = vi.spyOn(Storage.prototype, 'setItem')
    const indexedDatabaseOpen = vi.fn()
    vi.stubGlobal('indexedDB', { open: indexedDatabaseOpen })
    history.replaceState({}, '', '/#/business/schedule/exceptions')
    mockedList.mockImplementation((from, to) => Promise.resolve(windowOf(from, to, [record()])))
    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Добави промяна' }))
    await screen.findByRole('heading', { name: 'Нова промяна в графика' })
    fireEvent.click(await screen.findByRole('radio', { name: 'Отсъствие' }))

    expect(storageWrite).not.toHaveBeenCalled()
    expect(indexedDatabaseOpen).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
})

describe('shared unsaved-changes guard', () => {
  async function openDirtyCreate() {
    history.replaceState({}, '', '/#/business/schedule/exceptions/new')
    render(<App />)
    fireEvent.click(await screen.findByRole('radio', { name: 'Неработно време' }))
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-12-24' } })
  }

  it('guards sidebar navigation, preserves values on reject and continues on confirm', async () => {
    await openDirtyCreate()

    fireEvent.click(screen.getByRole('link', { name: 'Екип' }))
    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Остани' })).toHaveFocus()

    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(screen.getByLabelText('От')).toHaveValue('2026-12-24')

    fireEvent.click(screen.getByRole('link', { name: 'Екип' }))
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))
    expect(await screen.findByRole('heading', { name: 'Екип' })).toBeInTheDocument()
  })

  it('guards browser Back/Forward and restores the URL when cancelled', async () => {
    await openDirtyCreate()
    const dirtyHash = window.location.hash

    await act(async () => {
      history.pushState({}, '', '/#/business/schedule/exceptions')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })

    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(window.location.hash).toBe(dirtyHash)
    expect(screen.getByLabelText('От')).toHaveValue('2026-12-24')
  })

  it('really resets the form when a confirmed discard stays on the same route, and guards it again afterwards', async () => {
    await openDirtyCreate()

    await act(async () => {
      history.pushState({}, '', '/#/business/schedule/exceptions/new')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))

    expect(screen.getByRole('radio', { name: 'Неработно време' })).not.toBeChecked()
    expect(screen.queryByLabelText('От')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('radio', { name: 'Отсъствие' }))
    fireEvent.click(screen.getByRole('link', { name: 'Екип' }))
    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
  })

  it('ignores a second guarded navigation while the first confirmation is open', async () => {
    await openDirtyCreate()

    fireEvent.click(screen.getByRole('link', { name: 'Екип' }))
    await act(async () => {
      history.pushState({}, '', '/#/business/services')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))

    expect(await screen.findByRole('heading', { name: 'Екип' })).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('guards logout', async () => {
    await openDirtyCreate()

    fireEvent.click(screen.getAllByRole('button', { name: 'Изход' })[0]!)

    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
    expect(mockedRequest).not.toHaveBeenCalledWith('/api/auth/logout', expect.anything())
  })

  it('guards the tabs while a period dialog holds typed input', async () => {
    history.replaceState({}, '', '/#/business/schedule/exceptions/new')
    render(<App />)
    fireEvent.click(await screen.findByRole('radio', { name: 'Допълнителни работни часове' }))
    fireEvent.click(screen.getByRole('button', { name: '+ Добави' }))
    fireEvent.change(within(screen.getByRole('dialog')).getByLabelText('Начален час'), {
      target: { value: '09:00' },
    })

    fireEvent.click(screen.getAllByRole('link', { name: 'Работно време' })[0]!)

    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('does not prompt after a successful create', async () => {
    history.replaceState({}, '', '/#/business/schedule/exceptions/new')
    mockedCreate.mockResolvedValue({ ...record(), timezone: 'Europe/Sofia' })
    mockedGet.mockResolvedValue({ ...record(), timezone: 'Europe/Sofia' })
    render(<App />)
    fireEvent.click(await screen.findByRole('radio', { name: 'Неработно време' }))
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-12-24' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-12-26' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    expect(await screen.findByRole('heading', { name: 'Промяна в графика' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'Екип' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
})

describe('Business-scoped state invalidation', () => {
  function Harness({ session }: { session: Session }) {
    const [busy, setBusy] = useState(false)
    const { feedback, setFeedback, beginFeedback } = useFeedback('harness')
    return (
      <UnsavedChangesGuardProvider>
        <AuthenticatedApplication
          session={session}
          setSession={() => undefined}
          busy={busy}
          setBusy={setBusy}
          feedback={feedback}
          setFeedback={setFeedback}
          beginFeedback={beginFeedback}
        />
      </UnsavedChangesGuardProvider>
    )
  }

  const businessA: Session = {
    ...owner,
    businesses: [
      { id: 'business-a', displayName: 'Бизнес А', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
      { id: 'business-b', displayName: 'Бизнес Б', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
    ],
    activeBusinessId: 'business-a',
  }
  const businessB: Session = { ...businessA, activeBusinessId: 'business-b' }

  it('discards a stale Business-A response, drops A\'s window and resolves B\'s own default', async () => {
    history.replaceState(
      {},
      '',
      '/#/business/schedule/exceptions?from=2026-10-01&to=2026-10-30&page=0&size=10&sort=dates&direction=asc',
    )
    let resolveA!: (value: ScheduleExceptionWindow) => void
    mockedList.mockImplementationOnce(
      () => new Promise<ScheduleExceptionWindow>((resolve) => (resolveA = resolve)),
    )
    mockedList.mockImplementation((from, to) =>
      Promise.resolve(windowOf(from, to, [record({ id: 'b-1', kind: 'BUSINESS_CLOSURE' })])),
    )

    const { rerender } = render(<Harness session={businessA} />)
    await waitFor(() => expect(mockedList).toHaveBeenCalledTimes(1))
    expect(screen.getByText('Зареждане на промените…')).toBeInTheDocument()

    rerender(<Harness session={businessB} />)
    await screen.findByRole('table')
    expect(window.location.hash).not.toContain('from=2026-10-01')
    const today = localDateIn(new Date(), 'Europe/Sofia')!
    await waitFor(() =>
      expect(window.location.hash).toBe(
        `#/business/schedule/exceptions?from=${today}&to=${addDays(today, 29)}&page=0&size=10&sort=dates&direction=asc`,
      ),
    )

    await act(async () => {
      resolveA(windowOf('2026-10-01', '2026-10-30', [record({ id: 'a-1', firstDate: '2026-10-05', lastDate: '2026-10-05' })]))
      await Promise.resolve()
    })

    expect(screen.queryByText('05.10.2026')).not.toBeInTheDocument()
    expect(screen.getAllByRole('row')).toHaveLength(2)
  })

  it('discards a dirty create form without a prompt when the Business context is replaced', async () => {
    history.replaceState({}, '', '/#/business/schedule/exceptions/new')
    const { rerender } = render(<Harness session={businessA} />)
    fireEvent.click(await screen.findByRole('radio', { name: 'Неработно време' }))
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-12-24' } })

    rerender(<Harness session={businessB} />)

    expect(await screen.findByRole('radio', { name: 'Неработно време' })).not.toBeChecked()
    expect(mockedStaff).toHaveBeenCalledTimes(2)
  })

  it('shows the API error text only through the safe channel', async () => {
    history.replaceState(
      {},
      '',
      '/#/business/schedule/exceptions?from=2026-10-01&to=2026-10-30&page=0&size=10&sort=dates&direction=asc',
    )
    mockedList.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'SQL: secret'))
    render(<App />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Промените не могат да бъдат заредени.')
    expect(screen.queryByText(/secret/)).not.toBeInTheDocument()
  })
})

describe('list window preservation', () => {
  const WINDOW = '?from=2026-11-01&to=2026-11-30&page=0&size=10&sort=dates&direction=asc'
  const LIST = `#/business/schedule/exceptions${WINDOW}`
  const closure = record({ id: 'exception-1', firstDate: '2026-11-10', lastDate: '2026-11-10' })
  const detail: ScheduleExceptionDetails = { ...closure, timezone: 'Europe/Sofia' }

  beforeEach(() => {
    history.replaceState({}, '', `/${LIST}`)
    mockedList.mockImplementation((from, to) => Promise.resolve(windowOf(from, to, [closure])))
    mockedGet.mockResolvedValue(detail)
    mockedCreate.mockResolvedValue(detail)
    mockedDelete.mockResolvedValue(undefined)
  })

  async function openDetail() {
    render(<App />)
    fireEvent.click(await screen.findByRole('link', { name: /Отвори: Неработно време/ }))
    await screen.findByRole('heading', { name: 'Промяна в графика' })
    await screen.findByRole('button', { name: 'Обратно към графика' })
  }

  it('list → detail → back restores the exact window and pushes the detail as ordinary navigation', async () => {
    const push = vi.spyOn(history, 'pushState')
    await openDetail()

    expect(push).toHaveBeenCalledTimes(1)
    expect(window.location.hash).toBe(`#/business/schedule/exceptions/exception-1${WINDOW}`)

    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))

    expect(await screen.findByRole('heading', { name: 'Работно време' })).toBeInTheDocument()
    expect(window.location.hash).toBe(LIST)
    expect(screen.getByLabelText('От')).toHaveValue('2026-11-01')
    expect(screen.getByLabelText('До')).toHaveValue('2026-11-30')
    await waitFor(() =>
      expect(mockedList).toHaveBeenLastCalledWith('2026-11-01', '2026-11-30', expect.anything()),
    )
  })

  it('list → create → cancel restores the window', async () => {
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Добави промяна' }))
    await screen.findByRole('heading', { name: 'Нова промяна в графика' })
    expect(window.location.hash).toBe(`#/business/schedule/exceptions/new${WINDOW}`)

    fireEvent.click(await screen.findByRole('button', { name: 'Отказ' }))

    await screen.findByRole('heading', { name: 'Работно време' })
    expect(window.location.hash).toBe(LIST)
  })

  it('list → create → "Обратно към графика" restores the window', async () => {
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Добави промяна' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Обратно към графика' }))

    await screen.findByRole('heading', { name: 'Работно време' })
    expect(window.location.hash).toBe(LIST)
  })

  it('successful create keeps the window on the detail and on the way back', async () => {
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Добави промяна' }))
    fireEvent.click(await screen.findByRole('radio', { name: 'Неработно време' }))
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-11-10' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-10' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    await screen.findByRole('heading', { name: 'Промяна в графика' })
    expect(window.location.hash).toBe(`#/business/schedule/exceptions/exception-1${WINDOW}`)
    fireEvent.click(await screen.findByRole('button', { name: 'Обратно към графика' }))
    await screen.findByRole('heading', { name: 'Работно време' })
    expect(window.location.hash).toBe(LIST)
  })

  it('stacks heading, page feedback, and content as separate siblings of one layout stack', async () => {
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Добави промяна' }))
    fireEvent.click(await screen.findByRole('radio', { name: 'Неработно време' }))
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-11-10' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-10' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))
    await screen.findByText('Промяната е добавена.')
    // The feedback is shown before the detail finishes loading; wait for the
    // loaded detail (its back button) before asserting the layout structure.
    const backButton = await screen.findByRole('button', { name: 'Обратно към графика' })

    const main = document.querySelector('main.platform-main')!
    const children = Array.from(main.children)
    expect(children).toHaveLength(3)
    expect(children[0]).toHaveClass('platform-page-header')
    expect(children[1]).toHaveTextContent('Промяната е добавена.')
    expect(children[1]).not.toContainElement(backButton)
    expect(children[2]).toContainElement(backButton)
  })

  it('delete returns to the same window', async () => {
    await openDetail()
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий' }))
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий промяната' }))

    await screen.findByRole('heading', { name: 'Работно време' })
    expect(window.location.hash).toBe(LIST)
  })

  it('cancelling and saving an edit stay on the detail that still carries the window', async () => {
    await openDetail()
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(window.location.hash).toBe(`#/business/schedule/exceptions/exception-1${WINDOW}`)

    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    mockedReplace.mockResolvedValue({ ...detail, version: 1 })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await screen.findByText('Промените са запазени.')
    expect(window.location.hash).toBe(`#/business/schedule/exceptions/exception-1${WINDOW}`)
  })

  it('a conflict reload keeps the route and therefore the window', async () => {
    mockedReplace.mockRejectedValue(
      new ApiError(409, 'SCHEDULE_EXCEPTION_CONCURRENT_UPDATE', 'Обновете данните и опитайте отново.'),
    )
    await openDetail()
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Зареди актуалните данни' }))
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))

    await screen.findByRole('button', { name: 'Редактирай' })
    expect(window.location.hash).toBe(`#/business/schedule/exceptions/exception-1${WINDOW}`)
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    await screen.findByRole('heading', { name: 'Работно време' })
    expect(window.location.hash).toBe(LIST)
  })

  it('browser Back and Forward preserve the window on every entry', async () => {
    await openDetail()
    const detailHash = window.location.hash

    await act(async () => {
      history.pushState({}, '', `/${LIST}`)
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(await screen.findByRole('heading', { name: 'Работно време' })).toBeInTheDocument()
    expect(screen.getByLabelText('От')).toHaveValue('2026-11-01')
    expect(screen.getByLabelText('До')).toHaveValue('2026-11-30')

    await act(async () => {
      history.pushState({}, '', `/${detailHash}`)
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(await screen.findByRole('heading', { name: 'Промяна в графика' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    await screen.findByRole('heading', { name: 'Работно време' })
    expect(window.location.hash).toBe(LIST)
  })

  it('a direct detail or create URL without a window returns to the canonical default', async () => {
    history.replaceState({}, '', '/#/business/schedule/exceptions/exception-1')
    const { unmount } = render(<App />)
    await screen.findByRole('heading', { name: 'Промяна в графика' })
    fireEvent.click(await screen.findByRole('button', { name: 'Обратно към графика' }))
    const today = localDateIn(new Date(), 'Europe/Sofia')!
    await waitFor(() =>
      expect(window.location.hash).toBe(
        `#/business/schedule/exceptions?from=${today}&to=${addDays(today, 29)}&page=0&size=10&sort=dates&direction=asc`,
      ),
    )
    unmount()

    history.replaceState({}, '', '/#/business/schedule/exceptions/new')
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Отказ' }))
    await waitFor(() =>
      expect(window.location.hash).toBe(
        `#/business/schedule/exceptions?from=${today}&to=${addDays(today, 29)}&page=0&size=10&sort=dates&direction=asc`,
      ),
    )
  })

  it.each([
    ['?from=2026-11-01', '#/business/schedule/exceptions/new'],
    ['?from=bad&to=worse', '#/business/schedule/exceptions/new'],
    ['?from=2026-11-30&to=2026-11-01', '#/business/schedule/exceptions/exception-1'],
  ])('discards the invalid return window %s from the address', async (query, canonical) => {
    history.replaceState({}, '', `/${canonical}${query}`)
    render(<App />)

    await screen.findByRole('heading', {
      name: canonical.endsWith('/new') ? 'Нова промяна в графика' : 'Промяна в графика',
    })
    expect(window.location.hash).toBe(canonical)
  })

  it('never carries Business A\'s window to Business B', async () => {
    function Harness({ session }: { session: Session }) {
      const [busy, setBusy] = useState(false)
      const { feedback, setFeedback, beginFeedback } = useFeedback('harness')
      return (
        <UnsavedChangesGuardProvider>
          <AuthenticatedApplication
            session={session}
            setSession={() => undefined}
            busy={busy}
            setBusy={setBusy}
            feedback={feedback}
            setFeedback={setFeedback}
            beginFeedback={beginFeedback}
          />
        </UnsavedChangesGuardProvider>
      )
    }
    const businessA: Session = {
      ...owner,
      businesses: [
        { id: 'business-a', displayName: 'Бизнес А', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
        { id: 'business-b', displayName: 'Бизнес Б', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
      ],
      activeBusinessId: 'business-a',
    }
    history.replaceState({}, '', `/#/business/schedule/exceptions/exception-1${WINDOW}`)
    const { rerender } = render(<Harness session={businessA} />)
    await screen.findByRole('heading', { name: 'Промяна в графика' })

    rerender(<Harness session={{ ...businessA, activeBusinessId: 'business-b' }} />)

    await waitFor(() =>
      expect(window.location.hash).toBe('#/business/schedule/exceptions/exception-1'),
    )
  })

  it('shows none of the retired terminology on the list, create and detail pages', async () => {
    const retired = [
      'Промени по дати',
      'Нова промяна по дата',
      'Промяна по дата',
      'Обратно към промените',
      'Добави промяната',
      'Затваряне на бизнеса',
      'Промяна на работния ден',
    ]
    render(<App />)
    await screen.findByRole('table')
    for (const text of retired) expect(document.body.textContent).not.toContain(text)

    fireEvent.click(screen.getByRole('link', { name: /Отвори: Неработно време/ }))
    await screen.findByRole('heading', { name: 'Промяна в графика' })
    for (const text of retired) expect(document.body.textContent).not.toContain(text)
  })
})

describe('list sorting preservation', () => {
  const WINDOW = '?from=2026-11-01&to=2026-11-30&page=0&size=10&sort=dates&direction=asc'
  const KIND_ASC = '?from=2026-11-01&to=2026-11-30&page=0&size=10&sort=kind&direction=asc'
  const KIND_DESC = '?from=2026-11-01&to=2026-11-30&page=0&size=10&sort=kind&direction=desc'
  const SORTED = '?from=2026-11-01&to=2026-11-30&page=0&size=10&sort=status&direction=desc'
  const LIST = `#/business/schedule/exceptions${SORTED}`
  const closure = record({ id: 'exception-1', firstDate: '2026-11-10', lastDate: '2026-11-10' })
  const other = record({
    id: 'exception-2',
    kind: 'STAFF_TIME_OFF',
    staffMemberId: 'staff-a',
    firstDate: '2026-11-12',
    lastDate: '2026-11-12',
  })
  const detail: ScheduleExceptionDetails = { ...closure, timezone: 'Europe/Sofia' }

  beforeEach(() => {
    history.replaceState({}, '', `/${LIST}`)
    mockedList.mockImplementation((from, to) =>
      Promise.resolve(windowOf(from, to, [closure, other])),
    )
    mockedGet.mockResolvedValue(detail)
    mockedCreate.mockResolvedValue(detail)
    mockedDelete.mockResolvedValue(undefined)
  })

  const activeSort = () =>
    screen.getByRole('columnheader', { name: /Статус/ }).getAttribute('aria-sort')

  async function openDetail() {
    render(<App />)
    fireEvent.click(await screen.findByRole('link', { name: /Отвори: Неработно време/ }))
    await screen.findByRole('heading', { name: 'Промяна в графика' })
  }

  it('pushes an explicit sort click and restores each ordering with Back/Forward', async () => {
    history.replaceState({}, '', `/#/business/schedule/exceptions${WINDOW}`)
    render(<App />)
    await screen.findByRole('table')
    const push = vi.spyOn(history, 'pushState')

    fireEvent.click(within(screen.getByRole('columnheader', { name: /Вид/ })).getByRole('button'))
    expect(push).toHaveBeenCalledTimes(1)
    expect(window.location.hash).toBe(`#/business/schedule/exceptions${KIND_ASC}`)

    fireEvent.click(within(screen.getByRole('columnheader', { name: /Вид/ })).getByRole('button'))
    expect(window.location.hash).toBe(`#/business/schedule/exceptions${KIND_DESC}`)
    expect(screen.getByRole('columnheader', { name: /Вид/ })).toHaveAttribute('aria-sort', 'descending')

    // Back to the previous entry (kind ascending), as the browser would.
    await act(async () => {
      history.replaceState({}, '', `/#/business/schedule/exceptions${KIND_ASC}`)
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    await waitFor(() =>
      expect(screen.getByRole('columnheader', { name: /Вид/ })).toHaveAttribute('aria-sort', 'ascending'),
    )
    // Forward again.
    await act(async () => {
      history.replaceState({}, '', `/#/business/schedule/exceptions${KIND_DESC}`)
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    await waitFor(() =>
      expect(screen.getByRole('columnheader', { name: /Вид/ })).toHaveAttribute('aria-sort', 'descending'),
    )
    // A sort never refetches the window.
    expect(mockedList).toHaveBeenCalledTimes(1)
  })

  it('reload: a URL carrying the sort renders it', async () => {
    render(<App />)
    await screen.findByRole('table')
    expect(activeSort()).toBe('descending')
  })

  it('an invalid sort in the URL falls back to the canonical default', async () => {
    history.replaceState({}, '', '/#/business/schedule/exceptions?from=2026-11-01&to=2026-11-30&page=-3&size=7&sort=bogus&direction=up')
    render(<App />)
    await screen.findByRole('table')
    expect(screen.getByRole('columnheader', { name: /Дати/ })).toHaveAttribute('aria-sort', 'ascending')
  })

  it('list → detail → back preserves the window and the sorting', async () => {
    await openDetail()
    expect(window.location.hash).toBe(`#/business/schedule/exceptions/exception-1${SORTED}`)
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    await screen.findByRole('table')
    expect(window.location.hash).toBe(LIST)
    expect(activeSort()).toBe('descending')
    expect(screen.getByLabelText('От')).toHaveValue('2026-11-01')
  })

  it('list → create → cancel preserves the window and the sorting', async () => {
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Добави промяна' }))
    await screen.findByRole('heading', { name: 'Нова промяна в графика' })
    expect(window.location.hash).toBe(`#/business/schedule/exceptions/new${SORTED}`)
    fireEvent.click(await screen.findByRole('button', { name: 'Отказ' }))
    await screen.findByRole('table')
    expect(window.location.hash).toBe(LIST)
  })

  it('successful create keeps the sorting on the detail and on the way back', async () => {
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Добави промяна' }))
    fireEvent.click(await screen.findByRole('radio', { name: 'Неработно време' }))
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-11-10' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-10' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))
    await screen.findByRole('heading', { name: 'Промяна в графика' })
    expect(window.location.hash).toBe(`#/business/schedule/exceptions/exception-1${SORTED}`)
    fireEvent.click(await screen.findByRole('button', { name: 'Обратно към графика' }))
    await screen.findByRole('table')
    expect(window.location.hash).toBe(LIST)
  })

  it('deleting from the detail returns to the same window and sorting', async () => {
    await openDetail()
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий' }))
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий промяната' }))
    await screen.findByRole('table')
    expect(window.location.hash).toBe(LIST)
    expect(activeSort()).toBe('descending')
  })

  it('a failed delete keeps the record and the route (window and sorting)', async () => {
    mockedDelete.mockRejectedValue(new Error('offline'))
    await openDetail()
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий' }))
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий промяната' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Промяната не може да бъде изтрита.')
    expect(screen.getByRole('button', { name: 'Изтрий' })).toBeInTheDocument()
    expect(window.location.hash).toBe(`#/business/schedule/exceptions/exception-1${SORTED}`)
  })

  it('edit save/cancel stay on the detail that carries the sorting', async () => {
    await openDetail()
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(window.location.hash).toBe(`#/business/schedule/exceptions/exception-1${SORTED}`)
  })

  it('never offers a list-level delete, and deletion stays behind the detail view', async () => {
    render(<App />)
    await screen.findByRole('table')
    expect(screen.queryByRole('button', { name: /Изтрий/ })).toBeNull()
    expect(document.querySelectorAll('tbody button')).toHaveLength(0)
  })

  it('drops the sorting together with the window when the Business changes', async () => {
    function Harness({ session }: { session: Session }) {
      const [busy, setBusy] = useState(false)
      const { feedback, setFeedback, beginFeedback } = useFeedback('harness')
      return (
        <UnsavedChangesGuardProvider>
          <AuthenticatedApplication
            session={session}
            setSession={() => undefined}
            busy={busy}
            setBusy={setBusy}
            feedback={feedback}
            setFeedback={setFeedback}
            beginFeedback={beginFeedback}
          />
        </UnsavedChangesGuardProvider>
      )
    }
    const businessA: Session = {
      ...owner,
      businesses: [
        { id: 'business-a', displayName: 'Бизнес А', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
        { id: 'business-b', displayName: 'Бизнес Б', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
      ],
      activeBusinessId: 'business-a',
    }
    const { rerender } = render(<Harness session={businessA} />)
    await screen.findByRole('table')
    rerender(<Harness session={{ ...businessA, activeBusinessId: 'business-b' }} />)
    await waitFor(() => expect(window.location.hash).not.toContain('sort='))
    await waitFor(() => expect(window.location.hash).toMatch(/from=\d{4}-\d{2}-\d{2}&to=/))
  })
})

describe('list pagination through the router', () => {
  const Q = (page: number, size = 10, sort = 'dates', direction = 'asc') =>
    `?from=2026-11-01&to=2026-11-30&page=${page}&size=${size}&sort=${sort}&direction=${direction}`
  const LIST_AT = (page: number, size = 10, sort = 'dates', direction = 'asc') =>
    `#/business/schedule/exceptions${Q(page, size, sort, direction)}`

  function records(count: number): ScheduleExceptionItem[] {
    return Array.from({ length: count }, (_, index) => {
      const day = String(1 + (index % 28)).padStart(2, '0')
      return record({
        id: `exception-${index + 1}`,
        firstDate: `2026-11-${day}`,
        lastDate: `2026-11-${day}`,
      })
    })
  }

  let current: ScheduleExceptionItem[]
  beforeEach(() => {
    current = records(23)
    mockedList.mockImplementation((from, to) => Promise.resolve(windowOf(from, to, current)))
    mockedGet.mockImplementation((id) => {
      const found = current.find((entry) => entry.id === id)!
      return Promise.resolve({ ...found, timezone: 'Europe/Sofia' })
    })
    mockedDelete.mockResolvedValue(undefined)
    mockedCreate.mockResolvedValue({ ...current[0]!, timezone: 'Europe/Sofia' })
  })

  const rows = () => document.querySelectorAll('tbody tr').length
  const start = async (hash: string) => {
    history.replaceState({}, '', `/${hash}`)
    render(<App />)
    await screen.findByRole('table')
  }

  it('canonicalizes a bare window by replacing history with the complete list state', async () => {
    const replace = vi.spyOn(history, 'replaceState')
    await start('#/business/schedule/exceptions?from=2026-11-01&to=2026-11-30')
    expect(window.location.hash).toBe(LIST_AT(0))
    expect(replace).toHaveBeenCalled()
  })

  it('pushes page, size and sort changes and Back/Forward restore each complete state', async () => {
    await start(LIST_AT(0))
    const push = vi.spyOn(history, 'pushState')

    fireEvent.click(screen.getByRole('button', { name: 'Следваща' }))
    expect(window.location.hash).toBe(LIST_AT(1))
    fireEvent.change(screen.getByLabelText('Резултати на страница'), { target: { value: '25' } })
    expect(window.location.hash).toBe(LIST_AT(0, 25))
    fireEvent.click(within(screen.getByRole('columnheader', { name: /Вид/ })).getByRole('button'))
    expect(window.location.hash).toBe(LIST_AT(0, 25, 'kind', 'asc'))
    expect(push).toHaveBeenCalledTimes(3)

    for (const [hash, expectedRows] of [
      [LIST_AT(0, 25), 23],
      [LIST_AT(1), 10],
      [LIST_AT(0, 25, 'kind', 'asc'), 23],
    ] as const) {
      await act(async () => {
        history.replaceState({}, '', `/${hash}`)
        window.dispatchEvent(new PopStateEvent('popstate'))
      })
      await waitFor(() => expect(rows()).toBe(expectedRows))
    }
    // Paging, size and sorting never refetch the complete window.
    expect(mockedList).toHaveBeenCalledTimes(1)
  })

  it('a filter change pushes, resets the page and keeps size and sort', async () => {
    await start(LIST_AT(1, 10, 'status', 'desc'))
    const push = vi.spyOn(history, 'pushState')
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-25' } })
    fireEvent.click(screen.getByRole('button', { name: 'Покажи' }))
    await waitFor(() =>
      expect(window.location.hash).toBe(
        '#/business/schedule/exceptions?from=2026-11-01&to=2026-11-25&page=0&size=10&sort=status&direction=desc',
      ),
    )
    expect(push).toHaveBeenCalledTimes(1)
  })

  it('reload: a URL with the complete state renders that page', async () => {
    await start(LIST_AT(2, 10, 'kind', 'desc'))
    expect(rows()).toBe(3)
    expect(screen.getByText('Показани 21–23 от 23')).toBeInTheDocument()
  })

  it.each([
    ['list → detail → back', async () => {
      fireEvent.click(await screen.findAllByRole('link', { name: /Отвори:/ }).then((links) => links[0]!))
      await screen.findByRole('heading', { name: 'Промяна в графика' })
      fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    }],
    ['list → create → cancel', async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Добави промяна' }))
      await screen.findByRole('heading', { name: 'Нова промяна в графика' })
      expect(window.location.hash).toBe(`#/business/schedule/exceptions/new${Q(1, 10, 'kind', 'desc')}`)
      fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    }],
    ['edit cancel', async () => {
      fireEvent.click(await screen.findAllByRole('link', { name: /Отвори:/ }).then((links) => links[0]!))
      await screen.findByRole('heading', { name: 'Промяна в графика' })
      fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
      fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
      expect(window.location.hash).toContain(Q(1, 10, 'kind', 'desc'))
      fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    }],
  ])('%s preserves page, size, sort and direction', async (_name, act1) => {
    await start(LIST_AT(1, 10, 'kind', 'desc'))
    await act1()
    await screen.findByRole('table')
    expect(window.location.hash).toBe(LIST_AT(1, 10, 'kind', 'desc'))
    expect(screen.getByText('Показани 11–20 от 23')).toBeInTheDocument()
  })

  it('successful create keeps the complete state on the detail and on the way back', async () => {
    await start(LIST_AT(1, 10, 'status', 'asc'))
    fireEvent.click(screen.getByRole('button', { name: 'Добави промяна' }))
    fireEvent.click(await screen.findByRole('radio', { name: 'Неработно време' }))
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-11-10' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-10' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))
    await screen.findByRole('heading', { name: 'Промяна в графика' })
    expect(window.location.hash).toBe(`#/business/schedule/exceptions/exception-1${Q(1, 10, 'status', 'asc')}`)
  })

  it('edit save and a conflict reload stay on the detail carrying the complete state', async () => {
    await start(LIST_AT(1))
    fireEvent.click((await screen.findAllByRole('link', { name: /Отвори:/ }))[0]!)
    await screen.findByRole('heading', { name: 'Промяна в графика' })
    const detailHash = window.location.hash
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    mockedReplace.mockResolvedValue({ ...current[10]!, timezone: 'Europe/Sofia', version: 1 })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await screen.findByText('Промените са запазени.')
    expect(window.location.hash).toBe(detailHash)
    expect(detailHash).toContain('page=1')
  })

  it('failed delete keeps the record and the route with its complete state', async () => {
    mockedDelete.mockRejectedValue(new Error('offline'))
    await start(LIST_AT(1, 10, 'kind', 'desc'))
    fireEvent.click((await screen.findAllByRole('link', { name: /Отвори:/ }))[0]!)
    await screen.findByRole('heading', { name: 'Промяна в графика' })
    const detailHash = window.location.hash
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий' }))
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий промяната' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Промяната не може да бъде изтрита.')
    expect(window.location.hash).toBe(detailHash)
    expect(screen.getByRole('button', { name: 'Изтрий' })).toBeInTheDocument()
  })

  it('deleting the only record on the last page recovers to the previous page by replacing history', async () => {
    current = records(11)
    await start(LIST_AT(1))
    expect(rows()).toBe(1)
    fireEvent.click(screen.getByRole('link', { name: /Отвори:/ }))
    await screen.findByRole('heading', { name: 'Промяна в графика' })
    current = current.slice(0, 10)
    const push = vi.spyOn(history, 'pushState')
    const replace = vi.spyOn(history, 'replaceState')
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий' }))
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий промяната' }))

    await screen.findByRole('table')
    await waitFor(() => expect(window.location.hash).toBe(LIST_AT(0)))
    expect(rows()).toBe(10)
    expect(screen.getByText('Показани 1–10 от 10')).toBeInTheDocument()
    // Returning to the list is one push; the invalid page is replaced, never pushed.
    expect(push).toHaveBeenCalledTimes(1)
    expect(String(push.mock.calls[0]![2])).toContain('page=1')
    expect(String(replace.mock.calls.at(-1)![2])).toContain('page=0')
    expect(screen.queryByText(/Няма промени/)).not.toBeInTheDocument()
  })

  it('an out-of-range page in the URL never renders empty and is replaced with the last valid page', async () => {
    const replace = vi.spyOn(history, 'replaceState')
    await start(LIST_AT(9))
    expect(rows()).toBe(3)
    await waitFor(() => expect(window.location.hash).toBe(LIST_AT(2)))
    expect(replace).toHaveBeenCalled()
  })
})
