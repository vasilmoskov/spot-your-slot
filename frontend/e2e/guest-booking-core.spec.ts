import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { API_ORIGIN } from './support/environment'
import { signInAsPlatformAdmin } from './support/provisioning'
import {
  availableDates,
  callsTo,
  dateWithSlots,
  canonicalOrder,
  closeOwner,
  expectJourneyStateClean,
  expectPublicTrafficPrivate,
  factsText,
  freshEmail,
  freshPhone,
  Guest,
  MESSAGES,
  ownerCustomers,
  ownerCustomerTotal,
  postBody,
  provisionBookingBusiness,
  publicBook,
  recordConsole,
  recordTraffic,
  reviewedLines,
  SALON_SERVICES,
  SALON_STAFF,
  slotsOn,
  totalText,
  type BookingBusiness,
} from './support/booking'

// Real-browser journeys of the public guest booking against the real backend (issue #18, phase M7).
// Everything the backend can produce is real: no request is intercepted or answered by a stub.

let adminContext: BrowserContext
let admin: Page
let salon: BookingBusiness

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000)
  adminContext = await browser.newContext()
  admin = await adminContext.newPage()
  await signInAsPlatformAdmin(admin)
  salon = await provisionBookingBusiness(browser, adminContext, admin, {
    name: 'Салон за резервации (ядро)',
    slugBase: 'booking-core',
    services: SALON_SERVICES,
    staff: SALON_STAFF,
  })
})

test.afterAll(async () => {
  await closeOwner(salon)
  await adminContext.close()
})

async function guestPage(browser: Browser) {
  const context = await browser.newContext()
  const page = await context.newPage()
  return { context, page, guest: new Guest(page) }
}

function id(name: string): string {
  const value = salon.serviceIds[name]
  if (!value) throw new Error(`Unknown Service ${name}`)
  return value
}

function labelOf(isoStart: string): string {
  return isoStart.slice(11, 16)
}

