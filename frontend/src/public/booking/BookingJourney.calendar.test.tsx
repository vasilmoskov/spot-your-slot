import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AVAILABLE_DATES,
  SLOTS_BY_DATE,
  availabilityBody,
  click,
  flush,
  heading,
  installServer,
  json,
  next,
  openPage,
  pickDate,
  pickSlot,
  startFromService,
  stubAttemptIds,
  ATTEMPT_ONE,
  type FakeServer,
} from './testSupport'

let server: FakeServer

beforeEach(() => {
  window.history.replaceState(null, '', '/example-studio')
  server = installServer()
  stubAttemptIds(ATTEMPT_ONE)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function openDateStep() {
  await openPage()
  await startFromService('Подстригване')
  await next()
}

const cell = (name: RegExp | string) => screen.getByRole('gridcell', { name })
const title = () => document.querySelector('.booking-calendar-title') as HTMLElement
const nav = (name: 'Предишен месец' | 'Следващ месец') => screen.getByRole('button', { name })

async function key(element: HTMLElement, keyName: string) {
  fireEvent.keyDown(element, { key: keyName })
  await flush()
}

describe('the calendar composition', () => {
  it('shows one shared section with a monthly Bulgarian calendar, Monday first, and the slots of the selected date', async () => {
    await openDateStep()
    expect(heading()).toHaveTextContent('Дата и час')
    const picker = document.querySelector('.booking-picker') as HTMLElement
    expect(picker).not.toBeNull()
    expect(title()).toHaveTextContent('Октомври 2026')
    const headers = within(screen.getByRole('grid')).getAllByRole('columnheader')
    expect(headers.map((header) => header.textContent)).toEqual(['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Нд'])
    // The slots share the section with the calendar and belong to the selected date.
    const slots = within(picker).getByRole('group', { name: 'Свободни часове' })
    expect(slots).toHaveTextContent('сряда, 7 октомври 2026 г.')
    expect(within(slots).getAllByRole('radio').map((radio) => radio.getAttribute('value'))).toEqual(
      SLOTS_BY_DATE['2026-10-07']!.map((slot) => slot.start),
    )
    // No explanation of the timezone is shown; the times are the Business's.
    expect(document.body.textContent).not.toContain('Часовете са по')
  })

  it('never shows a technical timezone identifier', async () => {
    await openDateStep()
    await pickSlot('10:00')
    expect(document.body.textContent).not.toMatch(/Europe\/|Sofia|UTC\+0[0-9]:00 /)
    expect(document.body.textContent).not.toContain('Europe/Sofia')
  })

  it('marks available dates as choices and every other date in the horizon as unavailable', async () => {
    await openDateStep()
    const available = cell(/^сряда, 7 октомври 2026 г\./)
    expect(available).toHaveAttribute('aria-selected', 'true')
    expect(cell(/^четвъртък, 8 октомври 2026 г\./)).toHaveAttribute('aria-selected', 'false')
    const unavailable = cell(/^петък, 9 октомври 2026 г\., няма свободни часове$/)
    expect(unavailable).toHaveAttribute('aria-disabled', 'true')
    expect(unavailable).not.toHaveAttribute('aria-selected')
    // Dates before the first bookable date are outside the booking period.
    expect(cell(/^вторник, 6 октомври 2026 г\., извън периода за записване$/)).toHaveAttribute(
      'aria-disabled',
      'true',
    )
  })

  it('selects an available date and ignores an unavailable one, without any request for it', async () => {
    await openDateStep()
    const reads = server.of('GET', '/availability').length
    await click(cell(/^петък, 9 октомври 2026 г\., няма свободни часове$/))
    expect(cell(/^сряда, 7 октомври 2026 г\./)).toHaveAttribute('aria-selected', 'true')
    expect(server.of('GET', '/availability')).toHaveLength(reads)

    await pickDate('четвъртък, 8 октомври 2026 г.')
    expect(cell(/^четвъртък, 8 октомври 2026 г\./)).toHaveAttribute('aria-selected', 'true')
    expect(server.of('GET', '/availability').at(-1)!.url.searchParams.get('date')).toBe('2026-10-08')
    expect(screen.getByRole('group', { name: 'Свободни часове' })).toHaveTextContent('четвъртък, 8 октомври 2026 г.')
  })

  it('invents no slot: every offered time and every available date comes from the answer', async () => {
    server.on((request) =>
      request.path.endsWith('/availability')
        ? json(200, {
            date: request.url.searchParams.get('date'),
            timezone: 'Europe/Sofia',
            availableDates: ['2026-10-12'],
            slots: request.url.searchParams.get('date') === '2026-10-12'
              ? [{ start: '2026-10-12T14:20:00+03:00', end: '2026-10-12T15:05:00+03:00' }]
              : [],
          })
        : undefined,
    )
    await openDateStep()
    expect(screen.getAllByRole('gridcell', { selected: true })).toHaveLength(1)
    expect(cell(/^понеделник, 12 октомври 2026 г\./)).toHaveAttribute('aria-selected', 'true')
    expect(within(screen.getByRole('group', { name: 'Свободни часове' })).getAllByRole('radio')).toHaveLength(1)
    expect(screen.getByRole('radio', { name: '14:20' })).toBeInTheDocument()
    // Every other date, including the two that used to be offered, is unavailable.
    for (const unavailable of AVAILABLE_DATES) {
      expect(document.querySelector(`[aria-label^="${unavailable}"]`)).toBeNull()
    }
    expect(screen.getAllByRole('gridcell').filter((c) => c.getAttribute('aria-selected') === 'false')).toHaveLength(0)
  })

  it('summarizes the selected date and time, with offsets only for the repeated hour', async () => {
    await openDateStep()
    expect(document.querySelector('.booking-picker-summary')).toBeEmptyDOMElement()
    await pickSlot('10:00')
    expect(document.querySelector('.booking-picker-summary')).toHaveTextContent(
      'Избрано: сряда, 7 октомври 2026 г., 10:00 – 10:45',
    )
    expect(document.querySelector('.booking-picker-summary')).toHaveAttribute('role', 'status')

    await pickDate('неделя, 25 октомври 2026 г.')
    await pickSlot(/03:30 \(UTC\+03:00\)/)
    expect(document.querySelector('.booking-picker-summary')).toHaveTextContent(
      'Избрано: неделя, 25 октомври 2026 г., 03:30 (UTC+03:00) – 03:15 (UTC+02:00)',
    )
  })

  it('shows the empty state of a date that has no times', async () => {
    server.on((request) =>
      request.path.endsWith('/availability')
        ? json(200, availabilityBody(request.url.searchParams.get('date') === '2026-10-25' ? '2026-10-25' : '2026-10-07', AVAILABLE_DATES))
        : undefined,
    )
    server.on((request) =>
      request.path.endsWith('/availability') && request.url.searchParams.get('date') === '2026-10-08'
        ? json(200, { date: '2026-10-08', timezone: 'Europe/Sofia', availableDates: AVAILABLE_DATES, slots: [] })
        : undefined,
    )
    await openDateStep()
    await pickDate('четвъртък, 8 октомври 2026 г.')
    expect(screen.getByText('Няма свободни часове за тази дата.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Напред' })).toBeDisabled()
  })
})

describe('month navigation inside the booking horizon', () => {
  it('stays within the first and last month of the horizon and disables the ends', async () => {
    await openDateStep()
    // The horizon is 7 October to 5 November 2026.
    expect(nav('Предишен месец')).toHaveAttribute('aria-disabled', 'true')
    expect(nav('Следващ месец')).not.toHaveAttribute('aria-disabled', 'true')

    await click(nav('Предишен месец'))
    expect(title()).toHaveTextContent('Октомври 2026')

    await click(nav('Следващ месец'))
    expect(title()).toHaveTextContent('Ноември 2026')
    expect(nav('Следващ месец')).toHaveAttribute('aria-disabled', 'true')
    expect(nav('Предишен месец')).not.toHaveAttribute('aria-disabled', 'true')
    // Days after the last bookable date are outside the period.
    expect(cell(/^четвъртък, 5 ноември 2026 г\./)).toBeInTheDocument()
    expect(cell(/^петък, 6 ноември 2026 г\., извън периода за записване$/)).toHaveAttribute('aria-disabled', 'true')

    await click(nav('Следващ месец'))
    expect(title()).toHaveTextContent('Ноември 2026')
    await click(nav('Предишен месец'))
    expect(title()).toHaveTextContent('Октомври 2026')
  })

  it('announces the month politely and keeps the selection while another month is shown', async () => {
    await openDateStep()
    expect(title()).toHaveAttribute('aria-live', 'polite')
    await click(nav('Следващ месец'))
    expect(screen.getByRole('grid')).toHaveAccessibleName('Ноември 2026')
    await click(nav('Предишен месец'))
    expect(cell(/^сряда, 7 октомври 2026 г\./)).toHaveAttribute('aria-selected', 'true')
  })

  it('offers a date of the last month when the answer lists one', async () => {
    server.on((request) =>
      request.path.endsWith('/availability')
        ? json(200, availabilityBody(request.url.searchParams.get('date') ?? '', [...AVAILABLE_DATES, '2026-11-03']))
        : undefined,
    )
    await openDateStep()
    await click(nav('Следващ месец'))
    await pickDate('вторник, 3 ноември 2026 г.')
    expect(cell(/^вторник, 3 ноември 2026 г\./)).toHaveAttribute('aria-selected', 'true')
    expect(server.of('GET', '/availability').at(-1)!.url.searchParams.get('date')).toBe('2026-11-03')
  })
})

describe('keyboard operation of the calendar', () => {
  it('has one tab stop on the selected date and moves it with the arrow keys', async () => {
    await openDateStep()
    const tabbable = () =>
      screen.getAllByRole('gridcell').filter((candidate) => candidate.getAttribute('tabindex') === '0')
    expect(tabbable()).toHaveLength(1)
    expect(tabbable()[0]).toBe(cell(/^сряда, 7 октомври 2026 г\./))

    cell(/^сряда, 7 октомври 2026 г\./).focus()
    await key(cell(/^сряда, 7 октомври 2026 г\./), 'ArrowRight')
    expect(cell(/^четвъртък, 8 октомври 2026 г\./)).toHaveFocus()
    expect(tabbable()).toHaveLength(1)

    await key(cell(/^четвъртък, 8 октомври 2026 г\./), 'ArrowDown')
    expect(cell(/^четвъртък, 15 октомври 2026 г\./)).toHaveFocus()
    await key(cell(/^четвъртък, 15 октомври 2026 г\./), 'ArrowLeft')
    expect(cell(/^сряда, 14 октомври 2026 г\./)).toHaveFocus()
    await key(cell(/^сряда, 14 октомври 2026 г\./), 'ArrowUp')
    expect(cell(/^сряда, 7 октомври 2026 г\./)).toHaveFocus()
    // Moving the focus does not select anything.
    expect(cell(/^сряда, 7 октомври 2026 г\./)).toHaveAttribute('aria-selected', 'true')
    expect(cell(/^четвъртък, 8 октомври 2026 г\./)).toHaveAttribute('aria-selected', 'false')
  })

  it('uses Home and End for the week and stops at the edges of the horizon', async () => {
    await openDateStep()
    cell(/^сряда, 7 октомври 2026 г\./).focus()
    await key(cell(/^сряда, 7 октомври 2026 г\./), 'End')
    expect(cell(/^неделя, 11 октомври 2026 г\./)).toHaveFocus()
    await key(cell(/^неделя, 11 октомври 2026 г\./), 'Home')
    // The Monday of that week is before the first bookable date, so the focus stops at the first date.
    expect(cell(/^сряда, 7 октомври 2026 г\./)).toHaveFocus()
    await key(cell(/^сряда, 7 октомври 2026 г\./), 'ArrowLeft')
    expect(cell(/^сряда, 7 октомври 2026 г\./)).toHaveFocus()
  })

  it('changes the month with Page Up and Page Down within the horizon', async () => {
    await openDateStep()
    cell(/^сряда, 7 октомври 2026 г\./).focus()
    await key(cell(/^сряда, 7 октомври 2026 г\./), 'PageDown')
    expect(title()).toHaveTextContent('Ноември 2026')
    // The same day of the next month is past the last date (5 November), so it is clamped.
    expect(cell(/^четвъртък, 5 ноември 2026 г\./)).toHaveFocus()
    await key(cell(/^четвъртък, 5 ноември 2026 г\./), 'PageUp')
    expect(title()).toHaveTextContent('Октомври 2026')
    // 5 October is before the first bookable date, so the focus stops at 7 October.
    expect(cell(/^сряда, 7 октомври 2026 г\./)).toHaveFocus()
  })

  it('selects with Enter and Space, but never an unavailable date', async () => {
    await openDateStep()
    cell(/^сряда, 7 октомври 2026 г\./).focus()
    await key(cell(/^сряда, 7 октомври 2026 г\./), 'ArrowRight')
    await key(cell(/^четвъртък, 8 октомври 2026 г\./), 'Enter')
    expect(cell(/^четвъртък, 8 октомври 2026 г\./)).toHaveAttribute('aria-selected', 'true')

    await key(cell(/^четвъртък, 8 октомври 2026 г\./), 'ArrowRight')
    await key(cell(/^петък, 9 октомври 2026 г\./), ' ')
    expect(cell(/^четвъртък, 8 октомври 2026 г\./)).toHaveAttribute('aria-selected', 'true')

    await key(cell(/^петък, 9 октомври 2026 г\./), 'a')
    expect(cell(/^петък, 9 октомври 2026 г\./)).toHaveFocus()
  })

  it('keeps the focused date in the tab order after the month changes with the buttons', async () => {
    await openDateStep()
    await click(nav('Следващ месец'))
    const tabbable = screen.getAllByRole('gridcell').filter((candidate) => candidate.getAttribute('tabindex') === '0')
    expect(tabbable).toHaveLength(1)
    // No date of November is available, so the first bookable day of the month is the tab stop.
    expect(tabbable[0]).toBe(cell(/^неделя, 1 ноември 2026 г\./))
  })
})

describe('today and the Business-local date', () => {
  it('labels today once, from the Business timezone', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    // 21:30 UTC on 6 October is 7 October in Sofia.
    vi.setSystemTime(new Date('2026-10-06T21:30:00Z'))
    await openDateStep()
    const today = screen.getAllByRole('gridcell').filter((candidate) => candidate.getAttribute('aria-current') === 'date')
    expect(today).toHaveLength(1)
    expect(today[0]).toHaveAccessibleName('сряда, 7 октомври 2026 г., днес')
    expect(today[0]).toHaveClass('booking-day--today')
  })

  it('labels no date as today when today is outside the horizon', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2027-01-15T10:00:00Z'))
    await openDateStep()
    expect(screen.queryAllByRole('gridcell').filter((c) => c.getAttribute('aria-current') === 'date')).toHaveLength(0)
  })
})

