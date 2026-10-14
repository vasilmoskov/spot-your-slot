import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow } from './support/browser'
import { API_ORIGIN } from './support/environment'
import { signInAsPlatformAdmin } from './support/provisioning'
import { expectNoClippedContent } from './support/publicProfile'
import {
  availableDates,
  callsTo,
  canonicalOrder,
  closeOwner,
  expectJourneyStateClean,
  expectPublicTrafficPrivate,
  freshEmail,
  freshPhone,
  Guest,
  MESSAGES,
  nextBookingResponse,
  postBody,
  provisionBookingBusiness,
  publicAvailability,
  publicOptions,
  recordConsole,
  recordTraffic,
  slotsOn,
  totalText,
  type BookingBusiness,
} from './support/booking'

// Navigation, keyboard, accessibility, responsive and privacy checks of the real journey.

let adminContext: BrowserContext
let admin: Page
let salon: BookingBusiness
let layout: BookingBusiness

const LONG_SERVICE =
  'Измиване и оформяне на косата с професионални продукти и дълбока грижа за скалпа — процедура с изключително дълго наименование за проверка на пренасянето на текста'
const LONG_STAFF = 'Анна-Мария Константинова-Димитрова със специалност в козметиката и продължително име'
const MAX_NAMES = ['Премиум Алфа', 'Премиум Бета', 'Премиум Гама', 'Премиум Делта', 'Премиум Епсилон']

test.beforeAll(async ({ browser }) => {
  test.setTimeout(150_000)
  adminContext = await browser.newContext()
  admin = await adminContext.newPage()
  await signInAsPlatformAdmin(admin)
  salon = await provisionBookingBusiness(browser, adminContext, admin, {
    name: 'Салон за резервации (преглед)',
    slugBase: 'booking-experience',
    services: [
      { name: 'Дамско подстригване', durationMinutes: 45, price: '25.00' },
      { name: 'Маникюр', durationMinutes: 60, price: '35.00' },
      { name: 'Масаж на лице', durationMinutes: 90, price: '55.50' },
    ],
    staff: [{ name: 'Анна Георгиева', services: ['Дамско подстригване', 'Маникюр', 'Масаж на лице'], hours: { start: '09:00', end: '18:00' } }],
  })
  layout = await provisionBookingBusiness(browser, adminContext, admin, {
    name: 'Салон с дълги имена и най-високи цени',
    slugBase: 'booking-layout',
    services: [
      { name: LONG_SERVICE, durationMinutes: 30, price: '18.90' },
      ...MAX_NAMES.map((name) => ({ name, durationMinutes: 96, price: '9999999999.99' })),
    ],
    staff: [{ name: LONG_STAFF, services: [LONG_SERVICE, ...MAX_NAMES], hours: { start: '08:00', end: '20:00' } }],
  })
})

test.afterAll(async () => {
  await closeOwner(salon)
  await closeOwner(layout)
  await adminContext.close()
})

async function newGuest(browser: Browser, slug: string, viewport?: { width: number, height: number }) {
  const context = await browser.newContext(viewport ? { viewport } : {})
  const page = await context.newPage()
  const guest = new Guest(page)
  await guest.open(slug)
  return { context, page, guest }
}

