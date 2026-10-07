import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { vi } from 'vitest'
import { PublicBusinessPage } from '../PublicBusinessPage'

// A deterministic stand-in for the public HTTP API. It speaks the real Phase 5 response shapes
// (ADR-0026), answers every request through a handler the test can replace, and lets a test hold a
// response open (`deferred`) so overlapping requests are ordered by the test and never by a timer.

export const SLUG = 'example-studio'
export const SERVICE_HAIR = '11111111-1111-4111-8111-111111111111'
export const SERVICE_COLOUR = '22222222-2222-4222-8222-222222222222'
export const STAFF_MARIA = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
export const STAFF_GEORGI = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
export const ATTEMPT_ONE = '5b0f7c2e-8d7a-4c1e-9a55-0b6f3c1d2e01'

export const profileBody = {
  slug: SLUG,
  displayName: 'Примерно студио',
  businessType: 'HAIR_SALON',
  description: 'Уютно студио в центъра.',
  phone: '+359 88 000 0000',
  address: null,
  services: [
    {
      id: SERVICE_HAIR,
      name: 'Подстригване',
      description: 'Измиване и оформяне.',
      durationMinutes: 45,
      price: 25,
    },
    { id: SERVICE_COLOUR, name: 'Боядисване', description: null, durationMinutes: 120, price: 80.5 },
  ],
}

export const optionsBody = {
  timezone: 'Europe/Sofia',
  firstDate: '2026-10-07',
  lastDate: '2026-11-05',
  staff: [
    { id: STAFF_MARIA, displayName: 'Мария Иванова' },
    { id: STAFF_GEORGI, displayName: 'Георги Петров' },
  ],
}

export const AVAILABLE_DATES = ['2026-10-07', '2026-10-08', '2026-10-25']

export const SLOTS_BY_DATE: Record<string, { start: string; end: string }[]> = {
  '2026-10-07': [
    { start: '2026-10-07T10:00:00+03:00', end: '2026-10-07T10:45:00+03:00' },
    { start: '2026-10-07T11:00:00+03:00', end: '2026-10-07T11:45:00+03:00' },
  ],
  '2026-10-08': [{ start: '2026-10-08T09:00:00+03:00', end: '2026-10-08T09:45:00+03:00' }],
  // The clocks go back on 2026-10-25 at 04:00: the wall-clock time 03:30 occurs twice.
  '2026-10-25': [
    { start: '2026-10-25T03:30:00+03:00', end: '2026-10-25T03:15:00+02:00' },
    { start: '2026-10-25T03:30:00+02:00', end: '2026-10-25T04:15:00+02:00' },
  ],
}

export function availabilityBody(date: string, dates: string[] = AVAILABLE_DATES) {
  return {
    date,
    timezone: 'Europe/Sofia',
    availableDates: dates,
    slots: SLOTS_BY_DATE[date] ?? [],
  }
}

export const bookingBody = {
  reference: 'K7M2Q9XW4B',
  status: 'CONFIRMED',
  service: { name: 'Подстригване', durationMinutes: 45, price: 25 },
  staff: { displayName: 'Мария Иванова' },
  start: '2026-10-07T10:00:00+03:00',
  end: '2026-10-07T10:45:00+03:00',
  timezone: 'Europe/Sofia',
}

export function problem(code: string, status: number, extra: Record<string, unknown> = {}) {
  return {
    status,
    code,
    title: 'Заявката не може да бъде изпълнена.',
    detail: 'Безопасно съобщение.',
    instance: '/api/public/businesses',
    ...extra,
  }
}

export function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

export type Deferred<T> = {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason: unknown) => void
}

export function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

export type Recorded = {
  method: string
  url: URL
  path: string
  body: string | null
  credentials: RequestCredentials | undefined
  headers: Record<string, string>
  signal: AbortSignal | undefined
}

type Handler = (request: Recorded) => Response | Promise<Response> | undefined

export class FakeServer {
  readonly requests: Recorded[] = []
  private readonly handlers: Handler[] = []

  constructor() {
    this.handlers.push(defaultHandler)
  }

  /** A handler added later wins; it returns undefined to leave the request to the earlier ones. */
  on(handler: Handler) {
    this.handlers.unshift(handler)
  }

