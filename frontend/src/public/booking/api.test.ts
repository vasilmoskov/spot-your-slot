import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  BookingReadError,
  decodeConfirmedBooking,
  fetchAvailability,
  fetchBookingOptions,
  parseRetryAfter,
  postBooking,
} from './api'

const SERVICE = '11111111-1111-4111-8111-111111111111'
const OTHER = '80000000-0000-4000-8000-000000000000'
const LOW = '7fffffff-ffff-4fff-bfff-ffffffffffff'
const controller = () => new AbortController().signal
const bookingBody = {
  reference: 'K7M2Q9XW4B',
  status: 'CONFIRMED',
  services: [{ name: 'Подстригване', durationMinutes: 45, price: 25 }],
  totalDurationMinutes: 45,
  totalPrice: 25,
  staff: { displayName: 'Мария' },
  start: '2026-10-07T10:00:00+03:00',
  end: '2026-10-07T10:45:00+03:00',
  timezone: 'Europe/Sofia',
}

function respond(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status, headers })
}

function useFetch(handler: (url: string, init: RequestInit) => Promise<Response> | Response) {
  const fetchMock = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) =>
    handler(String(url), init ?? {}),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => vi.unstubAllGlobals())

describe('Retry-After', () => {
  it('reads whole seconds and HTTP dates, clamps, and ignores anything else', () => {
    const now = Date.parse('2026-10-07T08:00:00Z')
    expect(parseRetryAfter('30', now)).toBe(30)
    expect(parseRetryAfter(' 2 ', now)).toBe(2)
    expect(parseRetryAfter('0', now)).toBe(0)
    expect(parseRetryAfter('999999', now)).toBe(3600)
    expect(parseRetryAfter(new Date(now + 10_000).toUTCString(), now)).toBe(10)
    expect(parseRetryAfter(new Date(now - 10_000).toUTCString(), now)).toBe(0)
    for (const value of ['soon', '-5', '1.5', '', null]) expect(parseRetryAfter(value, now)).toBeNull()
  })
})

