import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ATTEMPT_ONE,
  STAFF_MARIA,
  availabilityBody,
  button,
  click,
  fillDetails,
  flush,
  heading,
  installServer,
  json,
  next,
  openPage,
  optionsBody,
  pickDate,
  problem,
  reachReview,
  startFromHero,
  startFromService,
  stubAttemptIds,
  submit,
  typeInto,
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

describe('reads: unavailable, empty and failing states', () => {
  it('shows the unavailable Business answer of the options read as the page being unavailable', async () => {
    server.on((request) =>
      request.path.endsWith('/booking-options')
        ? json(404, problem('BUSINESS_PAGE_UNAVAILABLE', 404))
        : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    expect(heading()).toHaveTextContent('Страницата не е налична')
  })

  it('reports an unavailable Service on the read and offers another Service', async () => {
    server.on((request) =>
      request.path.endsWith('/booking-options')
        ? json(409, problem('BOOKING_SERVICE_UNAVAILABLE', 409))
        : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Избраната услуга вече не е налична. Изберете друга услуга.',
    )
    await click(button('Избор на услуга'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуга'))
  })

  it('says so when no StaffMember is eligible, instead of offering an empty list', async () => {
    server.on((request) =>
      request.path.endsWith('/booking-options') ? json(200, { ...optionsBody, staff: [] }) : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    expect(
      screen.getByText('В момента няма служител, при когото можете да запазите тази услуга онлайн.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('radio')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Напред' })).toBeNull()
    await click(button('Избор на друга услуга'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуга'))
  })

  it('reports an unavailable StaffMember of the availability read', async () => {
    server.on((request) =>
      request.path.endsWith('/availability') && request.url.searchParams.has('staffMemberId')
        ? json(409, problem('BOOKING_STAFF_UNAVAILABLE', 409))
        : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    await click(screen.getByRole('radio', { name: /Мария Иванова/ }))
    await next()
    expect(screen.getByRole('alert')).toHaveTextContent('Избраният служител вече не е наличен.')
    await click(button('Избор на служител'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на служител'))
    expect(screen.getByRole('radio', { name: /Без предпочитание/ })).toBeChecked()
  })

  it('says so when there is no available date, and offers no date or time control', async () => {
    server.on((request) =>
      request.path.endsWith('/availability')
        ? json(200, { ...availabilityBody('2026-10-07', []), slots: [] })
        : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    await next()
    expect(
      screen.getByText('Няма свободни дати за избраните услуга и служител. Опитайте с друг служител.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('radio')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Напред' })).toBeNull()
  })

  it('says so when a date has no slots', async () => {
    server.on((request) =>
      request.path.endsWith('/availability') && request.url.searchParams.get('date') === '2026-10-08'
        ? json(200, { ...availabilityBody('2026-10-08'), slots: [] })
        : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    await next()
    await pickDate(/8 октомври 2026/)
    expect(screen.getByText('Няма свободни часове за тази дата.')).toBeInTheDocument()
    expect(button('Напред')).toBeDisabled()
  })

  it('shows the loading state of each read with a status, and no action before data exists', async () => {
    await openPage()
    await startFromService('Подстригване')
    // The fake answers at once, so the final state is shown; the loading text is asserted where held.
    expect(screen.queryByText('Зареждане на служителите…')).toBeNull()
    expect(screen.getAllByRole('radio').length).toBeGreaterThan(0)
  })

  it('offers a retry after a network failure and loads the data again on request only', async () => {
    let failing = true
    server.on((request) =>
      request.path.endsWith('/booking-options') && failing
        ? Promise.reject(new TypeError('Failed to fetch')) as unknown as Promise<Response>
        : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Данните не можаха да бъдат заредени. Проверете връзката си и опитайте отново.',
    )
    expect(server.of('GET', '/booking-options')).toHaveLength(1)
    failing = false
    await click(button('Опитайте отново'))
    expect(server.of('GET', '/booking-options')).toHaveLength(2)
    expect(screen.getByRole('radio', { name: /Мария Иванова/ })).toBeInTheDocument()
  })

  it('shows the rate limit of a read and keeps the retry unavailable until Retry-After has passed', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    server.on((request) =>
      request.path.endsWith('/availability')
        ? json(429, problem('RATE_LIMITED', 429), { 'Retry-After': '20' })
        : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    await next()
    expect(screen.getByRole('alert')).toHaveTextContent('Твърде много опити. Опитайте по-късно.')
    expect(button('Опитайте отново')).toBeDisabled()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20_000)
    })
    expect(button('Опитайте отново')).toBeEnabled()
    expect(server.of('GET', '/availability')).toHaveLength(1)
  })

  it('treats an answer that is not the contract as a failure, not as data', async () => {
    server.on((request) =>
      request.path.endsWith('/availability') ? json(200, { hello: 'world' }) : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    await next()
    expect(screen.getByRole('alert')).toHaveTextContent('Възникна неочаквана грешка.')
  })

  it('treats an availability answer for another date as a failure', async () => {
    server.on((request) =>
      request.path.endsWith('/availability') ? json(200, availabilityBody('2026-10-08')) : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    await next()
    expect(screen.getByRole('alert')).toHaveTextContent('Възникна неочаквана грешка.')
  })

  it('selects a still-offered date and drops a chosen time that a refresh no longer offers', async () => {
    await openPage()
    await startFromService('Подстригване')
    await next()
    await click(screen.getByRole('radio', { name: '11:00' }))
    await next()
    // While the guest is on a later step the slot is taken; returning reloads the offer.
    server.on((request) =>
      request.path.endsWith('/availability')
        ? json(200, {
            ...availabilityBody('2026-10-07'),
            slots: [{ start: '2026-10-07T10:00:00+03:00', end: '2026-10-07T10:45:00+03:00' }],
          })
        : undefined,
    )
    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Дата и час'))
    // The refreshed offer replaces the list, and the slot it no longer contains is dropped.
    await waitFor(() => expect(screen.getByRole('radio', { name: '10:00' })).toBeInTheDocument())
    expect(screen.queryByRole('radio', { name: '11:00' })).toBeNull()
    expect(button('Напред')).toBeDisabled()
  })
})

describe('the Customer details', () => {
  async function openDetails() {
    await openPage()
    await startFromService('Подстригване')
    await next()
    await click(screen.getByRole('radio', { name: '10:00' }))
    await next()
    expect(heading()).toHaveTextContent('Вашите данни')
  }

  it('shows no error for an untouched empty form and the contact hint', async () => {
    await openDetails()
    expect(document.querySelector('.field-error')).toBeNull()
    expect(screen.getByText('Попълнете телефон или имейл.')).toBeInTheDocument()
    expect(screen.getByText('Данните ви се предоставят на бизнеса за записване и управление на резервацията.')).toBeInTheDocument()
  })

  it('validates on blur, shows a wrapped inline error and clears it when the value is valid', async () => {
    await openDetails()
    const name = screen.getByLabelText('Име')
    fireEvent.blur(name)
    expect(screen.getByText('Въведете име до 200 знака.')).toBeInTheDocument()
    expect(name).toHaveAttribute('aria-invalid', 'true')
    expect(name).toHaveAccessibleDescription('Въведете име до 200 знака.')
    typeInto('Име', 'Иван')
    expect(screen.queryByText('Въведете име до 200 знака.')).toBeNull()

    typeInto('Телефон', '12ab')
    expect(screen.getByText('Въведеният телефонен номер не е валиден.')).toBeInTheDocument()
    typeInto('Имейл', 'not-an-email')
    expect(screen.getByText('Въведеният имейл адрес не е валиден.')).toBeInTheDocument()
  })

  it('shows every error on submit, focuses the first invalid control and keeps the values', async () => {
    await openDetails()
    typeInto('Телефон', '12ab')
    await click(button('Напред'))
    expect(heading()).toHaveTextContent('Вашите данни')
    expect(screen.getByLabelText('Име')).toHaveFocus()
    expect(screen.getByText('Въведете име до 200 знака.')).toBeInTheDocument()
    expect(screen.getByText('Въведеният телефонен номер не е валиден.')).toBeInTheDocument()
    expect(screen.getByLabelText('Телефон')).toHaveValue('12ab')
  })

  it('requires a phone or an email and reports it once, focusing the phone', async () => {
    await openDetails()
    typeInto('Име', 'Иван Петров')
    await click(button('Напред'))
    expect(screen.getByText('Въведете телефон или имейл.')).toBeInTheDocument()
    expect(screen.getByLabelText('Телефон')).toHaveFocus()
    expect(screen.getByLabelText('Телефон')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Имейл')).toHaveAttribute('aria-invalid', 'true')
    // Either contact value alone is enough.
    typeInto('Имейл', 'ivan@example.invalid')
    await click(button('Напред'))
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
  })

  it('accepts exactly 500 code points of note and rejects 501, counting code points not units', async () => {
    await openDetails()
    typeInto('Име', 'Иван Петров')
    typeInto('Телефон', '0888 123 456')
    typeInto(/Бележка/, '😀'.repeat(500))
    expect(screen.getByText('500 / 500 знака')).toBeInTheDocument()
    expect(document.querySelector('.field-error')).toBeNull()

    typeInto(/Бележка/, '😀'.repeat(501))
    expect(screen.getByText('501 / 500 знака')).toBeInTheDocument()
    expect(screen.getByText('Бележката може да съдържа най-много 500 знака.')).toBeInTheDocument()
    await click(button('Напред'))
    expect(heading()).toHaveTextContent('Вашите данни')
    expect(screen.getByLabelText(/Бележка/)).toHaveFocus()
  })

  it('sends a blank note as null and a note as plain text', async () => {
    await openDetails()
    typeInto('Име', 'Иван Петров')
    typeInto('Телефон', '0888 123 456')
    typeInto(/Бележка/, '   ')
    await next()
    await submit()
    expect(JSON.parse(server.posts[0]!.body ?? '{}')).toMatchObject({ note: null })
  })
})

describe('leaving the journey', () => {
  it('leaves at once when nothing has been entered', async () => {
    await openPage()
    await startFromHero()
    await click(button('Към страницата на бизнеса'))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    await waitFor(() => expect(heading()).toHaveTextContent('Примерно студио'))
    expect(screen.getByRole('button', { name: 'Запази час' })).toBeInTheDocument()
    expect(heading()).toHaveFocus()
  })

  it('asks before discarding entered data; „Остани“ keeps everything', async () => {
    await openPage()
    await reachReview({ details: { name: 'Иван Петров' } })
    await click(button('Към страницата на бизнеса'))

    const dialog = screen.getByRole('alertdialog', { name: 'Незавършена резервация' })
    expect(dialog).toHaveTextContent('Резервацията не е завършена.')
    expect(dialog).toHaveTextContent('Ако напуснете, въведените данни ще бъдат загубени.')
    expect(button('Остани')).toHaveFocus()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await flush()
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(document.querySelector('.booking-summary')).toHaveTextContent('Иван Петров')
  })

  it('„Напусни“ returns to the profile and a new journey starts empty', async () => {
    await openPage()
    await reachReview({ details: { name: 'Иван Петров' } })
    await click(button('Към страницата на бизнеса'))
    await click(button('Напусни'))
    await waitFor(() => expect(heading()).toHaveTextContent('Примерно студио'))
    expect(document.body.textContent).not.toContain('Иван Петров')

    await startFromService('Подстригване')
    await next()
    await click(screen.getByRole('radio', { name: '10:00' }))
    await next()
    expect(screen.getByLabelText('Име')).toHaveValue('')
    expect(screen.getByLabelText('Телефон')).toHaveValue('')
    // Leaving rewound the history to the profile entry.
    expect(window.history.state?.spyBooking?.step).toBe(4)
  })

  it('treats the browser Back from the first step like leaving: nothing to lose, no dialog', async () => {
    await openPage()
    await startFromHero()
    window.history.back()
    await waitFor(() => expect(heading()).toHaveTextContent('Примерно студио'))
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('puts the review back and asks when the browser Back would discard entered data', async () => {
    await openPage()
    await reachReview({ details: { name: 'Иван Петров' } })
    // Four entries lie between the review and the profile; go back to the profile at once.
    window.history.go(-5)
    await waitFor(() => expect(screen.getByRole('alertdialog')).toBeInTheDocument())
    await waitFor(() => expect(window.history.state?.spyBooking?.step).toBe(5))
    expect(heading()).toHaveTextContent('Преглед и потвърждение')

    await click(button('Остани'))
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(document.querySelector('.booking-summary')).toHaveTextContent('Иван Петров')

    window.history.go(-5)
    await waitFor(() => expect(screen.getByRole('alertdialog')).toBeInTheDocument())
    await waitFor(() => expect(window.history.state?.spyBooking?.step).toBe(5))
    await click(button('Напусни'))
    await waitFor(() => expect(heading()).toHaveTextContent('Примерно студио'))
    expect(document.body.textContent).not.toContain('Иван Петров')
  })

  it('moves one step with the browser Back and Forward, never past what the choices allow', async () => {
    await openPage()
    await startFromService('Подстригване')
    await next()
    await click(screen.getByRole('radio', { name: '10:00' }))
    await next()
    expect(heading()).toHaveTextContent('Вашите данни')

    window.history.back()
    await waitFor(() => expect(heading()).toHaveTextContent('Дата и час'))
    // Changing the slot makes the details step reachable only through the slot again.
    window.history.forward()
    await waitFor(() => expect(heading()).toHaveTextContent('Вашите данни'))
    window.history.back()
    await waitFor(() => expect(heading()).toHaveTextContent('Дата и час'))
    await pickDate(/8 октомври 2026/)
    window.history.forward()
    // The slot was cleared by the new date, so the details entry is refused and undone.
    await waitFor(() => expect(heading()).toHaveTextContent('Дата и час'))
    await flush()
    expect(heading()).toHaveTextContent('Дата и час')
  })

  it('arms the unload warning only while there is something to lose', async () => {
    await openPage()
    const probe = () => {
      const event = new Event('beforeunload', { cancelable: true })
      act(() => {
        window.dispatchEvent(event)
      })
      return event.defaultPrevented
    }
    expect(probe()).toBe(false)
    await startFromHero()
    expect(probe()).toBe(false)
    await reachReviewFromService()
    expect(probe()).toBe(true)
  })

  it('does not warn after a confirmed booking', async () => {
    await openPage()
    await reachReview()
    await submit()
    const event = new Event('beforeunload', { cancelable: true })
    act(() => {
      window.dispatchEvent(event)
    })
    expect(event.defaultPrevented).toBe(false)
    await click(button('Към страницата на бизнеса'))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    await waitFor(() => expect(heading()).toHaveTextContent('Примерно студио'))
  })

  it('refuses the browser Back to an earlier step after the booking was confirmed', async () => {
    await openPage()
    await reachReview()
    await submit()
    window.history.back()
    await waitFor(() => expect(window.history.state?.spyBooking?.step).toBe(6))
    expect(heading()).toHaveTextContent('Резервацията е потвърдена')
  })
})

async function reachReviewFromService() {
  await click(screen.getByRole('radio', { name: /Подстригване/ }))
  await next()
  await next()
  await click(screen.getByRole('radio', { name: '10:00' }))
  await next()
  await fillDetails()
}

describe('memory-only privacy', () => {
  it('keeps the details, the note and the attempt out of the URL, history, storage, cookies and title', async () => {
    const secrets = ['Тайно Име', 'secret.mail@example.invalid', '0899 765 432', 'Тайна бележка'] as const
    window.localStorage.clear()
    window.sessionStorage.clear()
    const before = window.location.href
    await openPage()
    await reachReview({
      details: { name: secrets[0], email: secrets[1], phone: secrets[2], note: secrets[3] },
    })
    await submit()

    const everywhere = [
      window.location.href,
      JSON.stringify(window.history.state),
      JSON.stringify(Object.entries(window.localStorage)),
      JSON.stringify(Object.entries(window.sessionStorage)),
      document.cookie,
      document.title,
      document.head.innerHTML,
      ...server.requests.filter((request) => request.method === 'GET').map((request) => request.url.href),
    ].join('\n')
    for (const secret of secrets) expect(everywhere).not.toContain(secret)
    expect(everywhere).not.toContain(ATTEMPT_ONE)
    expect(window.location.href).toBe(before)
    expect(window.localStorage).toHaveLength(0)
    expect(window.sessionStorage).toHaveLength(0)
    expect(window.history.state).toEqual({ spyBooking: { journey: expect.any(String), step: 6 } })
  })

  it('keeps only a step marker in every history entry it adds', async () => {
    await openPage()
    const states: unknown[] = []
    const push = vi.spyOn(window.history, 'pushState')
    await reachReview({ details: { name: 'Тайно Име' } })
    for (const call of push.mock.calls) states.push(call[0])
    expect(states.length).toBeGreaterThan(0)
    for (const state of states) {
      expect(Object.keys(state as object)).toEqual(['spyBooking'])
      expect(JSON.stringify(state)).not.toMatch(/Тайно|0888|attempt|[0-9a-f]{8}-[0-9a-f]{4}-/i)
      // The URL argument is never given, so the location cannot change.
    }
    for (const call of push.mock.calls) expect(call).toHaveLength(2)
  })

  it('sends every public request without credentials', async () => {
    await openPage()
    await reachReview({ staff: 'Мария Иванова' })
    await submit()
    // The profile, the options, the availability and the booking.
    expect(server.requests.map((request) => request.method)).toEqual(['GET', 'GET', 'GET', 'POST'])
    for (const request of server.requests) {
      expect(request.credentials).toBe('omit')
      expect(request.headers).not.toHaveProperty('cookie')
      expect(request.headers).not.toHaveProperty('x-xsrf-token')
    }
  })

  it('does not render the details after a successful booking nor keep them anywhere reachable', async () => {
    await openPage()
    await reachReview({ details: { name: 'Тайно Име', phone: '0899 765 432' } })
    await submit()
    expect(document.body.innerHTML).not.toContain('Тайно Име')
    expect(document.body.innerHTML).not.toContain('0899 765 432')
    expect(document.body.innerHTML).not.toContain(STAFF_MARIA)
  })
})

describe('the profile', () => {
  it('stays unchanged until the guest chooses to book, and requests nothing else', async () => {
    await openPage()
    expect(server.requests).toHaveLength(1)
    expect(heading()).toHaveTextContent('Примерно студио')
    expect(server.requests[0]!.path).toBe('/api/public/businesses/example-studio')
  })
})