  fetch = (input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> => {
    const url = new URL(String(input))
    const headers: Record<string, string> = {}
    new Headers(init.headers).forEach((value, key) => {
      headers[key] = value
    })
    const request: Recorded = {
      method: init.method ?? 'GET',
      url,
      path: url.pathname,
      body: typeof init.body === 'string' ? init.body : null,
      credentials: init.credentials,
      headers,
      signal: init.signal ?? undefined,
    }
    this.requests.push(request)
    for (const handler of this.handlers) {
      const response = handler(request)
      if (response !== undefined) return withAbort(Promise.resolve(response), request.signal)
    }
    return Promise.reject(new TypeError('no handler'))
  }

  of(method: string, pathEnd: string): Recorded[] {
    return this.requests.filter(
      (request) => request.method === method && request.path.endsWith(pathEnd),
    )
  }

  get posts(): Recorded[] {
    return this.of('POST', '/bookings')
  }
}

function withAbort(promise: Promise<Response>, signal: AbortSignal | undefined): Promise<Response> {
  if (!signal) return promise
  return new Promise<Response>((resolve, reject) => {
    const abort = () => reject(new DOMException('The operation was aborted.', 'AbortError'))
    if (signal.aborted) {
      abort()
      return
    }
    signal.addEventListener('abort', abort, { once: true })
    promise.then(resolve, reject)
  })
}

function defaultHandler(request: Recorded): Response | undefined {
  const { method, path, url } = request
  if (method === 'GET' && path === `/api/public/businesses/${SLUG}`) return json(200, profileBody)
  if (method === 'GET' && path.endsWith('/booking-options')) return json(200, optionsBody)
  if (method === 'GET' && path.endsWith('/availability')) {
    return json(200, availabilityBody(url.searchParams.get('date') ?? ''))
  }
  if (method === 'POST' && path.endsWith('/bookings')) return json(201, bookingBody)
  return undefined
}

export function installServer(): FakeServer {
  const server = new FakeServer()
  vi.stubGlobal('fetch', server.fetch)
  return server
}

/** Lets resolved promises, state updates and effects settle without any timer. */
export async function flush(rounds = 12) {
  await act(async () => {
    for (let index = 0; index < rounds; index += 1) await Promise.resolve()
  })
}

export async function openPage(slug = SLUG) {
  const view = render(<PublicBusinessPage key={slug} slug={slug} />)
  await flush()
  return view
}

export async function click(element: HTMLElement) {
  fireEvent.click(element)
  await flush()
}

export function button(name: string | RegExp) {
  return screen.getByRole('button', { name })
}

export function heading() {
  return screen.getByRole('heading', { level: 1 })
}

export async function startFromHero() {
  await click(button('Запази час'))
}

/** The hero action, then the named Service at the first step, then the StaffMember step. */
export async function startFromService(name: string) {
  await startFromHero()
  await click(screen.getByRole('radio', { name: new RegExp(name) }))
  await next()
}

export async function next() {
  await click(button('Напред'))
}

export async function chooseStaff(label: string) {
  await click(screen.getByRole('radio', { name: new RegExp(label) }))
}

/** A date by its long name; the cell name may carry a suffix (today, no free time). */
export async function pickDate(longName: string | RegExp) {
  const name =
    typeof longName === 'string'
      ? new RegExp(`^${longName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)
      : longName
  await click(screen.getByRole('gridcell', { name }))
}

export async function pickSlot(text: string | RegExp) {
  const group = screen.getByRole('group', { name: 'Свободни часове' })
  await click(within(group).getByRole('radio', { name: text }))
}

export function typeInto(label: string | RegExp, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

export async function fillDetails(
  values: { name?: string; phone?: string; email?: string; note?: string } = {},
) {
  typeInto('Име', values.name ?? 'Иван Петров')
  typeInto('Телефон', values.phone ?? '0888 123 456')
  if (values.email) typeInto('Имейл', values.email)
  if (values.note) typeInto(/Бележка/, values.note)
  await flush()
}

/** Service -> staff preference -> first date and first slot -> details -> review. */
export async function reachReview(
  options: { staff?: string; slot?: string | RegExp; details?: Parameters<typeof fillDetails>[0] } = {},
) {
  await startFromService('Подстригване')
  if (options.staff) await chooseStaff(options.staff)
  await next()
  await pickSlot(options.slot ?? '10:00')
  await next()
  await fillDetails(options.details)
  await next()
}

export async function submit(label: string | RegExp = 'Потвърди резервацията') {
  await click(button(label))
}

/** Makes the browser's secure UUID source return the given ids in order, then real random ones. */
export function stubAttemptIds(...ids: string[]) {
  const queue = [...ids]
  const original = crypto.randomUUID.bind(crypto)
  return vi.spyOn(crypto, 'randomUUID').mockImplementation(() => (queue.shift() ?? original()) as `${string}-${string}-${string}-${string}-${string}`)
}

export function parsedBody(request: Recorded | undefined) {
  return JSON.parse(request?.body ?? 'null') as Record<string, unknown>
}
