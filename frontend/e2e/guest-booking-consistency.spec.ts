import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { signInAsPlatformAdmin } from './support/provisioning'
import {
  availableDates,
  dateSurvivingFirstBooking,
  callsTo,
  changeService,
  closeOwner,
  countBookingPosts,
  factsText,
  freshPhone,
  Guest,
  MESSAGES,
  nextBookingResponse,
  ownerCustomers,
  ownerCustomerTotal,
  postBody,
  provisionBookingBusiness,
  publicBook,
  recordTraffic,
  reviewedLines,
  setServiceActive,
  slotsOn,
  totalText,
  type BookingBusiness,
} from './support/booking'
import { API_ORIGIN } from './support/environment'

// Review editing, the real review-consistency check, and real concurrency, on the real backend.
// Owner changes use the supported Service API and are restored by the test that made them.

let adminContext: BrowserContext
let admin: Page
let consistency: BookingBusiness
let contention: BookingBusiness

const SERVICES = [
  { name: 'Дамско подстригване', durationMinutes: 45, price: '25.00' },
  { name: 'Маникюр', durationMinutes: 60, price: '35.00' },
  { name: 'Оформяне на вежди', durationMinutes: 15, price: '9.99' },
  { name: 'Педикюр', durationMinutes: 75, price: '42.00' },
]
const STAFF = [{
  name: 'Анна Георгиева',
  services: SERVICES.map((service) => service.name),
  hours: { start: '09:00', end: '18:00' },
}]

test.beforeAll(async ({ browser }) => {
  test.setTimeout(150_000)
  adminContext = await browser.newContext()
  admin = await adminContext.newPage()
  await signInAsPlatformAdmin(admin)
  consistency = await provisionBookingBusiness(browser, adminContext, admin, {
    name: 'Салон за резервации (проверка)', slugBase: 'booking-consistency', services: SERVICES, staff: STAFF,
  })
  contention = await provisionBookingBusiness(browser, adminContext, admin, {
    name: 'Салон за резервации (състезание)', slugBase: 'booking-contention', services: SERVICES, staff: STAFF,
  })
})

test.afterAll(async () => {
  await closeOwner(consistency)
  await closeOwner(contention)
  await adminContext.close()
})

function ids(business: BookingBusiness, ...names: string[]): string[] {
  return names.map((name) => business.serviceIds[name]!)
}

const profileUrl = (business: BookingBusiness) => `${API_ORIGIN}/api/public/businesses/${business.slug}`

test.describe('D. Review editing', () => {
  test('every edit action returns to its step and a still-valid edit returns directly to the review', async ({ browser, request }) => {
    const context = await browser.newContext()
    const page = await context.newPage()
    const guest = new Guest(page)
    const selection = ['Дамско подстригване', 'Маникюр']
    const serviceIds = ids(consistency, ...selection)
    const date = (await availableDates(request, consistency.slug, serviceIds))[0]!
    const slot = (await slotsOn(request, consistency.slug, serviceIds, date))[0]!
    await guest.open(consistency.slug)
    await guest.toReviewFor({ services: selection, staff: 'Анна Георгиева', date, slot: slot.start, details: { name: 'Редактор Гост', phone: freshPhone() } })

    const edits: [string, string][] = [
      ['Промени услугите', 'Избор на услуги'],
      ['Промени служителя', 'Избор на служител'],
      ['Промени датата и часа', 'Дата и час'],
      ['Промени данните', 'Вашите данни'],
    ]
    for (const [action, heading] of edits) {
      await page.getByRole('button', { name: action }).click()
      await expect(guest.heading()).toHaveText(heading)
      // Nothing changed, so the designed shortcut leads straight back to the review.
      await guest.button('Към прегледа').click()
      await expect(guest.heading()).toHaveText('Преглед и потвърждение')
      await expect(guest.summary()).toContainText('Редактор Гост')
    }
    await context.close()
  })
})

