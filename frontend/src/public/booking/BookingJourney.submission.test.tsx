import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ATTEMPT_ONE,
  SERVICE_COLOUR,
  bookingBody,
  button,
  click,
  deferred,
  flush,
  heading,
  installServer,
  json,
  next,
  openPage,
  parsedBody,
  pickSlot,
  problem,
  reachReview,
  stubAttemptIds,
  submit,
  typeInto,
  startFromService,
  type FakeServer,
} from './testSupport'

const ATTEMPT_TWO = '6c1e8d3f-9e8b-4d2f-8b66-1c7f4d2e3f02'
const UNCERTAIN = 'Не получихме потвърждение за резервацията. Опитайте отново.'
const ROLLBACK = 'Резервацията не беше направена. Опитайте отново.'

let server: FakeServer
let randomUuid: ReturnType<typeof stubAttemptIds>

type PostResponse = () => Response | Promise<Response>

// The POST answers come from a queue, so each attempt of a test has exactly the outcome it scripts.
function scriptPosts(...responses: PostResponse[]) {
  const queue = [...responses]
  server.on((request) => {
    if (request.method !== 'POST') return undefined
    const respond = queue.shift()
    if (!respond) throw new Error('an unscripted booking request was sent')
    return respond()
  })
}

beforeEach(() => {
  window.history.replaceState(null, '', '/example-studio')
  server = installServer()
  randomUuid = stubAttemptIds(ATTEMPT_ONE, ATTEMPT_TWO)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('duplicate submissions', () => {
  it('sends one request for repeated clicks and disables the controls while it is pending', async () => {
    const pending = deferred<Response>()
    scriptPosts(() => pending.promise)
    await openPage()
    await reachReview()

    const confirm = button('Потвърди резервацията')
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    await flush()

    expect(server.posts).toHaveLength(1)
    expect(screen.getByText('Изпращане на резервацията…')).toBeInTheDocument()
    expect(button('Изпращане…')).toBeDisabled()
    for (const edit of screen.getAllByRole('button', { name: /^Промени/ })) {
      expect(edit).toBeDisabled()
    }
    expect(screen.queryByRole('button', { name: 'Назад' })).toBeNull()

    pending.resolve(json(201, bookingBody))
    await flush()
    expect(server.posts).toHaveLength(1)
    expect(heading()).toHaveTextContent('Резервацията е потвърдена')
  })

  it('never posts on its own: not at the review, not after navigation, not after a rerender', async () => {
    await openPage()
    await reachReview()
    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Вашите данни'))
    await next()
    await flush()
    expect(server.posts).toHaveLength(0)
  })
})

// Every way a send can end without a verdict about the booking, and the one proven rollback. Each
// keeps the attempt: the next send carries the very same ID and the very same body.
describe('the attempt identity across every retry path', () => {
  const failures: [string, PostResponse, string][] = [
    ['a network failure', () => Promise.reject(new TypeError('Failed to fetch')), UNCERTAIN],
    [
      'the uncertain outcome',
      () => json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503)),
      UNCERTAIN,
    ],
    ['an unknown server error', () => json(500, problem('INTERNAL_ERROR', 500)), UNCERTAIN],
    ['a gateway error without a body', () => new Response('<html>', { status: 502 }), UNCERTAIN],
    ['a request timeout', () => new Response('', { status: 408 }), UNCERTAIN],
    ['an unreadable success', () => new Response('<html>', { status: 201 }), UNCERTAIN],
    ['a malformed success', () => json(201, { reference: 'K7M2Q9XW4B' }), UNCERTAIN],
    [
      'a success with an unknown status',
      () => json(201, { ...bookingBody, status: 'PENDING' }),
      UNCERTAIN,
    ],
    [
      'a success with an invalid timezone',
      () => json(201, { ...bookingBody, timezone: 'Nowhere/Land' }),
      UNCERTAIN,
    ],
    [
      'the proven rollback',
      () => json(503, problem('BOOKING_TEMPORARILY_UNAVAILABLE', 503)),
      ROLLBACK,
    ],
    [
      'the rate limit',
      () => json(429, problem('RATE_LIMITED', 429), { 'Retry-After': '0' }),
      'Твърде много опити. Опитайте по-късно.',
    ],
  ]

  it.each(failures)('keeps the ID and the exact body after %s', async (_name, failure, message) => {
    scriptPosts(failure, () => json(201, bookingBody))
    await openPage()
    await reachReview({ details: { name: '  Иван   Петров ', email: 'Ivan@Example.invalid', note: 'Втора\nредица' } })
    await submit()

    expect(screen.getByRole('alert')).toHaveTextContent(message)
    expect(server.posts).toHaveLength(1)
    // Nothing retried by itself, and no new identity was drawn.
    await flush()
    expect(server.posts).toHaveLength(1)
    expect(randomUuid).toHaveBeenCalledTimes(1)

    await click(button('Опитайте отново'))

    expect(server.posts).toHaveLength(2)
    expect(server.posts[1]!.body).toBe(server.posts[0]!.body)
    expect(parsedBody(server.posts[1])).toMatchObject({ attemptId: ATTEMPT_ONE })
    expect(randomUuid).toHaveBeenCalledTimes(1)
    expect(heading()).toHaveTextContent('Резервацията е потвърдена')
  })

  it('keeps the same attempt through several consecutive uncertain results', async () => {
    scriptPosts(
      () => Promise.reject(new TypeError('Failed to fetch')),
      () => json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503)),
      () => new Response('', { status: 502 }),
      () => json(200, bookingBody),
    )
    await openPage()
    await reachReview()
    await submit()
    await click(button('Опитайте отново'))
    await click(button('Опитайте отново'))
    await click(button('Опитайте отново'))

    expect(new Set(server.posts.map((post) => post.body)).size).toBe(1)
    expect(randomUuid).toHaveBeenCalledTimes(1)
    expect(heading()).toHaveTextContent('Резервацията е потвърдена')
  })
})

