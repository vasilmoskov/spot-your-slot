import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { apiRequest, expectBrowserStorageEmpty, expectNoHorizontalOverflow } from './support/browser'
import {
  canonicalOrder,
  closeOwner,
  freshPhone,
  provisionBookingBusiness,
  publicBook,
  reviewedLines,
  type BookingBusiness,
} from './support/booking'
import { expectNoSentinelInBrowser, seedCustomer, type CustomerRecord } from './support/customers'
import { signInAsPlatformAdmin } from './support/provisioning'
import { transitionBusiness } from './support/publicProfile'
import { addDays, businessToday } from './support/scheduleChanges'
import { API_ORIGIN } from './support/environment'

// Real-browser journeys of the Business calendar and Appointment management (issues #19 and #21, phase 12) against the
// real backend and PostgreSQL. Everything the backend decides (availability, conflicts, versions, lifecycle,
// authorization) is real. The one injected scenario is labelled PROXY: the request is really sent and committed, and
// only its response is dropped, so the unresolved-outcome interface can be shown against a real final state.

const TAG = Math.random().toString(36).slice(2, 7)
const HAIRCUT = 'Дамско подстригване'
const BROWS = 'Оформяне на вежди'
const MANICURE = 'Маникюр'
const ANNA = 'Анна Георгиева'
const BORIS = 'Борис Димитров'

const SERVICES = [
  { name: HAIRCUT, description: 'Измиване, подстригване и оформяне.', durationMinutes: 45, price: '25.00' },
  { name: BROWS, description: null, durationMinutes: 15, price: '9.99' },
  { name: MANICURE, description: 'Класически маникюр.', durationMinutes: 60, price: '35.00' },
]
const STAFF = [
  { name: ANNA, services: [HAIRCUT, BROWS, MANICURE], hours: { start: '09:00', end: '18:00' } },
  { name: BORIS, services: [HAIRCUT, BROWS, MANICURE], hours: { start: '09:00', end: '18:00' } },
]

type Slot = { start: string, end: string }
type Created = { id: string, version: number, start: string, end: string }

let adminContext: BrowserContext
let admin: Page
let salon: BookingBusiness
let other: BookingBusiness
let owner: Page
let anna: string
let boris: string
let customerOne: CustomerRecord
let customerTwo: CustomerRecord
let customerThree: CustomerRecord
let dayOne = ''
let dayTwo = ''
let dayThree = ''

test.describe.configure({ mode: 'serial' })

function serviceId(name: string): string {
  const id = salon.serviceIds[name]
  if (!id) throw new Error(`Unknown fixture Service ${name}`)
  return id
}

async function ownerJson<T>(path: string): Promise<T> {
  const result = await apiRequest(owner, 'GET', path)
  expect(result.status, `${path}: ${result.code ?? ''}`).toBe(200)
  return JSON.parse(result.text) as T
}

async function slots(staffId: string, names: string[], date: string): Promise<Slot[]> {
  const query = names.map((name) => `serviceId=${serviceId(name)}`).join('&')
  const read = await ownerJson<{ slots: Slot[] }>(
    `/api/business/appointment-options/availability?${query}&staffMemberId=${staffId}&date=${date}`,
  )
  return read.slots
}

/** Creates an Appointment through the supported Business API (the fixture route; the UI forms are driven elsewhere). */
async function createVisit(customer: CustomerRecord, staffId: string, names: string[], start: string): Promise<Created> {
  const lines = canonicalOrder(names.map(serviceId)).map((id) => {
    const seed = SERVICES.find((service) => salon.serviceIds[service.name] === id)!
    return {
      serviceId: id,
      reviewedServiceName: seed.name,
      reviewedDurationMinutes: seed.durationMinutes,
      reviewedPriceCents: Math.round(Number(seed.price) * 100),
    }
  })
  const result = await apiRequest(owner, 'POST', '/api/business/appointments', {
    customerId: customer.id,
    staffMemberId: staffId,
    start,
    services: lines,
  })
  expect(result.status, `create visit ${start} ${names.join('+')}: ${result.code ?? ''} ${result.text.slice(0, 200)}`).toBe(201)
  return JSON.parse(result.text) as Created
}