test.describe('A. One Service through the new request shape', () => {
  test('books one Service: one hero action, price-first facts, one POST, server facts and no duplicate on replay', async ({ browser, request }) => {
    const { context, page, guest } = await guestPage(browser)
    const audit = recordTraffic(page)
    const consoleLog = recordConsole(page)
    const phone = freshPhone()
    const cutId = id('Дамско подстригване')
    const dates = await availableDates(request, salon.slug, [cutId])
    // The second start of the first date that has two (today, late in the day, may offer only one).
    const { date, slots: cutSlots } = await dateWithSlots(dates, (candidate) => slotsOn(request, salon.slug, [cutId], candidate), 2)
    const slot = cutSlots[1]!
    const customersBefore = await ownerCustomerTotal(salon.owner!.page)

    await guest.open(salon.slug)
    // Exactly one hero action; the Service cards carry no booking action of their own.
    await expect(page.getByRole('button', { name: 'Запази час', exact: true })).toHaveCount(1)
    await expect(page.getByRole('button', { name: /Запази час за/ })).toHaveCount(0)
    await expect(page.getByRole('link', { name: /Запази час/ })).toHaveCount(0)

    await guest.start()
    await expect(guest.button('Напред')).toBeDisabled()
    await expect(page.locator('.booking-selection-empty')).toHaveText(MESSAGES.noSelection)

    await guest.select('Дамско подстригване')
    await expect(guest.choice('Дамско подстригване').locator('.booking-choice-facts')).toHaveText(factsText(2500, 45))
    await expect(page.locator('.booking-selection-total')).toHaveText(totalText(2500, 45))
    await guest.toStaff()
    await expect(page.locator('.booking-context')).toContainText(factsText(2500, 45))
    await guest.chooseStaff(null)
    await guest.toDate()
    await guest.chooseDate(date)
    await guest.chooseSlot(slot.start)
    await guest.toDetails()
    await guest.fillDetails({ name: 'Единичен Гост', phone })
    await guest.toReview()

    // The review of one Service repeats nothing: its line is its total.
    await expect(guest.summary()).toContainText(factsText(2500, 45))
    await expect(page.locator('.booking-summary-total')).toHaveCount(0)
    await expect(guest.summary()).not.toContainText('Общо')

    const created = page.waitForResponse((response) =>
      response.url() === `${API_ORIGIN}/api/public/businesses/${salon.slug}/bookings`
        && response.request().method() === 'POST')
    await guest.confirm()
    const response = await created
    expect(response.status()).toBe(201)
    const body = await response.json() as {
      reference: string, status: string, services: { name: string, durationMinutes: number, price: number }[],
      totalDurationMinutes: number, totalPrice: number, staff: { displayName: string }, start: string, end: string,
    }

    await expect(guest.heading()).toHaveText('Резервацията е потвърдена')
    // The confirmation shows the server-returned facts.
    expect(body.status).toBe('CONFIRMED')
    expect(body.services).toEqual([{ name: 'Дамско подстригване', durationMinutes: 45, price: 25 }])
    expect(body.totalDurationMinutes).toBe(45)
    expect(body.totalPrice).toBe(25)
    expect(body.start).toBe(slot.start)
    const confirmation = page.locator('main')
    await expect(confirmation).toContainText('Дамско подстригване')
    // One Service: its duration and price are the visit's facts, shown once and not as a total.
    await expect(confirmation).toContainText('Продължителност45 мин.')
    await expect(confirmation).toContainText('Цена25.00 €')
    await expect(confirmation).toContainText(body.staff.displayName)
    await expect(confirmation).toContainText(`${labelOf(slot.start)} – ${labelOf(slot.end)}`)
    await expect(confirmation).not.toContainText('Общо')
    await expect(confirmation).not.toContainText('·')

    // Reload and replay: nothing is sent by a reload, and the very same attempt returns the same visit.
    await expectJourneyStateClean(page, [phone, 'Единичен', 'Дамско', cutId])
    await page.reload()
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    const consoleLines = consoleLog.stop()
    const calls = await audit.stop()
    expectPublicTrafficPrivate(calls)

    const posts = callsTo(calls, 'POST', '/bookings')
    expect(posts).toHaveLength(1)
    expect(posts[0]!.status).toBe(201)
    const sent = postBody(posts[0]!)
    expect(Object.keys(sent)).toEqual(['attemptId', 'services', 'staffMemberId', 'start', 'customer', 'note'])
    expect(sent.services).toEqual([{ serviceId: cutId, reviewedDurationMinutes: 45, reviewedPriceCents: 2500 }])
    expect(sent.staffMemberId).toBeNull()
    expect(sent.start).toBe(slot.start)
    // The options read and the availability reads carry only the documented Service identifier.
    for (const read of calls.filter((call) => call.method === 'GET' && call.path.includes('/booking-options'))) {
      expect(new URLSearchParams(read.search).getAll('serviceId')).toEqual([cutId])
    }
    expect(consoleLines.filter((line) => line.includes(phone) || line.includes('Единичен'))).toEqual([])

    const customersAfter = await ownerCustomerTotal(salon.owner!.page)
    expect(customersAfter).toBe(customersBefore + 1)
    const replay = await publicBook(request, salon.slug, sent)
    expect(replay.status).toBe(200)
    expect(replay.body).toEqual(body)
    expect(await ownerCustomerTotal(salon.owner!.page)).toBe(customersAfter)
    await context.close()
  })
})