describe('reads that settle late', () => {
  it('shows the loading state for the slots of a date while the calendar stays usable', async () => {
    await openDateStep()
    let release: (response: Response) => void = () => undefined
    server.on((request) =>
      request.path.endsWith('/availability') && request.url.searchParams.get('date') === '2026-10-08'
        ? (new Promise<Response>((resolve) => {
            release = resolve
          }) as unknown as Response)
        : undefined,
    )
    await pickDate('четвъртък, 8 октомври 2026 г.')
    expect(screen.getByText('Зареждане на свободните часове…')).toBeInTheDocument()
    expect(screen.getAllByRole('gridcell').length).toBeGreaterThan(28)
    release(json(200, availabilityBody('2026-10-08')))
    await flush()
    expect(screen.getByRole('radio', { name: '09:00' })).toBeInTheDocument()
  })

  it('shows a failed read with a retry that sends the same request again', async () => {
    let failing = true
    server.on((request) => {
      if (request.path.endsWith('/availability') && failing) return Promise.reject(new TypeError('offline')) as unknown as Response
      return undefined
    })
    await openPage()
    await startFromService('Подстригване')
    await next()
    expect(screen.getByRole('alert')).toHaveTextContent(/Данните не можаха да бъдат заредени/)
    failing = false
    await click(screen.getByRole('button', { name: 'Опитайте отново' }))
    expect(screen.getByRole('grid')).toBeInTheDocument()
  })
})