test.describe('D. The real review-consistency check', () => {
  test('a price changed by the owner before confirming is a real BOOKING_REVIEW_CHANGED: no automatic POST, refreshed facts, a new attempt', async ({ browser, request }) => {
    const manicure = consistency.serviceIds['Маникюр']!
    const selection = ['Дамско подстригване', 'Маникюр']
    const serviceIds = ids(consistency, ...selection)
    const dates = await availableDates(request, consistency.slug, serviceIds)
    const slot = (await slotsOn(request, consistency.slug, serviceIds, dates[1]!))[0]!
    const context = await browser.newContext()
    const page = await context.newPage()
    const audit = recordTraffic(page)
    const posts = countBookingPosts(page)
    const guest = new Guest(page)
    const phone = freshPhone()
    await guest.open(consistency.slug)
    await guest.toReviewFor({ services: selection, staff: null, date: dates[1]!, slot: slot.start, details: { name: 'Променена Цена', phone } })
    await expect(page.locator('.booking-summary-total')).toHaveText(totalText(6000, 105))

    try {
      await changeService(consistency.owner!.page, manicure, { price: '38.50' })
      const firstAnswer = nextBookingResponse(page)
      const refreshed = page.waitForResponse((response) =>
        response.url() === profileUrl(consistency) && response.request().method() === 'GET')
      await guest.confirm()
      const refused = await firstAnswer
      expect(refused.status()).toBe(409)
      expect((await refused.json() as { code: string }).code).toBe('BOOKING_REVIEW_CHANGED')
      await refreshed
      // The guest is sent back to choose the time again, with the approved sentence and the new price.
      await expect(guest.heading()).toHaveText('Дата и час')
      await expect(guest.alert()).toHaveText(MESSAGES.reviewChanged)
      await expect(page.locator('.booking-context')).toContainText(factsText(6350, 105))
      await expect(page.locator('label.booking-slot input:checked')).toHaveCount(0)
      await expect(guest.button('Напред')).toBeDisabled()
      // Nothing was sent again by itself.
      expect(posts.count()).toBe(1)
      const firstAttempt = posts.attemptIds()[0]!

      const fresh = (await slotsOn(request, consistency.slug, serviceIds, dates[1]!))[0]!
      await guest.chooseDate(dates[1]!)
      await guest.chooseSlot(fresh.start)
      await guest.toDetails()
      // The details entered earlier are still there.
      await expect(page.getByLabel('Име', { exact: true })).toHaveValue('Променена Цена')
      await expect(page.getByLabel('Телефон', { exact: true })).toHaveValue(phone)
      await guest.toReview()
      await expect(guest.summary()).toContainText('Променена Цена')
      await expect(page.locator('.booking-summary-total')).toHaveText(totalText(6350, 105))
      expect(posts.count()).toBe(1)

      const secondAnswer = nextBookingResponse(page)
      await guest.confirm()
      expect((await secondAnswer).status()).toBe(201)
      await expect(guest.heading()).toHaveText('Резервацията е потвърдена')
      expect(posts.count()).toBe(2)
      expect(posts.attemptIds()[1]).not.toBe(firstAttempt)
      posts.stop()
      const calls = await audit.stop()
      const sent = callsTo(calls, 'POST', '/bookings').map(postBody)
      const lineOf = (body: Record<string, unknown>) =>
        (body.services as { serviceId: string, reviewedPriceCents: number }[]).find((line) => line.serviceId === manicure)!
      // The refused request carried the price that was reviewed; the new one carries the refreshed price.
      expect(lineOf(sent[0]!).reviewedPriceCents).toBe(3500)
      expect(lineOf(sent[1]!).reviewedPriceCents).toBe(3850)
      // Work is not duplicated: besides the page load, exactly one profile read completed for the
      // review change. (The development server renders under React StrictMode, which may start one
      // more read that the page aborts; the audit drops aborted requests, and the production build
      // issues a single read, checked by hand in the M6/M7 record.)
      const profileReads = calls.filter((call) => call.method === 'GET' && call.path === `/api/public/businesses/${consistency.slug}`)
      expect(profileReads).toHaveLength(2)
    } finally {
      await changeService(consistency.owner!.page, manicure, { price: '35.00' })
      await context.close()
    }
  })

  test('a duration changed by the owner refreshes the review, clears the invalid time and never submits the stale duration', async ({ browser, request }) => {
    const pedicure = consistency.serviceIds['Педикюр']!
    const selection = ['Дамско подстригване', 'Педикюр']
    const serviceIds = ids(consistency, ...selection)
    const dates = await availableDates(request, consistency.slug, serviceIds)
    const slot = (await slotsOn(request, consistency.slug, serviceIds, dates[2]!))[0]!
    const context = await browser.newContext()
    const page = await context.newPage()
    const audit = recordTraffic(page)
    const posts = countBookingPosts(page)
    const guest = new Guest(page)
    await guest.open(consistency.slug)
    await guest.toReviewFor({ services: selection, staff: null, date: dates[2]!, slot: slot.start, details: { name: 'Променена Продължителност', phone: freshPhone() } })
    await expect(page.locator('.booking-summary-total')).toHaveText(totalText(6700, 120))

    try {
      await changeService(consistency.owner!.page, pedicure, { durationMinutes: 90 })
      const firstAnswer = nextBookingResponse(page)
      await guest.confirm()
      const refused = await firstAnswer
      expect(refused.status()).toBe(409)
      expect((await refused.json() as { code: string }).code).toBe('BOOKING_REVIEW_CHANGED')
      await expect(guest.heading()).toHaveText('Дата и час')
      await expect(page.locator('.booking-context')).toContainText(factsText(6700, 135))
      await expect(guest.button('Напред')).toBeDisabled()
      expect(posts.count()).toBe(1)

      const fresh = (await slotsOn(request, consistency.slug, serviceIds, dates[2]!))[0]!
      expect((Date.parse(fresh.end) - Date.parse(fresh.start)) / 60_000).toBe(135)
      await guest.chooseDate(dates[2]!)
      await guest.chooseSlot(fresh.start)
      await guest.toDetails()
      await expect(page.getByLabel('Име', { exact: true })).toHaveValue('Променена Продължителност')
      await guest.toReview()
      await expect(page.locator('.booking-summary-total')).toHaveText(totalText(6700, 135))
      const secondAnswer = nextBookingResponse(page)
      await guest.confirm()
      expect((await secondAnswer).status()).toBe(201)
      posts.stop()
      const calls = await audit.stop()
      const sent = callsTo(calls, 'POST', '/bookings').map(postBody)
      const durations = sent.map((body) =>
        (body.services as { serviceId: string, reviewedDurationMinutes: number }[]).find((line) => line.serviceId === pedicure)!.reviewedDurationMinutes)
      expect(durations).toEqual([75, 90])
    } finally {
      await changeService(consistency.owner!.page, pedicure, { durationMinutes: 75 })
      await context.close()
    }
  })

  test('a Service deactivated before confirming follows the real contract: BOOKING_SERVICE_UNAVAILABLE and a way back to the selection', async ({ browser, request }) => {
    const brows = consistency.serviceIds['Оформяне на вежди']!
    const selection = ['Маникюр', 'Оформяне на вежди']
    const serviceIds = ids(consistency, ...selection)
    const dates = await availableDates(request, consistency.slug, serviceIds)
    const slot = (await slotsOn(request, consistency.slug, serviceIds, dates[3]!))[0]!
    const context = await browser.newContext()
    const page = await context.newPage()
    const posts = countBookingPosts(page)
    const guest = new Guest(page)
    await guest.open(consistency.slug)
    await guest.toReviewFor({ services: selection, staff: null, date: dates[3]!, slot: slot.start, details: { name: 'Спряна Услуга', phone: freshPhone() } })

    try {
      await setServiceActive(consistency.owner!.page, brows, false)
      const answer = nextBookingResponse(page)
      await guest.confirm()
      const refused = await answer
      expect(refused.status()).toBe(409)
      expect((await refused.json() as { code: string }).code).toBe('BOOKING_SERVICE_UNAVAILABLE')
      await expect(guest.alert()).toContainText(MESSAGES.serviceUnavailable)
      expect(posts.count()).toBe(1)
      await guest.button('Промяна на избора').click()
      await expect(guest.heading()).toHaveText('Избор на услуги')
      // The deactivated Service is no longer offered; the other selection is still there.
      await expect(guest.choice('Оформяне на вежди')).toHaveCount(0)
      await expect(guest.checkbox('Маникюр')).toBeChecked()
      expect(posts.count()).toBe(1)
    } finally {
      posts.stop()
      await setServiceActive(consistency.owner!.page, brows, true)
      await context.close()
    }
  })
})

