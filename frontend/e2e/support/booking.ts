import { expect, type APIRequestContext, type Browser, type BrowserContext, type Locator, type Page, type Request } from '@playwright/test'
import { apiRequest } from './browser'
import { API_ORIGIN } from './environment'
import {
  provisionBusiness,
  type BusinessSpec,
  type ProvisionedBusiness,
  type ServiceSeed,
} from './publicProfile'

// ---------------------------------------------------------------------------------------------
// Approved public wording (the frontend maps every stable problem code to one fixed sentence).
// ---------------------------------------------------------------------------------------------

export const MESSAGES = {
  identityConflict:
    'Телефонът и имейлът не съответстват. Проверете ги или въведете само единия контакт.',
  slotUnavailable: 'Избраният час вече не е свободен. Изберете друг час.',
  serviceUnavailable: 'Избраната услуга вече не е налична. Изберете друга услуга.',
  reviewChanged:
    'Условията на избраните услуги са променени. Прегледайте ги и потвърдете отново.',
  noStaffForSelection: 'В момента няма служител, който предлага всички избрани услуги.',
  limitCount: 'Не може да се добави: най-много 5 услуги.',
  limitDuration: 'Не може да се добави: общо най-много 8 часа.',
  noSelection: 'Изберете поне една услуга.',
  tooLong: 'Избраните услуги са с обща продължителност над 8 часа. Изберете по-малко услуги.',
} as const

export const NO_AVAILABLE_DATES_PATTERN = /Няма свободни дати/

const MONTHS = [
  'януари', 'февруари', 'март', 'април', 'май', 'юни',
  'юли', 'август', 'септември', 'октомври', 'ноември', 'декември',
]

/** «42.00 € — 75 мин.»: the approved price-first presentation. */
export function factsText(priceCents: number, minutes: number): string {
  return `${(priceCents / 100).toFixed(2)} € — ${minutes} мин.`
}

export function totalText(priceCents: number, minutes: number): string {
  return `Общо: ${factsText(priceCents, minutes)}`
}

// ---------------------------------------------------------------------------------------------
// Fixtures: Businesses, StaffMembers, Services, schedules, through supported APIs only.
// ---------------------------------------------------------------------------------------------

export type StaffSeed = {
  name: string
  services: string[]
  // Omitted: no working periods at all (an eligible StaffMember without any availability).
  hours?: { start: string, end: string, days?: Weekday[] }
}

export type Weekday = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'

