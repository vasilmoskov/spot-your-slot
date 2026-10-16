import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from '../../identity/api'
import type { CalendarState, ListNavigationMode } from '../../navigation'
import { BusinessCalendar } from './BusinessCalendar'
import { WIDE_VIEWPORT_QUERY } from './useWideViewport'
import {
  STAFF_ONE,
  STAFF_TWO,
  agendaResponse,
  appointment,
  calendarResponse,
  day,
  staff,
} from './testFixtures'

// Only the network helper is mocked: the real feature API modules build and send every request.
vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
const NOW = new Date('2026-10-14T10:00:00Z')
const ORIGINAL_TZ = process.env['TZ']

type Handler = (path: string, signal: AbortSignal | undefined) => Promise<unknown>
let handler: Handler
const staffCatalog = {
  staffMembers: [
    { id: STAFF_ONE, displayName: 'Мила Работникова', active: true },
    { id: STAFF_TWO, displayName: 'Георги Стилистов', active: true },
  ],
  page: 0,
  size: 50,
  totalElements: 2,
}

function calendarRequests(): string[] {
  return mockedRequest.mock.calls
    .map((call) => call[0] as string)
    .filter((path) => path.startsWith('/api/business/calendar') || path.startsWith('/api/business/appointments'))
}

beforeEach(() => {
  mockedRequest.mockReset()
  handler = async (path) => {
    if (path.startsWith('/api/business/staff-members')) return staffCatalog
    if (path.startsWith('/api/business/calendar')) return calendarResponse()
    if (path.startsWith('/api/business/appointments')) return agendaResponse()
    throw new Error(`unexpected ${path}`)
  }
  mockedRequest.mockImplementation((path, options) =>
    handler(path, (options as RequestInit | undefined)?.signal ?? undefined) as Promise<never>,
  )
  history.replaceState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  if (ORIGINAL_TZ === undefined) delete process.env['TZ']
  else process.env['TZ'] = ORIGINAL_TZ
  Object.defineProperty(window, 'matchMedia', { value: undefined, configurable: true, writable: true })
})

const BASE: CalendarState = {
  view: 'day',
  date: '2026-10-14',
  staffMemberId: null,
  page: 0,
  size: 10,
}

type Spies = {
  changes: Array<{ state: CalendarState; mode: ListNavigationMode | undefined }>
  opened: Array<{ id: string; calendar: CalendarState }>
  auth: string[]
}

function renderCalendar(initial: CalendarState = BASE, now = NOW) {
  const spies: Spies = { changes: [], opened: [], auth: [] }
  function Harness({ unrelated }: { unrelated: number }) {
    const [state, setState] = useState(initial)
    return (
      <div data-unrelated={unrelated}>
        <BusinessCalendar
          state={state}
          now={() => now}
          onStateChange={(next, mode) => {
            spies.changes.push({ state: next, mode })
            setState(next)
          }}
          onOpenAppointment={(id, calendar) => spies.opened.push({ id, calendar })}
          onAuthenticationRequired={(detail) => spies.auth.push(detail)}
        />
      </div>
    )
  }
  const view = render(<Harness unrelated={0} />)
  return {
    spies,
    rerender: (unrelated: number) => view.rerender(<Harness unrelated={unrelated} />),
  }
}