test.describe('E. Concurrency and replay', () => {
  const selection = ['Дамско подстригване', 'Маникюр']

  test('a slot taken by another browser before confirming gives the approved recovery and leaves nothing behind', async ({ browser, request }) => {
    const serviceIds = ids(contention, ...selection)
    const staffId = contention.staffIds['Анна Георгиева']!
    // The date and start come from the final selection and the StaffMember that both guests later submit, and
    // from a date that still has a bookable start after the first one is taken (today may not, late in the day).
    const dates = await availableDates(request, contention.slug, serviceIds, staffId)
    const { date, slot } = await dateSurvivingFirstBooking(dates, (candidate) =>
      slotsOn(request, contention.slug, serviceIds, candidate, staffId),
    )
    const loserPhone = freshPhone()
    const winnerPhone = freshPhone()
    const owner = contention.owner!.page
    const before = await ownerCustomerTotal(owner)

    const loserContext = await browser.newContext()
    const loserPage = await loserContext.newPage()
    const loser = new Guest(loserPage)
    await loser.open(contention.slug)
    await loser.toReviewFor({ services: selection, staff: 'Анна Георгиева', date, slot: slot.start, details: { name: 'Закъснял Гост', phone: loserPhone } })

    const winnerContext = await browser.newContext()
    const winnerPage = await winnerContext.newPage()
    const winner = new Guest(winnerPage)
    await winner.open(contention.slug)
    await winner.toReviewFor({ services: selection, staff: 'Анна Георгиева', date, slot: slot.start, details: { name: 'Победител Гост', phone: winnerPhone } })
    const won = nextBookingResponse(winnerPage)
    await winner.confirm()
    expect((await won).status()).toBe(201)
    await expect(winner.heading()).toHaveText('Резервацията е потвърдена')

    const lost = nextBookingResponse(loserPage)
    await loser.confirm()
    const refused = await lost
    expect(refused.status()).toBe(409)
    expect((await refused.json() as { code: string }).code).toBe('BOOKING_SLOT_UNAVAILABLE')
    await expect(loser.alert()).toHaveText(MESSAGES.slotUnavailable)
    await loser.button('Избор на друг час').click()
    await expect(loser.heading()).toHaveText('Дата и час')
    // The taken time is not offered any more, and the guest keeps the way on.
    await loser.chooseDate(date)
    await expect(loser.slot(slot.start)).toHaveCount(0)
    expect((await slotsOn(request, contention.slug, serviceIds, date, staffId)).map((s) => s.start)).not.toContain(slot.start)

    // No partial visit and no Customer for the loser; exactly one for the winner.
    expect(await ownerCustomers(owner, loserPhone)).toEqual([])
    expect(await ownerCustomers(owner, winnerPhone)).toHaveLength(1)
    expect(await ownerCustomerTotal(owner)).toBe(before + 1)
    await loserContext.close()
    await winnerContext.close()
  })

  test('two guests confirming the same slot at the same moment: one 201 and one BOOKING_SLOT_UNAVAILABLE', async ({ browser, request }) => {
    const serviceIds = ids(contention, ...selection)
    const dates = await availableDates(request, contention.slug, serviceIds)
    const slot = (await slotsOn(request, contention.slug, serviceIds, dates[1]!))[0]!
    const owner = contention.owner!.page
    const before = await ownerCustomerTotal(owner)
    const phones = [freshPhone(), freshPhone()]
    const contexts = await Promise.all([browser.newContext(), browser.newContext()])
    const pages = await Promise.all(contexts.map((context) => context.newPage()))
    const guests = pages.map((page) => new Guest(page))
    for (const [index, guest] of guests.entries()) {
      await guest.open(contention.slug)
      await guest.toReviewFor({ services: selection, staff: 'Анна Георгиева', date: dates[1]!, slot: slot.start, details: { name: `Състезател ${index + 1}`, phone: phones[index]! } })
    }
    const answers = pages.map((page) => nextBookingResponse(page))
    await Promise.all(guests.map((guest) => guest.confirm()))
    const responses = await Promise.all(answers)
    const statuses = responses.map((response) => response.status()).sort()
    expect(statuses).toEqual([201, 409])
    const winnerIndex = responses.findIndex((response) => response.status() === 201)
    const loserIndex = 1 - winnerIndex
    expect((await responses[loserIndex]!.json() as { code: string }).code).toBe('BOOKING_SLOT_UNAVAILABLE')
    await expect(guests[winnerIndex]!.heading()).toHaveText('Резервацията е потвърдена')
    await expect(guests[loserIndex]!.alert()).toHaveText(MESSAGES.slotUnavailable)
    expect(await ownerCustomerTotal(owner)).toBe(before + 1)
    expect(await ownerCustomers(owner, phones[loserIndex]!)).toEqual([])
    await Promise.all(contexts.map((context) => context.close()))
  })

  test('identical concurrent submissions create one visit and the loser replays it; a different payload is never a success', async ({ request }) => {
    const serviceIds = ids(contention, ...selection)
    const dates = await availableDates(request, contention.slug, serviceIds)
    const slot = (await slotsOn(request, contention.slug, serviceIds, dates[2]!))[0]!
    const owner = contention.owner!.page
    const before = await ownerCustomerTotal(owner)
    const phone = freshPhone()
    const body = {
      attemptId: crypto.randomUUID(),
      services: await reviewedLines(request, contention.slug, serviceIds),
      staffMemberId: contention.staffIds['Анна Георгиева']!,
      start: slot.start,
      customer: { displayName: 'Двойно Изпращане', phone, email: null },
      note: null,
    }
    const [first, second] = await Promise.all([
      publicBook(request, contention.slug, body),
      publicBook(request, contention.slug, body),
    ])
    expect([first.status, second.status].sort()).toEqual([200, 201])
    expect(first.body).toEqual(second.body)
    expect(await ownerCustomerTotal(owner)).toBe(before + 1)
    // The same attempt with a different payload is never a success.
    const different = await publicBook(request, contention.slug, { ...body, note: 'друго' })
    expect(different.status).toBe(409)
    expect(different.code).toBe('BOOKING_ATTEMPT_MISMATCH')
    expect(await ownerCustomerTotal(owner)).toBe(before + 1)
  })

  test('repeating a successful browser booking of two Services replays the same visit without new rows', async ({ browser, request }) => {
    const serviceIds = ids(contention, ...selection)
    const dates = await availableDates(request, contention.slug, serviceIds)
    const slot = (await slotsOn(request, contention.slug, serviceIds, dates[3]!))[0]!
    const owner = contention.owner!.page
    const context = await browser.newContext()
    const page = await context.newPage()
    const audit = recordTraffic(page)
    const guest = new Guest(page)
    await guest.open(contention.slug)
    await guest.toReviewFor({ services: selection, staff: null, date: dates[3]!, slot: slot.start, details: { name: 'Повторен Гост', phone: freshPhone() } })
    const created = nextBookingResponse(page)
    await guest.confirm()
    const response = await created
    expect(response.status()).toBe(201)
    const visit = await response.json()
    await expect(guest.heading()).toHaveText('Резервацията е потвърдена')
    const customers = await ownerCustomerTotal(owner)
    const calls = await audit.stop()
    const sent = postBody(callsTo(calls, 'POST', '/bookings')[0]!)
    const replay = await publicBook(request, contention.slug, sent)
    expect(replay.status).toBe(200)
    expect(replay.body).toEqual(visit)
    expect(await ownerCustomerTotal(owner)).toBe(customers)
    await context.close()
  })
})