function createRaw(customer: CustomerRecord, staffId: string, start: string) {
  const seed = SERVICES[0]!
  return apiRequest(owner, 'POST', '/api/business/appointments', {
    customerId: customer.id,
    staffMemberId: staffId,
    start,
    services: [{
      serviceId: serviceId(seed.name),
      reviewedServiceName: seed.name,
      reviewedDurationMinutes: seed.durationMinutes,
      reviewedPriceCents: Math.round(Number(seed.price) * 100),
    }],
  })
}

async function detailOf(id: string) {
  return ownerJson<{
    status: string
    start: string
    staffMemberId: string
    internalNote: string | null
    version: number
    cancellation?: { cancelledAt: string } | null
  }>(`/api/business/appointments/${id}`)
}

async function firstDatesWithSlots(count: number): Promise<string[]> {
  const found: string[] = []
  for (let offset = 3; found.length < count && offset < 28; offset += 1) {
    const date = addDays(businessToday(), offset)
    if ((await slots(anna, [HAIRCUT], date)).length >= 10 && (await slots(boris, [HAIRCUT], date)).length >= 10) {
      found.push(date)
    }
  }
  expect(found, 'enough bookable dates').toHaveLength(count)
  return found
}

function clock(iso: string): string {
  return iso.slice(11, 16)
}

function calendarUrl(view: 'day' | 'week' | 'agenda', date: string): string {
  return `/#/business/calendar?view=${view}&date=${date}${view === 'agenda' ? '&page=0&size=50' : ''}`
}

/** Opens a route in a fresh document: the owner page was mounted before the fixtures existed. */
async function open(page: Page, route: string): Promise<void> {
  await page.goto(route)
  await page.reload()
}

function entry(page: Page, name: string) {
  return page.locator('article, li').filter({ hasText: name }).filter({ has: page.getByRole('link') })
}

test.beforeAll(async ({ browser }) => {
  test.setTimeout(240_000)
  adminContext = await browser.newContext()
  admin = await adminContext.newPage()
  await signInAsPlatformAdmin(admin)
  salon = await provisionBookingBusiness(browser, adminContext, admin, {
    name: 'Салон за графика (E2E)', slugBase: 'calendar-journey', services: SERVICES, staff: STAFF,
  })
  other = await provisionBookingBusiness(browser, adminContext, admin, {
    name: 'Друг салон (E2E)', slugBase: 'calendar-other', services: SERVICES.slice(0, 1), staff: STAFF.slice(0, 1).map((member) => ({ ...member, services: [HAIRCUT] })),
  })
  owner = salon.owner!.page
  anna = salon.staffIds[ANNA]!
  boris = salon.staffIds[BORIS]!
  customerOne = await seedCustomer(owner, { displayName: `Мариета Първа ${TAG}`, phone: freshPhone(), email: null })
  customerTwo = await seedCustomer(owner, { displayName: `Невена Втора ${TAG}`, phone: freshPhone(), email: null })
  customerThree = await seedCustomer(owner, { displayName: `Радост Трета ${TAG}`, phone: freshPhone(), email: null })
  ;[dayOne, dayTwo, dayThree] = await firstDatesWithSlots(3) as [string, string, string]
})

test.afterAll(async () => {
  await closeOwner(salon)
  await closeOwner(other)
  await adminContext.close()
})