describe('an uncertain outcome', () => {
  async function reachUncertain() {
    scriptPosts(() => json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503)))
    await openPage()
    await reachReview()
    await submit()
  }

  it('freezes the review: nothing can be edited and only the same attempt can be repeated', async () => {
    await reachUncertain()

    expect(screen.getByRole('alert')).toHaveTextContent(UNCERTAIN)
    expect(screen.getByRole('alert')).toHaveFocus()
    expect(screen.getByText('Резервацията може вече да е направена. Не започвайте нова.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '+359 88 000 0000' })).toBeInTheDocument()
    for (const edit of screen.getAllByRole('button', { name: /^Промени/ })) {
      expect(edit).toBeDisabled()
    }
    expect(screen.queryByRole('button', { name: 'Назад' })).toBeNull()
    expect(button('Опитайте отново')).toBeEnabled()
    expect(button('Започни отначало')).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Потвърди резервацията' })).toBeNull()
  })

  it('refuses a browser Back and keeps the review, the data and the attempt', async () => {
    await reachUncertain()
    window.history.back()
    // The traversal is undone, so the frozen review is still the shown step.
    await waitFor(() => expect(window.history.state?.spyBooking?.step).toBe(5))
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(screen.getByRole('alert')).toHaveTextContent(UNCERTAIN)
    expect(server.posts).toHaveLength(1)
  })

  it('asks before leaving, warns that the booking may exist and shows the telephone', async () => {
    await reachUncertain()
    await click(button('Към страницата на бизнеса'))

    const dialog = screen.getByRole('alertdialog', { name: 'Резервацията може вече да е направена' })
    expect(dialog).toHaveTextContent('Резервацията може вече да е направена.')
    expect(dialog).toHaveTextContent('Ако напуснете, няма да видите резултата.')
    expect(dialog).toHaveTextContent('Телефон на бизнеса: +359 88 000 0000')
    expect(button('Остани')).toHaveFocus()

    await click(button('Остани'))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(screen.getByRole('alert')).toHaveTextContent(UNCERTAIN)
  })

  it('restarts only after the same warning, and then with a new attempt', async () => {
    await reachUncertain()
    await click(button('Започни отначало'))
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Резервацията може вече да е направена.')
    await click(button('Напусни'))

    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуга'))
    expect(screen.getByText('Стъпка 1 от 5')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('Иван Петров')
    expect(button('Напред')).toBeDisabled()
  })

  it('arms the best-effort page-unload warning for a pending and for an uncertain attempt', async () => {
    const pending = deferred<Response>()
    scriptPosts(() => pending.promise)
    await openPage()
    await reachReview()
    expect(unloadWarned()).toBe(true) // entered data
    await submit()
    expect(unloadWarned()).toBe(true) // pending
    pending.resolve(json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503)))
    await flush()
    expect(unloadWarned()).toBe(true) // uncertain
  })
})

