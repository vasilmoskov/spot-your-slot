import { API_BASE_URL } from '../../identity/api'
import { isValidTimeZone, parseDateOnly, parseInstant } from './dates'

// The public guest-booking HTTP contracts of ADR-0026 (Phase 5). Every request omits credentials
// and is session independent. Nothing here logs, stores or caches a request or a response.

export type BookingStaff = { id: string; displayName: string }

export type BookingOptions = {
  timezone: string
  firstDate: string
  lastDate: string
  staff: BookingStaff[]
}

export type Slot = { start: string; end: string }

export type Availability = {
  date: string
  timezone: string
  availableDates: string[]
  slots: Slot[]
}

export type ConfirmedBooking = {
  reference: string
  status: 'CONFIRMED' | 'CANCELLED'
  service: { name: string; durationMinutes: number; price: number }
  staff: { displayName: string }
  start: string
  end: string
  timezone: string
}

export type ReadUnavailable = { kind: 'business' | 'service' | 'staff' }

export type OptionsResult = { kind: 'options'; options: BookingOptions } | ReadUnavailable
export type AvailabilityResult = { kind: 'availability'; availability: Availability } | ReadUnavailable

// A read that failed for a reason the guest can only retry: the network, the limiter, or anything
// that is not the documented contract. It carries no backend detail.
export class BookingReadError extends Error {
  readonly reason: 'network' | 'rate-limited' | 'unexpected'
  readonly retryAfterSeconds: number | null

  constructor(reason: 'network' | 'rate-limited' | 'unexpected', retryAfterSeconds: number | null) {
    super('The booking data could not be loaded.')
    this.name = 'BookingReadError'
    this.reason = reason
    this.retryAfterSeconds = retryAfterSeconds
  }
}

export type SubmissionRejection =
  | 'business-unavailable'
  | 'service-unavailable'
  | 'staff-unavailable'
  | 'slot-unavailable'
  | 'identity-conflict'
  | 'attempt-mismatch'
  | 'validation'
  | 'unexpected'

export type BookingFieldErrors = Partial<
  Record<'displayName' | 'phone' | 'email' | 'contact' | 'note', string>
>

// What one POST answered. `rolled-back`, `rejected` (a documented code on its documented status) and
// `rate-limited` describe THIS request only; whether they also settle an earlier uncertain send of the
// same attempt is decided by the state machine, and they never do. `uncertain` proves nothing.
export type SubmissionOutcome =
  | { kind: 'success'; replayed: boolean; booking: ConfirmedBooking }
  | { kind: 'rolled-back'; retryAfterSeconds: number | null }
  | { kind: 'uncertain'; retryAfterSeconds: number | null }
  | { kind: 'rate-limited'; retryAfterSeconds: number | null }
  | { kind: 'rejected'; reason: SubmissionRejection; fieldErrors?: BookingFieldErrors }

const FIELD_ERROR_KEYS = ['displayName', 'phone', 'email', 'contact', 'note'] as const
const MAX_RETRY_AFTER_SECONDS = 3600
// The IMF-fixdate of RFC 9110; looser spellings that Date.parse would guess at are not trusted.
const HTTP_DATE = /^[A-Za-z]{3}, \d{2} [A-Za-z]{3} \d{4} \d{2}:\d{2}:\d{2} GMT$/

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== ''
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return undefined
  }
}

// `Retry-After` as whole seconds or an HTTP date; anything else, a negative value included, is
// ignored. Clamped so a hostile value cannot disable the control for hours.
export function parseRetryAfter(value: string | null, now: number = Date.now()): number | null {
  if (value === null) return null
  const trimmed = value.trim()
  let seconds: number
  if (/^\d+$/.test(trimmed)) {
    seconds = Number(trimmed)
  } else if (HTTP_DATE.test(trimmed)) {
    const at = Date.parse(trimmed)
    if (Number.isNaN(at)) return null
    seconds = Math.ceil((at - now) / 1000)
  } else {
    return null
  }
  if (!Number.isFinite(seconds)) return null
  return Math.min(Math.max(seconds, 0), MAX_RETRY_AFTER_SECONDS)
}

function problemCode(body: unknown): string | undefined {
  return isRecord(body) && typeof body.code === 'string' ? body.code : undefined
}

function decodeOptions(body: unknown): BookingOptions | undefined {
  if (!isRecord(body) || !Array.isArray(body.staff)) return undefined
  if (
    typeof body.timezone !== 'string' ||
    !isValidTimeZone(body.timezone) ||
    typeof body.firstDate !== 'string' ||
    !parseDateOnly(body.firstDate) ||
    typeof body.lastDate !== 'string' ||
    !parseDateOnly(body.lastDate)
  ) {
    return undefined
  }
  const staff: BookingStaff[] = []
  for (const candidate of body.staff) {
    if (!isRecord(candidate) || !isText(candidate.id) || !isText(candidate.displayName)) {
      return undefined
    }
    staff.push({ id: candidate.id, displayName: candidate.displayName })
  }
  return { timezone: body.timezone, firstDate: body.firstDate, lastDate: body.lastDate, staff }
}

