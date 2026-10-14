import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { signInAsPlatformAdmin } from './support/provisioning'
import {
  availableDates,
  closeOwner,
  countBookingPosts,
  freshPhone,
  Guest,
  nextBookingResponse,
  ownerCustomerTotal,
  provisionBookingBusiness,
  slotsOn,
  type BookingBusiness,
} from './support/booking'

// SIMULATED TRANSPORT FAILURE. This file is not real-backend evidence of the failure itself: a fault
// where the backend commits and the answer is lost cannot be produced through any supported API, so
// the browser drops the response after the real request has been delivered. Everything else in the
// scenario (the commit, the replay, the Customer count) is the real backend's behavior.

let adminContext: BrowserContext
let admin: Page
let business: BookingBusiness

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000)
  adminContext = await browser.newContext()
  admin = await adminContext.newPage()
  await signInAsPlatformAdmin(admin)
  business = await provisionBookingBusiness(browser, adminContext, admin, {
    name: 'Салон за симулирана загуба на отговор',
    slugBase: 'booking-simulated',
    services: [{ name: 'Дамско подстригване', durationMinutes: 45, price: '25.00' }],
    staff: [{ name: 'Анна Георгиева', services: ['Дамско подстригване'], hours: { start: '09:00', end: '18:00' } }],
  })
})

test.afterAll(async () => {
  await closeOwner(business)
  await adminContext.close()
})

test.describe('SIMULATED: the response of a committed booking is lost', () => {
  test('the journey freezes with its leave protection, and the retry replays the real visit with the same attempt', async ({ browser, request }) => {
    const ids = [business.serviceIds['Дамско подстригване']!]
    const date = (await availableDates(request, business.slug, ids))[0]!
    const slot = (await slotsOn(request, business.slug, ids, date))[0]!
    const owner = business.owner!.page
    const before = await ownerCustomerTotal(owner)
    const context = await browser.newContext()
    const page = await context.newPage()
    const posts = countBookingPosts(page)
    const bodies: string[] = []
    const guest = new Guest(page)
    await guest.open(business.slug)
    await guest.toReviewFor({
      services: ['Дамско подстригване'], staff: null, date, slot: slot.start,
      details: { name: 'Изгубен Отговор', phone: freshPhone() },
    })

    // SIMULATED: the request reaches the real backend (and commits), the answer never reaches the page.
    await page.route('**/api/public/businesses/*/bookings', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback()
      bodies.push(route.request().postData() ?? '')
      const real = await route.fetch()
      expect(real.status()).toBe(201)
      return route.abort('failed')
    })
    await guest.confirm()
    await expect(guest.alert()).toContainText('Не получихме потвърждение за резервацията. Опитайте отново.')
    await expect(guest.alert()).toContainText('Резервацията може вече да е направена. Не започвайте нова.')
    expect(posts.count()).toBe(1)

    // Frozen: every edit is disabled, Back is undone, and leaving asks with the stronger warning.
    for (const button of await page.locator('.booking-summary').getByRole('button').all()) {
      await expect(button).toBeDisabled()
    }
    await page.goBack()
    await expect(guest.heading()).toHaveText('Преглед и потвърждение')
    await expect(guest.alert()).toBeVisible()
    await guest.button('Към страницата на бизнеса').click()
    const dialog = page.getByRole('alertdialog')
    await expect(dialog).toContainText('Резервацията може вече да е направена.')
    expect(await dialog.getByRole('button').allTextContents()).toEqual(['Остани', 'Напусни'])
    await dialog.getByRole('button', { name: 'Остани' }).click()
    await expect(dialog).toHaveCount(0)

    // The real backend committed exactly one Customer even though the page saw no answer.
    expect(await ownerCustomerTotal(owner)).toBe(before + 1)

    // Retry with the transport restored: the very same bytes, answered by a real replay (200).
    await page.unroute('**/api/public/businesses/*/bookings')
    const replayed = nextBookingResponse(page)
    await guest.button('Опитайте отново').click()
    const response = await replayed
    expect(response.status()).toBe(200)
    expect(response.request().postData()).toBe(bodies[0])
    await expect(guest.heading()).toHaveText('Резервацията е потвърдена')
    expect(posts.count()).toBe(2)
    expect(await ownerCustomerTotal(owner)).toBe(before + 1)
    posts.stop()
    await context.close()
  })
})