test.describe('Calendar views and the Appointment detail', () => {
  test('a created Appointment appears in the day, week and agenda views, is filterable by StaffMember and opens in its detail', async () => {
    const [first] = await slots(anna, [HAIRCUT], dayOne)
    const visit = await createVisit(customerOne, anna, [HAIRCUT], first!.start)

    await open(owner, calendarUrl('day', dayOne))
    await expect(owner.getByRole('heading', { name: 'График', level: 1, exact: true })).toBeVisible()
    await expect(entry(owner, customerOne.displayName).first()).toBeVisible()
    await expect(owner.getByRole('navigation', { name: 'Изглед на графика' }).getByRole('link', { name: 'Ден' }))
      .toHaveAttribute('aria-current', 'page')

    // The StaffMember filter is applied by the server: the other StaffMember has nothing that day.
    await expect(owner.getByLabel('Член на екипа').locator('option')).toContainText([
      'Всички членове на екипа', ANNA, BORIS,
    ])
    await owner.getByLabel('Член на екипа').selectOption({ label: BORIS })
    await expect(owner.getByText('Няма часове за този ден.')).toBeVisible()
    await owner.getByLabel('Член на екипа').selectOption({ label: 'Всички членове на екипа' })
    await expect(entry(owner, customerOne.displayName).first()).toBeVisible()

    await open(owner, calendarUrl('week', dayOne))
    await expect(owner.getByRole('region', { name: 'Седмичен график' })).toContainText(customerOne.displayName)
    await open(owner, calendarUrl('agenda', dayOne))
    await expect(owner.getByText(customerOne.displayName).first()).toBeVisible()

    // At a narrow width the same data stays inside the viewport.
    await owner.setViewportSize({ width: 375, height: 800 })
    await expectNoHorizontalOverflow(owner, 'agenda with data (375)')
    await open(owner, calendarUrl('day', dayOne))
    await expect(entry(owner, customerOne.displayName).first()).toBeVisible()
    await expectNoHorizontalOverflow(owner, 'day with data (375)')
    await owner.setViewportSize({ width: 1280, height: 800 })

    await entry(owner, customerOne.displayName).first().getByRole('link').first().click()
    await expect(owner.getByRole('heading', { level: 2 }).first()).toContainText(clock(visit.start))
    const page = owner.locator('.appointment-detail')
    await expect(page).toContainText('Потвърден')
    await expect(page).toContainText('Предстоящ')
    await expect(page).toContainText(customerOne.displayName)
    await expect(page).toContainText(HAIRCUT)
    await expect(page).toContainText(ANNA)
    await expect(owner.getByRole('button', { name: 'Промени часа' })).toBeVisible()
    await expect(owner.getByRole('button', { name: 'Отмени часа' })).toBeVisible()
    await owner.getByRole('button', { name: 'Обратно към графика' }).click()
    await expect(owner.getByRole('heading', { name: 'График', level: 1, exact: true })).toBeVisible()
  })
})