describe('the reads', () => {
  it('requests booking options without credentials and decodes only the contract', async () => {
    const fetchMock = useFetch(() =>
      respond(200, {
        timezone: 'Europe/Sofia',
        firstDate: '2026-10-07',
        lastDate: '2026-11-05',
        staff: [{ id: 's1', displayName: 'Мария', email: 'private@example.invalid' }],
        extra: 1,
      }),
    )
    const result = await fetchBookingOptions('example-studio', [SERVICE], controller())
    expect(result).toEqual({
      kind: 'options',
      options: {
        timezone: 'Europe/Sofia',
        firstDate: '2026-10-07',
        lastDate: '2026-11-05',
        staff: [{ id: 's1', displayName: 'Мария' }],
      },
    })
    const [url, init] = fetchMock.mock.calls[0]!
    expect(String(url)).toBe(
      `http://localhost:8080/api/public/businesses/example-studio/booking-options?serviceId=${SERVICE}`,
    )
    expect(init).toMatchObject({ method: 'GET', credentials: 'omit' })
  })

  it('encodes the preference and the date in the availability query', async () => {
    const fetchMock = useFetch(() =>
      respond(200, { date: '2026-10-07', timezone: 'Europe/Sofia', availableDates: ['2026-10-07'], slots: [] }),
    )
    await fetchAvailability('example-studio', [SERVICE], '2026-10-07', 's 1', controller())
    expect(String(fetchMock.mock.calls[0]![0])).toContain(
      `/availability?serviceId=${SERVICE}&date=2026-10-07&staffMemberId=s+1`,
    )
    await fetchAvailability('example-studio', [SERVICE], '2026-10-07', null, controller())
    expect(String(fetchMock.mock.calls[1]![0])).not.toContain('staffMemberId')
  })

  it.each([
    ['the unavailable Business', 404, { code: 'BUSINESS_PAGE_UNAVAILABLE' }, { kind: 'business' }],
    ['an unavailable Service', 409, { code: 'BOOKING_SERVICE_UNAVAILABLE' }, { kind: 'service' }],
    ['an unavailable StaffMember', 409, { code: 'BOOKING_STAFF_UNAVAILABLE' }, { kind: 'staff' }],
    ['a selection that is too long', 409, { code: 'BOOKING_SELECTION_TOO_LONG' }, { kind: 'too-long' }],
  ])('maps %s to a result, not an error', async (_name, status, body, expected) => {
    useFetch(() => respond(status, body))
    await expect(fetchBookingOptions('s', [SERVICE], controller())).resolves.toEqual(expected)
  })

  it.each([
    ['the limiter', 429, { code: 'RATE_LIMITED' }, 'rate-limited'],
    ['a server error', 500, { code: 'INTERNAL_ERROR' }, 'unexpected'],
    ['an unknown 404', 404, { code: 'X' }, 'unexpected'],
    ['an answer that is not the contract', 200, { hello: 1 }, 'unexpected'],
    ['an invalid timezone', 200, { timezone: 'No/Where', firstDate: '2026-10-07', lastDate: '2026-10-08', staff: [] }, 'unexpected'],
  ])('maps %s to a failure', async (_name, status, body, reason) => {
    useFetch(() => respond(status, body, { 'Retry-After': '7' }))
    await expect(fetchBookingOptions('s', [SERVICE], controller())).rejects.toMatchObject({ reason })
  })

  it('carries the Retry-After seconds of a rate-limited read', async () => {
    useFetch(() => respond(429, { code: 'RATE_LIMITED' }, { 'Retry-After': '12' }))
    await expect(fetchBookingOptions('s', [SERVICE], controller())).rejects.toMatchObject({
      retryAfterSeconds: 12,
    })
  })

  it('reports a transport failure as a network failure and rethrows an abort', async () => {
    useFetch(() => Promise.reject(new TypeError('Failed to fetch')))
    await expect(fetchBookingOptions('s', [SERVICE], controller())).rejects.toBeInstanceOf(BookingReadError)
    const aborting = new AbortController()
    aborting.abort()
    useFetch(() => Promise.reject(new DOMException('aborted', 'AbortError')))
    await expect(fetchBookingOptions('s', [SERVICE], aborting.signal)).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('requests a selection as repeated serviceId parameters in the canonical order, whatever order was ticked', async () => {
    const fetchMock = useFetch((url) =>
      respond(
        200,
        url.includes('/booking-options')
          ? { timezone: 'Europe/Sofia', firstDate: '2026-10-07', lastDate: '2026-11-05', staff: [] }
          : { date: '2026-10-07', timezone: 'Europe/Sofia', availableDates: ['2026-10-07'], slots: [] },
      ),
    )
    await fetchBookingOptions('example-studio', [OTHER, SERVICE, LOW], controller())
    await fetchBookingOptions('example-studio', [LOW, OTHER, SERVICE], controller())
    await fetchAvailability('example-studio', [OTHER, LOW], '2026-10-07', null, controller())

    const urls = fetchMock.mock.calls.map((call) => String(call[0]))
    // Unsigned order: 0x11 < 0x7f < 0x80, which is also the lowercase text order.
    expect(urls[0]).toBe(
      `http://localhost:8080/api/public/businesses/example-studio/booking-options?serviceId=${SERVICE}&serviceId=${LOW}&serviceId=${OTHER}`,
    )
    expect(urls[1]).toBe(urls[0])
    expect(urls[2]).toBe(
      `http://localhost:8080/api/public/businesses/example-studio/availability?serviceId=${LOW}&serviceId=${OTHER}&date=2026-10-07`,
    )
    for (const url of urls) {
      expect(url).not.toMatch(/name|price|phone|email|attempt/i)
    }
  })

  it('rejects an availability answer for another date', async () => {
    useFetch(() =>
      respond(200, { date: '2026-10-08', timezone: 'Europe/Sofia', availableDates: [], slots: [] }),
    )
    await expect(
      fetchAvailability('s', [SERVICE], '2026-10-07', null, controller()),
    ).rejects.toMatchObject({ reason: 'unexpected' })
  })

  it('rejects slots that are not offset date-times', async () => {
    useFetch(() =>
      respond(200, {
        date: '2026-10-07',
        timezone: 'Europe/Sofia',
        availableDates: ['2026-10-07'],
        slots: [{ start: '10:00', end: '10:45' }],
      }),
    )
    await expect(
      fetchAvailability('s', [SERVICE], '2026-10-07', null, controller()),
    ).rejects.toMatchObject({ reason: 'unexpected' })
  })
})

describe('classifying a booking response', () => {
  const body_ = '{"attemptId":"x"}'
  const body = body_

  it('sends the exact text it was given, without credentials, and never aborts', async () => {
    const fetchMock = useFetch(() => respond(201, bookingBody))
    await postBooking('example-studio', body)
    const [url, init] = fetchMock.mock.calls[0]!
    expect(String(url)).toBe('http://localhost:8080/api/public/businesses/example-studio/bookings')
    expect(init).toMatchObject({ method: 'POST', credentials: 'omit', body })
    expect(init).not.toHaveProperty('signal')
  })

  it.each([
    [201, false],
    [200, true],
  ])('a %s success is created or replayed', async (status, replayed) => {
    useFetch(() => respond(status, bookingBody))
    await expect(postBooking('s', body)).resolves.toMatchObject({ kind: 'success', replayed })
  })

  it('decodes a cancelled replay', async () => {
    useFetch(() => respond(200, { ...bookingBody, status: 'CANCELLED' }))
    await expect(postBooking('s', body)).resolves.toMatchObject({
      kind: 'success',
      replayed: true,
      booking: { status: 'CANCELLED' },
    })
  })

  it.each([
    ['a network failure', () => Promise.reject(new TypeError('x'))],
    ['an unreadable success', () => respond(201, '<html>')],
    ['a success without a reference', () => respond(201, { ...bookingBody, reference: ' ' })],
    ['a success with another status', () => respond(201, { ...bookingBody, status: 'COMPLETED' })],
    ['a success with a string price', () => respond(201, { ...bookingBody, services: [{ ...bookingBody.services[0], price: '25' }] })],
    ['a success in the old single-Service shape', () => respond(201, { reference: 'K7M2Q9XW4B', status: 'CONFIRMED', service: { name: 'Подстригване', durationMinutes: 45, price: 25 }, staff: { displayName: 'Мария' }, start: bookingBody.start, end: bookingBody.end, timezone: 'Europe/Sofia' })],
    ['a success without lines', () => respond(201, { ...bookingBody, services: [] })],
    ['a success with six lines', () => respond(201, { ...bookingBody, services: Array.from({ length: 6 }, () => bookingBody.services[0]), totalDurationMinutes: 270, totalPrice: 150 })],
    ['a success whose total duration contradicts its lines', () => respond(201, { ...bookingBody, totalDurationMinutes: 46 })],
    ['a success whose total price contradicts its lines', () => respond(201, { ...bookingBody, totalPrice: 25.01 })],
    ['a success with a fractional line duration', () => respond(201, { ...bookingBody, services: [{ ...bookingBody.services[0], durationMinutes: 45.5 }], totalDurationMinutes: 45.5 })],
    ['a success with a third decimal on a line price', () => respond(201, { ...bookingBody, services: [{ ...bookingBody.services[0], price: 1.001 }], totalPrice: 1.001 })],
    ['a success whose sub-cent line and total agree only after rounding', () => respond(200, { ...bookingBody, services: [{ ...bookingBody.services[0], price: 1.001 }], totalPrice: 1 })],
    ['a success with a line one cent above the Service maximum', () => respond(201, { ...bookingBody, services: [{ ...bookingBody.services[0], price: 10000000000 }], totalPrice: 10000000000 })],
    ['a success with a total one cent above the visit maximum', () => respond(200, { ...bookingBody, services: Array.from({ length: 5 }, () => ({ name: 'Премиум', durationMinutes: 96, price: 9999999999.99 })), totalDurationMinutes: 480, totalPrice: 49999999999.96 })],
    ['a success with a total one cent off the exact sum of its lines', () => respond(201, { ...bookingBody, services: [{ name: 'А', durationMinutes: 10, price: 0.1 }, { name: 'Б', durationMinutes: 10, price: 0.2 }], totalDurationMinutes: 20, totalPrice: 0.31 })],
    ['a success with a negative line price', () => respond(201, { ...bookingBody, services: [{ ...bookingBody.services[0], price: -1 }], totalPrice: -1 })],
    ['a success with a bad instant', () => respond(201, { ...bookingBody, start: 'tomorrow' })],
    ['an unknown 500', () => respond(500, { code: 'INTERNAL_ERROR' })],
    ['an unknown 502', () => respond(502, '<html>')],
    ['an unknown 503', () => respond(503, '')],
    ['a 408', () => respond(408, '')],
    ['the documented uncertain outcome', () => respond(503, { code: 'BOOKING_OUTCOME_UNCERTAIN' })],
  ])('treats %s as uncertain, never as proof that nothing was booked', async (_name, answer) => {
    useFetch(answer)
    await expect(postBooking('s', body)).resolves.toMatchObject({ kind: 'uncertain' })
  })

  it.each([
    [400, 'VALIDATION_ERROR', 'validation'],
    [404, 'BUSINESS_PAGE_UNAVAILABLE', 'business-unavailable'],
    [409, 'BOOKING_SERVICE_UNAVAILABLE', 'service-unavailable'],
    [409, 'BOOKING_STAFF_UNAVAILABLE', 'staff-unavailable'],
    [409, 'BOOKING_SLOT_UNAVAILABLE', 'slot-unavailable'],
    [409, 'BOOKING_NOT_COMPLETED_ONLINE', 'identity-conflict'],
    [409, 'BOOKING_ATTEMPT_MISMATCH', 'attempt-mismatch'],
    [409, 'BOOKING_REVIEW_CHANGED', 'review-changed'],
    [413, 'REQUEST_TOO_LARGE', 'unexpected'],
    [415, 'UNSUPPORTED_MEDIA_TYPE', 'unexpected'],
  ])('maps the documented %s %s to the rejection %s', async (status, code, reason) => {
    useFetch(() => respond(status, { code }))
    await expect(postBooking('s', body)).resolves.toEqual({ kind: 'rejected', reason })
  })

  // A code counts only with its own status; anything else cannot establish the result.
  it.each([
    ['an unknown 4xx code', 400, { code: 'SOMETHING_ELSE' }],
    ['a refusal by a proxy', 403, { code: 'ACCESS_DENIED' }],
    ['a 4xx without a body', 400, ''],
    ['a 4xx with an unreadable body', 404, '<html>'],
    ['a documented slot code on the wrong status', 400, { code: 'BOOKING_SLOT_UNAVAILABLE' }],
    ['a validation code on a 409', 409, { code: 'VALIDATION_ERROR' }],
    ['a mismatch code on a 400', 400, { code: 'BOOKING_ATTEMPT_MISMATCH' }],
    ['the review-changed code on a 400', 400, { code: 'BOOKING_REVIEW_CHANGED' }],
    ['the review-changed code on a 503', 503, { code: 'BOOKING_REVIEW_CHANGED' }],
    ['the too-long code on a booking POST', 409, { code: 'BOOKING_SELECTION_TOO_LONG' }],
    ['the unavailable-Business code on a 409', 409, { code: 'BUSINESS_PAGE_UNAVAILABLE' }],
    ['a size code on a 400', 400, { code: 'REQUEST_TOO_LARGE' }],
    ['a rate limit without its code', 429, { code: 'OTHER' }],
    ['a rate limit without a body', 429, ''],
    ['the rollback code on a 500', 500, { code: 'BOOKING_TEMPORARILY_UNAVAILABLE' }],
    ['the rate-limit code on a 503', 503, { code: 'RATE_LIMITED' }],
    ['an inherited object key as a code', 400, { code: 'constructor' }],
    ['a 503 without a documented code', 503, { code: 'OTHER' }],
  ])('treats %s as unresolved, not as proof that nothing was booked', async (_name, status, body) => {
    useFetch(() => respond(status, body))
    await expect(postBooking('s', body_)).resolves.toMatchObject({ kind: 'uncertain' })
  })

  it('maps the proven rollback and the rate limit with their Retry-After', async () => {
    useFetch(() => respond(503, { code: 'BOOKING_TEMPORARILY_UNAVAILABLE' }, { 'Retry-After': '2' }))
    await expect(postBooking('s', body)).resolves.toEqual({ kind: 'rolled-back', retryAfterSeconds: 2 })
    useFetch(() => respond(429, { code: 'RATE_LIMITED' }, { 'Retry-After': '600' }))
    await expect(postBooking('s', body)).resolves.toEqual({ kind: 'rate-limited', retryAfterSeconds: 600 })
  })

  it('keeps only the known field errors of a validation rejection', async () => {
    useFetch(() =>
      respond(400, {
        code: 'VALIDATION_ERROR',
        fieldErrors: { phone: 'Телефон.', note: 'Бележка.', attemptId: 'hidden', email: '', services: 'Услуги.' },
      }),
    )
    await expect(postBooking('s', body)).resolves.toEqual({
      kind: 'rejected',
      reason: 'validation',
      fieldErrors: { phone: 'Телефон.', note: 'Бележка.', services: 'Услуги.' },
    })
  })
})

describe('decoding a confirmation', () => {
  it('copies only the documented fields', () => {
    const decoded = decodeConfirmedBooking({ ...bookingBody, internalId: 5, customer: { phone: 'x' } })
    expect(decoded).toEqual(bookingBody)
    expect(JSON.stringify(decoded)).not.toContain('internalId')
  })

  it('accepts five maximum-price lines whose cent sum is exact', () => {
    const line = { name: 'Премиум', durationMinutes: 96, price: 9999999999.99 }
    const decoded = decodeConfirmedBooking({
      ...bookingBody,
      services: [line, line, line, line, line],
      totalDurationMinutes: 480,
      totalPrice: 49999999999.95,
    })
    expect(decoded?.services).toHaveLength(5)
    expect(decoded?.totalPrice).toBe(49999999999.95)
  })

  const maxLine = { name: 'Премиум', durationMinutes: 96, price: 9999999999.99 }
  const withPrices = (linePrices: unknown[], totalPrice: unknown) => ({
    ...bookingBody,
    services: linePrices.map((price) => ({ name: 'Услуга', durationMinutes: 10, price })),
    totalDurationMinutes: linePrices.length * 10,
    totalPrice,
  })

  it.each([
    [[0], 0],
    [[0.01], 0.01],
    [[0.07, 0.29], 0.36],
    [[25, 80.5], 105.5],
    [[9999999999.99], 9999999999.99],
  ])('accepts the exact lines %j with the exact total %j', (prices, total) => {
    expect(decodeConfirmedBooking(withPrices(prices, total))).toBeDefined()
  })

  it('accepts five maximum lines with the maximum visit total', () => {
    const decoded = decodeConfirmedBooking({
      ...bookingBody,
      services: Array.from({ length: 5 }, () => maxLine),
      totalDurationMinutes: 480,
      totalPrice: 49999999999.95,
    })
    expect(decoded?.totalPrice).toBe(49999999999.95)
  })

  it.each([
    ['a third decimal on a line', withPrices([1.001], 1.001)],
    ['a half cent on a line', withPrices([1.005], 1.01)],
    ['a sub-cent line and a total that agree only after rounding', withPrices([1.001], 1)],
    ['a sub-cent total for exact lines', withPrices([1], 1.004)],
    ['a negative line price', withPrices([-1], -1)],
    ['a non-finite line price', { ...withPrices([1], 1), services: [{ name: 'Услуга', durationMinutes: 10, price: Number.NaN }] }],
    ['a string line price', withPrices(['1.00'], 1)],
    ['a missing line price', withPrices([undefined], 0)],
    ['a line one cent above the Service maximum', withPrices([10000000000], 10000000000)],
    ['a total one cent above the visit maximum', {
      ...bookingBody,
      services: Array.from({ length: 5 }, () => maxLine),
      totalDurationMinutes: 480,
      totalPrice: 49999999999.96,
    }],
    ['a total one cent away from the exact sum of its lines', withPrices([0.1, 0.2], 0.31)],
    ['a total one cent below the exact sum', withPrices([25, 80.5], 105.49)],
    ['a string total price', withPrices([1], '1.00')],
    ['a negative total price', withPrices([0], -0.01)],
    ['an overflowing total price', withPrices([1], 1e21)],
  ])('rejects %s', (_name, body) => {
    expect(decodeConfirmedBooking(body)).toBeUndefined()
  })

  it('compares the total with the exact sum of the cents, not with a decimal sum', () => {
    // 0.1 + 0.2 is 0.30000000000000004 as decimals; the exact total is 0.30.
    expect(decodeConfirmedBooking(withPrices([0.1, 0.2], 0.3))).toBeDefined()
  })

  it('keeps the lines in the order the server stored them', () => {
    const decoded = decodeConfirmedBooking({
      ...bookingBody,
      services: [
        { name: 'Б', durationMinutes: 30, price: 10 },
        { name: 'А', durationMinutes: 15, price: 5.5 },
      ],
      totalDurationMinutes: 45,
      totalPrice: 15.5,
    })
    expect(decoded?.services.map((line) => line.name)).toEqual(['Б', 'А'])
  })
})