function unloadWarned(): boolean {
  const event = new Event('beforeunload', { cancelable: true })
  act(() => {
    window.dispatchEvent(event)
  })
  return event.defaultPrevented
}

describe('a known rollback and the rate limit', () => {
  it('lets the guest change the choices, which drops the attempt and creates a new one', async () => {
    scriptPosts(
      () => json(503, problem('BOOKING_TEMPORARILY_UNAVAILABLE', 503)),
      () => json(201, bookingBody),
    )
    await openPage()
    await reachReview()
    await submit()
    expect(screen.getByRole('alert')).toHaveTextContent(ROLLBACK)

    await click(screen.getByRole('button', { name: 'Промени данните' }))
    await waitFor(() => expect(heading()).toHaveTextContent('Вашите данни'))
    typeInto('Име', 'Иван Георгиев')
    await returnToReview()
    await submit()

    expect(randomUuid).toHaveBeenCalledTimes(2)
    expect(parsedBody(server.posts[0])).toMatchObject({ attemptId: ATTEMPT_ONE })
    expect(parsedBody(server.posts[1])).toMatchObject({
      attemptId: ATTEMPT_TWO,
      customer: { displayName: 'Иван Георгиев' },
    })
    expect(heading()).toHaveTextContent('Резервацията е потвърдена')
  })

  it('keeps the attempt when the review is only visited and left unchanged', async () => {
    scriptPosts(
      () => json(503, problem('BOOKING_TEMPORARILY_UNAVAILABLE', 503)),
      () => json(201, bookingBody),
    )
    await openPage()
    await reachReview()
    await submit()
    await click(screen.getByRole('button', { name: 'Промени данните' }))
    await waitFor(() => expect(heading()).toHaveTextContent('Вашите данни'))
    await returnToReview()
    expect(screen.getByRole('alert')).toHaveTextContent(ROLLBACK)
    await click(button('Опитайте отново'))
    expect(server.posts[1]!.body).toBe(server.posts[0]!.body)
    expect(randomUuid).toHaveBeenCalledTimes(1)
  })

  it('keeps a frozen attempt frozen when the retry is refused by the rate limit', async () => {
    scriptPosts(
      () => json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503)),
      () => json(429, problem('RATE_LIMITED', 429), { 'Retry-After': '0' }),
      () => json(201, bookingBody),
    )
    await openPage()
    await reachReview()
    await submit()
    await click(button('Опитайте отново'))

    // A refused send says nothing about the first one, so the review stays frozen.
    expect(screen.getByRole('alert')).toHaveTextContent('Твърде много опити. Опитайте по-късно.')
    for (const edit of screen.getAllByRole('button', { name: /^Промени/ })) {
      expect(edit).toBeDisabled()
    }
    await click(button('Опитайте отново'))
    expect(new Set(server.posts.map((post) => post.body)).size).toBe(1)
    expect(randomUuid).toHaveBeenCalledTimes(1)
  })
})