test.describe('Manual creation', () => {
  test('creates a multi-Service visit with an inline new Customer and shows it in the detail and the calendar', async () => {
    const created = `Нова Клиентка ${TAG}`
    const phone = freshPhone()
    await open(owner, `/#/business/appointments/new?view=day&date=${dayTwo}`)
    await expect(owner.getByRole('heading', { name: 'Клиент', exact: true })).toBeVisible()
    await owner.getByRole('button', { name: 'Нов клиент' }).click()
    const dialog = owner.getByRole('dialog', { name: 'Нов клиент' })
    await dialog.getByLabel('Име', { exact: true }).fill(created)
    await dialog.getByLabel('Телефон', { exact: true }).fill(phone)
    await dialog.getByRole('button', { name: 'Добави' }).click()
    await expect(dialog).toHaveCount(0)
    await expect(owner.getByRole('radio', { name: new RegExp(created) })).toBeChecked()
    await owner.getByRole('button', { name: 'Напред' }).click()

    await owner.getByRole('checkbox', { name: new RegExp(HAIRCUT) }).check()
    await owner.getByRole('checkbox', { name: new RegExp(BROWS) }).check()
    await expect(owner.getByText('Избрани услуги: 2')).toBeVisible()
    await owner.getByRole('button', { name: 'Напред' }).click()

    await owner.getByRole('radio', { name: new RegExp(ANNA) }).check()
    await owner.getByRole('button', { name: 'Напред' }).click()

    await owner.getByLabel('Дата', { exact: true }).fill(dayTwo)
    const offered = await slots(anna, [HAIRCUT, BROWS], dayTwo)
    const wanted = offered[2]!
    await owner.locator(`input[name="manual-start"][value="${wanted.start}"]`).check()
    await owner.getByRole('button', { name: 'Напред' }).click()

    await owner.getByLabel('Бележка (по желание)').fill('Предпочита сутрешен час.')
    await owner.getByRole('button', { name: 'Напред' }).click()

    await expect(owner.getByRole('heading', { name: 'Преглед и потвърждение', level: 2 })).toBeVisible()
    const review = owner.getByRole('region', { name: 'Преглед и потвърждение' })
    await expect(review).toContainText(created)
    await expect(review).toContainText(HAIRCUT)
    await expect(review).toContainText(BROWS)
    await expect(review).toContainText(ANNA)
    await expect(review).toContainText('Предпочита сутрешен час.')
    await owner.getByRole('button', { name: 'Създай час' }).click()

    const detail = owner.locator('.appointment-detail')
    await expect(detail).toContainText(created)
    await expect(detail).toContainText(HAIRCUT)
    await expect(detail).toContainText(BROWS)
    await expect(detail).toContainText('Предпочита сутрешен час.')
    await expect(detail).toContainText(clock(wanted.start))
    const id = decodeURIComponent(owner.url()).match(/appointments\/([0-9a-f-]{36})/)![1]!
    const stored = await detailOf(id)
    expect(stored.status).toBe('CONFIRMED')
    expect(stored.start).toBe(wanted.start)
    expect(stored.staffMemberId).toBe(anna)
    // The 60-minute visit now occupies the time: the next offer for the haircut alone no longer contains its start.
    expect((await slots(anna, [HAIRCUT], dayTwo)).map((slot) => slot.start)).not.toContain(wanted.start)
  })

  test('a start taken by an online guest meanwhile is refused with recovery guidance, creates nothing, and is no longer offered', async ({ request }) => {
    const before = await ownerJson<{ appointments: unknown[] }>(
      `/api/business/calendar?from=${dayThree}&to=${dayThree}`,
    )
    await open(owner, `/#/business/appointments/new?view=day&date=${dayThree}`)
    await owner.getByLabel('Търсене на клиент', { exact: true }).fill(customerTwo.displayName)
    await owner.getByRole('radio', { name: new RegExp(customerTwo.displayName) }).check()
    await owner.getByRole('button', { name: 'Напред' }).click()
    await owner.getByRole('checkbox', { name: new RegExp(HAIRCUT) }).check()
    await owner.getByRole('button', { name: 'Напред' }).click()
    await owner.getByRole('radio', { name: new RegExp(ANNA) }).check()
    await owner.getByRole('button', { name: 'Напред' }).click()
    await owner.getByLabel('Дата', { exact: true }).fill(dayThree)
    const contested = (await slots(anna, [HAIRCUT], dayThree))[3]!
    await owner.locator(`input[name="manual-start"][value="${contested.start}"]`).check()
    await owner.getByRole('button', { name: 'Напред' }).click()
    await owner.getByRole('button', { name: 'Напред' }).click()
    await expect(owner.getByRole('heading', { name: 'Преглед и потвърждение', level: 2 })).toBeVisible()

    // A real online booking of the same StaffMember and start commits first.
    const online = await publicBook(request, salon.slug, {
      attemptId: crypto.randomUUID(),
      services: await reviewedLines(request, salon.slug, [serviceId(HAIRCUT)]),
      staffMemberId: anna,
      start: contested.start,
      customer: { displayName: `Онлайн Гост ${TAG}`, phone: freshPhone(), email: null },
      note: null,
    })
    expect(online.status, online.code ?? 'guest booking').toBe(201)

    await owner.getByRole('button', { name: 'Създай час' }).click()
    await expect(owner.getByText('Избраният час вече не е свободен. Изберете друг час.')).toBeVisible()
    // Nothing leaks about the winner, and the form is still here to recover from.
    await expect(owner.locator('main')).not.toContainText(`Онлайн Гост ${TAG}`)
    const after = await ownerJson<{ appointments: unknown[] }>(`/api/business/calendar?from=${dayThree}&to=${dayThree}`)
    expect(after.appointments.length).toBe(before.appointments.length + 1)
    // The form returns to the date and time step itself, with the start cleared and the offers re-read.
    await expect(owner.getByLabel('Дата', { exact: true })).toBeVisible()
    await expect(owner.locator('input[name="manual-start"]:checked')).toHaveCount(0)
    await expect(owner.locator('input[name="manual-start"]').first()).toBeVisible()
    await expect(owner.locator(`input[name="manual-start"][value="${contested.start}"]`)).toHaveCount(0)
    expect((await slots(anna, [HAIRCUT], dayThree)).map((slot) => slot.start)).not.toContain(contested.start)
  })
})