function decodeAvailability(body: unknown): Availability | undefined {
  if (!isRecord(body) || !Array.isArray(body.availableDates) || !Array.isArray(body.slots)) {
    return undefined
  }
  if (
    typeof body.date !== 'string' ||
    !parseDateOnly(body.date) ||
    typeof body.timezone !== 'string' ||
    !isValidTimeZone(body.timezone)
  ) {
    return undefined
  }
  const availableDates: string[] = []
  for (const candidate of body.availableDates) {
    if (typeof candidate !== 'string' || !parseDateOnly(candidate)) return undefined
    availableDates.push(candidate)
  }
  const slots: Slot[] = []
  for (const candidate of body.slots) {
    if (
      !isRecord(candidate) ||
      typeof candidate.start !== 'string' ||
      typeof candidate.end !== 'string' ||
      parseInstant(candidate.start) === null ||
      parseInstant(candidate.end) === null
    ) {
      return undefined
    }
    slots.push({ start: candidate.start, end: candidate.end })
  }
  return { date: body.date, timezone: body.timezone, availableDates, slots }
}

export function decodeConfirmedBooking(body: unknown): ConfirmedBooking | undefined {
  if (!isRecord(body) || !isRecord(body.service) || !isRecord(body.staff)) return undefined
  if (
    !isText(body.reference) ||
    (body.status !== 'CONFIRMED' && body.status !== 'CANCELLED') ||
    !isText(body.service.name) ||
    typeof body.service.durationMinutes !== 'number' ||
    !Number.isFinite(body.service.durationMinutes) ||
    typeof body.service.price !== 'number' ||
    !Number.isFinite(body.service.price) ||
    !isText(body.staff.displayName) ||
    typeof body.start !== 'string' ||
    typeof body.end !== 'string' ||
    parseInstant(body.start) === null ||
    parseInstant(body.end) === null ||
    typeof body.timezone !== 'string' ||
    !isValidTimeZone(body.timezone)
  ) {
    return undefined
  }
  return {
    reference: body.reference,
    status: body.status,
    service: {
      name: body.service.name,
      durationMinutes: body.service.durationMinutes,
      price: body.service.price,
    },
    staff: { displayName: body.staff.displayName },
    start: body.start,
    end: body.end,
    timezone: body.timezone,
  }
}

function decodeFieldErrors(body: unknown): BookingFieldErrors | undefined {
  if (!isRecord(body) || !isRecord(body.fieldErrors)) return undefined
  const result: BookingFieldErrors = {}
  for (const key of FIELD_ERROR_KEYS) {
    const message = body.fieldErrors[key]
    if (typeof message === 'string' && message !== '') result[key] = message
  }
  return Object.keys(result).length > 0 ? result : undefined
}

function baseUrl(slug: string, serviceId: string): string {
  return `${API_BASE_URL}/api/public/businesses/${encodeURIComponent(slug)}/services/${encodeURIComponent(serviceId)}`
}

// Maps a non-success read answer. Aborts are rethrown by the caller before this runs.
function readFailure(response: Response, body: unknown): ReadUnavailable | BookingReadError {
  const code = problemCode(body)
  if (response.status === 404 && code === 'BUSINESS_PAGE_UNAVAILABLE') return { kind: 'business' }
  if (response.status === 409 && code === 'BOOKING_SERVICE_UNAVAILABLE') return { kind: 'service' }
  if (response.status === 409 && code === 'BOOKING_STAFF_UNAVAILABLE') return { kind: 'staff' }
  if (response.status === 429) {
    return new BookingReadError('rate-limited', parseRetryAfter(response.headers.get('Retry-After')))
  }
  return new BookingReadError('unexpected', null)
}

async function getJson(
  url: string,
  signal: AbortSignal,
): Promise<{ response: Response; body: unknown }> {
  let response: Response
  try {
    response = await fetch(url, {
      method: 'GET',
      credentials: 'omit',
      headers: { Accept: 'application/json' },
      signal,
    })
  } catch (error) {
    if (signal.aborted) throw error
    throw new BookingReadError('network', null)
  }
  return { response, body: await readJson(response) }
}

