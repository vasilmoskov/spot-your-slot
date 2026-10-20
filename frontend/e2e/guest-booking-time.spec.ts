import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { signInAsPlatformAdmin } from './support/provisioning'
import {
  availableDates,
  callsTo,
  clockOf,
  closeOwner,
  expectPublicTrafficPrivate,
  freshPhone,
  Guest,
  postBody,
  provisionBookingBusiness,
  recordTraffic,
  slotsOn,
  type BookingBusiness,
} from './support/booking'

// The Europe/Sofia clock change of the real backend. The booking window is 30 days from today, so the
// scenario needs a fall-back date (the last Sunday of October) inside that window.

let adminContext: BrowserContext
let admin: Page
let business: BookingBusiness

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000)
  adminContext = await browser.newContext()
  admin = await adminContext.newPage()
  await signInAsPlatformAdmin(admin)
  business = await provisionBookingBusiness(browser, adminContext, admin, {
    name: 'Салон за смяна на часа',
    slugBase: 'booking-dst',
    services: [{ name: 'Консултация', durationMinutes: 30, price: '15.00' }],
    // Sundays only, from midnight: the working period spans the repeated 03:00 to 03:59 hour.
    staff: [{ name: 'Деница Тестова', services: ['Консултация'], hours: { start: '00:00', end: '08:00', days: ['SUNDAY'] } }],
  })
})

test.afterAll(async () => {
  await closeOwner(business)
  await adminContext.close()
})

/** The last Sunday of October of the current or the next year that is not before today. */
function nextFallBackDate(now: Date = new Date()): string {
  for (const year of [now.getUTCFullYear(), now.getUTCFullYear() + 1]) {
    const day = new Date(Date.UTC(year, 9, 31))
    while (day.getUTCDay() !== 0) day.setUTCDate(day.getUTCDate() - 1)
    const iso = day.toISOString().slice(0, 10)
    if (iso >= now.toISOString().slice(0, 10)) return iso
  }
  throw new Error('unreachable')
}

test.describe('G. The repeated hour of the clock change', () => {
  test('both 03:30 values are distinct, the second occurrence reaches the POST, the review and the confirmation', async ({ browser, request }) => {
    const serviceIds = [business.serviceIds['Консултация']!]
    const target = nextFallBackDate()
    const dates = await availableDates(request, business.slug, serviceIds)
    // A conditional, announced exclusion: outside the window the real backend cannot offer the scenario.
    test.skip(!dates.includes(target), `the fall-back date ${target} is outside the 30-day booking window today`)

    const slots = await slotsOn(request, business.slug, serviceIds, target)
    const repeated = slots.filter((slot) => clockOf(slot.start) === '03:30')
    expect(repeated.map((slot) => slot.start.slice(-6))).toEqual(['+03:00', '+02:00'])
    const second = repeated[1]!

    const context = await browser.newContext()
    const page = await context.newPage()
    const audit = recordTraffic(page)
    const guest = new Guest(page)
    const seen: string[] = []
    await guest.open(business.slug)
    await guest.start()
    await guest.select('Консултация')
    await guest.toStaff()
    await guest.chooseStaff(null)
    await guest.toDate()
    await guest.chooseDate(target)
    // Both occurrences are told apart in words, and nothing else carries a technical zone name.
    await expect(guest.slot(repeated[0]!.start)).toContainText('03:30 — първи път')
    await expect(guest.slot(second.start)).toContainText('03:30 — втори път')
    seen.push(await page.locator('main').innerText())
    await guest.chooseSlot(second.start)
    await expect(page.locator('.booking-picker-summary')).toContainText('03:30 — втори път')
    await guest.toDetails()
    await guest.fillDetails({ name: 'Втори Час', phone: freshPhone() })
    await guest.toReview()
    await expect(guest.summary()).toContainText('03:30 — втори път – 04:00')
    seen.push(await page.locator('main').innerText())

    const created = page.waitForResponse((response) =>
      response.url().endsWith('/bookings') && response.request().method() === 'POST')
    await guest.confirm()
    const response = await created
    expect(response.status()).toBe(201)
    const body = await response.json() as { start: string, end: string, timezone: string }
    expect(body.start).toBe(second.start)
    expect(body.start.endsWith('+02:00')).toBe(true)
    await expect(guest.heading()).toHaveText('Резервацията е потвърдена')
    await expect(page.locator('main')).toContainText('03:30 — втори път – 04:00')
    seen.push(await page.locator('main').innerText())

    const calls = await audit.stop()
    expectPublicTrafficPrivate(calls)
    const sent = postBody(callsTo(calls, 'POST', '/bookings')[0]!)
    expect(sent.start).toBe(second.start)
    // The technical identifier of the time zone is never displayed.
    for (const text of seen) {
      expect(text).not.toContain('Europe/Sofia')
      expect(text).not.toMatch(/Europe\//)
    }
    await context.close()
  })
})