test.describe('Concurrent creation (real PostgreSQL, nothing injected)', () => {
  test('of several simultaneous requests for the same or an overlapping time of one StaffMember at most one commits', async () => {
    const offered = await slots(boris, [HAIRCUT], dayTwo)
    const first = offered[1]!
    const overlapping = offered.find((slot) => slot.start > first.start && slot.start < first.end)
    expect(overlapping, 'the Business offers a start that overlaps the first').toBeTruthy()
    const before = await ownerJson<{ appointments: unknown[] }>(`/api/business/calendar?from=${dayTwo}&to=${dayTwo}`)
    const attempts = [
      createRaw(customerOne, boris, first.start),
      createRaw(customerTwo, boris, first.start),
      createRaw(customerThree, boris, overlapping!.start),
    ]
    const results = await Promise.all(attempts)
    const statuses = results.map((result) => result.status).sort()
    expect(statuses).toEqual([201, 409, 409])
    for (const loser of results.filter((result) => result.status === 409)) {
      expect(loser.code).toBe('APPOINTMENT_SLOT_UNAVAILABLE')
      for (const secret of [customerOne.displayName, customerTwo.displayName, customerThree.displayName]) {
        expect(loser.text).not.toContain(secret)
      }
    }
    const after = await ownerJson<{ appointments: { staffMemberId: string, start: string, end: string, status: string }[] }>(
      `/api/business/calendar?from=${dayTwo}&to=${dayTwo}`,
    )
    expect(after.appointments.length).toBe(before.appointments.length + 1)
    // The final calendar holds no two overlapping confirmed Appointments of one StaffMember.
    const mine = after.appointments.filter((a) => a.staffMemberId === boris && a.status === 'CONFIRMED')
    for (const a of mine) {
      for (const b of mine) {
        if (a !== b) expect(a.start < b.end && b.start < a.end, `${a.start} and ${b.start} overlap`).toBe(false)
      }
    }
  })
})

test.describe('Rescheduling and reassignment', () => {
  let visit: Created

  async function openReschedule(id: string) {
    await open(owner, `/#/business/appointments/${id}?view=day&date=${dayOne}`)
    await owner.getByRole('button', { name: 'Промени часа' }).click()
    await expect(owner.getByRole('heading', { name: 'Избор на служител', level: 2 })).toBeVisible()
  }

  test('moves the Appointment to another date and time with the same StaffMember, after a Преди/След review', async () => {
    const [seed] = await slots(anna, [HAIRCUT], dayOne)
    const second = (await slots(anna, [HAIRCUT], dayOne)).at(-1)!
    visit = await createVisit(customerTwo, anna, [HAIRCUT], second.start)
    void seed
    await openReschedule(visit.id)
    await owner.getByRole('radio', { name: /Текущ член на екипа/ }).check()
    await owner.getByRole('button', { name: 'Напред' }).click()
    await owner.getByLabel('Дата', { exact: true }).fill(dayTwo)
    const target = (await slots(anna, [HAIRCUT], dayTwo)).at(-1)!
    await owner.locator(`input[name="manual-start"][value="${target.start}"]`).check()
    await owner.getByRole('button', { name: 'Напред' }).click()
    const review = owner.getByRole('region', { name: 'Преглед и потвърждение' })
    await expect(review).toContainText('Преди')
    await expect(review).toContainText('След')
    await owner.getByRole('button', { name: 'Промени часа' }).click()
    await expect(owner.locator('.appointment-detail')).toContainText(clock(target.start))
    const stored = await detailOf(visit.id)
    expect(stored.start).toBe(target.start)
    expect(stored.staffMemberId).toBe(anna)
    expect(stored.version).toBeGreaterThan(visit.version)
    visit = { ...visit, version: stored.version, start: stored.start }
  })

  test('reassigns the Appointment to another eligible StaffMember', async () => {
    await open(owner, `/#/business/appointments/${visit.id}?view=day&date=${dayTwo}`)
    await owner.getByRole('button', { name: 'Промени часа' }).click()
    await owner.getByRole('radio', { name: new RegExp(BORIS) }).check()
    await owner.getByRole('button', { name: 'Напред' }).click()
    await owner.getByLabel('Дата', { exact: true }).fill(dayTwo)
    const target = (await slots(boris, [HAIRCUT], dayTwo))[1]!
    await owner.locator(`input[name="manual-start"][value="${target.start}"]`).check()
    await owner.getByRole('button', { name: 'Напред' }).click()
    await owner.getByRole('button', { name: 'Промени часа' }).click()
    await expect(owner.locator('.appointment-detail')).toContainText(BORIS)
    const stored = await detailOf(visit.id)
    expect(stored.staffMemberId).toBe(boris)
    expect(stored.start).toBe(target.start)
    visit = { ...visit, version: stored.version, start: stored.start }
  })

  test('a target taken meanwhile is refused, and the Appointment stays exactly as it was', async () => {
    const before = await detailOf(visit.id)
    await open(owner, `/#/business/appointments/${visit.id}?view=day&date=${dayTwo}`)
    await owner.getByRole('button', { name: 'Промени часа' }).click()
    await owner.getByRole('radio', { name: /Текущ член на екипа/ }).check()
    await owner.getByRole('button', { name: 'Напред' }).click()
    await owner.getByLabel('Дата', { exact: true }).fill(dayThree)
    const contested = (await slots(boris, [HAIRCUT], dayThree))[2]!
    await owner.locator(`input[name="manual-start"][value="${contested.start}"]`).check()
    await owner.getByRole('button', { name: 'Напред' }).click()
    await expect(owner.getByRole('heading', { name: 'Преглед и потвърждение', level: 2 })).toBeVisible()
    // The owner's own competing visit (the supported API) commits the same StaffMember and start first.
    await createVisit(customerThree, boris, [HAIRCUT], contested.start)
    await owner.getByRole('button', { name: 'Промени часа' }).click()
    await expect(owner.getByText('Избраният час вече не е свободен. Изберете друг час.')).toBeVisible()
    const after = await detailOf(visit.id)
    expect(after).toEqual(before)
  })
})

