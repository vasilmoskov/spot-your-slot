import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { signInAsPlatformAdmin } from './support/provisioning'
import {
  availableDates,
  callsTo,
  closeOwner,
  freshPhone,
  Guest,
  MESSAGES,
  provisionBookingBusiness,
  recordTraffic,
  SALON_SERVICES,
  SALON_STAFF,
  slotsOn,
  type BookingBusiness,
} from './support/booking'

// Selection limits, eligibility and invalidation of the multi-Service journey, on the real backend.

let adminContext: BrowserContext
let admin: Page
let salon: BookingBusiness
let withoutTime: BookingBusiness

test.beforeAll(async ({ browser }) => {
  test.setTimeout(150_000)
  adminContext = await browser.newContext()
  admin = await adminContext.newPage()
  await signInAsPlatformAdmin(admin)
  salon = await provisionBookingBusiness(browser, adminContext, admin, {
    name: 'Салон за резервации (избор)',
    slugBase: 'booking-selection',
    services: SALON_SERVICES,
    staff: SALON_STAFF,
  })
  // A StaffMember who offers the Services but has no working time at all.
  withoutTime = await provisionBookingBusiness(browser, adminContext, admin, {
    name: 'Салон без часове (избор)',
    slugBase: 'booking-no-time',
    services: [
      { name: 'Първа услуга', durationMinutes: 30, price: '20.00' },
      { name: 'Втора услуга', durationMinutes: 45, price: '30.00' },
    ],
    staff: [{ name: 'Петя Празнична', services: ['Първа услуга', 'Втора услуга'] }],
  })
})

test.afterAll(async () => {
  await closeOwner(salon)
  await closeOwner(withoutTime)
  await adminContext.close()
})

async function openJourney(browser: Browser, slug: string) {
  const context = await browser.newContext()
  const page = await context.newPage()
  const guest = new Guest(page)
  await guest.open(slug)
  await guest.start()
  return { context, page, guest }
}

test.describe('C. Selection limits', () => {
  test('without a selection «Напред» is disabled and says what to do', async ({ browser }) => {
    const { context, page, guest } = await openJourney(browser, salon.slug)
    await expect(guest.button('Напред')).toBeDisabled()
    await expect(page.locator('.booking-selection-empty')).toHaveText(MESSAGES.noSelection)
    await guest.select('Маникюр')
    await expect(guest.button('Напред')).toBeEnabled()
    await guest.select('Маникюр')
    await expect(guest.button('Напред')).toBeDisabled()
    await context.close()
  })

  test('a sixth Service cannot be added, its reason is visible, and a selected Service can always be removed', async ({ browser }) => {
    const { context, guest } = await openJourney(browser, salon.slug)
    const five = ['Оформяне на вежди', 'Дамско подстригване', 'Маникюр', 'Педикюр', 'Масаж на лице']
    const others = ['Боядисване на коса', 'Терапия за скалп', 'Цялостна грижа']
    await guest.select(...five)
    for (const name of others) {
      await expect(guest.checkbox(name)).toBeDisabled()
      await expect(guest.choice(name).locator('.booking-choice-reason')).toHaveText(MESSAGES.limitCount)
    }
    // The selected options stay enabled and removable.
    for (const name of five) await expect(guest.checkbox(name)).toBeEnabled()
    await guest.choice('Масаж на лице').click()
    await expect(guest.checkbox('Масаж на лице')).not.toBeChecked()
    // Removing one makes the others selectable again (the reasons are gone).
    await expect(guest.checkbox('Боядисване на коса')).toBeEnabled()
    await expect(guest.choice('Боядисване на коса').locator('.booking-choice-reason')).toHaveCount(0)
    await context.close()
  })

  test('a Service that would exceed 480 minutes cannot be added; exactly 480 is accepted', async ({ browser }) => {
    const { context, page, guest } = await openJourney(browser, salon.slug)
    // 120 + 135 + 90 + 75 = 420 minutes with four Services.
    await guest.select('Боядисване на коса', 'Терапия за скалп', 'Масаж на лице', 'Педикюр')
    await expect(guest.checkbox('Цялостна грижа')).toBeDisabled()
    await expect(guest.choice('Цялостна грижа').locator('.booking-choice-reason')).toHaveText(MESSAGES.limitDuration)
    // 45 more minutes still fit (465).
    await expect(guest.checkbox('Дамско подстригване')).toBeEnabled()
    await guest.choice('Педикюр').click()
    await guest.choice('Боядисване на коса').click()
    // 135 + 90 = 225; the exact 480 is reached with 150 + 135 + 90 + 60 + 45.
    await guest.select('Цялостна грижа', 'Маникюр', 'Дамско подстригване')
    await expect(page.locator('.booking-selection-total')).toHaveText('Общо: 295.75 € — 480 мин.')
    await expect(guest.button('Напред')).toBeEnabled()
    await expect(page.locator('.booking-selection-summary')).toBeVisible()
    // At the five-Service limit every other Service is refused, and every selected one is removable.
    await expect(guest.checkbox('Оформяне на вежди')).toBeDisabled()
    await guest.choice('Цялостна грижа').click()
    await expect(guest.checkbox('Цялостна грижа')).not.toBeChecked()
    await context.close()
  })

  test('the empty intersection is its own state with «Промяна на избора»', async ({ browser }) => {
    const { context, page, guest } = await openJourney(browser, salon.slug)
    // The brows are offered only by Борис, the facial massage only by Анна.
    await guest.select('Оформяне на вежди', 'Масаж на лице')
    await guest.next()
    await expect(guest.heading()).toHaveText('Избор на служител')
    await expect(page.locator('.booking-journey')).toContainText(MESSAGES.noStaffForSelection)
    await expect(page.getByRole('radio')).toHaveCount(0)
    await guest.button('Промяна на избора').click()
    await expect(guest.heading()).toHaveText('Избор на услуги')
    // The selection is kept for editing.
    await expect(guest.checkbox('Оформяне на вежди')).toBeChecked()
    await expect(guest.checkbox('Масаж на лице')).toBeChecked()
    await context.close()
  })

  test('eligible StaffMembers without any available time is a different state', async ({ browser }) => {
    const { context, page, guest } = await openJourney(browser, withoutTime.slug)
    await guest.select('Първа услуга', 'Втора услуга')
    await guest.toStaff()
    // The StaffMember is offered: the intersection is not empty.
    await expect(page.locator('label.booking-choice .booking-choice-title')).toHaveText(['Без предпочитание', 'Петя Празнична'])
    await expect(page.locator('.booking-journey')).not.toContainText(MESSAGES.noStaffForSelection)
    await guest.toDate()
    await expect(page.locator('.booking-journey')).toContainText('Няма свободни дати')
    await expect(page.locator('.booking-journey')).not.toContainText(MESSAGES.noStaffForSelection)
    await expect(page.getByRole('grid')).toHaveCount(0)
    await context.close()
  })
})

