import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App, AuthenticatedApplication } from './App'
import { request, type Session } from './identity/api'
import { UnsavedChangesGuardProvider } from './ui/UnsavedChangesGuard'
import { useFeedback } from './ui/useFeedback'
import { appointment, calendarResponse } from './business/calendar/testFixtures'
import {
  APPOINTMENT_ONE,
  FakeLifecycleBackend,
  NOW,
  cancelled,
  confirmed,
} from './business/appointments/lifecycleTestFixtures'

// Only the network helper is replaced: routing, the shell, the guard, the feature API modules and the
// session handling all run for real. The date is the only other thing faked, so that "future" and
// "started" are decided against a known instant.
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
let backend: FakeLifecycleBackend

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
  session = sessionOf('ACTIVE')
  backend = new FakeLifecycleBackend()
  mockedRequest.mockReset()
  mockedRequest.mockImplementation(async (path: string, options?: RequestInit) => {
    if (path === '/api/auth/session') return session
    if (path.startsWith('/api/business/calendar')) {
      return calendarResponse({
        appointments: [appointment({ status: backend.current.status })],
      })
    }
    if (path.startsWith(`/api/business/appointments/${APPOINTMENT_ONE}`)) return backend.handle(path, options)
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

const ORIGIN = 'view=day&date=2026-10-14'
const DETAIL = `#/business/appointments/${APPOINTMENT_ONE}?${ORIGIN}`
const CALENDAR = `#/business/calendar?${ORIGIN}`

async function openDetail(hash = DETAIL) {
  history.replaceState({}, '', `/${hash}`)
  render(<App />)
  await screen.findByRole('region', { name: 'Час' })
}

const cancelOpener = () => screen.queryByRole('button', { name: 'Отмени часа' })
const noteEditor = () => screen.queryByRole('button', { name: /Редактирай бележката|Добави бележка/ })

describe('visibility by role, Business lifecycle, and Appointment state', () => {
  it('shows both operations to the owner of an ACTIVE Business', async () => {
    await openDetail()
    expect(cancelOpener()).toBeInTheDocument()
    expect(noteEditor()).toBeInTheDocument()
  })

  it.each(['DRAFT', 'SUSPENDED'])('shows neither to the owner of a %s Business, which stays readable', async (status) => {
    session = sessionOf(status)
    backend.current = confirmed({ businessStatus: status as 'DRAFT' | 'SUSPENDED', internalNote: 'Само за четене.' })
    await openDetail()
    expect(cancelOpener()).toBeNull()
    expect(noteEditor()).toBeNull()
    expect(screen.queryByRole('button', { name: 'Промени часа' })).toBeNull()
    expect(screen.getByRole('region', { name: 'Бележки' })).toHaveTextContent('Само за четене.')
    expect(document.querySelector('.shell-banner')).not.toBeNull()
  })

  it.each(['DRAFT', 'SUSPENDED'])('shows neither when only the session says the Business is %s', async (status) => {
    session = sessionOf(status)
    await openDetail()
    expect(cancelOpener()).toBeNull()
    expect(noteEditor()).toBeNull()
  })

  it('a cancelled Appointment has no cancellation but keeps the note editor', async () => {
    backend.current = cancelled()
    await openDetail()
    expect(cancelOpener()).toBeNull()
    expect(noteEditor()).toBeInTheDocument()
  })

  it('a started Appointment has no cancellation but keeps the note editor', async () => {
    backend.current = confirmed({ start: '2026-10-02T10:00:00+03:00', end: '2026-10-02T11:15:00+03:00' })
    await openDetail()
    expect(cancelOpener()).toBeNull()
    expect(noteEditor()).toBeInTheDocument()
  })

  it.each(['MANAGER', 'STAFF'] as const)('a %s Membership never reaches the detail or any operation', async (role) => {
    session = { ...sessionOf('ACTIVE', role), businesses: [{ id: 'a', displayName: 'Студио А', role, status: 'ACTIVE' }] }
    history.replaceState({}, '', `/${DETAIL}`)
    render(<App />)
    await waitFor(() => expect(window.location.hash).toBe('#/profile'))
    expect(backend.calls).toHaveLength(0)
    expect(cancelOpener()).toBeNull()
  })

  it('a platform administrator without an owner Membership has no path to them', async () => {
    session = { email: 'a@example.invalid', displayName: 'Админ', platformAdmin: true, businesses: [] }
    history.replaceState({}, '', `/${DETAIL}`)
    render(<App />)
    await waitFor(() => expect(window.location.hash).not.toContain('appointments'))
    expect(backend.calls).toHaveLength(0)
  })

  it('an anonymous visitor sees the sign-in and nothing of the Appointment', async () => {
    mockedRequest.mockRejectedValue(new Error('no session'))
    history.replaceState({}, '', `/${DETAIL}`)
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Вход' })).toBeInTheDocument()
    expect(cancelOpener()).toBeNull()
  })
})

describe('cancellation through the application', () => {
  it('re-reads, stays on the detail route, and the calendar the owner returns to shows the cancelled state', async () => {
    await openDetail()
    const historyLength = history.length
    fireEvent.click(cancelOpener()!)
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Отмени часа' }))
    expect(await screen.findByText(/Този час е отменен/)).toBeInTheDocument()
    expect(window.location.hash).toBe(DETAIL)
    expect(history.length).toBe(historyLength)
    expect(backend.posts()).toHaveLength(1)
    expect(backend.calls.map((call) => call.method)).toEqual(['GET', 'POST', 'GET'])

    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    await waitFor(() => expect(window.location.hash).toBe(CALENDAR))
    const entry = await screen.findByText('Мария Тестова')
    const container = entry.closest('li, article, [role="listitem"], .calendar-appointment') ?? entry.parentElement!
    expect(within(container as HTMLElement).getByText('Отменен')).toBeInTheDocument()
    expect(screen.queryByText('Потвърден')).toBeNull()
  })

  it('a refused cancellation leaves the calendar showing what the server says', async () => {
    backend.cancel = () => {
      throw backend.problem(503, 'APPOINTMENT_TEMPORARILY_UNAVAILABLE')
    }
    await openDetail()
    fireEvent.click(cancelOpener()!)
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Отмени часа' }))
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    await waitFor(() => expect(window.location.hash).toBe(CALENDAR))
    expect(await screen.findByText('Мария Тестова')).toBeInTheDocument()
    expect(screen.queryByText('Отменен')).toBeNull()
  })

  it('an unresolved outcome keeps the frozen state across the shell and warns on every other exit', async () => {
    backend.cancel = () => {
      throw new TypeError('Failed to fetch')
    }
    await openDetail()
    fireEvent.click(cancelOpener()!)
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Отмени часа' }))
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('link', { name: 'Услуги' }))
    const warning = await screen.findByRole('alertdialog', { name: 'Неясен резултат' })
    fireEvent.click(within(warning).getByRole('button', { name: 'Остани' }))
    expect(window.location.hash).toBe(DETAIL)
    act(() => history.back())
    fireEvent.click(within(await screen.findByRole('alertdialog', { name: 'Неясен резултат' })).getByRole('button', { name: 'Остани' }))
    expect(window.location.hash).toBe(DETAIL)
    expect(cancelOpener()).toBeNull()
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

  it('a switch of Business discards an unresolved cancellation and an edited note with every Appointment value', async () => {
    backend.cancel = () => {
      throw new TypeError('Failed to fetch')
    }
    history.replaceState({}, '', `/${DETAIL}`)
    const { rerender } = render(<Harness value={session} />)
    await screen.findByRole('region', { name: 'Час' })
    fireEvent.click(cancelOpener()!)
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Отмени часа' }))
    await screen.findByRole('alert')
    expect(backend.posts()).toHaveLength(1)

    rerender(<Harness value={{ ...session, activeBusinessId: 'b' }} />)
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=day'))
    expect(screen.queryByText(/Не е ясно дали часът е отменен/)).toBeNull()
    expect(screen.queryByRole('region', { name: 'Час' })).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })
})

describe('the internal note through the application', () => {
  it('warns before the sidebar discards an edited note and keeps the edit on Остани', async () => {
    await openDetail()
    fireEvent.click(noteEditor()!)
    fireEvent.change(screen.getByRole('textbox', { name: 'Вътрешна бележка' }), { target: { value: 'Нещо важно' } })
    fireEvent.click(screen.getByRole('link', { name: 'Услуги' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Непотвърдена бележка' })
    expect(within(dialog).getByRole('button', { name: 'Остани' })).toHaveFocus()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Остани' }))
    expect(screen.getByRole('textbox', { name: 'Вътрешна бележка' })).toHaveValue('Нещо важно')
    expect(window.location.hash).toBe(DETAIL)
    fireEvent.click(screen.getByRole('link', { name: 'Услуги' }))
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Напусни' }))
    await waitFor(() => expect(window.location.hash).toContain('#/business/services'))
    expect(backend.posts()).toHaveLength(0)
  })

  it('guards browser Back of an edited note', async () => {
    await openDetail()
    fireEvent.click(noteEditor()!)
    fireEvent.change(screen.getByRole('textbox', { name: 'Вътрешна бележка' }), { target: { value: 'Нещо важно' } })
    act(() => history.back())
    expect(await screen.findByRole('alertdialog', { name: 'Непотвърдена бележка' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(window.location.hash).toBe(DETAIL)
  })

  it('saves, re-reads, and leaves no trace of the note anywhere but the screen', async () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const secret = 'ТАЙНА-БЕЛЕЖКА-4711'
    await openDetail()
    fireEvent.click(noteEditor()!)
    fireEvent.change(screen.getByRole('textbox', { name: 'Вътрешна бележка' }), { target: { value: secret } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази бележката' }))
    await screen.findByText('Бележката е запазена.')
    expect(screen.getByRole('region', { name: 'Бележки' })).toHaveTextContent(secret)
    expect(window.location.href).not.toContain(secret)
    expect(decodeURIComponent(window.location.href)).not.toContain(secret)
    expect(JSON.stringify(window.history.state)).not.toContain(secret)
    expect(document.title).not.toContain(secret)
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
    expect(document.cookie).not.toContain(secret)
    expect(log).not.toHaveBeenCalled()
    expect(error).not.toHaveBeenCalled()
    // The note is private to the detail: the calendar summary does not carry it.
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    await screen.findByText('Мария Тестова')
    expect(document.body.textContent).not.toContain(secret)
  })
})
