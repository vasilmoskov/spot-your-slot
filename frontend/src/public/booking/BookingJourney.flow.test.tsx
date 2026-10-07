import '@testing-library/jest-dom/vitest'
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ATTEMPT_ONE,
  SERVICE_COLOUR,
  SERVICE_HAIR,
  STAFF_GEORGI,
  STAFF_MARIA,
  availabilityBody,
  bookingBody,
  button,
  chooseStaff,
  click,
  deferred,
  fillDetails,
  flush,
  heading,
  installServer,
  json,
  next,
  openPage,
  parsedBody,
  pickDate,
  pickSlot,
  reachReview,
  startFromHero,
  startFromService,
  stubAttemptIds,
  submit,
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
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('the complete happy path', () => {
  it('books from a Service with no StaffMember preference using the real response shapes', async () => {
    await openPage()
    await startFromService('Подстригване')

    // Entering from a Service skips its own step and starts at the StaffMember preference.
    expect(heading()).toHaveTextContent('Избор на служител')
    expect(heading()).toHaveFocus()
    expect(screen.getByText('Стъпка 2 от 5')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Без предпочитание/ })).toBeChecked()
    expect(
      screen.getByText('Служителят се определя при потвърждаване на резервацията.'),
    ).toBeInTheDocument()
    await next()

    expect(heading()).toHaveTextContent('Дата и час')
    expect(screen.getByText('Часовете са по времето на бизнеса (Europe/Sofia).')).toBeInTheDocument()
    // The first available date is selected and its slots are those of the server.
    expect(screen.getByRole('radio', { name: /7 октомври 2026/ })).toBeChecked()
    await pickSlot('10:00')
    await next()

    expect(heading()).toHaveTextContent('Вашите данни')
    await fillDetails({ name: '  Иван Петров  ', phone: '0888 123 456', email: '', note: '' })
    await next()

    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    const summary = document.querySelector('.booking-summary') as HTMLElement
    expect(summary).toHaveTextContent('Подстригване')
    expect(summary).toHaveTextContent('45 мин. · 25.00 €')
    expect(summary).toHaveTextContent('Без предпочитание')
    expect(summary).toHaveTextContent('сряда, 7 октомври 2026 г.')
    expect(summary).toHaveTextContent('10:00 – 10:45')
    expect(summary).toHaveTextContent('Иван Петров')
    expect(summary).toHaveTextContent('0888 123 456')
    expect(
      screen.getByText(/Часът не е запазен, докато резервацията не бъде потвърдена/),
    ).toBeInTheDocument()
    // Nothing has been sent yet: a booking is never posted by merely reaching the review.
    expect(server.posts).toHaveLength(0)

    await submit()

    expect(server.posts).toHaveLength(1)
    const post = server.posts[0]!
    expect(post.path).toBe(`/api/public/businesses/example-studio/bookings`)
    expect(post.credentials).toBe('omit')
    expect(post.headers['content-type']).toBe('application/json')
    // Exactly the documented fields and nothing else, in the documented order.
    expect(Object.keys(parsedBody(post))).toEqual([
      'attemptId',
      'serviceId',
      'staffMemberId',
      'start',
      'customer',
      'note',
    ])
    expect(parsedBody(post)).toEqual({
      attemptId: ATTEMPT_ONE,
      serviceId: SERVICE_HAIR,
      staffMemberId: null,
      start: '2026-10-07T10:00:00+03:00',
      customer: { displayName: 'Иван Петров', phone: '0888 123 456', email: null },
      note: null,
    })

    // The confirmation shows only what the server returned.
    expect(heading()).toHaveTextContent('Резервацията е потвърдена')
    expect(heading()).toHaveFocus()
    const facts = document.querySelector('.booking-facts') as HTMLElement
    expect(facts).toHaveTextContent('Потвърдена')
    expect(facts).toHaveTextContent('K7M2Q9XW4B')
    expect(facts).toHaveTextContent('Подстригване')
    expect(facts).toHaveTextContent('Мария Иванова')
    expect(facts).toHaveTextContent('сряда, 7 октомври 2026 г.')
    expect(facts).toHaveTextContent('10:00 – 10:45')
    expect(facts).toHaveTextContent('45 мин.')
    expect(facts).toHaveTextContent('25.00 €')
    expect(facts).toHaveTextContent('Europe/Sofia')
    expect(screen.getByText(/Не изпращаме потвърждение по имейл или SMS/)).toBeInTheDocument()
    // Customer details leave memory once the server has answered.
    expect(document.body.textContent).not.toContain('0888 123 456')
    expect(document.body.textContent).not.toContain('Иван Петров')
  })

  it('starts from the general entry at the Service step and lists every Service', async () => {
    await openPage()
    await startFromHero()

    expect(heading()).toHaveTextContent('Избор на услуга')
    expect(screen.getByText('Стъпка 1 от 5')).toBeInTheDocument()
    expect(screen.getAllByRole('radio').map((radio) => (radio as HTMLInputElement).value)).toEqual([
      SERVICE_HAIR,
      SERVICE_COLOUR,
    ])
    // Nothing is chosen and the Service step cannot be left without a choice.
    expect(button('Напред')).toBeDisabled()
    await click(screen.getByRole('radio', { name: /Боядисване/ }))
    await next()
    expect(heading()).toHaveTextContent('Избор на служител')
    expect(screen.getByText('Боядисване')).toBeInTheDocument()
    expect(server.of('GET', '/booking-options')[0]!.path).toContain(SERVICE_COLOUR)
  })

  it('sends an explicit StaffMember and shows the member the server assigned, not the preference', async () => {
    server.on((request) =>
      request.method === 'POST'
        ? json(201, { ...bookingBody, staff: { displayName: 'Георги Петров' } })
        : undefined,
    )
    await openPage()
    await reachReview({ staff: 'Мария Иванова' })

    expect(document.querySelector('.booking-summary')).toHaveTextContent('Мария Иванова')
    expect(server.of('GET', '/availability').at(-1)!.url.searchParams.get('staffMemberId')).toBe(
      STAFF_MARIA,
    )
    await submit()

    expect(parsedBody(server.posts[0])).toMatchObject({ staffMemberId: STAFF_MARIA })
    expect(document.querySelector('.booking-facts')).toHaveTextContent('Георги Петров')
  })

  it('never sends a StaffMember query parameter for no preference', async () => {
    await openPage()
    await reachReview()
    for (const request of server.of('GET', '/availability')) {
      expect(request.url.searchParams.has('staffMemberId')).toBe(false)
    }
  })

  it('treats a prices and durations only as advertised until the server confirms', async () => {
    server.on((request) =>
      request.method === 'POST'
        ? json(201, {
            ...bookingBody,
            service: { name: 'Подстригване', durationMinutes: 50, price: 27.5 },
          })
        : undefined,
    )
    await openPage()
    await reachReview()
    expect(document.querySelector('.booking-summary')).toHaveTextContent('45 мин. · 25.00 €')
    await submit()
    expect(document.querySelector('.booking-facts')).toHaveTextContent('50 мин.')
    expect(document.querySelector('.booking-facts')).toHaveTextContent('27.50 €')
  })
})