test.describe('Internal note, cancellation and version conflicts', () => {
  test('edits the internal note, and a stale save is refused with the stored note kept', async () => {
    const [seed] = await slots(anna, [HAIRCUT], dayThree)
    const visit = await createVisit(customerOne, anna, [HAIRCUT], seed!.start)
    await open(owner, `/#/business/appointments/${visit.id}?view=day&date=${dayThree}`)
    await owner.getByRole('button', { name: 'Добави бележка' }).click()
    await owner.getByLabel('Вътрешна бележка').fill('Алергия към боя.')
    await owner.getByRole('button', { name: 'Запази бележката' }).click()
    await expect(owner.getByText('Бележката е запазена.')).toBeVisible()
    await expect(owner.locator('.appointment-detail')).toContainText('Алергия към боя.')
    const saved = await detailOf(visit.id)
    expect(saved.internalNote).toBe('Алергия към боя.')

    // Another session (the supported API) changes the Appointment after this page read it.
    await owner.getByRole('button', { name: 'Редактирай' }).click()
    await owner.getByLabel('Вътрешна бележка').fill('Нова бележка от екрана.')
    const concurrent = await apiRequest(owner, 'PUT', `/api/business/appointments/${visit.id}/internal-note`, {
      expectedVersion: saved.version,
      internalNote: 'Бележка от друга сесия.',
    })
    expect(concurrent.status, concurrent.code ?? 'concurrent note').toBe(200)
    await owner.getByRole('button', { name: 'Запази бележката' }).click()
    await expect(owner.getByText('Часът е променен междувременно.', { exact: false })).toBeVisible()
    expect((await detailOf(visit.id)).internalNote).toBe('Бележка от друга сесия.')
  })

  test('cancels after an explicit confirmation, keeps the record, releases the time, and refuses a repeated cancellation', async () => {
    const [seed] = await slots(anna, [HAIRCUT], dayThree)
    const target = (await slots(anna, [HAIRCUT], dayThree))[5] ?? seed!
    const visit = await createVisit(customerTwo, anna, [HAIRCUT], target.start)
    expect((await slots(anna, [HAIRCUT], dayThree)).map((slot) => slot.start)).not.toContain(target.start)

    await open(owner, `/#/business/appointments/${visit.id}?view=day&date=${dayThree}`)
    await owner.getByRole('button', { name: 'Отмени часа' }).click()
    const dialog = owner.getByRole('alertdialog', { name: 'Отмяна на час' })
    await expect(dialog).toContainText(customerTwo.displayName)
    // Declining changes nothing.
    await dialog.getByRole('button', { name: 'Отказ' }).click()
    expect((await detailOf(visit.id)).status).toBe('CONFIRMED')
    await owner.getByRole('button', { name: 'Отмени часа' }).click()
    await owner.getByRole('alertdialog', { name: 'Отмяна на час' }).getByRole('button', { name: 'Отмени часа' }).click()
    await expect(owner.getByText('Този час е отменен.')).toBeVisible()
    await expect(owner.getByText('Времето е свободно за ново записване.')).toBeVisible()
    await expect(owner.getByRole('button', { name: 'Промени часа' })).toHaveCount(0)
    const stored = await detailOf(visit.id)
    expect(stored.status).toBe('CANCELLED')
    expect(stored.cancellation?.cancelledAt).toBeTruthy()
    expect((await slots(anna, [HAIRCUT], dayThree)).map((slot) => slot.start)).toContain(target.start)

    const repeated = await apiRequest(owner, 'POST', `/api/business/appointments/${visit.id}/cancel`, { expectedVersion: stored.version })
    expect(repeated.status).toBe(409)
    expect((await detailOf(visit.id)).status).toBe('CANCELLED')
  })

  test('PROXY: a cancellation committed but answered with a network failure is reported as unresolved and then verified', async () => {
    const target = (await slots(boris, [HAIRCUT], dayThree)).at(-1)!
    const visit = await createVisit(customerThree, boris, [HAIRCUT], target.start)
    await open(owner, `/#/business/appointments/${visit.id}?view=day&date=${dayThree}`)
    await owner.getByRole('button', { name: 'Отмени часа' }).click()
    // PROXY evidence: the real request is sent and committed by the real backend; only its response is dropped.
    await owner.route('**/api/business/appointments/*/cancel', async (route) => {
      await route.fetch()
      await route.abort('failed')
    })
    await owner.getByRole('alertdialog', { name: 'Отмяна на час' }).getByRole('button', { name: 'Отмени часа' }).click()
    await expect(owner.getByText('Не е ясно дали часът е отменен.', { exact: false })).toBeVisible()
    await owner.unroute('**/api/business/appointments/*/cancel')
    expect((await detailOf(visit.id)).status).toBe('CANCELLED')
    await owner.getByRole('button', { name: 'Прочети часа отново' }).click()
    await expect(owner.getByText('Този час е отменен.')).toBeVisible()
  })
})