test.describe('B. Three Services as one visit', () => {
  test('books three Services: display order, price-first strings, eligible staff, canonical request and server lines', async ({ browser, request }) => {
    const { context, page, guest } = await guestPage(browser)
    const audit = recordTraffic(page)
    const phone = freshPhone()
    const names = ['Дамско подстригване', 'Маникюр', 'Масаж на лице']
    const ids = names.map(id)
    // Click order: the reverse of the canonical UUID order, so it differs from the request order.
    const canonical = canonicalOrder(ids)
    const clickOrder = [...canonical].reverse().map((serviceId) => names[ids.indexOf(serviceId)]!)
    const dates = await availableDates(request, salon.slug, ids)
    const { date, slots } = await dateWithSlots([...dates.slice(1), ...dates.slice(0, 1)], (candidate) => slotsOn(request, salon.slug, ids, candidate), 3)
    const slot = slots[2]!
    // One continuous slot covers the summed duration (195 minutes).
    expect((Date.parse(slot.end) - Date.parse(slot.start)) / 60_000).toBe(195)

    await guest.open(salon.slug)
    await guest.start()
    await guest.select(...clickOrder)
    // The visible lists stay in the public-profile order whatever the click order was.
    await expect(page.locator('.booking-selection-list li')).toHaveText(names)
    await expect(page.locator('.booking-selection-total')).toHaveText(totalText(11550, 195))
    for (const text of await page.locator('.booking-choice-facts').allTextContents()) {
      expect(text).toMatch(/^\d+\.\d{2} € — \d+ мин\.$/)
    }
    await expect(guest.button('Напред')).toBeEnabled()

    await guest.toStaff()
    // Only the StaffMember who offers all three Services is offered.
    await expect(page.locator('label.booking-choice .booking-choice-title')).toHaveText(['Без предпочитание', 'Анна Георгиева'])
    await expect(page.locator('.booking-context')).toContainText(factsText(11550, 195))
    await guest.chooseStaff(null)
    await guest.toDate()
    await guest.chooseDate(date)
    await guest.chooseSlot(slot.start)
    await expect(page.locator('.booking-picker-summary')).toContainText(`${labelOf(slot.start)} – ${labelOf(slot.end)}`)
    await guest.toDetails()
    await guest.fillDetails({ name: 'Тройна Гостенка', phone, note: 'Три услуги' })
    await guest.toReview()

    // One list, every Service once in the display order, one separated total.
    await expect(page.locator('.booking-summary-services li .booking-summary-strong')).toHaveText(names)
    await expect(page.locator('.booking-summary-total')).toHaveCount(1)
    await expect(page.locator('.booking-summary-total')).toHaveText(totalText(11550, 195))
    const rule = await page.locator('.booking-summary-total').evaluate((element) => {
      const style = getComputedStyle(element)
      return { top: style.borderTopWidth, style: style.borderTopStyle }
    })
    expect(rule).toEqual({ top: '1px', style: 'solid' })
    await expect(page.locator('.booking-summary-services li').nth(0)).toContainText(factsText(2500, 45))
    await expect(page.locator('.booking-summary-services li').nth(1)).toContainText(factsText(3500, 60))
    await expect(page.locator('.booking-summary-services li').nth(2)).toContainText(factsText(5550, 90))

    const created = page.waitForResponse((response) =>
      response.url().endsWith('/bookings') && response.request().method() === 'POST')
    await guest.confirm()
    const response = await created
    expect(response.status()).toBe(201)
    const body = await response.json() as {
      status: string, services: { name: string, durationMinutes: number, price: number }[],
      totalDurationMinutes: number, totalPrice: number, staff: { displayName: string }, start: string, end: string,
    }
    await expect(guest.heading()).toHaveText('Резервацията е потвърдена')

    // The server's stored lines, in execution (public-profile) order, and its own totals.
    expect(body.services.map((line) => line.name)).toEqual(names)
    expect(body.services.map((line) => line.durationMinutes)).toEqual([45, 60, 90])
    expect(body.services.map((line) => line.price)).toEqual([25, 35, 55.5])
    expect(body.totalDurationMinutes).toBe(195)
    expect(body.totalPrice).toBe(115.5)
    expect(body.staff.displayName).toBe('Анна Георгиева')
    expect(body.start).toBe(slot.start)
    expect(body.end).toBe(slot.end)
    const lines = page.locator('.booking-summary-services li')
    await expect(lines.locator('.booking-summary-strong')).toHaveText(names)
    await expect(lines.nth(0)).toContainText(factsText(2500, 45))
    await expect(page.locator('main')).toContainText('Анна Георгиева')
    await expect(page.locator('main')).toContainText('195 мин.')
    await expect(page.locator('main')).toContainText('115.50 €')

    const calls = await audit.stop()
    expectPublicTrafficPrivate(calls)
    const posts = callsTo(calls, 'POST', '/bookings')
    expect(posts).toHaveLength(1)
    const sent = postBody(posts[0]!)
    const lines_ = sent.services as { serviceId: string, reviewedDurationMinutes: number, reviewedPriceCents: number }[]
    expect(lines_.map((line) => line.serviceId)).toEqual(canonical)
    expect(lines_).toEqual(await reviewedLines(request, salon.slug, ids))
    // Reads carry the repeated canonical identifiers and nothing else.
    for (const read of calls.filter((call) => call.method === 'GET' && /booking-options|availability/.test(call.path))) {
      expect(new URLSearchParams(read.search).getAll('serviceId')).toEqual(canonical)
    }
    expect(clickOrder).not.toEqual(canonical.map((serviceId) => names[ids.indexOf(serviceId)]!))
    await context.close()
  })
})

