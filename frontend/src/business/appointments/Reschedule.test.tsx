import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import {
  back,
  chooseStaff,
  chooseTime,
  confirm,
  deferred,
  heading,
  installBackend,
  next,
  renderForm,
  staffStep,
  toReview,
} from './rescheduleTestHarness'
import {
  DAY,
  FakeRescheduleBackend,
  STAFF_ONE,
  STAFF_TWO,
  appointmentDetail,
  optionsResponse,
} from './rescheduleTestFixtures'
import type { RescheduleAvailability } from './rescheduleApi'
import { slot } from './testFixtures'

// Only the network helper is mocked: the real feature API modules build and send every request.
vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
let backend: FakeRescheduleBackend

beforeEach(() => {
  backend = installBackend(mockedRequest)
  history.replaceState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

const radios = () => screen.getAllByRole('radio') as HTMLInputElement[]

describe('the initial data', () => {
  it('reads exactly the Appointment and its reschedule options and shows only server facts, read-only', async () => {
    renderForm()
    await staffStep()

    expect(backend.calls.map((call) => `${call.method} ${call.path}`)).toEqual([
      'GET /api/business/appointments/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      'GET /api/business/appointments/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb/reschedule-options',
    ])
    const context = screen.getByRole('region', { name: 'Текущ час' })
    expect(context).toHaveTextContent('Мария Тестова')
    // The stored lines, ordered by position, with the exact stored total.
    const services = within(context).getAllByRole('listitem').map((item) => item.textContent)
    expect(services[0]).toContain('Подстригване')
    expect(services[1]).toContain('Боядисване')
    expect(context).toHaveTextContent('Общо: 80.50 € — 1 ч 15 мин')
    expect(context).toHaveTextContent('Мила Работникова')
    expect(context).toHaveTextContent('10 ноември 2026')
    expect(context).toHaveTextContent('Потвърден')
    // The immutability rule lives in the documentation, not as persistent instructional copy in the form.
    expect(document.body.textContent).not.toMatch(/не могат да се променят|създайте нов/)
    // Nothing in the context can be edited, the version and the technical zone never appear.
    expect(within(context).queryAllByRole('textbox')).toHaveLength(0)
    expect(within(context).queryAllByRole('button')).toHaveLength(0)
    expect(document.body.textContent).not.toMatch(/Europe\/Sofia|версия|version/i)
    expect(document.body.textContent).not.toContain('SYS-2026-0001')
  })

  it('shows a lost Appointment as one safe, non-retryable message and offers the way out', async () => {
    backend.detail = () => {
      throw backend.problem(404, 'APPOINTMENT_NOT_FOUND')
    }
    const { onLeaveToOrigin } = renderForm()

    expect(await screen.findByRole('alert')).toHaveTextContent('Часът не е намерен.')
    expect(screen.queryByRole('button', { name: 'Опитай отново' })).toBeNull()
    expect(document.body.textContent).not.toContain('backend text')
    fireEvent.click(screen.getByRole('button', { name: 'Към графика' }))
    expect(onLeaveToOrigin).toHaveBeenCalledTimes(1)
  })

  it('a failed read of the options is a retryable failure, distinct from an empty replacement list', async () => {
    let fail = true
    backend.options = () => {
      if (fail) throw backend.problem(500, 'INTERNAL_ERROR')
      return optionsResponse({ staffMembers: [] })
    }
    renderForm()

    expect(await screen.findByRole('alert')).toHaveTextContent('Възможностите за промяна не могат да бъдат заредени.')
    expect(screen.queryByText(/Няма друг член на екипа/)).toBeNull()
    fail = false
    fireEvent.click(screen.getByRole('button', { name: 'Опитай отново' }))
    expect(await screen.findByText(/Няма друг член на екипа, който извършва всички услуги/)).toBeInTheDocument()
  })

  it('an Appointment that is cancelled or has started has no mutation path and reads no options', async () => {
    backend.detail = () => appointmentDetail({ status: 'CANCELLED' })
    renderForm()

    expect(await screen.findByRole('alert')).toHaveTextContent('Часът не може да бъде променен, защото е отменен или вече е започнал.')
    expect(screen.queryByRole('button', { name: 'Напред' })).toBeNull()
    expect(screen.queryByRole('radio')).toBeNull()
    expect(backend.count('/reschedule-options')).toBe(0)
    cleanup()

    backend = installBackend(mockedRequest)
    backend.detail = () => appointmentDetail({ start: '2026-10-02T10:30:00+03:00', end: '2026-10-02T11:45:00+03:00' })
    renderForm()
    expect(await screen.findByRole('alert')).toHaveTextContent('отменен или вече е започнал')
    expect(backend.count('/reschedule-options')).toBe(0)
  })

  it.each([
    ['DRAFT', 'Бизнесът не е активен. Часовете могат да се променят само в активен бизнес.'],
    ['SUSPENDED', 'Спрян бизнес може само да преглежда данните си.'],
  ] as const)('a %s Business is read-only: no options are read and nothing can be sent', async (status, text) => {
    backend.detail = () => appointmentDetail({ businessStatus: status })
    renderForm()

    expect(await screen.findByRole('alert')).toHaveTextContent(text)
    expect(screen.queryByRole('button', { name: 'Напред' })).toBeNull()
    expect(backend.count('/reschedule-options')).toBe(0)
    expect(backend.posts()).toHaveLength(0)
  })

  it('requires the shared authentication recovery for an expired session', async () => {
    backend.detail = () => {
      throw backend.problem(401, 'AUTH_REQUIRED', 'Нужен е вход.')
    }
    const { onAuthenticationRequired } = renderForm()

    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledWith('Нужен е вход.'))
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('the StaffMember choice', () => {
  it('presents the current StaffMember apart from the replacements and never lists it twice', async () => {
    backend.options = () =>
      optionsResponse({
        // A (misbehaving) list that repeats the current StaffMember must not duplicate it.
        staffMembers: [
          { id: STAFF_ONE, displayName: 'Мила Работникова' },
          { id: STAFF_TWO, displayName: 'Георги Стилистов' },
        ],
      })
    renderForm()
    await staffStep()

    const names = radios().map((radio) => radio.closest('label')?.textContent)
    expect(names).toHaveLength(2)
    expect(names[0]).toContain('Мила Работникова')
    expect(names[0]).toContain('Текущ член на екипа')
    expect(names[1]).toContain('Георги Стилистов')
    expect(screen.getByText('Друг член на екипа')).toBeInTheDocument()
    // Keeping the current StaffMember is the unchanged state; nothing else is chosen for the owner.
    expect(radios().map((radio) => radio.checked)).toEqual([true, false])
    expect(screen.getAllByRole('group', { name: 'Избор на служител' })).toHaveLength(1)
  })

  it('wraps a very long name without a horizontal overflow rule being needed', async () => {
    const long = 'Александра-Константина Петкова-Георгиева-Йорданова-Стефанова-Ненчева'
    backend.options = () => optionsResponse({ staffMembers: [{ id: STAFF_TWO, displayName: long }] })
    renderForm()
    await staffStep()

    const title = screen.getByText(long)
    expect(title).toHaveClass('manual-choice-title')
  })

  it('an inactive current StaffMember cannot be kept: an explanation, no keep option, nothing preselected', async () => {
    backend.options = () =>
      optionsResponse({ currentStaffMember: { id: STAFF_ONE, displayName: 'Мила Работникова', available: false } })
    renderForm()
    await screen.findByRole('radio', { name: /Георги/ })

    expect(screen.getByRole('status', { name: '' })).toBeDefined()
    expect(screen.getByText(/Текущият член на екипа \(Мила Работникова\) вече не е наличен за този час/)).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: /Текущ член на екипа/ })).toBeNull()
    expect(radios().every((radio) => !radio.checked)).toBe(true)
    next()
    expect(screen.getByRole('alert')).toHaveTextContent('Изберете член на екипа.')
    expect(heading()).toHaveTextContent('Избор на служител')
    fireEvent.click(radios()[0]!)
    next()
    expect(heading()).toHaveTextContent('Дата и час')
  })

  it('an empty replacement list leaves the current StaffMember, with a statement and no error', async () => {
    backend.options = () => optionsResponse({ staffMembers: [] })
    renderForm()
    await staffStep()

    expect(radios()).toHaveLength(1)
    expect(screen.getByText('Няма друг член на екипа, който извършва всички услуги на този час.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('nobody available at all is a statement, not a read failure, and cannot proceed', async () => {
    backend.options = () =>
      optionsResponse({
        currentStaffMember: { id: STAFF_ONE, displayName: 'Мила Работникова', available: false },
        staffMembers: [],
      })
    renderForm()

    expect(await screen.findByText(/Няма наличен член на екипа за този час\. Текущият член на екипа \(Мила Работникова\) вече не е наличен/)).toBeInTheDocument()
    expect(screen.queryByText(/Изберете друг член на екипа/)).toBeNull()
    expect(screen.queryByRole('radio')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Опитай отново' })).toBeNull()
    next()
    expect(screen.getByRole('alert')).toHaveTextContent('Изберете член на екипа.')
    expect(heading()).toHaveTextContent('Избор на служител')
  })
})

describe('date and time', () => {
  it('starts on the original Business-local date and sends only the StaffMember and the date', async () => {
    renderForm()
    await staffStep()
    next()
    const date = await screen.findByLabelText('Дата')

    expect(date).toHaveValue(DAY)
    await screen.findByRole('group', { name: 'Свободни часове' })
    const call = backend.calls.find((entry) => entry.path.includes('availability'))!
    expect(call.method).toBe('GET')
    expect(call.path).toBe(
      `/api/business/appointments/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb/reschedule-options/availability?staffMemberId=${STAFF_ONE}&date=${DAY}`,
    )
    // No duration, Service, price, or ignored Appointment is ever part of a request.
    expect(call.path).not.toMatch(/duration|service|price|ignored/i)
  })

  it('does not offer the current unchanged start to the current StaffMember, but offers it to a replacement', async () => {
    renderForm()
    await staffStep()
    next()
    let group = await screen.findByRole('group', { name: 'Свободни часове' })
    expect(within(group).getAllByRole('radio').map((radio) => radio.closest('label')?.textContent)).toEqual([
      '09:15',
      '10:00',
    ])

    back()
    await chooseStaff('Георги')
    next()
    group = await screen.findByRole('group', { name: 'Свободни часове' })
    expect(within(group).getAllByRole('radio').map((radio) => radio.closest('label')?.textContent)).toEqual([
      '09:00',
      '09:15',
      '10:00',
    ])
  })

  it('shows both occurrences of a repeated local hour with their offsets and offers nothing in a spring gap', async () => {
    backend.detail = () =>
      appointmentDetail({ start: '2026-10-26T09:00:00+02:00', end: '2026-10-26T10:15:00+02:00' })
    backend.availability = (_staff, date): RescheduleAvailability => ({
      timezone: 'Europe/Sofia',
      date,
      durationMinutes: 75,
      slots: [
        slot('2026-10-25T03:30:00+03:00', 75),
        slot('2026-10-25T03:30:00+02:00', 75),
        slot('2026-10-25T04:00:00+02:00', 75),
      ],
    })
    renderForm()
    await staffStep()
    next()
    fireEvent.change(await screen.findByLabelText('Дата'), { target: { value: '2026-10-25' } })
    const group = await screen.findByRole('group', { name: 'Свободни часове' })

    const labels = within(group).getAllByRole('radio').map((radio) => radio.closest('label')?.textContent ?? '')
    expect(labels).toHaveLength(3)
    expect(labels[0]).toBe('03:30 — първи път')
    expect(labels[1]).toBe('03:30 — втори път')
    expect(labels[2]).toBe('04:00')
    expect(new Set(labels).size).toBe(3)
    expect(document.body.textContent).not.toMatch(/Europe\/Sofia|часова зона/i)

    // The spring gap is never synthesized: the screen lists exactly what the server offered.
    backend.availability = (_staff, date) => ({
      timezone: 'Europe/Sofia',
      date,
      durationMinutes: 75,
      slots: [slot('2027-03-28T02:30:00+02:00', 75), slot('2027-03-28T04:00:00+03:00', 75)],
    })
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2027-03-28' } })
    await waitFor(() => {
      const gap = within(screen.getByRole('group', { name: 'Свободни часове' })).getAllByRole('radio')
      expect(gap.map((radio) => radio.closest('label')?.textContent)).toEqual(['02:30', '04:00'])
    })
  })

  it('does not depend on the browser timezone for the date, the earliest date, or the displayed times', async () => {
    const previous = process.env['TZ']
    try {
      for (const zone of ['Pacific/Kiritimati', 'America/Los_Angeles', 'UTC']) {
        process.env['TZ'] = zone
        backend = installBackend(mockedRequest)
        // 00:30 in Sofia is the previous calendar day in UTC and in Los Angeles.
        backend.detail = () =>
          appointmentDetail({ start: '2026-11-10T00:30:00+02:00', end: '2026-11-10T01:45:00+02:00' })
        renderForm()
        await staffStep()
        expect(screen.getByRole('region', { name: 'Текущ час' })).toHaveTextContent('10 ноември 2026')
        expect(screen.getByRole('region', { name: 'Текущ час' })).toHaveTextContent('00:30–01:45')
        next()
        const date = await screen.findByLabelText('Дата')
        expect(date).toHaveValue('2026-11-10')
        // Today in the Business timezone at the injected instant (11:00 on 2 October in Sofia).
        expect(date).toHaveAttribute('min', '2026-10-02')
        cleanup()
      }
    } finally {
      if (previous === undefined) delete process.env['TZ']
      else process.env['TZ'] = previous
    }
  })

  it('a changed StaffMember or date clears the chosen start, and a start is required again', async () => {
    renderForm()
    await staffStep()
    next()
    await chooseTime(0)
    next()
    await screen.findByRole('heading', { level: 2, name: 'Преглед и потвърждение' })

    fireEvent.click(screen.getByRole('button', { name: 'Промени члена на екипа' }))
    await chooseStaff('Георги')
    next()
    // The date is kept, the start is not.
    expect(heading()).toHaveTextContent('Дата и час')
    expect(screen.getByLabelText('Дата')).toHaveValue(DAY)
    let group = await screen.findByRole('group', { name: 'Свободни часове' })
    expect(within(group).getAllByRole('radio').every((radio) => !(radio as HTMLInputElement).checked)).toBe(true)
    fireEvent.click(within(group).getAllByRole('radio')[1]!)
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-11' } })
    group = await screen.findByRole('group', { name: 'Свободни часове' })
    expect(within(group).getAllByRole('radio').every((radio) => !(radio as HTMLInputElement).checked)).toBe(true)
    next()
    expect(screen.getByRole('alert')).toHaveTextContent('Изберете час.')
  })

  it('returns straight to the review after an edit whose remaining choices stay valid', async () => {
    await toReview()

    fireEvent.click(screen.getByRole('button', { name: 'Промени датата и часа' }))
    await chooseTime(1)
    next()
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    // The StaffMember was not edited: no options were read again.
    expect(backend.count('/reschedule-options')).toBe(1)
  })

  it('shows an empty day, a failed read, and no eligible StaffMember as three different things', async () => {
    renderForm()
    await staffStep()
    next()
    await screen.findByRole('group', { name: 'Свободни часове' })

    backend.availability = (_staff, date) => ({ timezone: 'Europe/Sofia', date, durationMinutes: 75, slots: [] })
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-12' } })
    expect(await screen.findByText('Няма свободни часове за тази дата. Изберете друга дата.')).toBeInTheDocument()

    backend.availability = () => {
      throw backend.problem(500, 'INTERNAL_ERROR')
    }
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-13' } })
    expect(await screen.findByText('Свободните часове не могат да бъдат заредени.')).toBeInTheDocument()
    expect(screen.queryByText(/Няма свободни часове/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Опитай отново' })).toBeInTheDocument()
  })

  it('aborts an obsolete availability read and ignores a late response', async () => {
    renderForm()
    await staffStep()
    next()
    await screen.findByRole('group', { name: 'Свободни часове' })

    const slow = deferred<RescheduleAvailability>()
    backend.availability = (_staff, date) =>
      date === '2026-11-11'
        ? slow.promise
        : { timezone: 'Europe/Sofia', date, durationMinutes: 75, slots: [slot(`${date}T11:00:00+02:00`, 75)] }
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-11' } })
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-12' } })
    const group = await screen.findByRole('group', { name: 'Свободни часове' })
    expect(within(group).getAllByRole('radio').map((radio) => radio.closest('label')?.textContent)).toEqual(['11:00'])

    const stale = backend.calls.filter((call) => call.path.includes('date=2026-11-11'))
    expect(stale).toHaveLength(1)
    expect(stale[0]!.signal?.aborted).toBe(true)
    await act(async () => {
      slow.resolve({ timezone: 'Europe/Sofia', date: '2026-11-11', durationMinutes: 75, slots: [slot('2026-11-11T08:00:00+02:00', 75)] })
    })
    expect(within(screen.getByRole('group', { name: 'Свободни часове' })).getAllByRole('radio')).toHaveLength(1)
    expect(screen.queryByText('08:00')).toBeNull()
  })
})

describe('the review and the mutation', () => {
  it('shows the unchanged Customer and Services, the original beside the proposal, and the stored total', async () => {
    await toReview({ replacement: 'Георги', slot: 0 })

    const review = screen.getByRole('region', { name: 'Преглед и потвърждение' })
    expect(within(review).getByText('Клиент (без промяна)')).toBeInTheDocument()
    expect(review).toHaveTextContent('Мария Тестова')
    expect(review).toHaveTextContent('Услуги (без промяна)')
    expect(review).toHaveTextContent('Подстригване')
    expect(review).toHaveTextContent('Боядисване')
    expect(review).toHaveTextContent('Общо: 80.50 € — 1 ч 15 мин')
    expect(review).toHaveTextContent('Сега: Мила Работникова')
    expect(review).toHaveTextContent('След промяната: Георги Стилистов')
    expect(review).toHaveTextContent(/Сега: .*10 ноември 2026 г\., 09:00–10:15/)
    expect(review).toHaveTextContent(/След промяната: .*10 ноември 2026 г\., 09:00–10:15/)
    expect(review).not.toHaveTextContent('Европа')
  })

  it('sends one exact time-only body: expectedVersion and start, nothing else, and then completes', async () => {
    const { onRescheduled } = await toReview({ slot: 0 })
    confirm()

    await waitFor(() => expect(onRescheduled).toHaveBeenCalledTimes(1))
    expect(backend.posts()).toHaveLength(1)
    expect(backend.posts()[0]!.path).toBe('/api/business/appointments/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb/reschedule')
    expect(backend.posts()[0]!.body).toEqual({ expectedVersion: 7, start: `${DAY}T09:15:00+02:00` })
  })

  it('sends a reassignment to the same start without a start, and a combined change with both', async () => {
    const first = await toReview({ replacement: 'Георги', slot: 0 })
    confirm()
    await waitFor(() => expect(first.onRescheduled).toHaveBeenCalledTimes(1))
    expect(backend.posts()[0]!.body).toEqual({ expectedVersion: 7, staffMemberId: STAFF_TWO })
    cleanup()

    backend = installBackend(mockedRequest)
    const second = await toReview({ replacement: 'Георги', slot: 2 })
    confirm()
    await waitFor(() => expect(second.onRescheduled).toHaveBeenCalledTimes(1))
    expect(backend.posts()[0]!.body).toEqual({
      expectedVersion: 7,
      staffMemberId: STAFF_TWO,
      start: `${DAY}T10:00:00+02:00`,
    })
  })

  it('never sends a duration, a Service claim, a price, a Customer, or an ignored Appointment', async () => {
    await toReview({ replacement: 'Георги', slot: 2 })
    confirm()
    await waitFor(() => expect(backend.posts()).toHaveLength(1))

    expect(Object.keys(backend.posts()[0]!.body as object).sort()).toEqual(['expectedVersion', 'staffMemberId', 'start'])
  })

  it('a genuine no-op is never submitted: the confirmation stays disabled and a click sends nothing', async () => {
    // A (misbehaving) server that offers the unchanged start to the current StaffMember.
    backend.availability = (_staff, date) => ({
      timezone: 'Europe/Sofia',
      date,
      durationMinutes: 75,
      slots: [slot(`${date}T09:00:00+02:00`, 75)],
    })
    await toReview({ slot: 0 })

    expect(screen.getByRole('status')).toHaveTextContent('Няма промяна спрямо сегашния час.')
    const button = screen.getByRole('button', { name: 'Промени часа' })
    expect(button).toBeDisabled()
    fireEvent.click(button)
    expect(backend.posts()).toHaveLength(0)
  })

  it('treats another instant spelling of the original start as the same start', async () => {
    backend.availability = (_staff, date) => ({
      timezone: 'Europe/Sofia',
      date,
      durationMinutes: 75,
      slots: [{ start: `${date}T07:00:00Z`, end: `${date}T08:15:00Z` }],
    })
    await toReview({ slot: 0 })

    expect(screen.getByRole('button', { name: 'Промени часа' })).toBeDisabled()
  })

  it('blocks a second click while the request is pending and ignores stale callbacks', async () => {
    const pending = deferred<{ id: string; status: string; start: string; end: string; staffMemberId: string; version: number }>()
    backend.reschedule = () => pending.promise
    const { onRescheduled } = await toReview({ slot: 0 })

    const button = screen.getByRole('button', { name: 'Промени часа' })
    fireEvent.click(button)
    fireEvent.click(button)
    fireEvent.doubleClick(button)
    expect(backend.posts()).toHaveLength(1)
    expect(button).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Назад' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Обратно към часа' })).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('Промяна на часа…')
    await act(async () => {
      pending.resolve({
        id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        status: 'CONFIRMED',
        start: `${DAY}T09:15:00+02:00`,
        end: `${DAY}T10:30:00+02:00`,
        staffMemberId: STAFF_ONE,
        version: 8,
      })
    })
    expect(onRescheduled).toHaveBeenCalledTimes(1)
    expect(backend.posts()).toHaveLength(1)
  })

  it('a response that arrives after the form was left does not navigate', async () => {
    const pending = deferred<{ id: string; status: string; start: string; end: string; staffMemberId: string; version: number }>()
    backend.reschedule = () => pending.promise
    const { onRescheduled, view } = await toReview({ slot: 0 })

    confirm()
    view.unmount()
    await act(async () => {
      pending.resolve({
        id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        status: 'CONFIRMED',
        start: `${DAY}T09:15:00+02:00`,
        end: `${DAY}T10:30:00+02:00`,
        staffMemberId: STAFF_ONE,
        version: 8,
      })
    })
    expect(onRescheduled).not.toHaveBeenCalled()
  })

  it('the action order is Back before the forward action; on the first step Next comes first and «Отказ» is rightmost and red', async () => {
    renderForm()
    await staffStep()
    let labels = within(document.querySelector('.manual-actions') as HTMLElement).getAllByRole('button').map((b) => b.textContent)
    expect(labels).toEqual(['Напред', 'Отказ'])
    expect(screen.getByRole('button', { name: 'Отказ' })).toHaveClass('button--destructive')
    next()
    await screen.findByLabelText('Дата')
    labels = within(document.querySelector('.manual-actions') as HTMLElement).getAllByRole('button').map((b) => b.textContent)
    expect(labels).toEqual(['Назад', 'Напред'])
    await chooseTime(0)
    next()
    await screen.findByRole('button', { name: 'Промени часа' })
    labels = within(document.querySelector('.manual-actions') as HTMLElement).getAllByRole('button').map((b) => b.textContent)
    expect(labels).toEqual(['Назад', 'Промени часа'])
  })

  it('keeps the step heading focused after a step change and an error summary focused after a failed Next', async () => {
    renderForm()
    await staffStep()
    next()
    expect(heading()).toHaveFocus()
    const group = await screen.findByRole('group', { name: 'Свободни часове' })
    next()
    // No start chosen: the first offered start receives focus and the error is announced.
    expect(within(group).getAllByRole('radio')[0]).toHaveFocus()
    expect(screen.getByRole('alert')).toHaveTextContent('Изберете час.')
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '' } })
    next()
    expect(screen.getByLabelText('Дата')).toHaveFocus()
    expect(screen.getByText('Изберете дата.')).toBeInTheDocument()
    expect(screen.getByLabelText('Дата')).toHaveAttribute('aria-invalid', 'true')
  })
})

describe('privacy', () => {
  it('leaves nothing private in the URL, storage, history state, the title, cookies, or the console', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) => vi.spyOn(console, method))
    const title = document.title
    const { onRescheduled } = await toReview({ replacement: 'Георги', slot: 1 })
    confirm()
    await waitFor(() => expect(onRescheduled).toHaveBeenCalled())

    expect(window.location.href).toBe('http://localhost:3000/')
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
    expect(history.state).toEqual({})
    expect(document.title).toBe(title)
    expect(document.cookie).toBe('')
    for (const spy of spies) expect(spy).not.toHaveBeenCalled()
  })
})