const WORKING_WEEK: Weekday[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY']

export type BookingBusiness = ProvisionedBusiness & {
  staffIds: Record<string, string>
}

type Versioned = { id: string, version: number }

async function expectOk(result: { status: number, code: string | null }, label: string, status = 200) {
  expect(result.status, `${label}: ${result.code ?? ''}`).toBe(status)
}

export async function createStaffMember(owner: Page, seed: StaffSeed, serviceIds: Record<string, string>, tag: string): Promise<string> {
  const created = await apiRequest(owner, 'POST', '/api/business/staff-members', {
    displayName: seed.name,
    contactEmail: `staff-${tag}@example.invalid`,
    contactPhone: null,
  })
  await expectOk(created, `staff ${seed.name}`, 201)
  const staff = JSON.parse(created.text) as Versioned
  const assignments = await apiRequest(owner, 'GET', `/api/business/staff-members/${staff.id}/service-assignments`)
  await expectOk(assignments, 'assignments read')
  const assignmentVersion = (JSON.parse(assignments.text) as { version: number }).version
  const assigned = await apiRequest(owner, 'PUT', `/api/business/staff-members/${staff.id}/service-assignments`, {
    serviceIds: seed.services.map((name) => {
      const id = serviceIds[name]
      if (!id) throw new Error(`Unknown fixture Service ${name}`)
      return id
    }),
    expectedVersion: assignmentVersion,
  })
  await expectOk(assigned, 'assignments write')
  if (seed.hours) {
    const schedule = await apiRequest(owner, 'GET', `/api/business/staff-members/${staff.id}/working-schedule`)
    await expectOk(schedule, 'schedule read')
    const scheduleVersion = (JSON.parse(schedule.text) as { version: number }).version
    const written = await apiRequest(owner, 'PUT', `/api/business/staff-members/${staff.id}/working-schedule`, {
      periods: (seed.hours.days ?? WORKING_WEEK).map((weekday) => ({
        weekday,
        startTime: seed.hours!.start,
        endTime: seed.hours!.end,
      })),
      expectedVersion: scheduleVersion,
    })
    await expectOk(written, 'schedule write')
  }
  return staff.id
}

let staffCounter = 0

/**
 * Provisions an ACTIVE Business with Services and StaffMembers. The owner session stays open (it is
 * the supported route for later price, duration and lifecycle changes) and must be closed by the
 * caller through `business.owner.context.close()`.
 */
export async function provisionBookingBusiness(
  browser: Browser,
  adminContext: BrowserContext,
  admin: Page,
  spec: { name: string, slugBase: string, services: ServiceSeed[], staff: StaffSeed[] },
): Promise<BookingBusiness> {
  const businessSpec: BusinessSpec = {
    name: spec.name,
    slugBase: spec.slugBase,
    businessType: 'BEAUTY_STUDIO',
    phone: '+359 88 123 4567',
    description: 'Синтетичен бизнес за проверка на резервации.',
    services: spec.services,
    finalState: 'ACTIVE',
  }
  const business = await provisionBusiness(browser, adminContext, admin, businessSpec, { keepOwner: true })
  const owner = business.owner
  if (!owner) throw new Error('The booking fixture needs an open owner session')
  const staffIds: Record<string, string> = {}
  try {
    for (const seed of spec.staff) {
      staffCounter += 1
      staffIds[seed.name] = await createStaffMember(
        owner.page, seed, business.serviceIds, `${business.slug}-${staffCounter}`,
      )
    }
  } catch (error) {
    await owner.context.close()
    throw error
  }
  return { ...business, staffIds }
}

export async function closeOwner(business: BookingBusiness): Promise<void> {
  await business.owner?.context.close()
}

type ServiceBody = {
  name: string
  description: string | null
  durationMinutes: number
  price: number
  version: number
}

async function readService(owner: Page, id: string): Promise<ServiceBody> {
  const result = await apiRequest(owner, 'GET', `/api/business/services/${id}`)
  await expectOk(result, 'service read')
  return JSON.parse(result.text) as ServiceBody
}

/** An owner change through the supported API; used to prove the real review-consistency check. */
export async function changeService(
  owner: Page,
  id: string,
  changes: { durationMinutes?: number, price?: string },
): Promise<void> {
  const current = await readService(owner, id)
  const result = await apiRequest(owner, 'PUT', `/api/business/services/${id}`, {
    name: current.name,
    description: current.description,
    durationMinutes: changes.durationMinutes ?? current.durationMinutes,
    price: changes.price ?? current.price,
    expectedVersion: current.version,
  })
  await expectOk(result, 'service update')
}

export async function setServiceActive(owner: Page, id: string, active: boolean): Promise<void> {
  const current = await readService(owner, id)
  const result = await apiRequest(owner, 'POST', `/api/business/services/${id}/${active ? 'reactivate' : 'deactivate'}`, {
    expectedVersion: current.version,
  })
  await expectOk(result, active ? 'service reactivate' : 'service deactivate')
}

/** The number of Customers of the owner's active Business (an observable effect of public booking). */
export async function ownerCustomerTotal(owner: Page): Promise<number> {
  const result = await apiRequest(owner, 'GET', '/api/business/customers?size=10')
  await expectOk(result, 'customer total')
  return (JSON.parse(result.text) as { total: number }).total
}

export async function ownerCustomers(owner: Page, search: string): Promise<{ displayName: string, phone: string | null, email: string | null }[]> {
  const result = await apiRequest(owner, 'POST', '/api/business/customers/search', {
    search, page: 0, size: 10, sort: 'name', direction: 'asc',
  })
  await expectOk(result, 'customer search')
  return (JSON.parse(result.text) as { items: { displayName: string, phone: string | null, email: string | null }[] }).items
}

// ---------------------------------------------------------------------------------------------
// Public API (no session, no cookie): used to read the contract and to book as another guest.
// ---------------------------------------------------------------------------------------------

export type BookingOptionsBody = { timezone: string, firstDate: string, lastDate: string, staff: { id: string, displayName: string }[] }
export type AvailabilityBody = { date: string, timezone: string, availableDates: string[], slots: { start: string, end: string }[] }

function serviceQuery(serviceIds: readonly string[]): string {
  return serviceIds.map((id) => `serviceId=${encodeURIComponent(id)}`).join('&')
}

export async function publicOptions(request: APIRequestContext, slug: string, serviceIds: readonly string[]): Promise<BookingOptionsBody> {
  const response = await request.get(`${API_ORIGIN}/api/public/businesses/${slug}/booking-options?${serviceQuery(serviceIds)}`)
  expect(response.status()).toBe(200)
  return await response.json() as BookingOptionsBody
}

export async function publicAvailability(
  request: APIRequestContext,
  slug: string,
  serviceIds: readonly string[],
  date: string,
  staffId: string | null = null,
): Promise<AvailabilityBody> {
  const staff = staffId ? `&staffMemberId=${encodeURIComponent(staffId)}` : ''
  const response = await request.get(
    `${API_ORIGIN}/api/public/businesses/${slug}/availability?${serviceQuery(serviceIds)}&date=${date}${staff}`,
  )
  expect(response.status()).toBe(200)
  return await response.json() as AvailabilityBody
}

export type PublicBookingCall = { status: number, code: string | null, body: unknown, headers: Record<string, string> }

export async function publicBook(request: APIRequestContext, slug: string, body: unknown): Promise<PublicBookingCall> {
  const response = await request.post(`${API_ORIGIN}/api/public/businesses/${slug}/bookings`, { data: body })
  const text = await response.text()
  let parsed: unknown = null
  try {
    parsed = JSON.parse(text)
  } catch {
    // Not JSON; the body stays null.
  }
  const code = parsed && typeof parsed === 'object' && 'code' in parsed && typeof (parsed as { code: unknown }).code === 'string'
    ? (parsed as { code: string }).code
    : null
  return { status: response.status(), code, body: parsed, headers: response.headers() }
}

/** The reviewed terms of a Service line exactly as the live public profile shows them. */
export async function reviewedLines(request: APIRequestContext, slug: string, serviceIds: readonly string[]) {
  const response = await request.get(`${API_ORIGIN}/api/public/businesses/${slug}`)
  expect(response.status()).toBe(200)
  const profile = await response.json() as { services: { id: string, durationMinutes: number, price: number }[] }
  return serviceIds
    .map((id) => {
      const line = profile.services.find((service) => service.id === id)
      if (!line) throw new Error('Service is not in the public profile')
      return { serviceId: id, reviewedDurationMinutes: line.durationMinutes, reviewedPriceCents: Math.round(line.price * 100) }
    })
    .sort((left, right) => (left.serviceId < right.serviceId ? -1 : 1))
}

/** The canonical request order: unsigned byte order of the UUID, i.e. lowercase text order. */
export function canonicalOrder(ids: readonly string[]): string[] {
  return [...ids].sort((left, right) => (left.toLowerCase() < right.toLowerCase() ? -1 : 1))
}

/** The `HH:MM` of an offset timestamp as the interface shows it (the offset text is not converted). */
export function clockOf(start: string): string {
  return start.slice(11, 16)
}

let phoneCounter = 100 + Math.floor(Math.random() * 400)

/** A fresh synthetic canonical Bulgarian mobile number, unique within the run. */
export function freshPhone(): string {
  phoneCounter += 1
  return `+359885550${String(phoneCounter).padStart(3, '0')}`
}

export function freshEmail(tag: string): string {
  phoneCounter += 1
  return `${tag}-${phoneCounter}@example.invalid`
}

export function dayNumber(isoDate: string): number {
  return Number(isoDate.slice(8, 10))
}

// ---------------------------------------------------------------------------------------------
// Traffic audit: every request and response between the page and the API origin.
// ---------------------------------------------------------------------------------------------

export type RecordedCall = {
  method: string
  path: string
  search: string
  body: string | null
  hasCookie: boolean
  status: number | null
  noStore: boolean
  setCookie: boolean
  credentialsFree: boolean
}

type PendingCall = {
  request: Request
  aborted: boolean
  settled: Promise<RecordedCall | { failure: string }>
}

export type TrafficAudit = {
  /**
   * Stops listening, waits for every started header read and response, and only then returns the
   * calls. A header read that failed for a request the page did not abort throws: a failed read is
   * never evidence that no cookie was sent. Call it before navigating on or closing the context.
   */
  stop(): Promise<RecordedCall[]>
}

export function recordTraffic(page: Page): TrafficAudit {
  const pending = new Map<Request, PendingCall>()
  const onRequest = (request: Request): void => {
    if (!request.url().startsWith(API_ORIGIN)) return
    const url = new URL(request.url())
    const settled = (async (): Promise<RecordedCall | { failure: string }> => {
      try {
        const headers = await request.allHeaders()
        const response = await request.response()
        const responseHeaders = response ? await response.allHeaders() : {}
        return {
          method: request.method(),
          path: url.pathname,
          search: url.search,
          body: request.postData(),
          hasCookie: 'cookie' in headers,
          status: response ? response.status() : null,
          noStore: (responseHeaders['cache-control'] ?? '').includes('no-store'),
          setCookie: 'set-cookie' in responseHeaders,
          credentialsFree: !('cookie' in headers) && !('authorization' in headers),
        }
      } catch (error) {
        return { failure: error instanceof Error ? error.message : String(error) }
      }
    })()
    pending.set(request, { request, aborted: false, settled })
  }
  const onFailed = (request: Request): void => {
    const entry = pending.get(request)
    if (entry && request.failure()?.errorText.includes('ABORTED')) entry.aborted = true
  }
  page.on('request', onRequest)
  page.on('requestfailed', onFailed)

  let stopped: Promise<RecordedCall[]> | null = null
  const settle = async (): Promise<RecordedCall[]> => {
    page.off('request', onRequest)
    page.off('requestfailed', onFailed)
    const calls: RecordedCall[] = []
    const failures: string[] = []
    for (const entry of pending.values()) {
      const outcome = await entry.settled
      if (entry.aborted) continue
      if ('failure' in outcome) {
        failures.push(outcome.failure)
        continue
      }
      calls.push(outcome)
    }
    if (failures.length > 0) {
      throw new Error(`The traffic audit could not read a request or response (${failures.join('; ')})`)
    }
    return calls
  }
  return { stop: () => (stopped ??= settle()) }
}

export function callsTo(calls: readonly RecordedCall[], method: string, pathEnd: string): RecordedCall[] {
  return calls.filter((call) => call.method === method && call.path.endsWith(pathEnd))
}

export function postBody(call: RecordedCall): Record<string, unknown> {
  if (call.body === null) throw new Error('The request had no body')
  return JSON.parse(call.body) as Record<string, unknown>
}

/** Every public read and POST carried no cookie, no authorization and no Set-Cookie, and is no-store. */
export function expectPublicTrafficPrivate(calls: readonly RecordedCall[]): void {
  expect(calls.length).toBeGreaterThan(0)
  for (const call of calls) {
    expect(call.credentialsFree, `${call.method} ${call.path} sent credentials`).toBe(true)
    expect(call.hasCookie, `${call.method} ${call.path} sent a cookie`).toBe(false)
    expect(call.setCookie, `${call.method} ${call.path} set a cookie`).toBe(false)
    expect(call.noStore, `${call.method} ${call.path} was cacheable`).toBe(true)
  }
}

// ---------------------------------------------------------------------------------------------
// The guest journey driver.
// ---------------------------------------------------------------------------------------------

export type GuestDetails = { name: string, phone?: string, email?: string, note?: string }

export class Guest {
  constructor(readonly page: Page) {}

  heading(): Locator {
    return this.page.locator('#booking-step-heading')
  }

  button(name: string): Locator {
    return this.page.locator('.booking-journey').getByRole('button', { name, exact: true })
  }

  async open(slug: string): Promise<void> {
    await this.page.goto(`/${slug}`)
    await expect(this.page.getByRole('heading', { level: 1 })).toBeVisible()
  }

  async start(): Promise<void> {
    await this.page.getByRole('button', { name: 'Запази час', exact: true }).click()
    await expect(this.heading()).toHaveText('Избор на услуги')
  }

  choice(name: string): Locator {
    return this.page.locator('label.booking-choice', {
      has: this.page.locator('.booking-choice-title', { hasText: new RegExp(`^${escapeRegExp(name)}$`) }),
    })
  }

  checkbox(name: string): Locator {
    return this.choice(name).locator('input[type=checkbox]')
  }

  async select(...names: string[]): Promise<void> {
    for (const name of names) await this.choice(name).click()
  }

  async next(): Promise<void> {
    await this.button('Напред').click()
  }

  async toStaff(): Promise<void> {
    await this.next()
    await expect(this.heading()).toHaveText('Избор на служител')
  }

  async chooseStaff(name: string | null): Promise<void> {
    await this.choice(name ?? 'Без предпочитание').click()
  }

  /** From the staff step to the date step. */
  async toDate(): Promise<void> {
    await this.next()
    await expect(this.heading()).toHaveText('Дата и час')
  }

  async chooseDate(isoDate: string): Promise<void> {
    const [year, month, day] = isoDate.split('-').map(Number) as [number, number, number]
    const cell = this.page.getByRole('gridcell', {
      name: new RegExp(`, ${day} ${MONTHS[month - 1]} ${year} г\\.`),
    })
    await expect(this.page.getByRole('grid')).toBeVisible()
    for (let hop = 0; hop < 3 && (await cell.count()) === 0; hop += 1) {
      await this.page.getByRole('button', { name: 'Следващ месец' }).click()
    }
    await cell.click()
  }

  slot(start: string): Locator {
    return this.page.locator(`label.booking-slot:has(input[value="${start}"])`)
  }

  async chooseSlot(start: string): Promise<void> {
    await this.slot(start).click()
  }

  async toDetails(): Promise<void> {
    await this.next()
    await expect(this.heading()).toHaveText('Вашите данни')
  }

  async fillDetails(details: GuestDetails): Promise<void> {
    await this.page.getByLabel('Име', { exact: true }).fill(details.name)
    await this.page.getByLabel('Телефон', { exact: true }).fill(details.phone ?? '')
    await this.page.getByLabel('Имейл', { exact: true }).fill(details.email ?? '')
    if (details.note !== undefined) await this.page.getByLabel('Бележка (по желание)').fill(details.note)
  }

  async toReview(): Promise<void> {
    // «Напред» on the first pass, «Към прегледа» when the guest returned from the review to edit.
    await this.button('Напред').or(this.button('Към прегледа')).click()
    await expect(this.heading()).toHaveText('Преглед и потвърждение')
  }

  /** The whole journey from the Business page to the review step. */
  async toReviewFor(options: {
    services: string[]
    staff?: string | null
    date: string
    slot: string
    details: GuestDetails
  }): Promise<void> {
    await this.start()
    await this.select(...options.services)
    await this.toStaff()
    await this.chooseStaff(options.staff ?? null)
    await this.toDate()
    await this.chooseDate(options.date)
    await this.chooseSlot(options.slot)
    await this.toDetails()
    await this.fillDetails(options.details)
    await this.toReview()
  }

  async confirm(): Promise<void> {
    await this.button('Потвърди резервацията').click()
  }

  summary(): Locator {
    return this.page.locator('.booking-summary')
  }

  alert(): Locator {
    return this.page.locator('.booking-journey').getByRole('alert')
  }
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** The first `count` dates, after `skip`, on which the public availability offers the selection a slot. */
export async function availableDates(
  request: APIRequestContext,
  slug: string,
  serviceIds: readonly string[],
  staffId: string | null = null,
): Promise<string[]> {
  const options = await publicOptions(request, slug, serviceIds)
  const probe = await publicAvailability(request, slug, serviceIds, options.firstDate, staffId)
  return probe.availableDates
}

export async function slotsOn(
  request: APIRequestContext,
  slug: string,
  serviceIds: readonly string[],
  date: string,
  staffId: string | null = null,
): Promise<{ start: string, end: string }[]> {
  return (await publicAvailability(request, slug, serviceIds, date, staffId)).slots
}

// ---------------------------------------------------------------------------------------------
// Browser-state privacy.
// ---------------------------------------------------------------------------------------------

/** Storage, cookies, URL, title and history state hold no sentinel, and nothing is stored at all. */
export async function expectJourneyStateClean(page: Page, sentinels: readonly string[]): Promise<void> {
  const state = await page.evaluate(async (terms) => {
    const lower = terms.map((term) => term.toLowerCase())
    const has = (text: string) => lower.some((term) => text.toLowerCase().includes(term))
    const databases = await indexedDB.databases()
    const history_ = JSON.stringify(history.state ?? null)
    return {
      url: has(decodeURIComponent(location.href)),
      title: has(document.title),
      local: localStorage.length,
      session: sessionStorage.length,
      indexedDatabases: databases.length,
      cookie: document.cookie,
      historyState: has(history_),
      historyKeys: Object.keys((history.state ?? {}) as Record<string, unknown>),
      historyMarker: Object.keys(((history.state ?? {}) as { spyBooking?: Record<string, unknown> }).spyBooking ?? {}).sort(),
    }
  }, [...sentinels])
  expect(state).toEqual({
    url: false,
    title: false,
    local: 0,
    session: 0,
    indexedDatabases: 0,
    cookie: '',
    historyState: false,
    historyKeys: ['spyBooking'],
    historyMarker: ['journey', 'step'],
  })
}

export type ConsoleRecorder = { stop(): string[] }

export function recordConsole(page: Page): ConsoleRecorder {
  const lines: string[] = []
  const onConsole = (message: { type(): string, text(): string }): void => {
    lines.push(`${message.type()}: ${message.text()}`)
  }
  page.on('console', onConsole)
  return {
    stop() {
      page.off('console', onConsole)
      return lines
    },
  }
}

// ---------------------------------------------------------------------------------------------
// Shared fixture catalogue for the booking specs.
// ---------------------------------------------------------------------------------------------

export const SALON_SERVICES: ServiceSeed[] = [
  { name: 'Боядисване на коса', description: 'Цялостно боядисване с консултация за цвета.', durationMinutes: 120, price: '80.50' },
  { name: 'Дамско подстригване', description: 'Измиване, подстригване и оформяне.', durationMinutes: 45, price: '25.00' },
  { name: 'Маникюр', description: 'Класически маникюр.', durationMinutes: 60, price: '35.00' },
  { name: 'Масаж на лице', durationMinutes: 90, price: '55.50' },
  { name: 'Оформяне на вежди', durationMinutes: 15, price: '9.99' },
  { name: 'Педикюр', durationMinutes: 75, price: '42.00' },
  { name: 'Терапия за скалп', durationMinutes: 135, price: '60.25' },
  { name: 'Цялостна грижа', durationMinutes: 150, price: '120.00' },
]

const ALL_BUT_BROWS = SALON_SERVICES.map((service) => service.name).filter((name) => name !== 'Оформяне на вежди')

export const SALON_STAFF: StaffSeed[] = [
  // Offers every Service except the brows: the only member for «Масаж на лице».
  { name: 'Анна Георгиева', services: ALL_BUT_BROWS, hours: { start: '09:00', end: '18:00' } },
  // The only member for the brows; a partial set.
  { name: 'Борис Димитров', services: ['Дамско подстригване', 'Маникюр', 'Оформяне на вежди', 'Боядисване на коса'], hours: { start: '10:00', end: '19:00' } },
]

/** A live count of booking POSTs the page has sent (readable at any time; the audit is final only at stop). */
export function countBookingPosts(page: Page): { count(): number, attemptIds(): string[], stop(): void } {
  let posts = 0
  const attempts: string[] = []
  const onRequest = (request: Request): void => {
    if (request.method() !== 'POST' || !request.url().endsWith('/bookings')) return
    posts += 1
    try {
      const body = JSON.parse(request.postData() ?? '{}') as { attemptId?: string }
      if (body.attemptId) attempts.push(body.attemptId)
    } catch {
      // A body that is not JSON has no attempt identifier to record.
    }
  }
  page.on('request', onRequest)
  return { count: () => posts, attemptIds: () => [...attempts], stop: () => { page.off('request', onRequest) } }
}

/** The response to the next booking POST of the page (register before the click that sends it). */
export function nextBookingResponse(page: Page) {
  return page.waitForResponse((response) =>
    response.url().endsWith('/bookings') && response.request().method() === 'POST')
}
