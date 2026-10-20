import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App, AuthenticatedApplication } from './App'
import { ApiError, request, type Session } from './identity/api'
import { UnsavedChangesGuardProvider } from './ui/UnsavedChangesGuard'
import { useFeedback } from './ui/useFeedback'
import { calendarResponse } from './business/calendar/testFixtures'
import {
  APPOINTMENT_ONE,
  CUSTOMER_ONE,
  DAY,
  FakeRescheduleBackend,
  NOW,
  STAFF_TWO,
  appointmentDetail,
} from './business/appointments/rescheduleTestFixtures'

// Only the network helper is replaced: routing, the shell, the guard, the feature API modules and the
// session handling all run for real. The clock is the only other thing faked (the date, nothing else),
// so that "future" and "started" are decided against a known instant.
vi.mock('./identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)

const sessionOf = (status: string, role: 'BUSINESS_OWNER' | 'MANAGER' | 'STAFF' = 'BUSINESS_OWNER'): Session => ({
  email: 'ivan@example.invalid',
  displayName: 'Иван Собственик',
  platformAdmin: false,
  businesses: [
    { id: 'a', displayName: 'Студио А', role, status },
    { id: 'b', displayName: 'Студио Б', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
  ],
  activeBusinessId: 'a',
})

let session: Session
let backend: FakeRescheduleBackend

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
  session = sessionOf('ACTIVE')
  backend = new FakeRescheduleBackend()
  mockedRequest.mockReset()
  mockedRequest.mockImplementation(async (path: string, options?: RequestInit) => {
    if (path === '/api/auth/session') return session
    if (path.startsWith('/api/business/calendar')) return calendarResponse()
    if (path.startsWith(`/api/business/appointments/${APPOINTMENT_ONE}`)) return backend.handle(path, options)
    if (path.startsWith('/api/business/staff-members')) {
      return { staffMembers: [], page: 0, size: 50, totalElements: 0 }
    }
    throw new Error(`unexpected request ${path}`)
  })
  history.replaceState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

const ORIGIN = 'view=week&date=2026-11-10'
const DETAIL = `#/business/appointments/${APPOINTMENT_ONE}?${ORIGIN}`
const FORM = `#/business/appointments/${APPOINTMENT_ONE}/reschedule?${ORIGIN}`

async function openDetail(hash = DETAIL) {
  history.replaceState({}, '', `/${hash}`)
  render(<App />)
  await screen.findByRole('region', { name: 'Детайли' })
}

async function openForm(hash = DETAIL) {
  await openDetail(hash)
  fireEvent.click(await screen.findByRole('button', { name: 'Промени часа' }))
  await screen.findByRole('heading', { level: 1, name: 'Промяна на час' })
  await screen.findByRole('radio', { name: /Мила/ })
}

async function toReview() {
  fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
  const group = await screen.findByRole('group', { name: 'Свободни часове' })
  fireEvent.click(within(group).getAllByRole('radio')[1]!)
  fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
  await screen.findByRole('button', { name: 'Промени часа' })
}

describe('the entry on the Appointment detail', () => {
  it('offers the action to the owner of an ACTIVE Business for a future confirmed Appointment and opens the private route', async () => {
    await openDetail()
    const link = screen.getByRole('button', { name: 'Промени часа' })
    expect(link).toBeEnabled()

    fireEvent.click(link)
    expect(await screen.findByRole('heading', { level: 1, name: 'Промяна на час' })).toBeInTheDocument()
    expect(window.location.hash).toBe(FORM)
    expect(screen.getByRole('link', { name: 'График' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByRole('radio', { name: /Мила/ })).toBeChecked()
  })

  it('keeps the Customer origin in the route', async () => {
    await openForm(`#/business/appointments/${APPOINTMENT_ONE}?customer=${CUSTOMER_ONE}`)
    expect(window.location.hash).toBe(`#/business/appointments/${APPOINTMENT_ONE}/reschedule?customer=${CUSTOMER_ONE}`)
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към часа' }))
    await waitFor(() => expect(window.location.hash).toBe(`#/business/appointments/${APPOINTMENT_ONE}?customer=${CUSTOMER_ONE}`))
  })

  it.each([
    ['a cancelled Appointment', () => appointmentDetail({ status: 'CANCELLED' })],
    ['an Appointment that has started', () => appointmentDetail({ start: '2026-10-02T10:00:00+03:00', end: '2026-10-02T11:15:00+03:00' })],
    ['an Appointment starting exactly now', () => appointmentDetail({ start: '2026-10-02T11:00:00+03:00', end: '2026-10-02T12:15:00+03:00' })],
    ['a DRAFT Business', () => appointmentDetail({ businessStatus: 'DRAFT' })],
    ['a SUSPENDED Business', () => appointmentDetail({ businessStatus: 'SUSPENDED' })],
  ])('has no action for %s (absent, not disabled)', async (_label, detail) => {
    backend.detail = detail
    await openDetail()
    expect(screen.queryByText('Промени часа')).toBeNull()
  })

  it.each(['DRAFT', 'SUSPENDED'])('has no action when the session Business is %s', async (status) => {
    session = sessionOf(status)
    await openDetail()
    expect(screen.queryByText('Промени часа')).toBeNull()
    expect(document.querySelector('.shell-banner')).not.toBeNull()
  })

  it.each(['DRAFT', 'SUSPENDED'])('%s: a direct visit to the form returns to the read-only detail and reads nothing for it', async (status) => {
    session = sessionOf(status)
    history.replaceState({}, '', `/${FORM}`)
    render(<App />)
    await screen.findByRole('region', { name: 'Детайли' })
    await waitFor(() => expect(window.location.hash).toBe(DETAIL))
    expect(backend.count('/reschedule-options')).toBe(0)
    expect(screen.queryByRole('heading', { name: 'Избор на служител' })).toBeNull()
  })

  it.each(['MANAGER', 'STAFF'] as const)('does not offer the flow to a %s Membership, also by a direct route', async (role) => {
    session = { ...sessionOf('ACTIVE', role), businesses: [{ id: 'a', displayName: 'Студио А', role, status: 'ACTIVE' }] }
    history.replaceState({}, '', `/${FORM}`)
    render(<App />)
    await waitFor(() => expect(window.location.hash).toBe('#/profile'))
    expect(backend.calls).toHaveLength(0)
  })

  it('a platform administrator without an owner Membership has no path to it', async () => {
    session = { email: 'a@example.invalid', displayName: 'Админ', platformAdmin: true, businesses: [] }
    history.replaceState({}, '', `/${FORM}`)
    render(<App />)
    await waitFor(() => expect(window.location.hash).not.toContain('reschedule'))
    expect(backend.calls).toHaveLength(0)
  })

  it('does not render the form before sign-in', async () => {
    mockedRequest.mockRejectedValue(new Error('no session'))
    history.replaceState({}, '', `/${FORM}`)
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Вход' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Избор на служител' })).toBeNull()
  })

  it('a server refusal that arrives on the direct route is handled as a safe recovery', async () => {
    backend.detail = () => {
      throw new ApiError(403, 'ACCESS_DENIED', 'backend text that must never be shown')
    }
    history.replaceState({}, '', `/${FORM}`)
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Нямате достъп до тази операция.')
    expect(document.body.textContent).not.toContain('backend text')
    expect(screen.queryByRole('radio')).toBeNull()
    expect(backend.posts()).toHaveLength(0)
  })
})

describe('leaving, returning and completing', () => {
  it('Отказ returns to the detail and the detail returns to the exact calendar state the owner came from', async () => {
    const hash = `#/business/appointments/${APPOINTMENT_ONE}?view=agenda&date=2026-11-10&staff=${STAFF_TWO}&page=2&size=25`
    await openForm(hash)
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    await waitFor(() => expect(window.location.hash).toBe(hash))
    fireEvent.click(await screen.findByRole('button', { name: 'Обратно към графика' }))
    await waitFor(() =>
      expect(window.location.hash).toBe(`#/business/calendar?view=agenda&date=2026-11-10&staff=${STAFF_TWO}&page=2&size=25`),
    )
    expect(backend.posts()).toHaveLength(0)
  })

  it('does not ask when nothing was changed', async () => {
    await openForm()
    fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
    await screen.findByLabelText('Дата')
    fireEvent.click(screen.getByRole('link', { name: 'Услуги' }))
    await waitFor(() => expect(window.location.hash).toBe('#/business/services?page=0&size=10&sort=name&direction=asc'))
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('asks before discarding a changed proposal, and Остани keeps it', async () => {
    await openForm()
    fireEvent.click(await screen.findByRole('radio', { name: /Георги/ }))
    fireEvent.click(screen.getByRole('link', { name: 'Услуги' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })
    expect(dialog).toHaveTextContent('Промяната на часа още не е направена.')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Остани' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(screen.getByRole('radio', { name: /Георги/ })).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към часа' }))
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Напусни' }))
    await waitFor(() => expect(window.location.hash).toBe(DETAIL))
  })

  it('guards browser Back of a changed proposal and restores the route when the owner stays', async () => {
    await openForm()
    fireEvent.click(await screen.findByRole('radio', { name: /Георги/ }))
    act(() => history.back())
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(window.location.hash).toBe(FORM)
    expect(screen.getByRole('radio', { name: /Георги/ })).toBeChecked()
  })

  it('browser Forward after leaving mounts a fresh form that reads everything again and restores nothing', async () => {
    await openForm()
    fireEvent.click(await screen.findByRole('radio', { name: /Георги/ }))
    act(() => history.back())
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Напусни' }))
    await screen.findByRole('region', { name: 'Детайли' })
    const reads = backend.count('/reschedule-options')

    act(() => history.forward())
    expect(await screen.findByRole('heading', { level: 1, name: 'Промяна на час' })).toBeInTheDocument()
    expect(await screen.findByRole('radio', { name: /Мила/ })).toBeChecked()
    expect(screen.getByRole('radio', { name: /Георги/ })).not.toBeChecked()
    expect(backend.count('/reschedule-options')).toBe(reads + 1)
    expect(backend.posts()).toHaveLength(0)
  })

  it('success replaces the form with the re-read authoritative detail, shows one success message and never the form again', async () => {
    let moved = false
    backend.detail = () =>
      moved
        ? appointmentDetail({ start: `${DAY}T10:00:00+02:00`, end: `${DAY}T11:15:00+02:00`, version: 8 })
        : appointmentDetail()
    backend.reschedule = (body) => {
      moved = true
      return { id: APPOINTMENT_ONE, status: 'CONFIRMED', start: body.start ?? '', end: `${DAY}T11:15:00+02:00`, staffMemberId: '', version: 8 }
    }
    await openForm()
    const before = history.length
    await toReview()
    fireEvent.click(screen.getByRole('button', { name: 'Промени часа' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Час' })).toBeInTheDocument()
    expect(window.location.hash).toBe(DETAIL)
    expect(await screen.findAllByText('Часът е променен.')).toHaveLength(1)
    // The detail shows what the server now says, read again after the change.
    await waitFor(() => expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('10:00–11:15'))
    // Replaced, not pushed: no resubmittable form stays in history.
    expect(history.length).toBe(before)
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(backend.posts()).toHaveLength(1)
    expect(backend.posts()[0]!.body).toEqual({ expectedVersion: 7, start: `${DAY}T10:00:00+02:00` })
  })

  it('the route and the page stay on the form while the request is pending (nothing is moved optimistically)', async () => {
    let release!: () => void
    backend.reschedule = () =>
      new Promise((resolve) => {
        release = () => resolve({ id: APPOINTMENT_ONE, status: 'CONFIRMED', start: `${DAY}T10:00:00+02:00`, end: '', staffMemberId: '', version: 8 })
      })
    await openForm()
    await toReview()
    fireEvent.click(screen.getByRole('button', { name: 'Промени часа' }))

    await waitFor(() => expect(backend.posts()).toHaveLength(1))
    expect(window.location.hash).toBe(FORM)
    expect(screen.queryByText('Часът е променен.')).toBeNull()
    expect(screen.getByRole('heading', { level: 1, name: 'Промяна на час' })).toBeInTheDocument()
    await act(async () => release())
    expect(await screen.findByText('Часът е променен.')).toBeInTheDocument()
  })
})

describe('an unfinished proposal after a slot conflict', () => {
  async function conflict() {
    let failed = false
    backend.reschedule = (body) => {
      if (!failed) {
        failed = true
        throw backend.problem(409, 'APPOINTMENT_SLOT_UNAVAILABLE')
      }
      return { id: APPOINTMENT_ONE, status: 'CONFIRMED', start: body.start ?? '', end: '', staffMemberId: '', version: 8 }
    }
    await openForm()
    fireEvent.click(screen.getByRole('radio', { name: /Георги/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
    fireEvent.change(await screen.findByLabelText('Дата'), { target: { value: '2026-11-12' } })
    const group = await screen.findByRole('group', { name: 'Свободни часове' })
    fireEvent.click(within(group).getAllByRole('radio')[1]!)
    fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Промени часа' }))
    await screen.findByText('Избраният час вече не е свободен. Изберете друг час.')
    await screen.findByRole('group', { name: 'Свободни часове' })
  }

  it('keeps the StaffMember and the date, clears only the start, and warns on sidebar navigation', async () => {
    await conflict()
    expect(screen.getByLabelText('Дата')).toHaveValue('2026-11-12')
    const radios = within(screen.getByRole('group', { name: 'Свободни часове' })).getAllByRole('radio') as HTMLInputElement[]
    expect(radios.every((radio) => !radio.checked)).toBe(true)

    fireEvent.click(screen.getByRole('link', { name: 'Услуги' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Остани' }))
    expect(screen.getByLabelText('Дата')).toHaveValue('2026-11-12')
    expect(window.location.hash).toBe(FORM)
    fireEvent.click(screen.getByRole('link', { name: 'Услуги' }))
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Напусни' }))
    await waitFor(() => expect(window.location.hash).toContain('#/business/services'))
  })

  it('warns on browser Back and Остани keeps the proposal; Напусни then discards it', async () => {
    await conflict()
    act(() => history.back())
    const dialog = await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Остани' }))
    expect(window.location.hash).toBe(FORM)
    expect(screen.getByLabelText('Дата')).toHaveValue('2026-11-12')

    fireEvent.click(screen.getByRole('button', { name: 'Обратно към часа' }))
    fireEvent.click(within(await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })).getByRole('button', { name: 'Напусни' }))
    await screen.findByRole('region', { name: 'Детайли' })
    expect(window.location.hash).toBe(DETAIL)
    expect(backend.posts()).toHaveLength(1)
  })
})

describe('an unresolved outcome', () => {
  async function uncertain(hash = DETAIL) {
    backend.reschedule = () => {
      throw backend.problem(503, 'APPOINTMENT_OUTCOME_UNCERTAIN')
    }
    await openForm(hash)
    await toReview()
    fireEvent.click(screen.getByRole('button', { name: 'Промени часа' }))
    await screen.findByRole('alert')
  }

  it('the recovery returns to the authoritative detail or to the originating calendar state without an ordinary prompt', async () => {
    await uncertain(`#/business/appointments/${APPOINTMENT_ONE}?view=agenda&date=2026-11-10&page=2&size=25`)
    fireEvent.click(screen.getByRole('button', { name: 'Към графика' }))
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=agenda&date=2026-11-10&page=2&size=25'))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })

  it('Към часа reads the Appointment again', async () => {
    await uncertain()
    const reads = backend.count(`/${APPOINTMENT_ONE}`)
    fireEvent.click(screen.getByRole('button', { name: 'Към часа' }))
    await screen.findByRole('region', { name: 'Детайли' })
    expect(window.location.hash).toBe(DETAIL)
    expect(backend.count(`/${APPOINTMENT_ONE}`)).toBe(reads + 1)
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('leaving elsewhere uses the unresolved warning, and staying keeps the frozen form', async () => {
    await uncertain()
    fireEvent.click(screen.getByRole('link', { name: 'Услуги' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Неясен резултат' })
    expect(dialog).toHaveTextContent('Проверете часа в графика, преди да я правите отново.')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Остани' }))
    expect(screen.queryByRole('button', { name: 'Промени часа' })).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })

  it('browser Back is warned about, and Forward never reopens the submitted review or a POST', async () => {
    await uncertain()
    act(() => history.back())
    const dialog = await screen.findByRole('alertdialog', { name: 'Неясен резултат' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Напусни' }))
    await screen.findByRole('region', { name: 'Детайли' })
    act(() => history.forward())
    expect(await screen.findByRole('heading', { level: 2, name: 'Избор на служител' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Промени часа' })).toBeNull()
    expect(screen.queryByText(/След промяната/)).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })
})

describe('Business context', () => {
  function Harness({ value }: { value: Session }) {
    const [busy, setBusy] = useState(false)
    const { feedback, setFeedback, beginFeedback } = useFeedback('harness')
    return (
      <UnsavedChangesGuardProvider>
        <AuthenticatedApplication
          session={value}
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

  it('a switch of Business discards every Appointment-scoped value, including an unresolved review', async () => {
    backend.reschedule = () => {
      throw backend.problem(503, 'APPOINTMENT_OUTCOME_UNCERTAIN')
    }
    history.replaceState({}, '', `/${FORM}`)
    const { rerender } = render(<Harness value={session} />)
    await screen.findByRole('radio', { name: /Мила/ })
    fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
    const group = await screen.findByRole('group', { name: 'Свободни часове' })
    fireEvent.click(within(group).getAllByRole('radio')[0]!)
    fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Промени часа' }))
    await screen.findByRole('alert')

    rerender(<Harness value={{ ...session, activeBusinessId: 'b' }} />)
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=day'))
    expect(screen.queryByText(/След промяната/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Промени часа' })).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })

  it('a lost Business context leaves the flow for the Business selection', async () => {
    mockedRequest.mockImplementation(async (path: string) => {
      if (path === '/api/auth/session') {
        const { activeBusinessId: _gone, ...rest } = session
        void _gone
        session = rest
        return session
      }
      if (path.startsWith('/api/business/')) throw new ApiError(403, 'ACTIVE_BUSINESS_REQUIRED', 'x')
      throw new Error(path)
    })
    history.replaceState({}, '', `/${FORM}`)
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Бизнеси' })).toBeInTheDocument()
    await waitFor(() => expect(window.location.hash).toBe('#/businesses'))
  })
})

describe('privacy', () => {
  it('keeps every private value out of the URL, history state, title, storage, cookies and console across the whole flow', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) => vi.spyOn(console, method))
    const title = document.title
    await openForm()
    const during: string[] = []
    const snapshot = () =>
      during.push(
        [
          window.location.href,
          JSON.stringify(history.state),
          document.title,
          JSON.stringify({ ...window.localStorage }),
          JSON.stringify({ ...window.sessionStorage }),
          document.cookie,
        ].join('\n'),
      )
    snapshot()
    fireEvent.click(screen.getByRole('radio', { name: /Георги/ }))
    snapshot()
    await toReview()
    snapshot()
    fireEvent.click(screen.getByRole('button', { name: 'Промени часа' }))
    await screen.findByRole('heading', { level: 1, name: 'Час' })
    snapshot()

    for (const text of during) {
      expect(text).not.toMatch(/Мария|Тестова|maria|\+359|Подстригване|Боядисване|Георги|Мила|80\.5|SYS-/)
      // The version of the Appointment is never put anywhere.
      expect(text).not.toMatch(/version|expectedVersion/)
    }
    expect(document.title).toBe(title)
    for (const spy of spies) expect(spy).not.toHaveBeenCalled()
  })
})