test.describe('Customer history', () => {
  test('lists the Customer visits earlier first, reverses them, and filters by one Business-local date', async () => {
    const historyCustomer = await seedCustomer(owner, { displayName: `История Клиент ${TAG}`, phone: freshPhone(), email: null })
    const one = await createVisit(historyCustomer, anna, [HAIRCUT], (await slots(anna, [HAIRCUT], dayOne))[0]!.start)
    const two = await createVisit(historyCustomer, anna, [HAIRCUT], (await slots(anna, [HAIRCUT], dayTwo))[0]!.start)
    const three = await createVisit(historyCustomer, anna, [BROWS], (await slots(anna, [BROWS], dayThree))[0]!.start)
    const cancelled = await apiRequest(owner, 'POST', `/api/business/appointments/${three.id}/cancel`, { expectedVersion: three.version })
    expect(cancelled.status, cancelled.code ?? 'cancel').toBe(200)

    await open(owner, `/#/business/customers/${historyCustomer.id}`)
    const history = owner.locator('.customer-history')
    await expect(history.getByRole('heading', { name: 'Часове', level: 3 })).toBeVisible()
    const rows = history.locator('.customer-history-entry')
    await expect(rows).toHaveCount(3)
    const startsOf = async () => rows.locator('.calendar-entry-time a').allTextContents()
    const ascending = await startsOf()
    expect(ascending[0]).toContain(clock(one.start))
    await expect(rows.nth(2)).toContainText('Отменен')

    await history.getByLabel('Подредба').selectOption({ label: 'От най-късна дата' })
    await expect.poll(startsOf).toEqual([...ascending].reverse())

    await history.getByLabel('Подредба').selectOption({ label: 'От най-ранна дата' })
    const field = history.getByLabel('Дата', { exact: true })
    await field.pressSequentially(`${two.start.slice(8, 10)}${two.start.slice(5, 7)}${two.start.slice(0, 4)}`)
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText(clock(two.start))
    await field.clear()
    await expect(rows).toHaveCount(3)

    await expectNoSentinelInBrowser(owner, [historyCustomer.displayName, customerOne.displayName])
    await expectBrowserStorageEmpty(owner)
  })
})