describe('selection changes and dependent state', () => {
  it('keeps every previous input when moving back and forward between steps', async () => {
    await openPage()
    await reachReview({ staff: 'Георги Петров', details: { name: 'Иван Петров', email: 'ivan@example.invalid', note: 'Моля, тих стол.' } })

    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Вашите данни'))
    expect(screen.getByLabelText('Име')).toHaveValue('Иван Петров')
    expect(screen.getByLabelText('Телефон')).toHaveValue('0888 123 456')
    expect(screen.getByLabelText('Имейл')).toHaveValue('ivan@example.invalid')
    expect(screen.getByLabelText(/Бележка/)).toHaveValue('Моля, тих стол.')

    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Дата и час'))
    expect(screen.getByRole('radio', { name: /7 октомври 2026/ })).toBeChecked()
    expect(screen.getByRole('radio', { name: '10:00' })).toBeChecked()

    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на служител'))
    expect(screen.getByRole('radio', { name: /Георги Петров/ })).toBeChecked()
  })

  it('invalidates the date and time when the StaffMember changes and loads the new preference', async () => {
    await openPage()
    await startFromService('Подстригване')
    await next()
    await pickDate(/8 октомври 2026/)
    await pickSlot('09:00')
    expect(screen.getByRole('radio', { name: '09:00' })).toBeChecked()

    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на служител'))
    await chooseStaff('Мария Иванова')
    await next()

    // The earlier date and slot are gone; the first date of the new preference is selected.
    expect(screen.getByRole('radio', { name: /7 октомври 2026/ })).toBeChecked()
    expect(screen.queryByRole('radio', { name: '09:00' })).toBeNull()
    expect(screen.getByRole('radio', { name: '10:00' })).not.toBeChecked()
    expect(button('Напред')).toBeDisabled()
    expect(server.of('GET', '/availability').at(-1)!.url.searchParams.get('staffMemberId')).toBe(
      STAFF_MARIA,
    )
  })

  it('invalidates the preference, date and time when the Service changes', async () => {
    await openPage()
    await startFromService('Подстригване')
    await chooseStaff('Георги Петров')
    await next()
    await pickSlot('10:00')

    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на служител'))
    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуга'))
    await click(screen.getByRole('radio', { name: /Боядисване/ }))
    await next()

    expect(heading()).toHaveTextContent('Избор на служител')
    expect(screen.getByRole('radio', { name: /Без предпочитание/ })).toBeChecked()
    await next()
    expect(screen.queryByRole('radio', { name: '10:00' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: '10:00' })).not.toBeChecked()
    expect(server.of('GET', '/booking-options').at(-1)!.path).toContain(SERVICE_COLOUR)
  })

  it('does not reload the booking options when only the selected date changes', async () => {
    await openPage()
    await startFromService('Подстригване')
    await next()
    expect(server.of('GET', '/booking-options')).toHaveLength(1)

    await pickDate(/8 октомври 2026/)
    await pickDate(/7 октомври 2026/)
    await pickDate(/25 октомври 2026/)

    expect(server.of('GET', '/booking-options')).toHaveLength(1)
    // Each date loads its own slots; the first request already loaded the first date.
    expect(server.of('GET', '/availability').map((request) => request.url.searchParams.get('date'))).toEqual([
      '2026-10-07',
      '2026-10-08',
      '2026-10-07',
      '2026-10-25',
    ])
  })

  it('clears the chosen time when another date is picked', async () => {
    await openPage()
    await startFromService('Подстригване')
    await next()
    await pickSlot('11:00')
    await pickDate(/8 октомври 2026/)
    expect(button('Напред')).toBeDisabled()
    expect(screen.getByRole('radio', { name: '09:00' })).not.toBeChecked()
  })
})