test.describe('H. Navigation and leave protection', () => {
  test('browser Back and Forward walk the unfinished steps without a trap', async ({ browser, request }) => {
    const { context, page, guest } = await newGuest(browser, salon.slug)
    const ids = [salon.serviceIds['Дамско подстригване']!]
    const date = (await availableDates(request, salon.slug, ids))[0]!
    await guest.start()
    await guest.select('Дамско подстригване')
    await guest.toStaff()
    await guest.toDate()
    await guest.chooseDate(date)

    await page.goBack()
    await expect(guest.heading()).toHaveText('Избор на служител')
    await page.goBack()
    await expect(guest.heading()).toHaveText('Избор на услуги')
    await expect(guest.checkbox('Дамско подстригване')).toBeChecked()
    await page.goForward()
    await expect(guest.heading()).toHaveText('Избор на служител')
    await page.goForward()
    await expect(guest.heading()).toHaveText('Дата и час')
    // Back out of the journey: nothing was entered yet (no time, no details), so it leaves without a question.
    await page.goBack()
    await page.goBack()
    await page.goBack()
    await expect(page.getByRole('button', { name: 'Запази час', exact: true })).toBeVisible()
    await expect(page.getByRole('alertdialog')).toHaveCount(0)
    await context.close()
  })

  test('leaving with a chosen time asks first; «Остани» keeps the journey and «Напусни» ends it', async ({ browser, request }) => {
    const { context, page, guest } = await newGuest(browser, salon.slug)
    const ids = [salon.serviceIds['Маникюр']!]
    const date = (await availableDates(request, salon.slug, ids))[0]!
    const slot = (await slotsOn(request, salon.slug, ids, date))[0]!
    await guest.start()
    // A clean journey, even with a selection, leaves without a question.
    await guest.select('Маникюр')
    await guest.button('Към страницата на бизнеса').click()
    await expect(page.getByRole('button', { name: 'Запази час', exact: true })).toBeVisible()
    await expect(page.getByRole('alertdialog')).toHaveCount(0)

    await guest.start()
    await guest.select('Маникюр')
    await guest.toStaff()
    await guest.toDate()
    await guest.chooseDate(date)
    await guest.chooseSlot(slot.start)
    await guest.button('Към страницата на бизнеса').click()
    const dialog = page.getByRole('alertdialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('Резервацията не е завършена.')
    // The safe action comes first in the DOM and has the focus.
    expect(await dialog.getByRole('button').allTextContents()).toEqual(['Остани', 'Напусни'])
    await expect(dialog.getByRole('button', { name: 'Остани' })).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(guest.heading()).toHaveText('Дата и час')
    await guest.button('Към страницата на бизнеса').click()
    await dialog.getByRole('button', { name: 'Напусни' }).click()
    await expect(page.getByRole('button', { name: 'Запази час', exact: true })).toBeVisible()
    await context.close()
  })
})

test.describe('H. Keyboard, focus and order', () => {
  test('native checkboxes toggle with Space, focus follows the step headings and is visible, Back precedes the forward action', async ({ browser, request }) => {
    const { context, page, guest } = await newGuest(browser, salon.slug)
    const ids = [salon.serviceIds['Дамско подстригване']!]
    const date = (await availableDates(request, salon.slug, ids))[0]!
    await guest.start()
    await expect(guest.heading()).toBeFocused()
    // Tab reaches the first checkbox (the leave action comes first), Space toggles it.
    const first = guest.checkbox('Дамско подстригване')
    await first.focus()
    await expect(first).toBeFocused()
    await page.keyboard.press('Space')
    await expect(first).toBeChecked()
    await page.keyboard.press('Space')
    await expect(first).not.toBeChecked()
    await page.keyboard.press('Space')
    await expect(first).toBeChecked()
    // The focused option shows a visible indicator (outline or shadow on it or on its card).
    const visible = await first.evaluate((input) => {
      const style = (element: Element) => getComputedStyle(element)
      const own = style(input)
      const card = input.closest('label') as HTMLElement
      const cardStyle = style(card)
      const outlined = (css: CSSStyleDeclaration) => css.outlineStyle !== 'none' && parseFloat(css.outlineWidth) > 0
      return outlined(own) || outlined(cardStyle) || cardStyle.boxShadow !== 'none' || own.boxShadow !== 'none'
    })
    expect(visible, 'the focused checkbox shows a focus indicator').toBe(true)

    const order = async (back: string, forward: string) => page.evaluate(([backLabel, forwardLabel]) => {
      const buttons = Array.from(document.querySelectorAll('.booking-journey button'))
      const find = (label: string) => buttons.find((button) => button.textContent?.trim() === label)
      const left = find(backLabel!)
      const right = find(forwardLabel!)
      return !!left && !!right && !!(left.compareDocumentPosition(right) & Node.DOCUMENT_POSITION_FOLLOWING)
    }, [back, forward])

    await guest.toStaff()
    await expect(guest.heading()).toBeFocused()
    await expect(guest.button('Напред')).toBeVisible()
    expect(await order('Назад', 'Напред'), 'Назад precedes Напред').toBe(true)
    await guest.toDate()
    await expect(guest.heading()).toBeFocused()
    await expect(guest.button('Напред')).toBeVisible()
    expect(await order('Назад', 'Напред'), 'Назад precedes Напред').toBe(true)
    await guest.chooseDate(date)
    const slot = (await slotsOn(request, salon.slug, ids, date))[0]!
    await guest.chooseSlot(slot.start)
    await guest.toDetails()
    await expect(guest.heading()).toBeFocused()
    expect(await order('Назад', 'Напред'), 'Назад precedes Напред').toBe(true)
    await guest.fillDetails({ name: 'Клавиатурен Гост', phone: freshPhone() })
    await guest.toReview()
    await expect(guest.heading()).toBeFocused()
    expect(await order('Назад', 'Потвърди резервацията'), 'Назад precedes the confirm action').toBe(true)
    await context.close()
  })

  test('an error moves the focus to its alert, and the identity conflict names no Customer', async ({ browser, request }) => {
    const { context, page, guest } = await newGuest(browser, salon.slug)
    const ids = [salon.serviceIds['Маникюр']!]
    const date = (await availableDates(request, salon.slug, ids))[1]!
    const slot = (await slotsOn(request, salon.slug, ids, date))[0]!
    const phone = freshPhone()
    const email = freshEmail('known')
    // An existing Customer, created through the supported owner API.
    const seeded = await salon.owner!.page.evaluate(async ({ origin, seed }) => {
      const csrf = await (await fetch(`${origin}/api/auth/csrf`, { credentials: 'include' })).json() as { headerName: string, token: string }
      const response = await fetch(`${origin}/api/business/customers`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify(seed),
      })
      return response.status
    }, { origin: API_ORIGIN, seed: { displayName: 'Известен Клиент', phone, email } })
    expect(seeded).toBe(201)

    await guest.toReviewFor({ services: ['Маникюр'], staff: null, date, slot: slot.start, details: { name: 'Нов Опит', phone, email: freshEmail('other') } })
    const answered = nextBookingResponse(page)
    await guest.confirm()
    expect((await answered).status()).toBe(409)
    await expect(guest.alert()).toBeFocused()
    expect((await guest.alert().innerText()).startsWith(MESSAGES.identityConflict)).toBe(true)
    expect(await page.locator('main').innerText()).not.toContain('Известен Клиент')
    await context.close()
  })
})