/** The eligible StaffMembers, the timezone and the bookable range of one Service. */
export async function fetchBookingOptions(
  slug: string,
  serviceId: string,
  signal: AbortSignal,
): Promise<OptionsResult> {
  const { response, body } = await getJson(`${baseUrl(slug, serviceId)}/booking-options`, signal)
  if (response.status === 200) {
    const options = decodeOptions(body)
    if (options) return { kind: 'options', options }
    throw new BookingReadError('unexpected', null)
  }
  const failure = readFailure(response, body)
  if (failure instanceof BookingReadError) throw failure
  return failure
}

/** The available dates and the slots of one date. `staffId` null is "no preference". */
export async function fetchAvailability(
  slug: string,
  serviceId: string,
  date: string,
  staffId: string | null,
  signal: AbortSignal,
): Promise<AvailabilityResult> {
  const query = new URLSearchParams({ date })
  if (staffId !== null) query.set('staffMemberId', staffId)
  const { response, body } = await getJson(
    `${baseUrl(slug, serviceId)}/availability?${query.toString()}`,
    signal,
  )
  if (response.status === 200) {
    const availability = decodeAvailability(body)
    if (availability && availability.date === date) return { kind: 'availability', availability }
    throw new BookingReadError('unexpected', null)
  }
  const failure = readFailure(response, body)
  if (failure instanceof BookingReadError) throw failure
  return failure
}

// The documented 4xx answers of `POST …/bookings` (ADR-0026), each valid only with its own status.
const REJECTIONS: Record<string, { status: number; reason: SubmissionRejection }> = {
  VALIDATION_ERROR: { status: 400, reason: 'validation' },
  BUSINESS_PAGE_UNAVAILABLE: { status: 404, reason: 'business-unavailable' },
  BOOKING_SERVICE_UNAVAILABLE: { status: 409, reason: 'service-unavailable' },
  BOOKING_STAFF_UNAVAILABLE: { status: 409, reason: 'staff-unavailable' },
  BOOKING_SLOT_UNAVAILABLE: { status: 409, reason: 'slot-unavailable' },
  BOOKING_NOT_COMPLETED_ONLINE: { status: 409, reason: 'identity-conflict' },
  BOOKING_ATTEMPT_MISMATCH: { status: 409, reason: 'attempt-mismatch' },
  // Refused before any booking work: the body was never read as a booking.
  REQUEST_TOO_LARGE: { status: 413, reason: 'unexpected' },
  UNSUPPORTED_MEDIA_TYPE: { status: 415, reason: 'unexpected' },
}

/**
 * Sends one booking attempt. `body` is the exact frozen JSON text: a retry passes the very same
 * string, so the attempt ID and every field are byte-identical. The request is never aborted and
 * never retried here, and the function never throws: every outcome, including a network failure,
 * is classified. A transport failure, an unreadable or malformed success, an unknown 5xx and a
 * 408 are all `uncertain`, because the commit may have happened (ADR-0024).
 */
export async function postBooking(
  slug: string,
  body: string,
  now: number = Date.now(),
): Promise<SubmissionOutcome> {
  let response: Response
  try {
    response = await fetch(`${API_BASE_URL}/api/public/businesses/${encodeURIComponent(slug)}/bookings`, {
      method: 'POST',
      credentials: 'omit',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body,
    })
  } catch {
    return { kind: 'uncertain', retryAfterSeconds: null }
  }

  const retryAfterSeconds = parseRetryAfter(response.headers.get('Retry-After'), now)

  if (response.status === 200 || response.status === 201) {
    const decoded = decodeConfirmedBooking(await readJson(response))
    if (!decoded) return { kind: 'uncertain', retryAfterSeconds: null }
    return { kind: 'success', replayed: response.status === 200, booking: decoded }
  }

  const problem = await readJson(response)
  const code = problemCode(problem)

  if (response.status === 429 && code === 'RATE_LIMITED') {
    return { kind: 'rate-limited', retryAfterSeconds }
  }
  if (response.status === 503 && code === 'BOOKING_TEMPORARILY_UNAVAILABLE') {
    return { kind: 'rolled-back', retryAfterSeconds }
  }
  const documented =
    code !== undefined && Object.hasOwn(REJECTIONS, code) ? REJECTIONS[code] : undefined
  if (documented && documented.status === response.status) {
    const fieldErrors = documented.reason === 'validation' ? decodeFieldErrors(problem) : undefined
    return fieldErrors
      ? { kind: 'rejected', reason: documented.reason, fieldErrors }
      : { kind: 'rejected', reason: documented.reason }
  }
  // Everything else cannot safely establish the result: `BOOKING_OUTCOME_UNCERTAIN`, any other 5xx,
  // a 408, and any 4xx that is not a documented code with its documented status (a proxy refusal,
  // a code on the wrong status, an unknown code, an unreadable body) may follow a commit.
  return { kind: 'uncertain', retryAfterSeconds }
}