test.describe('Authorization and Business lifecycle', () => {
  test('private Appointment data is closed to anonymous callers, the platform administrator and other Businesses, and a foreign id looks missing', async ({ request }) => {
    const [seed] = await slots(anna, [HAIRCUT], dayOne)
    const visit = await createVisit(customerThree, anna, [HAIRCUT], ((await slots(anna, [HAIRCUT], dayOne))[4] ?? seed!).start)
    const paths = [
      `/api/business/calendar?from=${dayOne}&to=${dayOne}`,
      `/api/business/appointments/${visit.id}`,
      `/api/business/customers/${customerThree.id}/appointments`,
    ]
    for (const path of paths) {
      // No session: authentication is required (401 AUTH_REQUIRED), and nothing private is in the answer.
      const anonymous = await request.get(`${API_ORIGIN}${path}`)
      const anonymousBody = await anonymous.text()
      expect(anonymous.status(), `anonymous ${path}`).toBe(401)
      expect((JSON.parse(anonymousBody) as { code: string }).code, `anonymous ${path}`).toBe('AUTH_REQUIRED')
      expect(anonymousBody).not.toContain(customerThree.displayName)
      // The platform administrator has no selected Business and no Membership: the private calendar is closed to them
      // (403 ACTIVE_BUSINESS_REQUIRED), whatever the path.
      const asAdmin = await apiRequest(admin, 'GET', path)
      expect(asAdmin.status, `platform administrator ${path}`).toBe(403)
      expect(asAdmin.code, `platform administrator ${path}`).toBe('ACTIVE_BUSINESS_REQUIRED')
      expect(asAdmin.text).not.toContain(customerThree.displayName)
    }
    // Another Business's owner cannot read it, and the answer is the same as for an identifier that never existed.
    const foreign = await apiRequest(other.owner!.page, 'GET', `/api/business/appointments/${visit.id}`)
    const missing = await apiRequest(other.owner!.page, 'GET', `/api/business/appointments/${crypto.randomUUID()}`)
    expect(foreign.status).toBe(404)
    expect(foreign.code).toBe('APPOINTMENT_NOT_FOUND')
    expect(foreign.status).toBe(missing.status)
    expect(foreign.code).toBe(missing.code)
    expect(foreign.text).toBe(missing.text)
    const foreignCancel = await apiRequest(other.owner!.page, 'POST', `/api/business/appointments/${visit.id}/cancel`, { expectedVersion: visit.version })
    expect(foreignCancel.status).toBe(404)
    expect(foreignCancel.code).toBe('APPOINTMENT_NOT_FOUND')
    expect((await detailOf(visit.id)).status).toBe('CONFIRMED')
  })

  test('a SUSPENDED Business stays readable but offers and accepts no change, and reactivation restores management', async () => {
    const [seed] = await slots(anna, [HAIRCUT], dayOne)
    const visit = await createVisit(customerOne, anna, [HAIRCUT], ((await slots(anna, [HAIRCUT], dayOne)).at(-2) ?? seed!).start)
    await transitionBusiness(admin, salon.id, 'suspend')
    try {
      await open(owner, `/#/business/appointments/${visit.id}?view=day&date=${dayOne}`)
      await expect(owner.locator('.appointment-detail')).toContainText(customerOne.displayName)
      await expect(owner.getByRole('button', { name: 'Промени часа' })).toHaveCount(0)
      await expect(owner.getByRole('button', { name: 'Отмени часа' })).toHaveCount(0)
      await open(owner, calendarUrl('day', dayOne))
      await expect(owner.getByRole('heading', { name: 'График', level: 1, exact: true })).toBeVisible()
      await expect(owner.getByRole('link', { name: 'Добави нов час' })).toHaveCount(0)
      await expect(owner.getByRole('button', { name: 'Добави нов час' })).toHaveCount(0)
      const cancel = await apiRequest(owner, 'POST', `/api/business/appointments/${visit.id}/cancel`, { expectedVersion: visit.version })
      // The contract for a SUSPENDED Business: 409 BUSINESS_SUSPENDED, and nothing changes.
      expect(cancel.status, cancel.code ?? 'suspended cancel').toBe(409)
      expect(cancel.code).toBe('BUSINESS_SUSPENDED')
      expect((await detailOf(visit.id)).status).toBe('CONFIRMED')
    } finally {
      await transitionBusiness(admin, salon.id, 'reactivate')
    }
    await open(owner, `/#/business/appointments/${visit.id}?view=day&date=${dayOne}`)
    await expect(owner.getByRole('button', { name: 'Промени часа' })).toBeVisible()
  })
})
