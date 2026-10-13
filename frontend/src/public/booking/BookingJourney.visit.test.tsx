import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ATTEMPT_ONE,
  SLUG,
  STAFF_GEORGI,
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
  optionsBody,
  parsedBody,
  pickSlot,
  problem,
  profileBody,
  selectionsOf,
  startFromHero,
  startFromServices,
  stubAttemptIds,
  submit,
  tick,
  type FakeServer,
} from './testSupport'

// The multi-Service journey of ADR-0027 (phase M5): the checkbox selection and its limits, the exact
// integer-cents totals, the downstream invalidation, the intersection states, the whole-visit review,
// the new request shape with its canonical ordering, the visit response, and the review-change recovery.

// Profile order is deliberately NOT the identifier order: canonical (unsigned) order is
// EPSILON, BETA, DELTA, GAMMA, ALFA, ZETA.
const ALFA = '90909090-9090-4909-8909-909090909090'
const BETA = '10101010-1010-4101-8101-101010101010'
const GAMMA = '80000000-0000-4000-8000-000000000000'
const DELTA = '7fffffff-ffff-4fff-bfff-ffffffffffff'
const EPSILON = '00000000-0000-4000-8000-000000000001'
const ZETA = 'ffffffff-ffff-4fff-bfff-ffffffffffff'
const ATTEMPT_TWO = '6c1a8d3f-2b4e-4f90-8c11-7d2e4a5b6c02'

type Spec = { id: string; name: string; durationMinutes: number; price: number }

const CATALOG: Spec[] = [
  { id: ALFA, name: 'Алфа', durationMinutes: 45, price: 25 },
  { id: BETA, name: 'Бета', durationMinutes: 120, price: 80.5 },
  { id: GAMMA, name: 'Гама', durationMinutes: 30, price: 10.1 },
  { id: DELTA, name: 'Делта', durationMinutes: 15, price: 0.2 },
  { id: EPSILON, name: 'Епсилон', durationMinutes: 60, price: 33.33 },
  { id: ZETA, name: 'Зета', durationMinutes: 10, price: 1 },
]

let server: FakeServer
let catalog: Spec[]

function profile(specs: Spec[]) {
  return {
    ...profileBody,
    services: specs.map((spec) => ({ ...spec, description: null })),
  }
}

/** Serves the given Services as the public profile; later calls change what a re-read returns. */
function serveCatalog(specs: Spec[]) {
  catalog = specs
  server.on((request) =>
    request.method === 'GET' && request.path === `/api/public/businesses/${SLUG}`
      ? json(200, profile(catalog))
      : undefined,
  )
}

