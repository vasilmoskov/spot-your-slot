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
const controller = () => new AbortController().signal
const bookingBody = {
  reference: 'K7M2Q9XW4B',
  status: 'CONFIRMED',
  service: { name: 'Подстригване', durationMinutes: 45, price: 25 },
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
    const result = await fetchBookingOptions('example-studio', SERVICE, controller())
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
      `http://localhost:8080/api/public/businesses/example-studio/services/${SERVICE}/booking-options`,
    )
    expect(init).toMatchObject({ method: 'GET', credentials: 'omit' })
  })

  it('encodes the preference and the date in the availability query', async () => {
    const fetchMock = useFetch(() =>
      respond(200, { date: '2026-10-07', timezone: 'Europe/Sofia', availableDates: ['2026-10-07'], slots: [] }),
    )
    await fetchAvailability('example-studio', SERVICE, '2026-10-07', 's 1', controller())
    expect(String(fetchMock.mock.calls[0]![0])).toContain('/availability?date=2026-10-07&staffMemberId=s+1')
    await fetchAvailability('example-studio', SERVICE, '2026-10-07', null, controller())
    expect(String(fetchMock.mock.calls[1]![0])).not.toContain('staffMemberId')
  })

  it.each([
    ['the unavailable Business', 404, { code: 'BUSINESS_PAGE_UNAVAILABLE' }, { kind: 'business' }],
    ['an unavailable Service', 409, { code: 'BOOKING_SERVICE_UNAVAILABLE' }, { kind: 'service' }],
    ['an unavailable StaffMember', 409, { code: 'BOOKING_STAFF_UNAVAILABLE' }, { kind: 'staff' }],
  ])('maps %s to a result, not an error', async (_name, status, body, expected) => {
    useFetch(() => respond(status, body))
    await expect(fetchBookingOptions('s', SERVICE, controller())).resolves.toEqual(expected)
  })

  it.each([
    ['the limiter', 429, { code: 'RATE_LIMITED' }, 'rate-limited'],
    ['a server error', 500, { code: 'INTERNAL_ERROR' }, 'unexpected'],
    ['an unknown 404', 404, { code: 'X' }, 'unexpected'],
    ['an answer that is not the contract', 200, { hello: 1 }, 'unexpected'],
    ['an invalid timezone', 200, { timezone: 'No/Where', firstDate: '2026-10-07', lastDate: '2026-10-08', staff: [] }, 'unexpected'],
  ])('maps %s to a failure', async (_name, status, body, reason) => {
    useFetch(() => respond(status, body, { 'Retry-After': '7' }))
    await expect(fetchBookingOptions('s', SERVICE, controller())).rejects.toMatchObject({ reason })
  })

  it('carries the Retry-After seconds of a rate-limited read', async () => {
    useFetch(() => respond(429, { code: 'RATE_LIMITED' }, { 'Retry-After': '12' }))
    await expect(fetchBookingOptions('s', SERVICE, controller())).rejects.toMatchObject({
      retryAfterSeconds: 12,
    })
  })

  it('reports a transport failure as a network failure and rethrows an abort', async () => {
    useFetch(() => Promise.reject(new TypeError('Failed to fetch')))
    await expect(fetchBookingOptions('s', SERVICE, controller())).rejects.toBeInstanceOf(BookingReadError)
    const aborting = new AbortController()
    aborting.abort()
    useFetch(() => Promise.reject(new DOMException('aborted', 'AbortError')))
    await expect(fetchBookingOptions('s', SERVICE, aborting.signal)).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('rejects an availability answer for another date', async () => {
    useFetch(() =>
      respond(200, { date: '2026-10-08', timezone: 'Europe/Sofia', availableDates: [], slots: [] }),
    )
    await expect(
      fetchAvailability('s', SERVICE, '2026-10-07', null, controller()),
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
      fetchAvailability('s', SERVICE, '2026-10-07', null, controller()),
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
    ['a success with a string price', () => respond(201, { ...bookingBody, price: '25' , service: { ...bookingBody.service, price: '25' } })],
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
        fieldErrors: { phone: 'Телефон.', note: 'Бележка.', attemptId: 'hidden', email: '' },
      }),
    )
    await expect(postBooking('s', body)).resolves.toEqual({
      kind: 'rejected',
      reason: 'validation',
      fieldErrors: { phone: 'Телефон.', note: 'Бележка.' },
    })
  })
})

describe('decoding a confirmation', () => {
  it('copies only the documented fields', () => {
    const decoded = decodeConfirmedBooking({ ...bookingBody, internalId: 5, customer: { phone: 'x' } })
    expect(decoded).toEqual(bookingBody)
    expect(JSON.stringify(decoded)).not.toContain('internalId')
  })
})
