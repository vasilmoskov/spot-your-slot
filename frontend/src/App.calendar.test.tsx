import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { App, AuthenticatedApplication } from './App'
import { UnsavedChangesGuardProvider } from './ui/UnsavedChangesGuard'
import { useFeedback } from './ui/useFeedback'
import { ApiError, request, type Session } from './identity/api'
import {
  APPOINTMENT_ONE,
  CUSTOMER_ONE,
  STAFF_ONE,
  agendaResponse,
  calendarResponse,
  detailResponse,
  historyPage,
} from './business/calendar/testFixtures'

// Only the network helper is replaced: routing, the shell, the feature API modules and the
// application's session handling all run for real.
vi.mock('./identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)

const owner = (status: string = 'ACTIVE'): Session => ({
  email: 'ivan@example.invalid',
  displayName: 'Иван Собственик',
  platformAdmin: false,
  businesses: [
    { id: 'a', displayName: 'Студио А', role: 'BUSINESS_OWNER', status },
    { id: 'b', displayName: 'Студио Б', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
  ],
  activeBusinessId: 'a',
})

let session: Session
let businessAnswer: (path: string) => unknown

const customerRecord = {
  id: CUSTOMER_ONE,
  displayName: 'Мария Тестова',
  phone: '+359895555777',
  email: 'maria@example.test',
  version: 1,
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-02T09:30:00Z',
}

function defaultAnswer(path: string): unknown {
  if (path.startsWith('/api/business/staff-members')) {
    return {
      staffMembers: [{ id: STAFF_ONE, displayName: 'Мила Работникова', active: true }],
      page: 0,
      size: 50,
      totalElements: 1,
    }
  }
  if (path.startsWith('/api/business/calendar')) return calendarResponse()
  if (path.startsWith(`/api/business/appointments/${APPOINTMENT_ONE}`)) return detailResponse()
  if (path.startsWith('/api/business/appointments')) return agendaResponse()
  if (path.startsWith(`/api/business/customers/${CUSTOMER_ONE}/appointments`)) return historyPage()
  if (path === `/api/business/customers/${CUSTOMER_ONE}`) return customerRecord
  throw new Error(`unexpected business request ${path}`)
}

beforeEach(() => {
  session = owner()
  businessAnswer = defaultAnswer
  mockedRequest.mockReset()
  mockedRequest.mockImplementation(async (path: string) => {
    if (path === '/api/auth/session') return session
    if (path.startsWith('/api/business/')) return businessAnswer(path)
    throw new Error(`unexpected request ${path}`)
  })
  history.replaceState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

function Harness({ session: value }: { session: Session }) {
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

const businessRequests = (prefix: string) =>
  mockedRequest.mock.calls.map((call) => call[0] as string).filter((path) => path.startsWith(prefix))

describe('График navigation', () => {
  it('adds the destination to the Business group, active on its routes only', async () => {
    history.replaceState({}, '', '/#/business/calendar?view=day&date=2026-10-14')
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'График' })).toBeInTheDocument()
    const group = screen.getByRole('group', { name: 'Студио А' })
    const link = within(group).getByRole('link', { name: 'График' })
    expect(link).toHaveAttribute('aria-current', 'page')
    expect(within(group).getByRole('link', { name: 'Услуги' })).not.toHaveAttribute('aria-current')
    expect(within(group).getByRole('link', { name: 'Работно време' })).not.toHaveAttribute('aria-current')
  })

  it('keeps the Промени в графика tab inside Работно време, unchanged', async () => {
    history.replaceState({}, '', '/#/business/schedule/exceptions')
    businessAnswer = (path) => {
      if (path.startsWith('/api/business/schedule-exceptions'))
        return { from: '2026-10-01', to: '2026-10-31', timezone: 'Europe/Sofia', exceptions: [] }
      return defaultAnswer(path)
    }
    render(<App />)
    expect(await screen.findByRole('link', { name: 'Промени в графика' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'График' })).not.toHaveAttribute('aria-current')
  })

  it('opens the calendar from the sidebar and adopts a canonical route', async () => {
    history.replaceState({}, '', '/#/business/services')
    businessAnswer = (path) =>
      path.startsWith('/api/business/services')
        ? { services: [], page: 0, size: 10, totalElements: 0 }
        : defaultAnswer(path)
    render(<App />)
    await screen.findByRole('heading', { level: 1, name: 'Услуги' })
    fireEvent.click(screen.getByRole('link', { name: 'График' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'График' })).toBeInTheDocument()
    await waitFor(() => expect(window.location.hash).toMatch(/^#\/business\/calendar\?view=day&date=\d{4}-\d{2}-\d{2}$/))
  })

  it('supports browser Back and Forward between views and the detail', async () => {
    history.replaceState({}, '', '/#/business/calendar?view=day&date=2026-10-14')
    render(<App />)
    await screen.findByRole('link', { name: '10:00–10:45' })
    fireEvent.click(screen.getByRole('link', { name: 'Седмица' }))
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=week&date=2026-10-14'))
    fireEvent.click(await screen.findByRole('link', { name: '10:00–10:45' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Час' })).toBeInTheDocument()
    expect(window.location.hash).toBe(`#/business/appointments/${APPOINTMENT_ONE}?view=week&date=2026-10-14`)

    act(() => history.back())
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=week&date=2026-10-14'))
    expect(await screen.findByRole('link', { name: 'Седмица' })).toHaveAttribute('aria-current', 'page')
    act(() => history.back())
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=day&date=2026-10-14'))
    expect(await screen.findByRole('link', { name: 'Ден' })).toHaveAttribute('aria-current', 'page')
    act(() => history.forward())
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=week&date=2026-10-14'))
  })

  it('returns from the detail to the exact calendar state the user left', async () => {
    history.replaceState({}, '', `/#/business/appointments/${APPOINTMENT_ONE}?view=agenda&date=2026-10-14&page=2&size=25`)
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Обратно към графика' }))
    await waitFor(() =>
      expect(window.location.hash).toBe('#/business/calendar?view=agenda&date=2026-10-14&page=2&size=25'),
    )
  })

  it('shows the safe not-found state of a stale Appointment and returns to the calendar with the Business kept', async () => {
    history.replaceState({}, '', `/#/business/appointments/${APPOINTMENT_ONE}?view=day&date=2026-10-14`)
    businessAnswer = (path) => {
      if (path.startsWith('/api/business/appointments/'))
        throw new ApiError(404, 'APPOINTMENT_NOT_FOUND', 'Часът не е намерен.')
      return defaultAnswer(path)
    }
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Часът не е намерен.')
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'График' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Студио А' })).toBeInTheDocument()
  })
})

describe('Customer history integration', () => {
  it('opens an Appointment from the Customer history and returns to that Customer', async () => {
    history.replaceState({}, '', `/#/business/customers/${CUSTOMER_ONE}`)
    render(<App />)
    const history_ = await screen.findByRole('region', { name: 'Часове' })
    fireEvent.click(await within(history_).findByRole('link', { name: /14 октомври 2026/ }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Час' })).toBeInTheDocument()
    expect(window.location.hash).toBe(`#/business/appointments/${APPOINTMENT_ONE}?customer=${CUSTOMER_ONE}`)
    expect(screen.getByRole('link', { name: 'Клиенти' }).getAttribute('aria-current')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към клиента' }))
    await waitFor(() => expect(window.location.hash).toBe(`#/business/customers/${CUSTOMER_ONE}`))
    expect(await screen.findByRole('region', { name: 'Часове' })).toBeInTheDocument()
  })

  it('shows the Customer edit action and the history in a SUSPENDED Business, without adding Customer behaviour', async () => {
    session = owner('SUSPENDED')
    history.replaceState({}, '', `/#/business/customers/${CUSTOMER_ONE}`)
    render(<App />)
    expect(await screen.findByRole('button', { name: 'Редактирай' })).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: 'Часове' })).toBeInTheDocument()
    expect(screen.getAllByText(/Бизнесът е временно спрян/)).toHaveLength(1)
  })
})

describe('lifecycle states', () => {
  async function lifecycle(status: string) {
    session = owner(status)
    history.replaceState({}, '', '/#/business/calendar?view=day&date=2026-10-14')
    render(<App />)
    await screen.findByRole('link', { name: '10:00–10:45' })
  }

  it('shows no lifecycle notice for an ACTIVE Business', async () => {
    await lifecycle('ACTIVE')
    expect(document.querySelector('.shell-banner')).toBeNull()
  })

  it.each([
    ['DRAFT', 'Бизнесът още не е активен. Можете да преглеждате графика и часовете, но не можете да ги променяте.'],
    ['SUSPENDED', 'Бизнесът е временно спрян. Можете да преглеждате графика и часовете, но не можете да ги променяте.'],
  ])('%s: the calendar stays readable, with exactly one explanation and no mutation control', async (status, text) => {
    await lifecycle(status)
    expect(screen.getAllByText(text)).toHaveLength(1)
    expect(document.querySelectorAll('.shell-banner')).toHaveLength(1)
    expect(screen.queryByText(/конфигурацията/)).toBeNull()
    const buttons = screen.getAllByRole('button').map((button) => button.textContent)
    expect(buttons).not.toContain('Добави час')
    expect(document.body.textContent).not.toMatch(/Нов час|Отмени час|Пренасрочи|Запази/)
  })

  it('shows the same single notice on the Appointment detail', async () => {
    session = owner('SUSPENDED')
    history.replaceState({}, '', `/#/business/appointments/${APPOINTMENT_ONE}`)
    render(<App />)
    await screen.findByRole('region', { name: 'Детайли' })
    expect(document.querySelectorAll('.shell-banner')).toHaveLength(1)
    expect(screen.getAllByRole('button').map((button) => button.textContent)).toEqual([
      'Меню',
      'Изход',
      'Изход',
      'Обратно към графика',
    ])
  })
})

describe('access', () => {
  it.each(['MANAGER', 'STAFF'] as const)('does not offer the calendar to a %s Membership', async (role) => {
    session = {
      ...owner(),
      businesses: [{ id: 'a', displayName: 'Студио А', role, status: 'ACTIVE' }],
    }
    history.replaceState({}, '', '/#/business/calendar?view=day')
    render(<App />)
    await waitFor(() => expect(window.location.hash).toBe('#/profile'))
    expect(screen.queryByRole('link', { name: 'График' })).toBeNull()
    expect(businessRequests('/api/business/calendar')).toHaveLength(0)
  })
})

describe('lost Business context and switching', () => {
  it('leaves the calendar for the Business selection when the context is gone', async () => {
    history.replaceState({}, '', '/#/business/calendar?view=day&date=2026-10-14')
    businessAnswer = () => {
      const { activeBusinessId: _gone, ...rest } = session
      void _gone
      session = rest
      throw new ApiError(403, 'ACTIVE_BUSINESS_REQUIRED', 'Изберете бизнес, за да продължите.')
    }
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Бизнеси' })).toBeInTheDocument()
    await waitFor(() => expect(window.location.hash).toBe('#/businesses'))
  })

  it('does not carry the StaffMember filter or date of one Business to another, nor show a late answer of the previous one', async () => {
    history.replaceState({}, '', `/#/business/calendar?view=week&date=2026-10-14&staff=${STAFF_ONE}`)
    const { rerender } = render(<Harness session={session} />)
    await screen.findByRole('link', { name: '10:00–10:45' })
    let resolveLate!: (value: unknown) => void
    businessAnswer = (path) => {
      if (path.startsWith('/api/business/calendar') && path.includes('staffMemberId')) {
        return new Promise((resolve) => (resolveLate = resolve))
      }
      return defaultAnswer(path)
    }
    // A refresh for the old Business is still pending when the user switches Business.
    fireEvent.click(screen.getByRole('link', { name: 'Седмица' }))
    rerender(<Harness session={{ ...session, activeBusinessId: 'b' }} />)
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=week'))
    await act(async () => {
      resolveLate?.(
        calendarResponse({
          appointments: [
            { ...calendarResponse().appointments[0]!, customerDisplayName: 'Данни от Студио А' },
          ],
        }),
      )
    })
    expect(screen.queryByText('Данни от Студио А')).toBeNull()
    expect(businessRequests('/api/business/calendar').at(-1)).not.toContain('staffMemberId')
  })
})

describe('privacy', () => {
  it('keeps Customer data out of the URL, history state, title and storage across the whole flow', async () => {
    history.replaceState({}, '', '/#/business/calendar?view=day&date=2026-10-14')
    const title = document.title
    render(<App />)
    fireEvent.click(await screen.findByRole('link', { name: '10:00–10:45' }))
    await screen.findByRole('region', { name: 'Клиент' })
    const haystack = [
      window.location.href,
      JSON.stringify(history.state),
      document.title,
      JSON.stringify({ ...window.localStorage }),
      JSON.stringify({ ...window.sessionStorage }),
    ].join('\n')
    expect(haystack).not.toMatch(/Мария|Тестова|maria|\+359|Подстригване/)
    expect(document.title).toBe(title)
  })
})

describe('working time on the day Calendar after a schedule change', () => {
  const MONDAY = '2026-10-26'
  const staffAt = (end: string) => ({
    ...calendarResponse().staffMembers[0]!,
    workingIntervals: [{ start: `${MONDAY}T09:00:00+02:00`, end: `${MONDAY}T${end}:00+02:00` }],
  })
  const responseFor = (end: string) => ({
    ...calendarResponse(),
    from: MONDAY,
    to: MONDAY,
    days: [{ date: MONDAY, start: `${MONDAY}T00:00:00+02:00`, end: '2026-10-27T00:00:00+02:00' }],
    staffMembers: [staffAt(end)],
    appointments: [],
  })

  it('shows the saved period and the grid again when the owner returns, without a browser reload', async () => {
    let end = '18:00'
    businessAnswer = (path) => (path.startsWith('/api/business/calendar') ? responseFor(end) : defaultAnswer(path))
    history.replaceState({}, '', `/#/business/calendar?view=day&date=${MONDAY}`)
    render(<App />)
    expect(await screen.findByText('09:00–18:00')).toBeInTheDocument()
    const labels = () => Array.from(document.querySelectorAll('.calendar-axis-label')).map((label) => label.textContent)
    expect(labels().at(-1)).toBe('18:00')

    // The owner leaves for the schedule, saves 09:00–19:00 there, and comes back through the sidebar.
    fireEvent.click(screen.getByRole('link', { name: 'Екип' }))
    await screen.findByRole('heading', { level: 1, name: 'Екип' })
    end = '19:00'
    // Back returns to the same date (the sidebar link opens today); the Calendar mounts again and reads again.
    act(() => history.back())
    expect(await screen.findByText('09:00–19:00')).toBeInTheDocument()
    expect(screen.queryByText('09:00–18:00')).toBeNull()
    expect(labels().at(-1)).toBe('19:00')
    expect(businessRequests('/api/business/calendar').length).toBeGreaterThanOrEqual(2)
  })

  it('keeps a date-specific exception over the weekly period: a closure shows no working time', async () => {
    businessAnswer = (path) =>
      path.startsWith('/api/business/calendar')
        ? { ...responseFor('19:00'), staffMembers: [{ ...staffAt('19:00'), workingIntervals: [], closures: [{ date: MONDAY, kind: 'STAFF_TIME_OFF' }] }] }
        : defaultAnswer(path)
    history.replaceState({}, '', `/#/business/calendar?view=day&date=${MONDAY}`)
    render(<App />)
    expect(await screen.findByText('Отсъствие')).toBeInTheDocument()
    expect(screen.queryByText('09:00–19:00')).toBeNull()
  })
})