beforeEach(() => {
  window.history.replaceState(null, '', `/${SLUG}`)
  server = installServer()
  serveCatalog(CATALOG)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function checkbox(name: string) {
  return screen.getByRole('checkbox', { name: new RegExp(name) }) as HTMLInputElement
}

function summary() {
  return document.querySelector('.booking-selection-summary') as HTMLElement | null
}

function reviewSummary() {
  return document.querySelector('.booking-summary') as HTMLElement
}

function visitBody(lines: { name: string; durationMinutes: number; price: number }[], extra = {}) {
  const minutes = lines.reduce((sum, line) => sum + line.durationMinutes, 0)
  const cents = lines.reduce((sum, line) => sum + Math.round(line.price * 100), 0)
  return {
    ...bookingBody,
    services: lines,
    totalDurationMinutes: minutes,
    totalPrice: cents / 100,
    ...extra,
  }
}

function scriptPosts(...answers: (() => Response | Promise<Response>)[]) {
  const queue = [...answers]
  server.on((request) => (request.method === 'POST' ? (queue.shift() ?? (() => json(201, bookingBody)))() : undefined))
}

// «Към прегледа» goes forward in the browser history, which a browser (and jsdom) completes after the click.
async function returnToReview() {
  await click(button('Към прегледа'))
  await waitFor(() => expect(heading()).toHaveTextContent('Преглед и потвърждение'))
}

async function toReview(names: string[], options: { staff?: string; slot?: string } = {}) {
  await openPage()
  await startFromServices(...names)
  if (options.staff) await chooseStaff(options.staff)
  await next()
  await pickSlot(options.slot ?? '10:00')
  await next()
  await fillDetails()
  await next()
}

describe('the Service selection step', () => {
  it('is a labelled group of real checkboxes with nothing preselected', async () => {
    await openPage()
    await startFromHero()

    expect(heading()).toHaveTextContent('Избор на услуги')
    const group = screen.getByRole('group', { name: 'Избор на услуги' })
    const boxes = within(group).getAllByRole('checkbox') as HTMLInputElement[]
    expect(boxes).toHaveLength(6)
    expect(boxes.every((box) => box.type === 'checkbox' && !box.checked)).toBe(true)
    expect(screen.queryAllByRole('radio')).toHaveLength(0)
    // The public-profile order, not the identifier order.
    expect(boxes.map((box) => box.value)).toEqual([ALFA, BETA, GAMMA, DELTA, EPSILON, ZETA])
    expect(button('Напред')).toBeDisabled()
    // The group is described by the limit hint and the summary, so both reach assistive technology.
    const described = group.getAttribute('aria-describedby')!.split(' ')
    expect(described.map((id) => document.getElementById(id)?.textContent)).toEqual([
      'Най-много 5 услуги и общо 8 часа за едно посещение.',
      'Изберете поне една услуга.',
    ])
  })

  it('uses native focusable checkboxes, which a keyboard toggles with Space (a click)', async () => {
    await openPage()
    await startFromHero()

    for (const box of screen.getAllByRole('checkbox') as HTMLInputElement[]) {
      expect(box.tagName).toBe('INPUT')
      expect(box).not.toHaveAttribute('tabindex', '-1')
      expect(box).not.toBeDisabled()
    }
    checkbox('Алфа').focus()
    expect(checkbox('Алфа')).toHaveFocus()
    // Space on a focused native checkbox dispatches a click; that is the whole operation.
    await click(checkbox('Алфа'))
    expect(checkbox('Алфа')).toBeChecked()
    await click(checkbox('Алфа'))
    expect(checkbox('Алфа')).not.toBeChecked()
  })

  it('allows one, two and five Services', async () => {
    await openPage()
    await startFromHero()

    await tick('Алфа')
    expect(button('Напред')).toBeEnabled()
    await tick('Бета')
    expect(button('Напред')).toBeEnabled()
    await tick('Гама')
    await tick('Делта')
    await tick('Епсилон')
    expect((screen.getAllByRole('checkbox') as HTMLInputElement[]).filter((box) => box.checked)).toHaveLength(5)
    expect(button('Напред')).toBeEnabled()
  })

  it('shows the selected names, the summed duration and the exact total before «Напред»', async () => {
    await openPage()
    await startFromHero()
    expect(summary()).toBeNull()

    await tick('Алфа')
    expect(summary()).toHaveTextContent('Алфа')
    expect(document.querySelector('.booking-selection-total')).toHaveTextContent('Общо: 45 мин. · 25.00 €')
    await tick('Бета')
    // The names stay in the public-profile order, whatever order they were ticked in.
    expect(Array.from(summary()!.querySelectorAll('li')).map((item) => item.textContent)).toEqual([
      'Алфа',
      'Бета',
    ])
    expect(document.querySelector('.booking-selection-total')).toHaveTextContent('Общо: 165 мин. · 105.50 €')
    const total = document.querySelector('.booking-selection-total')!
    expect(total).toHaveAttribute('role', 'status')
  })

  it('adds the prices in integer cents, so a decimal sum cannot drift', async () => {
    // 0.1 + 0.7 + 0.2 + 0.3 + 0.4 is 1.7000000000000002 as decimals; as cents it is exactly 170.
    expect(0.1 + 0.7 + 0.2 + 0.3 + 0.4).not.toBe(1.7)
    serveCatalog(
      [0.1, 0.7, 0.2, 0.3, 0.4].map((price, index) => ({
        id: CATALOG[index]!.id,
        name: CATALOG[index]!.name,
        durationMinutes: 10,
        price,
      })),
    )
    await openPage()
    await startFromHero()
    for (const name of ['Алфа', 'Бета', 'Гама', 'Делта', 'Епсилон']) await tick(name)

    expect(document.querySelector('.booking-selection-total')).toHaveTextContent('Общо: 50 мин. · 1.70 €')
  })

  it('supports five maximum Service prices exactly and formats them like a single price', async () => {
    const maximum = ['Алфа', 'Бета', 'Гама', 'Делта', 'Епсилон'].map((name, index) => ({
      id: CATALOG[index]!.id,
      name,
      durationMinutes: 96,
      price: 9999999999.99,
    }))
    serveCatalog([...maximum, { id: ZETA, name: 'Зета', durationMinutes: 1, price: 0 }])
    await openPage()
    await startFromHero()
    expect(screen.getAllByText('96 мин. · 9999999999.99 €')).toHaveLength(5)
    for (const name of ['Алфа', 'Бета', 'Гама', 'Делта', 'Епсилон']) await tick(name)

    expect(document.querySelector('.booking-selection-total')).toHaveTextContent(
      'Общо: 480 мин. · 49999999999.95 €',
    )
    expect(button('Напред')).toBeEnabled()
  })

  it('prevents a sixth Service with a visible reason and keeps every selected one removable', async () => {
    await openPage()
    await startFromHero()
    for (const name of ['Алфа', 'Гама', 'Делта', 'Епсилон', 'Зета']) await tick(name)

    // Only the unselected, non-addable option is disabled, and it says why in text.
    expect(checkbox('Бета')).toBeDisabled()
    expect(within(checkbox('Бета').closest('label')!).getByText(/най-много 5 услуги/)).toBeInTheDocument()
    const reason = checkbox('Бета').getAttribute('aria-describedby')!
    expect(document.getElementById(reason)).toHaveTextContent('Не може да се добави: най-много 5 услуги.')
    fireEvent.click(checkbox('Бета'))
    await flush()
    expect(checkbox('Бета')).not.toBeChecked()
    for (const name of ['Алфа', 'Гама', 'Делта', 'Епсилон', 'Зета']) {
      expect(checkbox(name)).toBeEnabled()
      expect(checkbox(name)).toBeChecked()
    }
    // Removing one makes the others addable again.
    await tick('Зета')
    expect(checkbox('Бета')).toBeEnabled()
  })

  it('accepts exactly 480 minutes and prevents 481', async () => {
    serveCatalog([
      { id: ALFA, name: 'Алфа', durationMinutes: 400, price: 10 },
      { id: BETA, name: 'Бета', durationMinutes: 80, price: 10 },
      { id: GAMMA, name: 'Гама', durationMinutes: 81, price: 10 },
    ])
    await openPage()
    await startFromHero()
    await tick('Алфа')

    expect(checkbox('Бета')).toBeEnabled()
    expect(checkbox('Гама')).toBeDisabled()
    expect(document.getElementById(checkbox('Гама').getAttribute('aria-describedby')!)).toHaveTextContent(
      'Не може да се добави: общо най-много 8 часа.',
    )
    await tick('Бета')
    expect(document.querySelector('.booking-selection-total')).toHaveTextContent('Общо: 480 мин.')
    expect(button('Напред')).toBeEnabled()
    // Both selected Services remain removable even though nothing more fits.
    expect(checkbox('Алфа')).toBeEnabled()
    expect(checkbox('Бета')).toBeEnabled()
    expect(checkbox('Гама')).toBeDisabled()
  })

  it('does not show a validation panel while the limit can be communicated beside the options', async () => {
    await openPage()
    await startFromHero()
    for (const name of ['Алфа', 'Гама', 'Делта', 'Епсилон', 'Зета']) await tick(name)

    expect(screen.queryByRole('alert')).toBeNull()
    expect(document.querySelector('.status-error')).toBeNull()
  })

  it('disables the whole group while a booking is being sent', async () => {
    const pending = deferred<Response>()
    scriptPosts(() => pending.promise)
    await toReview(['Алфа'])
    await submit()

    await click(button('Промени услугите'))
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    pending.resolve(json(201, visitBody([{ name: 'Алфа', durationMinutes: 45, price: 25 }])))
    await flush()
  })
})

describe('the selection is a set', () => {
  it('requests the same options URL and the same booking bytes whatever order the boxes were ticked in', async () => {
    stubAttemptIds(ATTEMPT_ONE, ATTEMPT_ONE)
    await toReview(['Алфа', 'Гама', 'Бета'])
    const firstOptions = server.of('GET', '/booking-options').at(-1)!
    await submit()
    const firstBody = server.posts[0]!.body
    cleanup()
    window.history.replaceState(null, '', `/${SLUG}`)

    await toReview(['Бета', 'Алфа', 'Гама'])
    const secondOptions = server.of('GET', '/booking-options').at(-1)!
    await submit()

    expect(secondOptions.url.href).toBe(firstOptions.url.href)
    expect(server.posts[1]!.body).toBe(firstBody)
  })

  it('sends the repeated canonical serviceId parameters, never a name or a price', async () => {
    await openPage()
    await startFromServices('Алфа', 'Бета', 'Гама')

    const options = server.of('GET', '/booking-options')[0]!
    // Canonical (unsigned) order: BETA (0x10) < GAMMA (0x80) < ALFA (0x90), unlike the profile order.
    expect(options.url.searchParams.getAll('serviceId')).toEqual([BETA, GAMMA, ALFA])
    expect(options.url.search).toMatch(/^\?serviceId=[0-9a-f-]{36}(&serviceId=[0-9a-f-]{36})*$/)
    await next()
    const availability = server.of('GET', '/availability')[0]!
    expect(availability.url.searchParams.getAll('serviceId')).toEqual([BETA, GAMMA, ALFA])
    expect(availability.url.searchParams.get('date')).toBe('2026-10-07')
    expect(availability.path).toBe('/api/public/businesses/example-studio/availability')
    for (const request of server.requests) expect(request.credentials).toBe('omit')
  })

  it('keeps the visible lists in the public-profile order and the request in the canonical order', async () => {
    stubAttemptIds(ATTEMPT_ONE)
    await toReview(['Гама', 'Алфа', 'Епсилон'])

    // Review: profile order (ALFA, GAMMA, EPSILON).
    const lines = Array.from(reviewSummary().querySelectorAll('.booking-summary-services li strong, .booking-summary-services .booking-summary-strong'))
    expect(lines.map((line) => line.textContent)).toEqual(['Алфа', 'Гама', 'Епсилон'])
    await submit()

    // Request: canonical order (EPSILON 0x00, GAMMA 0x80, ALFA 0x90).
    const body = parsedBody(server.posts[0]) as { services: { serviceId: string }[] }
    expect(body.services.map((line) => line.serviceId)).toEqual([EPSILON, GAMMA, ALFA])
  })

  it('sends exactly the new request shape with the reviewed facts, also for one Service', async () => {
    stubAttemptIds(ATTEMPT_ONE)
    await toReview(['Бета'])
    await submit()

    const post = server.posts[0]!
    expect(Object.keys(parsedBody(post))).toEqual([
      'attemptId',
      'services',
      'staffMemberId',
      'start',
      'customer',
      'note',
    ])
    expect(parsedBody(post)).toEqual({
      attemptId: ATTEMPT_ONE,
      services: [{ serviceId: BETA, reviewedDurationMinutes: 120, reviewedPriceCents: 8050 }],
      staffMemberId: null,
      start: '2026-10-07T10:00:00+03:00',
      customer: { displayName: 'Иван Петров', phone: '0888 123 456', email: null },
      note: null,
    })
    expect(post.credentials).toBe('omit')
  })

  it('claims the exact cents of five maximum prices and sends one POST for the whole visit', async () => {
    const maximum = ['Алфа', 'Бета', 'Гама', 'Делта', 'Епсилон'].map((name, index) => ({
      id: CATALOG[index]!.id,
      name,
      durationMinutes: 96,
      price: 9999999999.99,
    }))
    serveCatalog(maximum)
    stubAttemptIds(ATTEMPT_ONE)
    await toReview(['Алфа', 'Бета', 'Гама', 'Делта', 'Епсилон'])
    expect(reviewSummary()).toHaveTextContent('Общо: 480 мин. · 49999999999.95 €')
    await submit()

    expect(server.posts).toHaveLength(1)
    const body = parsedBody(server.posts[0]) as { services: Record<string, unknown>[] }
    expect(body.services).toHaveLength(5)
    for (const line of body.services) {
      expect(line.reviewedDurationMinutes).toBe(96)
      expect(line.reviewedPriceCents).toBe(999_999_999_999)
      expect(Number.isInteger(line.reviewedPriceCents)).toBe(true)
    }
  })
})

describe('changing the selection', () => {
  it('clears the StaffMember, the date and the time but keeps the Customer details', async () => {
    await toReview(['Алфа'], { staff: 'Мария Иванова', slot: '11:00' })
    expect(reviewSummary()).toHaveTextContent('Мария Иванова')

    await click(button('Промени услугите'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    await tick('Гама')
    expect(screen.queryByRole('button', { name: 'Към прегледа' })).toBeNull()
    await next()
    expect(heading()).toHaveTextContent('Избор на служител')
    expect(screen.getByRole('radio', { name: /Без предпочитание/ })).toBeChecked()
    expect(screen.getByRole('radio', { name: /Мария Иванова/ })).not.toBeChecked()
    await next()
    expect(screen.getByRole('radio', { name: '10:00' })).not.toBeChecked()
    expect(screen.queryByRole('radio', { checked: true })).toBeNull()
    expect(button('Напред')).toBeDisabled()
    await pickSlot('10:00')
    // The details are still valid, so the way back to the review is direct (approved edit mode).
    await returnToReview()
    expect(reviewSummary()).toHaveTextContent('Иван Петров')
    expect(reviewSummary()).toHaveTextContent('0888 123 456')
    expect(reviewSummary()).toHaveTextContent('Алфа')
    expect(reviewSummary()).toHaveTextContent('Гама')
    expect(reviewSummary()).toHaveTextContent('Без предпочитание')
  })

  it('does not keep a StaffMember that exists in both answers', async () => {
    await openPage()
    await startFromServices('Алфа')
    await chooseStaff('Мария Иванова')
    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    await tick('Гама')
    await next()

    // Both option answers list Мария, yet the guest must choose again for the new set.
    expect(screen.getByRole('radio', { name: /Без предпочитание/ })).toBeChecked()
    expect(screen.getByRole('radio', { name: /Мария Иванова/ })).not.toBeChecked()
  })

  it('ignores a late booking-options answer for a selection the guest has left', async () => {
    const slow = deferred<Response>()
    server.on((request) =>
      request.path.endsWith('/booking-options') && request.url.searchParams.getAll('serviceId').length === 1
        ? slow.promise
        : undefined,
    )
    await openPage()
    await startFromServices('Алфа')
    const stale = server.of('GET', '/booking-options')[0]!

    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    await tick('Гама')
    await next()
    expect(stale.signal?.aborted).toBe(true)
    slow.resolve(json(200, { ...optionsBody, staff: [{ id: STAFF_GEORGI, displayName: 'Само от старата заявка' }] }))
    await flush()

    expect(screen.queryByText('Само от старата заявка')).toBeNull()
    expect(screen.getByRole('radio', { name: /Мария Иванова/ })).toBeInTheDocument()
    expect(selectionsOf(server.of('GET', '/booking-options')).at(-1)).toEqual([GAMMA, ALFA])
  })

  it('ignores a late availability answer for a selection the guest has left', async () => {
    const slow = deferred<Response>()
    server.on((request) =>
      request.path.endsWith('/availability') && request.url.searchParams.getAll('serviceId').length === 1
        ? slow.promise
        : undefined,
    )
    await openPage()
    await startFromServices('Алфа')
    await next()
    const stale = server.of('GET', '/availability')[0]!

    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на служител'))
    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    await tick('Гама')
    await next()
    await next()
    expect(stale.signal?.aborted).toBe(true)
    slow.resolve(
      json(200, {
        date: '2026-10-07',
        timezone: 'Europe/Sofia',
        availableDates: ['2026-10-07'],
        slots: [{ start: '2026-10-07T17:00:00+03:00', end: '2026-10-07T17:45:00+03:00' }],
      }),
    )
    await flush()

    expect(screen.queryByRole('radio', { name: '17:00' })).toBeNull()
    expect(screen.getByRole('radio', { name: '10:00' })).toBeInTheDocument()
  })
})

describe('the StaffMember step for a selection', () => {
  it('offers exactly the StaffMembers the server returned for the whole selection', async () => {
    server.on((request) =>
      request.path.endsWith('/booking-options') && request.url.searchParams.getAll('serviceId').length === 2
        ? json(200, { ...optionsBody, staff: [{ id: STAFF_GEORGI, displayName: 'Георги Петров' }] })
        : undefined,
    )
    await openPage()
    await startFromServices('Алфа', 'Бета')

    expect(heading()).toHaveTextContent('Избор на служител')
    expect(screen.getByRole('radio', { name: /Без предпочитание/ })).toBeChecked()
    expect(screen.getByRole('radio', { name: /Георги Петров/ })).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: /Мария Иванова/ })).toBeNull()
  })

  it('names the empty intersection and returns to the selection without losing the details', async () => {
    server.on((request) =>
      request.path.endsWith('/booking-options') && request.url.searchParams.getAll('serviceId').length === 2
        ? json(200, { ...optionsBody, staff: [] })
        : undefined,
    )
    await toReview(['Алфа'])
    await click(button('Промени услугите'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    await tick('Бета')
    await next()

    expect(heading()).toHaveTextContent('Избор на служител')
    expect(screen.getByText('В момента няма служител, който предлага всички избрани услуги.')).toBeInTheDocument()
    expect(screen.queryByRole('radio')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Напред' })).toBeNull()
    await click(button('Промяна на избора'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    expect(checkbox('Алфа')).toBeChecked()
    expect(checkbox('Бета')).toBeChecked()
    // Back to a selection that has staff: the details entered earlier are still there.
    await tick('Бета')
    await next()
    await next()
    await pickSlot('10:00')
    await returnToReview()
    expect(reviewSummary()).toHaveTextContent('Иван Петров')
  })

  it('tells eligible StaffMembers without any time apart from an empty intersection', async () => {
    server.on((request) =>
      request.path.endsWith('/availability')
        ? json(200, { date: '2026-10-07', timezone: 'Europe/Sofia', availableDates: [], slots: [] })
        : undefined,
    )
    await openPage()
    await startFromServices('Алфа', 'Бета')

    // There are eligible members ...
    expect(screen.getByRole('radio', { name: /Мария Иванова/ })).toBeInTheDocument()
    expect(screen.queryByText('В момента няма служител, който предлага всички избрани услуги.')).toBeNull()
    await next()
    // ... but no time for the whole visit.
    expect(
      screen.getByText('Няма свободни дати за избраните услуги и служител. Опитайте с друг служител или променете избора.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('В момента няма служител, който предлага всички избрани услуги.')).toBeNull()
  })
})

describe('selection-level read problems', () => {
  async function leaveForTooLong(path: string) {
    server.on((request) =>
      request.path.endsWith(path) && request.url.searchParams.getAll('serviceId').length === 2
        ? json(409, problem('BOOKING_SELECTION_TOO_LONG', 409))
        : undefined,
    )
    await openPage()
    await startFromServices('Алфа', 'Бета')
  }

  it('returns to a refreshed Service selection when the options say the selection became too long', async () => {
    await leaveForTooLong('/booking-options')

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Избраните услуги са с обща продължителност над 8 часа. Изберете по-малко услуги.',
    )
    const profileReads = server.of('GET', '/example-studio').length
    expect(profileReads).toBe(1)
    await click(button('Промяна на избора'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    await flush()

    expect(server.of('GET', '/example-studio')).toHaveLength(profileReads + 1)
    expect(server.posts).toHaveLength(0)
    // Nothing was submitted and the selection is the guest's to change.
    expect(checkbox('Алфа')).toBeChecked()
  })

  it('handles the same problem on the availability read', async () => {
    await leaveForTooLong('/availability')
    await next()

    expect(screen.getByRole('alert')).toHaveTextContent('над 8 часа')
    await click(button('Промяна на избора'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    expect(server.posts).toHaveLength(0)
  })

  it('removes a Service that the refreshed profile no longer offers', async () => {
    server.on((request) =>
      request.path.endsWith('/booking-options') ? json(409, problem('BOOKING_SERVICE_UNAVAILABLE', 409)) : undefined,
    )
    await openPage()
    await startFromServices('Алфа', 'Бета')
    expect(screen.getByRole('alert')).toHaveTextContent('Избраната услуга вече не е налична.')
    serveCatalog(CATALOG.filter((spec) => spec.id !== BETA))

    await click(button('Промяна на избора'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    await flush()

    expect(screen.queryByRole('checkbox', { name: /Бета/ })).toBeNull()
    expect(checkbox('Алфа')).toBeChecked()
  })
})

describe('the whole-visit review', () => {
  it('shows every Service with its facts, the totals, the member, the time and the details in one list', async () => {
    await toReview(['Гама', 'Алфа'], { staff: 'Георги Петров' })

    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    const text = reviewSummary().textContent ?? ''
    expect(text.indexOf('Алфа')).toBeLessThan(text.indexOf('Гама'))
    expect(reviewSummary()).toHaveTextContent('45 мин. · 25.00 €')
    expect(reviewSummary()).toHaveTextContent('30 мин. · 10.10 €')
    expect(reviewSummary()).toHaveTextContent('Общо: 75 мин. · 35.10 €')
    expect(reviewSummary()).toHaveTextContent('Георги Петров')
    expect(reviewSummary()).toHaveTextContent('сряда, 7 октомври 2026 г.')
    expect(reviewSummary()).toHaveTextContent('10:00 – 10:45')
    expect(reviewSummary()).toHaveTextContent('Иван Петров')
    expect(document.querySelectorAll('.booking-summary-row')).toHaveLength(4)
    expect(server.posts).toHaveLength(0)
  })

  it('opens the Service step from «Промени услугите» and the confirm action stays on the right', async () => {
    await toReview(['Алфа'])
    const actions = Array.from(document.querySelectorAll('.booking-actions button')).map((item) => item.textContent)
    expect(actions).toEqual(['Назад', 'Потвърди резервацията'])

    await click(button('Промени услугите'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    expect(checkbox('Алфа')).toBeChecked()
  })
})

describe('the visit confirmation', () => {
  it('shows the server lines and totals, not the earlier client calculation', async () => {
    scriptPosts(() =>
      json(
        201,
        visitBody([
          { name: 'Алфа', durationMinutes: 50, price: 27.5 },
          { name: 'Гама', durationMinutes: 35, price: 11 },
        ]),
      ),
    )
    await toReview(['Алфа', 'Гама'])
    expect(reviewSummary()).toHaveTextContent('Общо: 75 мин. · 35.10 €')
    await submit()

    expect(heading()).toHaveTextContent('Резервацията е потвърдена')
    const facts = document.querySelector('.booking-facts') as HTMLElement
    expect(facts).toHaveTextContent('Алфа')
    expect(facts).toHaveTextContent('50 мин. · 27.50 €')
    expect(facts).toHaveTextContent('Гама')
    expect(facts).toHaveTextContent('35 мин. · 11.00 €')
    expect(facts).toHaveTextContent('85 мин.')
    expect(facts).toHaveTextContent('38.50 €')
    expect(facts).not.toHaveTextContent('35.10')
    expect(facts).not.toHaveTextContent('K7M2Q9XW4B')
    expect(facts).not.toHaveTextContent('Europe/Sofia')
    expect(screen.getAllByRole('heading')).toHaveLength(1)
  })

  it('shows the lines in the order the server stored them', async () => {
    scriptPosts(() =>
      json(
        201,
        visitBody([
          { name: 'Зета', durationMinutes: 10, price: 1 },
          { name: 'Алфа', durationMinutes: 45, price: 25 },
        ]),
      ),
    )
    await toReview(['Алфа', 'Зета'])
    await submit()

    const names = Array.from(document.querySelectorAll('.booking-facts .booking-summary-services li .booking-summary-strong')).map(
      (item) => item.textContent,
    )
    expect(names).toEqual(['Зета', 'Алфа'])
  })

  it('treats a replay (200) like a creation and a CANCELLED replay as not confirmed', async () => {
    scriptPosts(() => json(200, visitBody([{ name: 'Алфа', durationMinutes: 45, price: 25 }, { name: 'Гама', durationMinutes: 30, price: 10.1 }])))
    await toReview(['Алфа', 'Гама'])
    await submit()
    expect(heading()).toHaveTextContent('Резервацията е потвърдена')
    expect(document.querySelectorAll('.booking-facts .booking-summary-services li')).toHaveLength(2)

    cleanup()
    window.history.replaceState(null, '', `/${SLUG}`)
    scriptPosts(() => json(200, visitBody([{ name: 'Алфа', durationMinutes: 45, price: 25 }], { status: 'CANCELLED' })))
    await toReview(['Алфа'])
    await submit()
    expect(heading()).toHaveTextContent('Резервацията е отменена')
    expect(document.querySelector('.status-success')).toBeNull()
  })

  it.each([
    ['a sub-cent line and a total that agree only after rounding', [{ name: 'Алфа', durationMinutes: 45, price: 1.001 }], 1],
    ['a total one cent off its line', [{ name: 'Алфа', durationMinutes: 45, price: 25 }], 25.01],
    ['a line above the Service maximum', [{ name: 'Алфа', durationMinutes: 45, price: 10000000000 }], 10000000000],
  ])('never confirms %s', async (_name, lines, totalPrice) => {
    scriptPosts(() => json(201, { ...visitBody(lines), totalPrice }))
    await toReview(['Алфа'])
    await submit()

    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(document.querySelector('.booking-facts')).toBeNull()
    expect(screen.getByRole('alert')).toHaveTextContent('Не получихме потвърждение за резервацията.')
    // The attempt stays frozen: the same bytes can be resent and a restart is warned.
    expect(button('Опитайте отново')).toBeEnabled()
  })

  it('does not confirm a response whose totals contradict its lines', async () => {
    scriptPosts(() => json(201, { ...visitBody([{ name: 'Алфа', durationMinutes: 45, price: 25 }]), totalPrice: 99 }))
    await toReview(['Алфа'])
    await submit()

    // An unreadable success is an uncertain outcome: the attempt stays and nothing is confirmed.
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(screen.getByRole('alert')).toHaveTextContent('Не получихме потвърждение за резервацията.')
  })
})

describe('the exact attempt of a visit', () => {
  it('resends the very same bytes after an uncertain answer and never posts per Service', async () => {
    stubAttemptIds(ATTEMPT_ONE, ATTEMPT_TWO)
    scriptPosts(() => json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503)))
    await toReview(['Алфа', 'Гама', 'Епсилон'])
    await submit()
    const first = server.posts[0]!.body
    await click(button('Опитайте отново'))

    expect(server.posts).toHaveLength(2)
    expect(server.posts[1]!.body).toBe(first)
    expect(parsedBody(server.posts[1])).toMatchObject({ attemptId: ATTEMPT_ONE })
    expect((parsedBody(server.posts[1]) as { services: unknown[] }).services).toHaveLength(3)
  })

  it('drops a prepared (retryable) attempt when the selection changes before the first answer settles it', async () => {
    stubAttemptIds(ATTEMPT_ONE, ATTEMPT_TWO)
    scriptPosts(() => json(503, problem('BOOKING_TEMPORARILY_UNAVAILABLE', 503)))
    await toReview(['Алфа'])
    await submit()
    expect(button('Опитайте отново')).toBeEnabled()

    await click(button('Промени услугите'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    await tick('Гама')
    await next()
    await next()
    await pickSlot('10:00')
    await returnToReview()
    await submit()

    expect(server.posts).toHaveLength(2)
    expect(parsedBody(server.posts[1])).toMatchObject({ attemptId: ATTEMPT_TWO })
    expect((parsedBody(server.posts[1]) as { services: unknown[] }).services).toHaveLength(2)
  })
})

describe('BOOKING_REVIEW_CHANGED on the first send', () => {
  const REVIEW_CHANGED = 'Условията на избраните услуги са променени. Прегледайте ги и потвърдете отново.'

  async function firstSendRefused(mutate: () => void, names = ['Алфа', 'Гама'], options: { staff?: string } = {}) {
    stubAttemptIds(ATTEMPT_ONE, ATTEMPT_TWO)
    scriptPosts(() => json(409, problem('BOOKING_REVIEW_CHANGED', 409)))
    await openPage()
    await startFromServices(...names)
    if (options.staff) await chooseStaff(options.staff)
    await next()
    await pickSlot('10:00')
    await next()
    await fillDetails()
    await next()
    mutate()
    await submit()
    await flush()
  }

  it('discards the attempt, re-reads the facts and sends nothing by itself', async () => {
    const profileReads = () => server.of('GET', '/example-studio').length
    await firstSendRefused(() => serveCatalog(CATALOG.map((spec) => (spec.id === GAMMA ? { ...spec, price: 12.5 } : spec))))

    expect(server.posts).toHaveLength(1)
    // The page load and exactly one re-read.
    expect(profileReads()).toBe(2)
    // The guest is sent to the time, with the approved message, and nothing was sent again.
    await waitFor(() => expect(heading()).toHaveTextContent('Дата и час'))
    expect(screen.getByRole('alert')).toHaveTextContent(REVIEW_CHANGED)
    expect(server.posts).toHaveLength(1)
    expect(document.querySelector('.booking-context')).toHaveTextContent('Алфа, Гама')
    expect(document.querySelector('.booking-context')).toHaveTextContent('75 мин. · 37.50 €')
    // The time must be chosen again.
    expect(screen.queryByRole('radio', { checked: true })).toBeNull()
    expect(button('Напред')).toBeDisabled()
  })

  it('shows the refreshed duration and price on the review and claims them with a NEW attempt', async () => {
    await firstSendRefused(() =>
      serveCatalog(CATALOG.map((spec) => (spec.id === ALFA ? { ...spec, durationMinutes: 50, price: 27.5 } : spec))),
    )
    await waitFor(() => expect(heading()).toHaveTextContent('Дата и час'))
    // The options and the availability are read again for the refreshed terms.
    await waitFor(() => expect(screen.getByRole('group', { name: 'Свободни часове' })).toBeInTheDocument())
    await pickSlot('11:00')
    await next()
    // The details entered earlier are still there.
    expect(heading()).toHaveTextContent('Вашите данни')
    expect(screen.getByLabelText('Име')).toHaveValue('Иван Петров')
    await next()
    expect(reviewSummary()).toHaveTextContent('50 мин. · 27.50 €')
    expect(reviewSummary()).toHaveTextContent('Общо: 80 мин. · 37.60 €')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(server.posts).toHaveLength(1)

    await submit()

    expect(server.posts).toHaveLength(2)
    const second = parsedBody(server.posts[1]) as { attemptId: string; services: Record<string, unknown>[] }
    expect(second.attemptId).toBe(ATTEMPT_TWO)
    expect(second.services).toEqual([
      { serviceId: GAMMA, reviewedDurationMinutes: 30, reviewedPriceCents: 1010 },
      { serviceId: ALFA, reviewedDurationMinutes: 50, reviewedPriceCents: 2750 },
    ])
    expect(server.posts[1]!.body).not.toBe(server.posts[0]!.body)
  })

  it('removes a Service that is no longer offered and says so', async () => {
    await firstSendRefused(() => serveCatalog(CATALOG.filter((spec) => spec.id !== GAMMA)))

    await waitFor(() => expect(heading()).toHaveTextContent('Дата и час'))
    expect(screen.getByRole('alert')).toHaveTextContent(REVIEW_CHANGED)
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Някои от избраните услуги вече не се предлагат и са премахнати от избора.',
    )
    expect(document.querySelector('.booking-context')).toHaveTextContent('Алфа')
    expect(document.querySelector('.booking-context')).not.toHaveTextContent('Гама')
    expect(document.querySelector('.booking-context')).toHaveTextContent('45 мин. · 25.00 €')
  })

  it('returns to the Service step when no selected Service is offered any more', async () => {
    await firstSendRefused(() => serveCatalog(CATALOG.filter((spec) => spec.id !== GAMMA && spec.id !== ALFA)))

    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    expect(screen.getByRole('alert')).toHaveTextContent(REVIEW_CHANGED)
    expect((screen.getAllByRole('checkbox') as HTMLInputElement[]).every((box) => !box.checked)).toBe(true)
    expect(button('Напред')).toBeDisabled()
    expect(server.posts).toHaveLength(1)
  })

  it('keeps the StaffMember preference only while the refreshed answer still offers the member', async () => {
    await firstSendRefused(
      () => serveCatalog(CATALOG.map((spec) => (spec.id === ALFA ? { ...spec, price: 26 } : spec))),
      ['Алфа'],
      { staff: 'Мария Иванова' },
    )
    await waitFor(() => expect(heading()).toHaveTextContent('Дата и час'))
    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на служител'))
    expect(screen.getByRole('radio', { name: /Мария Иванова/ })).toBeChecked()

    // The refreshed answer no longer offers her: the preference falls back to «Без предпочитание».
    server.on((request) =>
      request.path.endsWith('/booking-options')
        ? json(200, { ...optionsBody, staff: [{ id: STAFF_GEORGI, displayName: 'Георги Петров' }] })
        : undefined,
    )
    await click(button('Назад'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    await tick('Алфа')
    await tick('Алфа')
    await next()
    expect(screen.getByRole('radio', { name: /Без предпочитание/ })).toBeChecked()
    expect(screen.queryByRole('radio', { name: /Мария Иванова/ })).toBeNull()
  })

  it('offers a retry when the profile cannot be re-read, and still sends nothing', async () => {
    stubAttemptIds(ATTEMPT_ONE, ATTEMPT_TWO)
    scriptPosts(() => json(409, problem('BOOKING_REVIEW_CHANGED', 409)))
    await toReview(['Алфа'])
    let failing = true
    server.on((request) =>
      request.method === 'GET' && request.path === `/api/public/businesses/${SLUG}` && failing
        ? (Promise.reject(new TypeError('offline')) as unknown as Response)
        : undefined,
    )
    await submit()
    await flush()

    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(screen.getByRole('alert')).toHaveTextContent(REVIEW_CHANGED)
    expect(screen.queryByRole('button', { name: 'Потвърди резервацията' })).toBeNull()
    expect(server.posts).toHaveLength(1)

    failing = false
    await click(button('Опитайте отново'))
    await waitFor(() => expect(heading()).toHaveTextContent('Дата и час'))
    expect(server.posts).toHaveLength(1)
  })
})

describe('BOOKING_REVIEW_CHANGED after an earlier uncertain answer', () => {
  it('keeps the same attempt, the same bytes and the frozen state, and refreshes nothing', async () => {
    stubAttemptIds(ATTEMPT_ONE, ATTEMPT_TWO)
    scriptPosts(
      () => json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503)),
      () => json(409, problem('BOOKING_REVIEW_CHANGED', 409)),
    )
    await toReview(['Алфа', 'Гама'])
    await submit()
    const first = server.posts[0]!.body
    const profileReads = server.of('GET', '/example-studio').length
    await click(button('Опитайте отново'))

    expect(server.posts).toHaveLength(2)
    expect(server.posts[1]!.body).toBe(first)
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Условията на услугите са променени. Ако резервацията вече е направена, тя е с условията, които прегледахте.',
    )
    expect(screen.getByRole('alert')).toHaveTextContent('Резервацията може вече да е направена.')
    // No silent refresh, no new attempt, the review stays uneditable and the warned restart remains.
    expect(server.of('GET', '/example-studio')).toHaveLength(profileReads)
    for (const edit of screen.getAllByRole('button', { name: /^Промени/ })) expect(edit).toBeDisabled()
    expect(button('Започни отначало')).toBeEnabled()
    expect(button('Опитайте отново')).toBeEnabled()

    // The same attempt is still the one that is resent.
    await click(button('Опитайте отново'))
    expect(server.posts[2]!.body).toBe(first)
    expect(parsedBody(server.posts[2])).toMatchObject({ attemptId: ATTEMPT_ONE })
  })

  it('keeps the leave warning for the possibly committed attempt', async () => {
    scriptPosts(
      () => json(503, problem('BOOKING_OUTCOME_UNCERTAIN', 503)),
      () => json(409, problem('BOOKING_REVIEW_CHANGED', 409)),
    )
    await toReview(['Алфа'])
    await submit()
    await click(button('Опитайте отново'))

    await click(button('Към страницата на бизнеса'))
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Резервацията може вече да е направена.')
  })
})

describe('privacy of the selection', () => {
  it('writes no Service, price, name or attempt to the URL, storage, cookies, history or title', async () => {
    window.localStorage.clear()
    window.sessionStorage.clear()
    stubAttemptIds(ATTEMPT_ONE)
    await toReview(['Алфа', 'Бета', 'Гама'])
    await submit()

    const everywhere = [
      window.location.href,
      JSON.stringify(window.history.state),
      JSON.stringify(Object.entries(window.localStorage)),
      JSON.stringify(Object.entries(window.sessionStorage)),
      document.cookie,
      document.title,
      document.head.innerHTML,
    ].join('\n')
    for (const secret of [ALFA, BETA, GAMMA, 'Алфа', 'Бета', 'Гама', '80.50', '25.00', '8050', ATTEMPT_ONE]) {
      expect(everywhere).not.toContain(secret)
    }
    expect(window.localStorage).toHaveLength(0)
    expect(window.sessionStorage).toHaveLength(0)
    expect(window.history.state).toEqual({ spyBooking: { journey: expect.any(String), step: 6 } })
  })

  it('keeps every history entry to the step marker while selecting and editing', async () => {
    const push = vi.spyOn(window.history, 'pushState')
    await toReview(['Алфа', 'Гама'])
    await click(button('Промени услугите'))
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на услуги'))
    await tick('Бета')

    expect(push.mock.calls.length).toBeGreaterThan(0)
    for (const call of push.mock.calls) {
      expect(Object.keys(call[0] as object)).toEqual(['spyBooking'])
      expect(JSON.stringify(call[0])).not.toMatch(/Алфа|Гама|Бета|8050|[0-9a-f]{8}-[0-9a-f]{4}-/i)
      expect(call).toHaveLength(2)
    }
  })

  it('does not log anything while the selection, the review change and the recovery happen', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) =>
      vi.spyOn(console, method).mockImplementation(() => undefined),
    )
    stubAttemptIds(ATTEMPT_ONE)
    scriptPosts(() => json(409, problem('BOOKING_REVIEW_CHANGED', 409)))
    await toReview(['Алфа', 'Гама'])
    await submit()
    await flush()

    for (const spy of spies) expect(spy).not.toHaveBeenCalled()
  })
})