test.describe('F. Customer matching', () => {
  async function reachReview(browser: Browser, request: Parameters<typeof availableDates>[0], index: number, details: { name: string, phone?: string, email?: string }) {
    const { context, page, guest } = await guestPage(browser)
    const audit = recordTraffic(page)
    const brows = id('Оформяне на вежди')
    const dates = await availableDates(request, salon.slug, [brows])
    const { date, slots } = await dateWithSlots([...dates.slice(2), ...dates.slice(0, 2)], (candidate) => slotsOn(request, salon.slug, [brows], candidate), index + 1)
    const slot = slots[index]!
    await guest.open(salon.slug)
    await guest.toReviewFor({ services: ['Оформяне на вежди'], staff: null, date, slot: slot.start, details })
    return { context, page, guest, audit }
  }

  test('a new guest with both contacts, an existing Customer with exact contacts, and the intentional conflict', async ({ browser, request }) => {
    const owner = salon.owner!.page
    const phone = freshPhone()
    const email = freshEmail('guest')
    const total0 = await ownerCustomerTotal(owner)

    // 1. A new guest with both contacts creates exactly one Customer.
    const first = await reachReview(browser, request, 3, { name: 'Нов Клиент', phone, email })
    await first.guest.confirm()
    await expect(first.guest.heading()).toHaveText('Резервацията е потвърдена')
    expect(await ownerCustomerTotal(owner)).toBe(total0 + 1)
    const stored = await ownerCustomers(owner, email)
    expect(stored).toHaveLength(1)
    expect(stored[0]!.email).toBe(email)
    await first.audit.stop()
    await first.context.close()

    // 2. An existing Customer with exactly matching contacts is reused: no second Customer.
    const second = await reachReview(browser, request, 4, { name: 'Нов Клиент', phone, email })
    await second.guest.confirm()
    await expect(second.guest.heading()).toHaveText('Резервацията е потвърдена')
    expect(await ownerCustomerTotal(owner)).toBe(total0 + 1)
    await second.audit.stop()
    await second.context.close()

    // 3. The intentional conflict: the phone of one Customer with a different e-mail.
    const third = await reachReview(browser, request, 5, { name: 'Друг Клиент', phone, email: freshEmail('other') })
    const conflictResponse = third.page.waitForResponse((response) => response.url().endsWith('/bookings') && response.request().method() === 'POST')
    await third.guest.confirm()
    const conflict = await conflictResponse
    expect(conflict.status()).toBe(409)
    expect((await conflict.json() as { code: string }).code).toBe('BOOKING_NOT_COMPLETED_ONLINE')
    await expect(third.guest.alert()).toContainText(MESSAGES.identityConflict)
    expect((await third.guest.alert().innerText()).startsWith(MESSAGES.identityConflict)).toBe(true)
    // The sentence names neither the matched contact nor any Customer identity, and creates nothing.
    const shown = (await third.page.locator('main').innerText())
    expect(shown).not.toContain('Нов Клиент')
    expect(shown).not.toContain(email)
    expect(await ownerCustomerTotal(owner)).toBe(total0 + 1)
    await third.audit.stop()
    await third.context.close()
  })
})