test.describe('H. Responsive smoke at the supported widths', () => {
  for (const width of [375, 320]) {
    test(`${width} px: no overflow, calendar cells and every other control meet their target, long names wrap`, async ({ browser, request }) => {
      const { context, page, guest } = await newGuest(browser, layout.slug, { width, height: 800 })
      // The date comes from the same selection the journey ends with (five 96-minute Services, 480 minutes):
      // a shorter Service has more dates, so the first of them can have no time for the final selection.
      const ids = MAX_NAMES.map((name) => layout.serviceIds[name]!)
      const dates = await availableDates(request, layout.slug, ids)
      await guest.start()
      await expectNoHorizontalOverflow(page)

      // Five maximum prices: the whole selection summary is usable at this width.
      await guest.select(...MAX_NAMES)
      await expect(page.locator('.booking-selection-total')).toHaveText(totalText(4999999999995, 480))
      await expectNoHorizontalOverflow(page)
      await guest.choice(MAX_NAMES[4]!).click()
      await guest.select(LONG_SERVICE)
      await expectNoClippedContent(page, '.booking-choice-title')
      await expectNoHorizontalOverflow(page)
      await guest.select(LONG_SERVICE)
      await guest.select(MAX_NAMES[4]!)
      await expect(guest.button('Напред')).toBeEnabled()
      await guest.toStaff()
      await expectNoClippedContent(page, '.booking-choice-title')
      await expect(page.locator('label.booking-choice .booking-choice-title').nth(1)).toHaveText(LONG_STAFF)
      await expectNoHorizontalOverflow(page)
      await guest.toDate()
      await guest.chooseDate(dates[0]!)
      await expect(page.locator('label.booking-slot').first()).toBeVisible()
      await expectNoHorizontalOverflow(page)

      // The calendar-grid exception: day cells are at least 24 px wide and 44 px high.
      const cells = await page.locator('td.booking-day:not(.booking-day--outside)').evaluateAll((elements) =>
        elements.map((element) => {
          const box = element.getBoundingClientRect()
          return { width: box.width, height: box.height }
        }))
      expect(cells.length).toBeGreaterThan(0)
      for (const cell of cells) {
        expect(cell.width).toBeGreaterThanOrEqual(24)
        expect(cell.height).toBeGreaterThanOrEqual(44)
      }
      // Every control outside the calendar grid keeps the 44 px target.
      const small = await page.evaluate(() => {
        const targets = Array.from(document.querySelectorAll(
          '.booking-journey button, .booking-journey label.booking-choice, .booking-journey label.booking-slot, .booking-journey input[type=text], .booking-journey input[type=tel], .booking-journey input[type=email]',
        ))
        return targets
          .filter((element) => !element.closest('.booking-calendar-grid'))
          .map((element) => {
            const box = element.getBoundingClientRect()
            return { name: (element.textContent ?? '').trim().slice(0, 30), width: box.width, height: box.height }
          })
          .filter((box) => box.width > 0 && Math.min(box.width, box.height) < 44)
      })
      expect(small).toEqual([])

      await context.close()
    })
  }

  test('a review of five maximum prices stays usable at 320 px', async ({ browser, request }) => {
    const { context, page, guest } = await newGuest(browser, layout.slug, { width: 320, height: 800 })
    const ids = MAX_NAMES.map((name) => layout.serviceIds[name]!)
    const date = (await availableDates(request, layout.slug, ids))[0]!
    const slot = (await slotsOn(request, layout.slug, ids, date))[0]!
    await guest.toReviewFor({ services: MAX_NAMES, staff: null, date, slot: slot.start, details: { name: 'Максимален Гост', phone: freshPhone() } })
    await expect(page.locator('.booking-summary-total')).toHaveText(totalText(4999999999995, 480))
    await expect(page.locator('.booking-summary-services li')).toHaveCount(5)
    await expectNoHorizontalOverflow(page)
    await expectNoClippedContent(page, '.booking-summary-strong')
    await context.close()
  })
})