describe('Retry-After with controlled time', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    vi.setSystemTime(new Date('2026-10-07T08:00:00Z'))
  })

  async function advance(milliseconds: number) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(milliseconds)
    })
  }

  it('keeps the retry unavailable until the 429 wait has passed, and never posts by itself', async () => {
    scriptPosts(
      () => json(429, problem('RATE_LIMITED', 429), { 'Retry-After': '30' }),
      () => json(201, bookingBody),
    )
    await openPage()
    await reachReview()
    await submit()

    expect(server.posts).toHaveLength(1)
    expect(button('Опитайте отново')).toBeDisabled()
    await advance(29_999)
    expect(button('Опитайте отново')).toBeDisabled()
    expect(server.posts).toHaveLength(1)
    await advance(1)
    // The wait is over, but nothing was sent: the guest decides.
    expect(button('Опитайте отново')).toBeEnabled()
    await advance(120_000)
    expect(server.posts).toHaveLength(1)

    await click(button('Опитайте отново'))
    expect(server.posts).toHaveLength(2)
    expect(server.posts[1]!.body).toBe(server.posts[0]!.body)
  })

  it('honours the advisory two seconds of the 503 results', async () => {
    scriptPosts(
      () => json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503), { 'Retry-After': '2' }),
      () => json(201, bookingBody),
    )
    await openPage()
    await reachReview()
    await submit()

    expect(button('Опитайте отново')).toBeDisabled()
    await advance(1_999)
    expect(button('Опитайте отново')).toBeDisabled()
    await advance(1)
    expect(button('Опитайте отново')).toBeEnabled()
  })

  it('reads an HTTP-date Retry-After against the clock and ignores an invalid value', async () => {
    scriptPosts(
      () =>
        json(429, problem('RATE_LIMITED', 429), {
          'Retry-After': new Date(Date.now() + 10_000).toUTCString(),
        }),
      () => json(429, problem('RATE_LIMITED', 429), { 'Retry-After': 'soon' }),
    )
    await openPage()
    await reachReview()
    await submit()
    expect(button('Опитайте отново')).toBeDisabled()
    await advance(10_000)
    expect(button('Опитайте отново')).toBeEnabled()

    await click(button('Опитайте отново'))
    // An unreadable value imposes no wait.
    expect(button('Опитайте отново')).toBeEnabled()
  })
})

describe('successful responses', () => {
  it('shows a replay of a confirmed booking as confirmed', async () => {
    scriptPosts(() => json(200, bookingBody))
    await openPage()
    await reachReview()
    await submit()
    expect(heading()).toHaveTextContent('Резервацията е потвърдена')
    // One heading says it; there is no second success sentence and no status panel.
    expect(document.body.textContent).not.toContain('Тази резервация вече беше потвърдена')
    expect(document.body.textContent).not.toContain('Часът е запазен')
    expect(document.querySelector('.status-success')).toBeNull()
    expect(screen.getAllByRole('heading')).toHaveLength(1)
  })

  it('shows a replay of a cancelled reservation as cancelled, never as a confirmed booking', async () => {
    scriptPosts(() => json(200, { ...bookingBody, status: 'CANCELLED' }))
    await openPage()
    await reachReview()
    await submit()

    expect(heading()).toHaveTextContent('Резервацията е отменена')
    expect(screen.getByText('Тази резервация е отменена и часът не е запазен.')).toBeInTheDocument()
    expect(document.querySelector('.booking-facts')).not.toHaveTextContent('Статус')
    expect(document.body.textContent).not.toContain('Резервацията е потвърдена')
    expect(document.body.textContent).not.toContain('Часът е запазен')
    expect(document.querySelector('.status-success')).toBeNull()
  })

  it('promises nothing the backend does not do: no lookup, cancellation, payment or notification', async () => {
    await openPage()
    await reachReview()
    await submit()
    const text = document.body.textContent ?? ''
    expect(text).not.toMatch(/плащ|отмяна онлайн|изпратихме|ще получите|имейл потвърждение/i)
    expect(screen.getByText(/За промяна или отмяна се свържете с бизнеса/)).toBeInTheDocument()
    expect(screen.getByText('Запазете данните за резервацията.')).toBeInTheDocument()
    expect(text).not.toMatch(/Не изпращаме|по имейл или SMS/)
    expect(screen.queryByRole('link', { name: /отмени|провери/i })).toBeNull()
  })
})