const deferredResponse = <T,>() => {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('first load', () => {
  it('names the Business timezone and renders the Business-local day', async () => {
    renderCalendar()
    expect(await screen.findByRole('heading', { level: 2, name: /14 октомври 2026/ })).toBeInTheDocument()
    expect(await screen.findByText('Часова зона: Europe/Sofia')).toBeInTheDocument()
    expect(calendarRequests()).toEqual(['/api/business/calendar?from=2026-10-14&to=2026-10-14'])
  })

  it('adopts the Business "today" once, replacing history, and refetches only when the day differs', async () => {
    // 23:30 UTC is already the next day in Sofia; the browser (UTC) first guesses the 14th.
    process.env['TZ'] = 'UTC'
    handler = async (path) => {
      if (path.startsWith('/api/business/staff-members')) return staffCatalog
      return calendarResponse({ from: path.includes('2026-10-15') ? '2026-10-15' : '2026-10-14' })
    }
    const { spies } = renderCalendar({ ...BASE, date: null }, new Date('2026-10-14T23:30:00Z'))
    await screen.findByText('Часова зона: Europe/Sofia')
    await waitFor(() =>
      expect(calendarRequests()).toEqual([
        '/api/business/calendar?from=2026-10-14&to=2026-10-14',
        '/api/business/calendar?from=2026-10-15&to=2026-10-15',
      ]),
    )
    expect(spies.changes).toEqual([{ state: { ...BASE, date: '2026-10-15' }, mode: 'replace' }])
    expect(await screen.findByRole('heading', { level: 2, name: /15 октомври 2026/ })).toBeInTheDocument()
  })

  it('does not refetch when the browser guess already equals the Business today', async () => {
    process.env['TZ'] = 'UTC'
    renderCalendar({ ...BASE, date: null })
    await screen.findByText('Часова зона: Europe/Sofia')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Днес' })).toBeDisabled())
    expect(calendarRequests()).toHaveLength(1)
  })
})

describe('the three views', () => {
  it('switches between day, week and agenda with the exact bounded requests', async () => {
    renderCalendar()
    await screen.findByText('Мария Тестова')
    const views = screen.getByRole('navigation', { name: 'Изглед на графика' })
    expect(within(views).getByRole('link', { name: 'Ден' })).toHaveAttribute('aria-current', 'page')

    handler = async (path) =>
      path.startsWith('/api/business/staff-members')
        ? staffCatalog
        : path.startsWith('/api/business/appointments')
          ? agendaResponse()
          : calendarResponse({
              from: '2026-10-12',
              to: '2026-10-18',
              days: ['12', '13', '14', '15', '16', '17', '18'].map((d) => day(`2026-10-${d}`)),
            })
    fireEvent.click(within(views).getByRole('link', { name: 'Седмица' }))
    await waitFor(() =>
      expect(calendarRequests().at(-1)).toBe('/api/business/calendar?from=2026-10-12&to=2026-10-18'),
    )
    await screen.findByRole('heading', { level: 3, name: 'Пн, 12 октомври 2026' })
    expect(within(views).getByRole('link', { name: 'Седмица' })).toHaveAttribute('aria-current', 'page')

    fireEvent.click(within(views).getByRole('link', { name: 'Списък' }))
    await waitFor(() =>
      expect(calendarRequests().at(-1)).toBe(
        '/api/business/appointments?from=2026-10-01&to=2026-10-31&page=0&size=10',
      ),
    )
    await screen.findByText('Мария Тестова')
    expect(within(views).getByRole('link', { name: 'Списък' })).toHaveAttribute('aria-current', 'page')
  })

  it('moves one period at a time in the order previous, today, next', async () => {
    renderCalendar()
    await screen.findByText('Мария Тестова')
    const group = screen.getByRole('group', { name: 'Навигация по период' })
    expect(within(group).getAllByRole('button').map((button) => button.textContent)).toEqual([
      'Предишен',
      'Днес',
      'Следващ',
    ])
    fireEvent.click(within(group).getByRole('button', { name: 'Следващ' }))
    await waitFor(() =>
      expect(calendarRequests().at(-1)).toBe('/api/business/calendar?from=2026-10-15&to=2026-10-15'),
    )
    fireEvent.click(within(group).getByRole('button', { name: 'Предишен' }))
    fireEvent.click(within(group).getByRole('button', { name: 'Предишен' }))
    await waitFor(() =>
      expect(calendarRequests().at(-1)).toBe('/api/business/calendar?from=2026-10-13&to=2026-10-13'),
    )
    fireEvent.click(within(group).getByRole('button', { name: 'Днес' }))
    await waitFor(() =>
      expect(calendarRequests().at(-1)).toBe('/api/business/calendar?from=2026-10-14&to=2026-10-14'),
    )
  })

  it('never requests more than 31 dates in the agenda, even for a 31-day month', async () => {
    renderCalendar({ ...BASE, view: 'agenda' })
    await screen.findByText('Мария Тестова')
    const path = calendarRequests().at(-1)!
    const params = new URLSearchParams(path.split('?')[1])
    const from = Date.parse(`${params.get('from')}T00:00:00Z`)
    const to = Date.parse(`${params.get('to')}T00:00:00Z`)
    expect((to - from) / 86_400_000 + 1).toBeLessThanOrEqual(31)
  })

  it('shows an explicit empty state per view', async () => {
    handler = async (path) =>
      path.startsWith('/api/business/staff-members')
        ? staffCatalog
        : path.startsWith('/api/business/appointments')
          ? agendaResponse([], { total: 0 })
          : calendarResponse({ appointments: [] })
    renderCalendar()
    expect(await screen.findByText('Няма часове за този ден.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'Седмица' }))
    expect(await screen.findByText('Няма часове през тази седмица.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'Списък' }))
    expect(await screen.findByText('Няма часове в този период.')).toBeInTheDocument()
  })
})

describe('Appointment entries', () => {
  it('shows start–end in the Business timezone, status, Customer, ordered Services, StaffMember and duration', async () => {
    handler = async (path) =>
      path.startsWith('/api/business/staff-members')
        ? staffCatalog
        : calendarResponse({
            appointments: [
              appointment({
                start: '2026-10-14T07:00:00Z',
                end: '2026-10-14T08:15:00Z',
                durationMinutes: 75,
                services: [
                  { name: 'Подстригване', durationMinutes: 30 },
                  { name: 'Боядисване', durationMinutes: 45 },
                ],
              }),
            ],
          })
    renderCalendar()
    const link = await screen.findByRole('link', { name: '10:00–11:15' })
    const entry = link.closest('article')!
    expect(within(entry).getByText('Потвърден')).toBeInTheDocument()
    expect(within(entry).getByText('Мария Тестова')).toBeInTheDocument()
    expect(within(entry).getByText('Подстригване, Боядисване')).toBeInTheDocument()
    expect(entry).toHaveTextContent('1 ч 15 мин')
  })

  it('renders the time in the Business timezone under any browser timezone', async () => {
    for (const zone of ['Pacific/Kiritimati', 'America/Los_Angeles', 'UTC']) {
      process.env['TZ'] = zone
      cleanup()
      renderCalendar()
      expect(await screen.findByRole('link', { name: '10:00–10:45' })).toBeInTheDocument()
    }
  })

  it('does not use the browser timezone for the date-only heading', async () => {
    for (const zone of ['Pacific/Kiritimati', 'Pacific/Pago_Pago']) {
      process.env['TZ'] = zone
      cleanup()
      renderCalendar({ ...BASE, date: '2026-10-25' })
      expect(await screen.findByRole('heading', { level: 2, name: /25 октомври 2026/ })).toBeInTheDocument()
    }
  })

  it('communicates CANCELLED in text and in structure, not only in colour', async () => {
    handler = async (path) =>
      path.startsWith('/api/business/staff-members')
        ? staffCatalog
        : calendarResponse({ appointments: [appointment({ status: 'CANCELLED' })] })
    renderCalendar()
    const link = await screen.findByRole('link', { name: '10:00–10:45' })
    const entry = link.closest('article')!
    expect(within(entry).getByText('Отменен')).toBeInTheDocument()
    expect(entry).toHaveClass('is-cancelled')
    expect(within(entry).queryByText('Потвърден')).toBeNull()
  })

  it('shows two StaffMembers booked at the same time side by side without implying a double booking', async () => {
    handler = async (path) =>
      path.startsWith('/api/business/staff-members')
        ? staffCatalog
        : calendarResponse({
            staffMembers: [
              staff({ id: STAFF_ONE, displayName: 'Мила Работникова' }),
              staff({ id: STAFF_TWO, displayName: 'Георги Стилистов' }),
            ],
            appointments: [
              appointment({ id: 'a1', customerDisplayName: 'Първи Клиент' }),
              appointment({
                id: 'a2',
                staffMemberId: STAFF_TWO,
                staffDisplayName: 'Георги Стилистов',
                customerDisplayName: 'Втори Клиент',
              }),
            ],
          })
    renderCalendar()
    const first = await screen.findByRole('region', { name: 'Мила Работникова' })
    const second = screen.getByRole('region', { name: 'Георги Стилистов' })
    expect(within(first).getByText('Първи Клиент')).toBeInTheDocument()
    expect(within(second).getByText('Втори Клиент')).toBeInTheDocument()
    // Each sits in its own StaffMember column at full width, not in a shared overlapping lane.
    const blocks = document.querySelectorAll<HTMLElement>('.calendar-block')
    expect(Array.from(blocks).map((block) => block.style.width)).toEqual(['100%', '100%'])
  })

  it('puts a cancelled and a confirmed entry of one StaffMember in separate lanes', async () => {
    handler = async (path) =>
      path.startsWith('/api/business/staff-members')
        ? staffCatalog
        : calendarResponse({
            appointments: [
              appointment({ id: 'a1', status: 'CANCELLED' }),
              appointment({ id: 'a2', customerDisplayName: 'Нов Клиент' }),
            ],
          })
    renderCalendar()
    await screen.findByText('Нов Клиент')
    const widths = Array.from(document.querySelectorAll<HTMLElement>('.calendar-block')).map(
      (block) => block.style.width,
    )
    expect(widths).toEqual(['50%', '50%'])
  })

  it('opens the detail through the entry link and keeps the calendar state', async () => {
    const { spies } = renderCalendar()
    fireEvent.click(await screen.findByRole('link', { name: '10:00–10:45' }))
    expect(spies.opened).toEqual([{ id: appointment().id, calendar: BASE }])
  })

  it('shows StaffMember working time and full-day closures as text', async () => {
    handler = async (path) =>
      path.startsWith('/api/business/staff-members')
        ? staffCatalog
        : calendarResponse({
            staffMembers: [
              staff({
                workingIntervals: [
                  { start: '2026-10-14T09:00:00+03:00', end: '2026-10-14T13:00:00+03:00' },
                ],
              }),
              staff({
                id: STAFF_TWO,
                displayName: 'Георги Стилистов',
                closures: [{ date: '2026-10-14', kind: 'STAFF_TIME_OFF' }],
              }),
            ],
          })
    renderCalendar()
    expect(await screen.findByText('Работно време: 09:00–13:00')).toBeInTheDocument()
    expect(screen.getByText('Отсъствие')).toBeInTheDocument()
  })
})

describe('daylight-saving days', () => {
  it('distinguishes the two occurrences of a repeated local time on a 25-hour day', async () => {
    handler = async (path) =>
      path.startsWith('/api/business/staff-members')
        ? staffCatalog
        : calendarResponse({
            from: '2026-10-25',
            to: '2026-10-25',
            days: [day('2026-10-25', '+03:00', '+02:00')],
            staffMembers: [staff()],
            appointments: [
              appointment({
                id: 'first',
                start: '2026-10-25T03:30:00+03:00',
                end: '2026-10-25T04:00:00+03:00',
                customerDisplayName: 'Първо Появяване',
              }),
              appointment({
                id: 'second',
                start: '2026-10-25T03:30:00+02:00',
                end: '2026-10-25T04:00:00+02:00',
                customerDisplayName: 'Второ Появяване',
              }),
            ],
          })
    renderCalendar({ ...BASE, date: '2026-10-25' })
    expect(await screen.findByRole('link', { name: /^03:30 \(UTC\+03:00\)–/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /^03:30 \(UTC\+02:00\)–/ })).toBeInTheDocument()
  })

  it('shows a 23-hour spring day without an hour label inside the gap', async () => {
    handler = async (path) =>
      path.startsWith('/api/business/staff-members')
        ? staffCatalog
        : calendarResponse({
            from: '2026-03-29',
            to: '2026-03-29',
            days: [day('2026-03-29', '+02:00', '+03:00')],
            staffMembers: [staff()],
            appointments: [
              appointment({
                start: '2026-03-29T02:00:00+02:00',
                end: '2026-03-29T05:00:00+03:00',
                durationMinutes: 120,
              }),
            ],
          })
    renderCalendar({ ...BASE, date: '2026-03-29' })
    await screen.findByText('Мария Тестова')
    const labels = Array.from(document.querySelectorAll('.calendar-axis-label')).map(
      (label) => label.textContent,
    )
    expect(labels).toEqual(['02:00', '04:00'])
  })
})

describe('loading, failure and retry', () => {
  it('shows the loading state, then a safe network failure that can be retried', async () => {
    const gate = deferredResponse<unknown>()
    let fail = true
    handler = async (path) => {
      if (path.startsWith('/api/business/staff-members')) return staffCatalog
      await gate.promise
      if (fail) throw new TypeError('Failed to fetch secret-diagnostic')
      return calendarResponse()
    }
    renderCalendar()
    expect(screen.getByText('Зареждане на графика…')).toBeInTheDocument()
    gate.resolve(undefined)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Няма връзка със сървъра.')
    expect(alert).not.toHaveTextContent('secret-diagnostic')
    fail = false
    fireEvent.click(screen.getByRole('button', { name: 'Опитай отново' }))
    expect(await screen.findByText('Мария Тестова')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('shows a generic message for an unexpected server failure, never the backend text', async () => {
    handler = async (path) => {
      if (path.startsWith('/api/business/staff-members')) return staffCatalog
      throw new ApiError(500, 'INTERNAL_ERROR', 'SQL state 23P01 relation appointment')
    }
    renderCalendar()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Възникна неочаквана грешка.')
    expect(alert).not.toHaveTextContent(/SQL|23P01/)
  })

  it('hands an expired login to the application', async () => {
    handler = async (path) => {
      if (path.startsWith('/api/business/staff-members')) return staffCatalog
      throw new ApiError(401, 'AUTH_REQUIRED', 'Влезте отново.')
    }
    const { spies } = renderCalendar()
    await waitFor(() => expect(spies.auth).toEqual(['Влезте отново.']))
  })
})

describe('stale and superseded requests', () => {
  it('aborts the superseded request and ignores a late answer', async () => {
    const first = deferredResponse<unknown>()
    const signals: AbortSignal[] = []
    handler = async (path, signal) => {
      if (path.startsWith('/api/business/staff-members')) return staffCatalog
      if (signal) signals.push(signal)
      if (path.includes('2026-10-14')) return first.promise
      return calendarResponse({
        from: '2026-10-15',
        to: '2026-10-15',
        days: [day('2026-10-15')],
        appointments: [
          appointment({
            id: 'new',
            customerDisplayName: 'Новият Ден',
            start: '2026-10-15T10:00:00+03:00',
            end: '2026-10-15T10:45:00+03:00',
          }),
        ],
      })
    }
    renderCalendar()
    expect(await screen.findByText('Зареждане на графика…')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Следващ' }))
    expect(await screen.findByText('Новият Ден')).toBeInTheDocument()
    expect(signals[0]!.aborted).toBe(true)
    await act(async () => {
      first.resolve(
        calendarResponse({ appointments: [appointment({ id: 'old', customerDisplayName: 'Старият Ден' })] }),
      )
    })
    expect(screen.queryByText('Старият Ден')).toBeNull()
    expect(screen.getByText('Новият Ден')).toBeInTheDocument()
  })

  it('keeps useful data on screen, marked busy, while the next range is loading', async () => {
    const next = deferredResponse<unknown>()
    handler = async (path) => {
      if (path.startsWith('/api/business/staff-members')) return staffCatalog
      if (path.includes('2026-10-15')) return next.promise
      return calendarResponse()
    }
    renderCalendar()
    await screen.findByText('Мария Тестова')
    fireEvent.click(screen.getByRole('button', { name: 'Следващ' }))
    await screen.findByRole('status')
    expect(screen.getByText('Мария Тестова')).toBeInTheDocument()
    expect(document.querySelector('.calendar-results')).toHaveAttribute('aria-busy', 'true')
    await act(async () => {
      next.resolve(calendarResponse({ appointments: [], days: [day('2026-10-15')] }))
    })
    await waitFor(() => expect(screen.queryByText('Мария Тестова')).toBeNull())
  })

  it('does not refetch when an unrelated parent re-renders', async () => {
    const { rerender } = renderCalendar()
    await screen.findByText('Мария Тестова')
    const before = mockedRequest.mock.calls.length
    rerender(1)
    rerender(2)
    expect(mockedRequest.mock.calls.length).toBe(before)
  })

  it('does not refetch the calendar when only the agenda page size matters for another view', async () => {
    const { spies } = renderCalendar()
    await screen.findByText('Мария Тестова')
    const before = calendarRequests().length
    expect(spies.changes).toHaveLength(0)
    expect(before).toBe(1)
  })
})

describe('StaffMember filter', () => {
  it('lists all staff, requests only the chosen one and keeps it while moving dates', async () => {
    renderCalendar()
    await screen.findByText('Мария Тестова')
    const select = await screen.findByLabelText('Член на екипа')
    await screen.findByRole('option', { name: 'Георги Стилистов' })
    expect(within(select).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Всички членове на екипа',
      'Мила Работникова',
      'Георги Стилистов',
    ])
    fireEvent.change(select, { target: { value: STAFF_TWO } })
    await waitFor(() =>
      expect(calendarRequests().at(-1)).toBe(
        `/api/business/calendar?from=2026-10-14&to=2026-10-14&staffMemberId=${STAFF_TWO}`,
      ),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Следващ' }))
    await waitFor(() =>
      expect(calendarRequests().at(-1)).toBe(
        `/api/business/calendar?from=2026-10-15&to=2026-10-15&staffMemberId=${STAFF_TWO}`,
      ),
    )
  })

  it('clears a StaffMember that disappeared, replaces history and says so once', async () => {
    handler = async (path) => {
      if (path.startsWith('/api/business/staff-members')) return staffCatalog
      if (path.includes('staffMemberId'))
        throw new ApiError(404, 'STAFF_MEMBER_NOT_FOUND', 'Членът на екипа не е намерен.')
      return calendarResponse()
    }
    const { spies } = renderCalendar({ ...BASE, staffMemberId: STAFF_TWO })
    expect(await screen.findByRole('status')).toHaveTextContent('Избраният член на екипа вече не е наличен.')
    expect(spies.changes.at(-1)).toEqual({ state: { ...BASE, staffMemberId: null }, mode: 'replace' })
    await screen.findByText('Мария Тестова')
    expect(screen.getByLabelText('Член на екипа')).toHaveValue('')
  })

  it('offers the date as a native control and ignores an invalid value', async () => {
    renderCalendar()
    await screen.findByText('Мария Тестова')
    const input = screen.getByLabelText('Дата')
    expect(input).toHaveAttribute('type', 'date')
    expect(input).toHaveValue('2026-10-14')
    fireEvent.change(input, { target: { value: '2026-11-02' } })
    await waitFor(() =>
      expect(calendarRequests().at(-1)).toBe('/api/business/calendar?from=2026-11-02&to=2026-11-02'),
    )
  })
})

describe('agenda', () => {
  const many = Array.from({ length: 3 }, (_, index) =>
    appointment({ id: `id-${index}`, customerDisplayName: `Клиент ${index + 1}` }),
  )

  it('keeps the server order and paging, pushing explicit page changes', async () => {
    handler = async (path) =>
      path.startsWith('/api/business/staff-members')
        ? staffCatalog
        : agendaResponse(many, { total: 25 })
    const { spies } = renderCalendar({ ...BASE, view: 'agenda' })
    await screen.findByText('Клиент 1')
    expect(screen.getAllByRole('article').map((entry) => entry.querySelector('.calendar-entry-customer')?.textContent)).toEqual([
      'Клиент 1',
      'Клиент 2',
      'Клиент 3',
    ])
    expect(screen.getByText('Показани 1–10 от 25')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Следваща' }))
    expect(spies.changes.at(-1)).toEqual({ state: { ...BASE, view: 'agenda', page: 1 }, mode: undefined })
    await waitFor(() =>
      expect(calendarRequests().at(-1)).toBe(
        '/api/business/appointments?from=2026-10-01&to=2026-10-31&page=1&size=10',
      ),
    )
    fireEvent.change(screen.getByLabelText('Резултати на страница'), { target: { value: '25' } })
    await waitFor(() =>
      expect(calendarRequests().at(-1)).toBe(
        '/api/business/appointments?from=2026-10-01&to=2026-10-31&page=0&size=25',
      ),
    )
  })

  it('recovers an empty page past the data by replacing history', async () => {
    handler = async (path) =>
      path.startsWith('/api/business/staff-members')
        ? staffCatalog
        : path.includes('page=5')
          ? agendaResponse([], { page: 5, total: 12 })
          : agendaResponse(many, { page: 1, total: 12 })
    const { spies } = renderCalendar({ ...BASE, view: 'agenda', page: 5 })
    await screen.findByText('Клиент 1')
    expect(spies.changes).toEqual([{ state: { ...BASE, view: 'agenda', page: 1 }, mode: 'replace' }])
  })
})

describe('narrow screens', () => {
  it('shows the day as a chronological list per StaffMember instead of the positional grid', async () => {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: (query: string) => ({
        matches: query !== WIDE_VIEWPORT_QUERY,
        media: query,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
      }),
    })
    renderCalendar()
    await screen.findByText('Мария Тестова')
    expect(document.querySelector('.calendar-grid')).toBeNull()
    expect(document.querySelector('.calendar-staff-list')).not.toBeNull()
    expect(document.querySelector('.calendar-block')).toBeNull()
  })
})

describe('read-only and privacy', () => {
  it('offers no create, reschedule, reassign, cancel or note control', async () => {
    renderCalendar()
    await screen.findByText('Мария Тестова')
    const names = screen.getAllByRole('button').map((button) => button.textContent)
    expect(names.sort()).toEqual(['Днес', 'Предишен', 'Следващ'].sort())
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(document.body.textContent).not.toMatch(/Добави|Нов час|Отмени|Пренасрочи|Смени|Запази|Редактирай/)
  })

  it('keeps the Customer and every private value out of the URL, history state, title, storage and console', async () => {
    const log = vi.spyOn(console, 'log')
    const warn = vi.spyOn(console, 'warn')
    const error = vi.spyOn(console, 'error')
    const title = document.title
    renderCalendar()
    await screen.findByText('Мария Тестова')
    fireEvent.click(screen.getByRole('link', { name: 'Списък' }))
    await screen.findByText('Мария Тестова')
    const haystack = [
      window.location.href,
      JSON.stringify(history.state),
      document.title,
      JSON.stringify({ ...window.localStorage }),
      JSON.stringify({ ...window.sessionStorage }),
    ].join('\n')
    expect(haystack).not.toMatch(/Мария|Тестова|maria|Подстригване/)
    expect(document.title).toBe(title)
    expect(log).not.toHaveBeenCalled()
    expect(warn).not.toHaveBeenCalled()
    expect(error.mock.calls.filter((call) => !String(call[0]).includes('act('))).toEqual([])
    for (const path of calendarRequests()) expect(decodeURIComponent(path)).not.toMatch(/Мария|maria/)
  })
})

describe('accessibility', () => {
  it('exposes landmarks, named controls and keyboard-operable view links', async () => {
    renderCalendar()
    await screen.findByText('Мария Тестова')
    expect(screen.getByRole('navigation', { name: 'Изглед на графика' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Навигация по период' })).toBeInTheDocument()
    expect(screen.getByLabelText('Дата')).toBeInTheDocument()
    expect(screen.getByLabelText('Член на екипа')).toBeInTheDocument()
    const week = screen.getByRole('link', { name: 'Седмица' })
    week.focus()
    expect(week).toHaveFocus()
    expect(screen.getByRole('heading', { level: 2 })).toHaveAttribute('aria-live', 'polite')
  })

  it('wraps long Customer, Service and StaffMember names instead of overflowing', async () => {
    const long = 'Изключително-дълго-име-без-интервали-'.repeat(8)
    handler = async (path) =>
      path.startsWith('/api/business/staff-members')
        ? staffCatalog
        : agendaResponse([
            appointment({
              customerDisplayName: long,
              staffDisplayName: long,
              services: [{ name: long, durationMinutes: 30 }],
            }),
          ])
    renderCalendar({ ...BASE, view: 'agenda' })
    expect(await screen.findByText(long, { selector: '.calendar-entry-customer' })).toBeInTheDocument()
    const css = readStyles()
    expect(rule(css, '.calendar-entry')).toContain('overflow-wrap: anywhere')
    expect(rule(css, '.calendar-entry')).toContain('min-width: 0')
  })
})

import { readFileSync } from 'node:fs'
function readStyles(): string {
  return readFileSync('src/styles.css', 'utf8')
}
function rule(css: string, selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\>]/g, '\\$&')
  const match = css.match(new RegExp(`(?:^|\\n)${escaped} \\{[^}]*\\}`))
  return match?.[0] ?? ''
}