describe('dates, timezone and the repeated daylight-saving hour', () => {
  it('shows each date from its own calendar day, never shifted by the browser timezone', async () => {
    await openPage()
    await startFromService('Подстригване')
    await next()
    expect(screen.getByRole('radio', { name: 'сряда, 7 октомври 2026 г.' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'четвъртък, 8 октомври 2026 г.' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'неделя, 25 октомври 2026 г.' })).toBeInTheDocument()
  })

  it('tells the two 03:30 slots of the repeated hour apart, in the list, the review and the result', async () => {
    server.on((request) =>
      request.method === 'POST'
        ? json(201, {
            ...bookingBody,
            start: '2026-10-25T03:30:00+02:00',
            end: '2026-10-25T04:15:00+02:00',
          })
        : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    await next()
    await pickDate(/25 октомври 2026/)

    const group = screen.getByRole('group', { name: 'Час' })
    const labels = within(group)
      .getAllByRole('radio')
      .map((radio) => radio.closest('label')?.textContent)
    expect(labels).toEqual(['03:30 (UTC+03:00)', '03:30 (UTC+02:00)'])

    await pickSlot('03:30 (UTC+02:00)')
    await next()
    await fillDetails()
    await next()
    // The elapsed time of the first slot crosses the change, and each end keeps its own offset.
    expect(document.querySelector('.booking-summary')).toHaveTextContent('03:30 (UTC+02:00) – 04:15')
    await submit()
    expect(parsedBody(server.posts[0])).toMatchObject({ start: '2026-10-25T03:30:00+02:00' })
    expect(document.querySelector('.booking-facts')).toHaveTextContent('03:30 (UTC+02:00) – 04:15')
  })

  it('formats the confirmation in the returned timezone, whatever the browser uses', async () => {
    server.on((request) =>
      request.method === 'POST'
        ? json(201, {
            ...bookingBody,
            timezone: 'Europe/Berlin',
            start: '2026-10-07T09:00:00+02:00',
            end: '2026-10-07T09:45:00+02:00',
          })
        : undefined,
    )
    await openPage()
    await reachReview()
    await submit()
    expect(document.querySelector('.booking-facts')).toHaveTextContent('09:00 – 09:45')
    expect(document.querySelector('.booking-facts')).toHaveTextContent('Europe/Berlin')
  })
})

describe('reads: cancellation, stale answers and Business switching', () => {
  it('aborts the previous availability read and ignores its late answer', async () => {
    const slow = deferred<Response>()
    server.on((request) =>
      request.path.endsWith('/availability') && request.url.searchParams.get('date') === '2026-10-08'
        ? slow.promise
        : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    await next()
    await pickDate(/8 октомври 2026/)
    expect(screen.getByText('Зареждане на свободните часове…')).toBeInTheDocument()
    const stale = server.of('GET', '/availability').at(-1)!

    // The guest picks another date before the first answer arrives.
    await pickDate(/25 октомври 2026/)
    expect(stale.signal?.aborted).toBe(true)
    slow.resolve(json(200, availabilityBody('2026-10-08')))
    await flush()

    // The late answer for 8 October never reaches the screen.
    expect(screen.queryByRole('radio', { name: '09:00' })).toBeNull()
    expect(screen.getByRole('radio', { name: /25 октомври 2026/ })).toBeChecked()
    expect(screen.getAllByRole('radio', { name: /03:30/ })).toHaveLength(2)
  })

  it('ignores a late booking-options answer for a Service the guest has left', async () => {
    const slow = deferred<Response>()
    server.on((request) =>
      request.path.includes(SERVICE_HAIR) && request.path.endsWith('/booking-options')
        ? slow.promise
        : undefined,
    )
    await openPage()
    await startFromService('Подстригване')
    expect(screen.getByText('Зареждане на служителите…')).toBeInTheDocument()
    const stale = server.of('GET', '/booking-options')[0]!

    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуга'))
    await click(screen.getByRole('radio', { name: /Боядисване/ }))
    await next()
    expect(stale.signal?.aborted).toBe(true)

    slow.resolve(json(200, { ...{ timezone: 'Europe/Sofia', firstDate: '2026-10-07', lastDate: '2026-11-05' }, staff: [{ id: STAFF_GEORGI, displayName: 'Само от старата заявка' }] }))
    await flush()
    expect(screen.queryByText('Само от старата заявка')).toBeNull()
    expect(screen.getByRole('radio', { name: /Мария Иванова/ })).toBeInTheDocument()
  })

  it('clears the booking state and ignores pending answers when another Business is shown', async () => {
    const slowPost = deferred<Response>()
    server.on((request) => (request.method === 'POST' ? slowPost.promise : undefined))
    const { rerender } = await openPage()
    await reachReview({ details: { name: 'Тайно Име' } })
    await submit()
    expect(server.posts).toHaveLength(1)

    const { PublicBusinessPage } = await import('../PublicBusinessPage')
    rerender(<PublicBusinessPage key="other-studio" slug="other-studio" />)
    await flush()
    slowPost.resolve(json(201, bookingBody))
    await flush()

    // The previous Business's journey, its details and its late result are gone.
    expect(document.body.textContent).not.toContain('Тайно Име')
    expect(screen.queryByText('Резервацията е потвърдена')).toBeNull()
    expect(screen.queryByText('K7M2Q9XW4B')).toBeNull()
  })
})