test.describe('I. Privacy and security of the public traffic', () => {
  test('a three-Service journey leaves no trace in the URL, storage, cookies, history, title or console, and sends no credentials', async ({ browser, request }) => {
    const { context, page, guest } = await newGuest(browser, salon.slug)
    const consoleLog = recordConsole(page)
    const audit = recordTraffic(page)
    const names = ['Дамско подстригване', 'Маникюр', 'Масаж на лице']
    const ids = names.map((name) => salon.serviceIds[name]!)
    const date = (await availableDates(request, salon.slug, ids))[2]!
    const slot = (await slotsOn(request, salon.slug, ids, date))[0]!
    const phone = freshPhone()
    const email = freshEmail('privacy')
    await guest.toReviewFor({ services: names, staff: 'Анна Георгиева', date, slot: slot.start, details: { name: 'Личен Гост', phone, email, note: 'Тайна бележка' } })
    const created = nextBookingResponse(page)
    await guest.confirm()
    expect((await created).status()).toBe(201)
    await expect(guest.heading()).toHaveText('Резервацията е потвърдена')

    const calls = await audit.stop()
    expectPublicTrafficPrivate(calls)
    const post = postBody(callsTo(calls, 'POST', '/bookings')[0]!)
    const sentinels = [
      phone, email, 'Личен', 'Тайна бележка', String(post.attemptId), 'Дамско', 'Маникюр', 'Масаж', '115.50', '11550',
      ...ids, salon.staffIds['Анна Георгиева']!,
    ]
    await expectJourneyStateClean(page, sentinels)
    const lines = consoleLog.stop()
    for (const line of lines) {
      for (const sentinel of sentinels) expect(line, 'console output carried journey data').not.toContain(sentinel)
    }
    // Only the documented Service identifiers appear in any request URL.
    for (const call of calls.filter((entry) => entry.method === 'GET')) {
      const params = new URLSearchParams(call.search)
      const keys = [...params.keys()].filter((key) => key !== 'serviceId' && key !== 'date' && key !== 'staffMemberId')
      expect(keys).toEqual([])
      expect(new URLSearchParams(call.search).getAll('serviceId').every((value) => canonicalOrder(ids).includes(value))).toBe(true)
    }
    await context.close()
  })

  test('an owner or administrator session leaves the public answers byte-identical', async ({ browser, request }) => {
    const ids = [salon.serviceIds['Маникюр']!]
    const date = (await availableDates(request, salon.slug, ids))[0]!
    const urls = [
      `${API_ORIGIN}/api/public/businesses/${salon.slug}`,
      `${API_ORIGIN}/api/public/businesses/${salon.slug}/booking-options?serviceId=${ids[0]}`,
      `${API_ORIGIN}/api/public/businesses/${salon.slug}/availability?serviceId=${ids[0]}&date=${date}`,
    ]
    const anonymous: string[] = []
    for (const url of urls) {
      const response = await request.get(url)
      expect(response.status()).toBe(200)
      expect(response.headers()['cache-control']).toContain('no-store')
      expect(response.headers()['set-cookie']).toBeUndefined()
      anonymous.push(await response.text())
    }
    // The same reads from pages that carry an owner session and an administrator session.
    for (const page of [salon.owner!.page, admin]) {
      const texts = await page.evaluate(async (targets) => {
        const out: string[] = []
        for (const url of targets) {
          const response = await fetch(url, { credentials: 'include' })
          out.push(`${response.status}:${await response.text()}`)
        }
        return out
      }, urls)
      expect(texts).toEqual(anonymous.map((text) => `200:${text}`))
    }
    // The typed helpers agree with the raw reads.
    expect((await publicOptions(request, salon.slug, ids)).staff.length).toBeGreaterThan(0)
    expect((await publicAvailability(request, salon.slug, ids, date)).date).toBe(date)
    // No public visit created a session for the guest context.
    const context = await browser.newContext()
    const page = await context.newPage()
    await page.goto(`/${salon.slug}`)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    expect((await context.cookies()).filter((cookie) => cookie.name === 'SPOTYOURSESSION')).toEqual([])
    await context.close()
  })
})