test.describe('C. Invalidation and unnecessary reads', () => {
  test('changing the Service set clears the stale StaffMember, date and time but keeps the guest details', async ({ browser, request }) => {
    const { context, page, guest } = await openJourney(browser, salon.slug)
    const first = ['Дамско подстригване', 'Маникюр']
    const ids = first.map((name) => salon.serviceIds[name]!)
    const dates = await availableDates(request, salon.slug, ids)
    const date = dates[0]!
    const slot = (await slotsOn(request, salon.slug, ids, date))[0]!
    const phone = freshPhone()
    await guest.select(...first)
    await guest.toStaff()
    await guest.chooseStaff('Анна Георгиева')
    await guest.toDate()
    await guest.chooseDate(date)
    await guest.chooseSlot(slot.start)
    await guest.toDetails()
    await guest.fillDetails({ name: 'Запазена Гостенка', phone, note: 'Бележка' })
    await guest.toReview()

    await page.getByRole('button', { name: 'Промени услугите' }).click()
    await expect(guest.heading()).toHaveText('Избор на услуги')
    // The Service set changes: Маникюр is replaced by Педикюр.
    await guest.select('Маникюр', 'Педикюр')
    await guest.next()
    await expect(guest.heading()).toHaveText('Избор на служител')
    // The StaffMember choice is back at the default and the date and time are gone.
    await expect(guest.choice('Без предпочитание').locator('input')).toBeChecked()
    await expect(guest.choice('Анна Георгиева').locator('input')).not.toBeChecked()
    await guest.next()
    await expect(guest.heading()).toHaveText('Дата и час')
    await expect(page.locator('label.booking-slot input:checked')).toHaveCount(0)
    await expect(guest.button('Напред')).toBeDisabled()
    // The details survive the change.
    await guest.chooseDate(date)
    const second = ids.slice(0, 1).concat(salon.serviceIds['Педикюр']!)
    const nextSlot = (await slotsOn(request, salon.slug, second, date))[0]!
    await guest.chooseSlot(nextSlot.start)
    // The details are still valid, so the designed shortcut returns directly to the review.
    await expect(guest.button('Към прегледа')).toBeVisible()
    await guest.toReview()
    await expect(guest.summary()).toContainText('Запазена Гостенка')
    await expect(guest.summary()).toContainText(phone)
    await expect(guest.summary()).toContainText('Бележка')
    await page.getByRole('button', { name: 'Промени данните' }).click()
    await expect(page.getByLabel('Име', { exact: true })).toHaveValue('Запазена Гостенка')
    await expect(page.getByLabel('Бележка (по желание)')).toHaveValue('Бележка')
    await context.close()
  })

  test('choosing another date reads availability again but never reloads the booking options', async ({ browser, request }) => {
    const { context, page, guest } = await openJourney(browser, salon.slug)
    const audit = recordTraffic(page)
    const ids = [salon.serviceIds['Дамско подстригване']!]
    const dates = await availableDates(request, salon.slug, ids)
    await guest.select('Дамско подстригване')
    await guest.toStaff()
    await guest.toDate()
    await guest.chooseDate(dates[0]!)
    await expect(page.locator('label.booking-slot').first()).toBeVisible()
    await guest.chooseDate(dates[1]!)
    await expect(page.locator('.booking-slots-date')).not.toHaveText('')
    await expect(page.locator('label.booking-slot').first()).toBeVisible()
    await guest.chooseDate(dates[2]!)
    await expect(page.locator('label.booking-slot').first()).toBeVisible()
    const calls = await audit.stop()
    expect(callsTo(calls, 'GET', '/booking-options')).toHaveLength(1)
    // One availability read per chosen date at the least; never another options read.
    expect(callsTo(calls, 'GET', '/availability').length).toBeGreaterThanOrEqual(3)
    await context.close()
  })
})