describe('rejections', () => {
  it('a slot that is no longer free: a distinct message, a new choice and a new attempt', async () => {
    scriptPosts(
      () => json(409, problem('BOOKING_SLOT_UNAVAILABLE', 409)),
      () => json(201, bookingBody),
    )
    await openPage()
    await reachReview()
    await submit()

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Избраният час вече не е свободен. Изберете друг час.',
    )
    // Sending the same choices again cannot help, so the confirm action is not offered.
    expect(screen.queryByRole('button', { name: /Потвърди|Опитайте отново/ })).toBeNull()

    await click(button('Избор на друг час'))
    await waitFor(() => expect(heading()).toHaveTextContent('Дата и час'))
    expect(screen.getByRole('radio', { name: '10:00' })).not.toBeChecked()
    expect(button('Напред')).toBeDisabled()
    // The offer is read again, so a taken slot is never shown from an earlier answer.
    expect(server.of('GET', '/availability').length).toBeGreaterThanOrEqual(2)

    await pickSlot('11:00')
    // Only the invalidated selection was asked for again; the details were kept.
    await returnToReview()
    await submit()
    expect(randomUuid).toHaveBeenCalledTimes(2)
    expect(parsedBody(server.posts[1])).toMatchObject({
      attemptId: ATTEMPT_TWO,
      start: '2026-10-07T11:00:00+03:00',
    })
  })

  it('a Service that is no longer available asks for another Service', async () => {
    scriptPosts(() => json(409, problem('BOOKING_SERVICE_UNAVAILABLE', 409)))
    await openPage()
    await reachReview()
    await submit()
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Избраната услуга вече не е налична. Изберете друга услуга.',
    )
    await click(button('Избор на услуга'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуга'))
    expect(screen.getByRole('radio', { name: /Подстригване/ })).not.toBeChecked()
  })

  it('a StaffMember that is no longer available offers another or no preference', async () => {
    scriptPosts(() => json(409, problem('BOOKING_STAFF_UNAVAILABLE', 409)))
    await openPage()
    await reachReview({ staff: 'Мария Иванова' })
    await submit()
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Избраният служител вече не е наличен. Изберете друг или „Без предпочитание“.',
    )
    await click(button('Избор на служител'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на служител'))
    expect(screen.getByRole('radio', { name: /Без предпочитание/ })).toBeChecked()
    // The StaffMembers are loaded again.
    expect(server.of('GET', '/booking-options').length).toBeGreaterThanOrEqual(2)
  })

  it('an identity conflict says the telephone and the email do not match, keeps the details and offers the way back', async () => {
    scriptPosts(() => json(409, problem('BOOKING_NOT_COMPLETED_ONLINE', 409)))
    await openPage()
    await reachReview()
    await submit()
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent(
      'Телефонът и имейлът не съответстват. Проверете ги или въведете само единия контакт.',
    )
    // The Business telephone stays available; nothing says which contact matched or that a Customer exists.
    expect(alert).toHaveTextContent('+359 88 000 0000')
    expect(alert.textContent).not.toMatch(/клиент|съществува|намерен|свържете се с бизнеса/i)
    expect(screen.queryByRole('button', { name: /Потвърди|Опитайте отново/ })).toBeNull()
    expect(button('Промяна на данните')).toBeEnabled()
    // The entered details are kept for the correction.
    await click(button('Промяна на данните'))
    await waitFor(() => expect(heading()).toHaveTextContent('Вашите данни'))
    expect(screen.getByLabelText('Име')).toHaveValue('Иван Петров')
    expect(screen.getByLabelText('Телефон')).toHaveValue('0888 123 456')
  })

  it('a mismatch is unresolved, not a rejection: the attempt stays frozen and only the warned restart is offered', async () => {
    scriptPosts(() => json(409, problem('BOOKING_ATTEMPT_MISMATCH', 409)))
    await openPage()
    await reachReview()
    await submit()
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Тази заявка вече е използвана с други данни. Започнете резервацията отново.',
    )
    // The same bytes would meet the same mismatch, and no new attempt may be drawn.
    expect(screen.queryByRole('button', { name: /Потвърди|Опитайте отново/ })).toBeNull()
    for (const edit of screen.getAllByRole('button', { name: /^Промени/ })) expect(edit).toBeDisabled()
    expect(randomUuid).toHaveBeenCalledTimes(1)

    await click(button('Започни отначало'))
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Резервацията може вече да е направена.')
    await click(button('Напусни'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуга'))
    expect(document.body.textContent).not.toContain('Иван Петров')
    expect(server.posts).toHaveLength(1)
  })

  it('a validation rejection shows the named fields inline and focuses the first', async () => {
    scriptPosts(() =>
      json(
        400,
        problem('VALIDATION_ERROR', 400, {
          fieldErrors: {
            phone: 'Въведеният телефонен номер не е валиден.',
            note: 'Бележката може да съдържа най-много 500 знака.',
            unknownField: 'Не трябва да се показва.',
          },
        }),
      ),
    )
    await openPage()
    await reachReview()
    await submit()
    expect(screen.getByRole('alert')).toHaveTextContent('Проверете въведените данни.')
    await click(button('Промяна на данните'))
    await waitFor(() => expect(heading()).toHaveTextContent('Вашите данни'))

    const phone = screen.getByLabelText('Телефон')
    expect(phone).toHaveFocus()
    expect(phone).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('Въведеният телефонен номер не е валиден.')).toBeInTheDocument()
    expect(screen.getByText('Бележката може да съдържа най-много 500 знака.')).toBeInTheDocument()
    expect(screen.queryByText('Не трябва да се показва.')).toBeNull()
    expect(screen.getByLabelText(/Бележка/)).toHaveAttribute('aria-invalid', 'true')

    // Editing a field drops its backend message.
    typeInto('Телефон', '0888 123 457')
    expect(screen.queryByText('Въведеният телефонен номер не е валиден.')).toBeNull()
  })

  it('maps a backend displayName error to the public name wording and never shows the backend sentence', async () => {
    scriptPosts(() =>
      json(
        400,
        problem('VALIDATION_ERROR', 400, {
          fieldErrors: { displayName: 'Въведете име до 200 знака.' },
        }),
      ),
    )
    await openPage()
    await reachReview()
    await submit()
    await click(button('Промяна на данните'))
    await waitFor(() => expect(heading()).toHaveTextContent('Вашите данни'))

    const name = screen.getByLabelText('Име')
    expect(name).toHaveFocus()
    expect(name).toHaveAttribute('aria-invalid', 'true')
    // The name is present, so the invalid-name sentence is shown, without a number.
    expect(screen.getByText('Проверете въведеното име.')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('до 200 знака')
    expect(name).toHaveValue('Иван Петров')
  })

  it('the unavailable Business ends the journey with the unavailable page', async () => {
    scriptPosts(() => json(404, problem('BUSINESS_PAGE_UNAVAILABLE', 404)))
    await openPage()
    await reachReview()
    await submit()
    expect(heading()).toHaveTextContent('Страницата не е налична')
    expect(document.body.textContent).not.toContain('Иван Петров')
  })

  it.each([
    ['an oversized request', 413, 'REQUEST_TOO_LARGE'],
    ['an unsupported media type', 415, 'UNSUPPORTED_MEDIA_TYPE'],
  ])('%s is a definite failure: the generic message and a new attempt on the next send', async (_name, status, code) => {
    scriptPosts(
      () => json(status, problem(code, status)),
      () => json(201, bookingBody),
    )
    await openPage()
    await reachReview()
    await submit()
    expect(screen.getByRole('alert')).toHaveTextContent('Възникна неочаквана грешка.')
    await click(button('Потвърди резервацията'))
    expect(parsedBody(server.posts[1])).toMatchObject({ attemptId: ATTEMPT_TWO })
  })

  it.each([
    ['an unknown code on a 400', () => json(400, problem('SOMETHING_ELSE', 400))],
    ['a refusal by a proxy', () => json(403, problem('ACCESS_DENIED', 403))],
    ['a documented code on the wrong status', () => json(400, problem('BOOKING_SLOT_UNAVAILABLE', 400))],
    ['a 4xx without a readable body', () => new Response('<html>', { status: 404 })],
  ])('%s cannot prove that nothing was booked: the first send stays unresolved and frozen', async (_name, answer) => {
    scriptPosts(answer, () => json(201, bookingBody))
    await openPage()
    await reachReview()
    await submit()
    expect(screen.getByRole('alert')).toHaveTextContent(UNCERTAIN)
    for (const edit of screen.getAllByRole('button', { name: /^Промени/ })) expect(edit).toBeDisabled()
    await click(button('Опитайте отново'))
    expect(server.posts[1]!.body).toBe(server.posts[0]!.body)
    expect(randomUuid).toHaveBeenCalledTimes(1)
  })
})

// After an uncertain send no later answer except a success may resolve the attempt: the same ID and
// the same bytes are sent, nothing can be edited, and no new attempt is ever drawn.
describe('an uncertain attempt followed by another answer', () => {
  const laterAnswers: [string, PostResponse, RegExp][] = [
    ['a proven rollback', () => json(503, problem('BOOKING_TEMPORARILY_UNAVAILABLE', 503)), /Не получихме потвърждение/],
    ['a rate limit', () => json(429, problem('RATE_LIMITED', 429), { 'Retry-After': '0' }), /Твърде много опити/],
    ['a slot rejection', () => json(409, problem('BOOKING_SLOT_UNAVAILABLE', 409)), /Не получихме потвърждение/],
    ['a Service rejection', () => json(409, problem('BOOKING_SERVICE_UNAVAILABLE', 409)), /Не получихме потвърждение/],
    ['a StaffMember rejection', () => json(409, problem('BOOKING_STAFF_UNAVAILABLE', 409)), /Не получихме потвърждение/],
    ['an identity conflict', () => json(409, problem('BOOKING_NOT_COMPLETED_ONLINE', 409)), /Не получихме потвърждение/],
    [
      'a validation rejection',
      () => json(400, problem('VALIDATION_ERROR', 400, { fieldErrors: { phone: 'Телефон.' } })),
      /Не получихме потвърждение/,
    ],
    ['an oversized-request rejection', () => json(413, problem('REQUEST_TOO_LARGE', 413)), /Не получихме потвърждение/],
    ['an unknown 4xx', () => json(403, problem('ACCESS_DENIED', 403)), /Не получихме потвърждение/],
    ['an unavailable Business', () => json(404, problem('BUSINESS_PAGE_UNAVAILABLE', 404)), /Не получихме потвърждение/],
    ['a mismatch', () => json(409, problem('BOOKING_ATTEMPT_MISMATCH', 409)), /вече е използвана с други данни/],
  ]

  it.each(laterAnswers)('%s keeps the attempt, the bytes and the freeze', async (_name, answer, message) => {
    scriptPosts(
      () => Promise.reject(new TypeError('Failed to fetch')),
      answer,
      () => json(200, bookingBody),
    )
    await openPage()
    await reachReview({ details: { name: 'Иван Петров', note: 'Бележка' } })
    await submit()
    expect(screen.getByRole('alert')).toHaveTextContent(UNCERTAIN)
    await click(button('Опитайте отново'))

    // Still on the frozen review, with the same message family, nothing editable, the page still mounted.
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(screen.getByRole('alert')).toHaveTextContent(message)
    for (const edit of screen.getAllByRole('button', { name: /^Промени/ })) expect(edit).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Назад' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Потвърди резервацията' })).toBeNull()
    expect(randomUuid).toHaveBeenCalledTimes(1)
    expect(unloadWarned()).toBe(true)
    // The leave warning is still in force.
    await click(button('Към страницата на бизнеса'))
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Резервацията може вече да е направена.')
    await click(button('Остани'))

    // A mismatch would only meet itself again; every other state resolves on the next valid success.
    if (message.source.includes('други данни')) {
      expect(screen.queryByRole('button', { name: 'Опитайте отново' })).toBeNull()
      expect(server.posts).toHaveLength(2)
      return
    }
    await click(button('Опитайте отново'))
    expect(server.posts).toHaveLength(3)
    expect(new Set(server.posts.map((post) => post.body)).size).toBe(1)
    expect(parsedBody(server.posts[2])).toMatchObject({ attemptId: ATTEMPT_ONE })
    expect(heading()).toHaveTextContent('Резервацията е потвърдена')
  })

  it('an unavailable Business after an uncertain send does not unmount the journey or drop the attempt', async () => {
    scriptPosts(
      () => json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503)),
      () => json(404, problem('BUSINESS_PAGE_UNAVAILABLE', 404)),
    )
    await openPage()
    await reachReview()
    await submit()
    await click(button('Опитайте отново'))
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(screen.getByRole('alert')).toHaveTextContent('Страницата на бизнеса в момента не е налична.')
    expect(screen.getByRole('alert')).toHaveTextContent('+359 88 000 0000')
    expect(document.querySelector('.booking-summary')).toHaveTextContent('Иван Петров')
    expect(button('Опитайте отново')).toBeEnabled()
  })

  it('a successful replay resolves the uncertainty', async () => {
    scriptPosts(
      () => json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503)),
      () => json(409, problem('BOOKING_SLOT_UNAVAILABLE', 409)),
      () => json(200, bookingBody),
    )
    await openPage()
    await reachReview()
    await submit()
    await click(button('Опитайте отново'))
    await click(button('Опитайте отново'))
    expect(heading()).toHaveTextContent('Резервацията е потвърдена')
    expect(unloadWarned()).toBe(false)
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('a cancelled replay resolves the uncertainty and is shown as cancelled', async () => {
    scriptPosts(
      () => json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503)),
      () => json(503, problem('BOOKING_TEMPORARILY_UNAVAILABLE', 503)),
      () => json(200, { ...bookingBody, status: 'CANCELLED' }),
    )
    await openPage()
    await reachReview()
    await submit()
    await click(button('Опитайте отново'))
    await click(button('Опитайте отново'))
    expect(heading()).toHaveTextContent('Резервацията е отменена')
    expect(document.querySelector('.status-success')).toBeNull()
    expect(unloadWarned()).toBe(false)
  })

  it('leaves a frozen attempt only through the warned restart, after which a new attempt may be drawn', async () => {
    scriptPosts(
      () => json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503)),
      () => json(409, problem('BOOKING_SLOT_UNAVAILABLE', 409)),
      () => json(201, bookingBody),
    )
    await openPage()
    await reachReview()
    await submit()
    await click(button('Опитайте отново'))
    await click(button('Започни отначало'))
    await click(button('Напусни'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуга'))
    await reachReviewFromStart()
    await submit()
    expect(randomUuid).toHaveBeenCalledTimes(2)
    expect(parsedBody(server.posts[2])).toMatchObject({ attemptId: ATTEMPT_TWO })
  })
})

describe('the Service reference of the request', () => {
  it('sends the Service id of the profile and nothing the client may not decide', async () => {
    await openPage()
    await startFromColour()
    await next()
    await pickSlot('10:00')
    await next()
    typeInto('Име', 'Иван Петров')
    typeInto('Телефон', '0888 123 456')
    await next()
    await submit()
    const body = parsedBody(server.posts[0])
    expect(body.serviceId).toBe(SERVICE_COLOUR)
    for (const forbidden of ['businessId', 'price', 'duration', 'end', 'status', 'timezone', 'source']) {
      expect(body).not.toHaveProperty(forbidden)
    }
  })
})

async function reachReviewFromStart() {
  await click(screen.getByRole('radio', { name: /Подстригване/ }))
  await next()
  await next()
  await pickSlot('10:00')
  await next()
  typeInto('Име', 'Иван Петров')
  typeInto('Телефон', '0888 123 456')
  await next()
}

// «Към прегледа» goes forward in the browser history, which a browser (and jsdom) completes after the click.
async function returnToReview() {
  await click(button('Към прегледа'))
  await waitFor(() => expect(heading()).toHaveTextContent('Преглед и потвърждение'))
}

async function startFromColour() {
  await startFromService('Боядисване')
}
