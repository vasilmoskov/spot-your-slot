import { devices, expect, test, type BrowserContext, type Page } from '@playwright/test'
import {
  SERVICES_HEADING,
  apiRequest,
  expectBrowserStorageEmpty,
  expectNoHorizontalOverflow,
  publicSession,
  signIn,
} from './support/browser'
import { requiredEnvironment } from './support/environment'
import {
  createDraftBusiness,
  inviteOwner,
  onboardOwner,
  signInAsPlatformAdmin,
  uniqueBusinessFixture,
  type BusinessFixture,
  type OwnerSession,
} from './support/provisioning'
import {
  ChangeTracker,
  addDays,
  businessToday,
  createStaffMember,
  listQuery,
  seedChange,
  seedClosure,
  seedTimeOff,
  shown,
  shownRange,
} from './support/scheduleChanges'

// The Business-owner "Промени в графика" journey (Issue #16, Phase 6). It
// provisions its own Business through the administrator UI and the invitation
// flow, so it depends on no other spec. Tests are independent of each other:
// each one seeds the records it needs through the private API, opens a fresh
// owner page and removes its records in `finally`. The only ordering
// assumption is that the Business starts DRAFT and ends ACTIVE; every test
// works in either state, and the lifecycle test restores any state it changes.

const BUSINESS_TEMPLATE: BusinessFixture = {
  name: 'Салон Хоризонт Графици',
  slug: 'salon-horizont-changes-e2e',
  ownerEmail: 'owner-changes@example.invalid',
  ownerFirstName: 'Николина',
  ownerLastName: 'Графикова',
}

const OTHER_BUSINESS_TEMPLATE: BusinessFixture = {
  name: 'Студио Меридиан Графици',
  slug: 'studio-meridian-changes-e2e',
  ownerEmail: 'owner-meridian@example.invalid',
  ownerFirstName: 'Борис',
  ownerLastName: 'Меридианов',
}

const STAFF_NAME = 'Елена Графикова Тестова'
const SUSPENDED_BANNER =
  'Бизнесът е временно спрян. Данните са видими, но конфигурацията не може да бъде променяна.'
const CHANGES_PATH = '/api/business/schedule-exceptions'
const PAGINATION_LABEL = 'Странициране на промените в графика'

let adminContext: BrowserContext
let admin: Page
let owner: OwnerSession
// An idle owner page used only for API calls, so tests never race a navigating page.
let ownerApi: Page
let business: BusinessFixture
let businessId: string
let staffMemberId: string
const tracker = new ChangeTracker()

test.beforeAll(async ({ browser }) => {
  test.setTimeout(150_000)
  business = uniqueBusinessFixture(BUSINESS_TEMPLATE)
  adminContext = await browser.newContext()
  admin = await adminContext.newPage()
  await signInAsPlatformAdmin(admin)
  businessId = await createDraftBusiness(admin, business)
  await inviteOwner(admin, businessId, business.ownerEmail)
  owner = await onboardOwner(browser, adminContext, business)
  ownerApi = await owner.context.newPage()
  await ownerApi.goto('/')
  await expect(ownerApi.getByRole('heading', SERVICES_HEADING)).toBeVisible()
  staffMemberId = await createStaffMember(ownerApi, STAFF_NAME)
})

test.afterAll(async () => {
  await owner?.context.close()
  await adminContext?.close()
})

async function withOwnerPage(run: (page: Page) => Promise<void>): Promise<void> {
  const page = await owner.context.newPage()
  try {
    await run(page)
  } finally {
    await page.close()
  }
}

function hashQuery(page: Page): string {
  return new URL(page.url()).hash.split('?')[1] ?? ''
}

function hashPath(page: Page): string {
  return new URL(page.url()).hash.split('?')[0] ?? ''
}

function recordRows(page: Page) {
  return page.locator('table.exception-table tbody tr')
}

function column(page: Page, label: 'Вид' | 'Дати' | 'Член на екипа' | 'Часове' | 'Статус') {
  return page.locator(`table.exception-table tbody td[data-label="${label}"]`)
}

function pagination(page: Page) {
  return page.getByRole('navigation', { name: PAGINATION_LABEL })
}

function sortHeader(page: Page, label: string) {
  return page.getByRole('columnheader', { name: label, exact: true }).getByRole('button')
}

function feedback(page: Page, text: string) {
  return page.getByRole('status').filter({ hasText: text })
}

/** Opens the list at a canonical window and waits until the route is canonical. */
async function openList(page: Page, from: string, to: string, query = ''): Promise<void> {
  await page.goto(`/#/business/schedule/exceptions?${query || listQuery(from, to)}`)
  await expect(page.getByRole('heading', { name: 'Работно време', level: 1, exact: true }))
    .toBeVisible()
  await expect(page.getByLabel('Резултати на страница').or(page.getByText('Няма промени за избрания период.')))
    .toBeVisible()
}

async function openAdminBusiness(): Promise<void> {
  await admin.goto(`/#/platform/businesses/${encodeURIComponent(businessId)}`)
  await expect(admin.getByRole('heading', { name: business.name, exact: true })).toBeVisible()
  await admin.getByText('Активиране', { exact: true }).click()
}

async function expectInsideViewport(page: Page, target: ReturnType<Page['locator']>): Promise<void> {
  const box = await target.boundingBox()
  const viewport = page.viewportSize()
  expect(box).not.toBeNull()
  expect(viewport).not.toBeNull()
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width).toBeLessThanOrEqual(viewport!.width)
}

test.describe('Schedule changes: navigation and list state', () => {
  test('the owner switches between the two tabs under one page heading and lands on the canonical list state', async () => {
    const today = businessToday()
    await withOwnerPage(async (page) => {
      await page.goto('/#/business/services')
      const sidebar = page.getByRole('navigation', { name: 'Навигация на бизнеса' })
      await sidebar.getByRole('link', { name: 'Работно време' }).click()
      const tabs = page.getByRole('navigation', { name: 'Работно време' })
      await expect(page.getByRole('heading', { name: 'Работно време', level: 1, exact: true }))
        .toBeVisible()
      await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
      await expect(tabs.getByRole('link', { name: 'Седмични графици' }))
        .toHaveAttribute('aria-current', 'page')
      await expect(tabs.getByRole('link', { name: 'Промени в графика' }))
        .not.toHaveAttribute('aria-current', 'page')

      await tabs.getByRole('link', { name: 'Промени в графика' }).click()
      await expect(tabs.getByRole('link', { name: 'Промени в графика' }))
        .toHaveAttribute('aria-current', 'page')
      await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
      await expect(page.getByRole('heading', { name: 'Работно време', level: 1, exact: true }))
        .toBeVisible()

      // The provisional window is replaced by the Business-local canonical one.
      await expect.poll(() => hashPath(page)).toBe('#/business/schedule/exceptions')
      await expect.poll(() => hashQuery(page)).toBe(listQuery(today, addDays(today, 29)))
      await expect(page.locator('#exception-window-from')).toHaveValue(today)
      await expect(page.locator('#exception-window-to')).toHaveValue(addDays(today, 29))
      await expect(page.getByText('Няма промени за избрания период.')).toBeVisible()

      await tabs.getByRole('link', { name: 'Седмични графици' }).click()
      await expect.poll(() => hashPath(page)).toBe('#/business/schedule')
      await expect(tabs.getByRole('link', { name: 'Седмични графици' }))
        .toHaveAttribute('aria-current', 'page')
      await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
      await expectBrowserStorageEmpty(page)
    })
  })

  test('the list pages, sorts and resizes deterministically and restores its state after a detail round trip', async () => {
    test.setTimeout(120_000)
    const today = businessToday()
    const from = today
    const to = addDays(today, 29)
    const day = (offset: number) => addDays(today, offset)
    try {
      for (let offset = 1; offset <= 25; offset += 1) {
        tracker.track((await seedClosure(ownerApi, day(offset))).id)
      }
      tracker.track((await seedTimeOff(ownerApi, staffMemberId, day(26))).id)
      tracker.track((await seedTimeOff(ownerApi, staffMemberId, day(27))).id)

      await withOwnerPage(async (page) => {
        await openList(page, from, to)
        const shownDates = (offsets: number[]) => offsets.map((offset) => shown(day(offset)))
        const range = (first: number, last: number) =>
          Array.from({ length: last - first + 1 }, (_, index) => first + index)

        // Canonical first page: exactly ten records, dates ascending.
        await expect(recordRows(page)).toHaveCount(10)
        await expect(column(page, 'Дати')).toHaveText(shownDates(range(1, 10)))
        await expect(pagination(page)).toContainText('Показани 1–10 от 27')
        await expect(pagination(page)).toContainText('Страница 1 от 3')
        await expect(pagination(page).getByRole('button', { name: 'Предишна' })).toBeDisabled()
        await expect.poll(() => hashQuery(page)).toBe(listQuery(from, to))

        await pagination(page).getByRole('button', { name: 'Следваща' }).click()
        await expect(column(page, 'Дати')).toHaveText(shownDates(range(11, 20)))
        await expect(pagination(page)).toContainText('Показани 11–20 от 27')
        await expect.poll(() => hashQuery(page)).toBe(listQuery(from, to, { page: 1 }))
        await pagination(page).getByRole('button', { name: 'Следваща' }).click()
        await expect(recordRows(page)).toHaveCount(7)
        await expect(pagination(page)).toContainText('Показани 21–27 от 27')
        await expect(pagination(page).getByRole('button', { name: 'Следваща' })).toBeDisabled()
        await pagination(page).getByRole('button', { name: 'Предишна' }).click()
        await expect(column(page, 'Дати')).toHaveText(shownDates(range(11, 20)))
        await expect(pagination(page)).toContainText('Показани 11–20 от 27')

        // Sorting by kind resets to the first page; descending puts absences first.
        await sortHeader(page, 'Вид').click()
        await expect(recordRows(page)).toHaveCount(10)
        await expect.poll(() => hashQuery(page)).toBe(listQuery(from, to, { sort: 'kind', direction: 'asc' }))
        await expect(column(page, 'Вид')).toHaveText(Array(10).fill('Неработно време'))
        await expect(column(page, 'Дати')).toHaveText(shownDates(range(1, 10)))
        await sortHeader(page, 'Вид').click()
        await expect.poll(() => hashQuery(page)).toBe(listQuery(from, to, { sort: 'kind', direction: 'desc' }))
        await expect(column(page, 'Дати')).toHaveText(shownDates([26, 27, ...range(1, 8)]))
        await expect(column(page, 'Вид').first()).toHaveText('Отсъствие')
        await expect(column(page, 'Член на екипа').first()).toHaveText(STAFF_NAME)

        // Changing the page size resets the page and keeps the ordering.
        await page.getByLabel('Резултати на страница').selectOption('25')
        await expect(recordRows(page)).toHaveCount(25)
        await expect(pagination(page)).toContainText('Показани 1–25 от 27')
        await expect.poll(() => hashQuery(page)).toBe(
          listQuery(from, to, { size: 25, sort: 'kind', direction: 'desc' }),
        )
        await pagination(page).getByRole('button', { name: 'Следваща' }).click()
        await expect(column(page, 'Дати')).toHaveText(shownDates([24, 25]))

        // Sorting by dates from the second page returns to the first page.
        await sortHeader(page, 'Дати').click()
        await expect(recordRows(page)).toHaveCount(25)
        await expect.poll(() => hashQuery(page)).toBe(listQuery(from, to, { size: 25 }))
        await sortHeader(page, 'Дати').click()
        await expect.poll(() => hashQuery(page)).toBe(listQuery(from, to, { size: 25, direction: 'desc' }))
        await expect(column(page, 'Дати').first()).toHaveText(shown(day(27)))

        // A narrower explicit window is applied, then the second page is opened.
        await page.locator('#exception-window-from').fill(day(1))
        await page.getByRole('button', { name: 'Покажи' }).click()
        const restored = listQuery(day(1), to, { size: 25, direction: 'desc', page: 1 })
        await expect.poll(() => hashQuery(page)).toBe(listQuery(day(1), to, { size: 25, direction: 'desc' }))
        await pagination(page).getByRole('button', { name: 'Следваща' }).click()
        await expect.poll(() => hashQuery(page)).toBe(restored)
        await expect(column(page, 'Дати')).toHaveText(shownDates([2, 1]))
        await expect(pagination(page)).toContainText('Показани 26–27 от 27')

        // Detail and back restore the window, page, size, sort and direction.
        await recordRows(page).filter({ hasText: shown(day(1)) }).getByRole('link').click()
        await expect(page.getByRole('heading', { name: 'Промяна в графика', level: 1 }))
          .toBeVisible()
        await expect.poll(() => hashQuery(page)).toBe(restored)
        await page.getByRole('button', { name: 'Обратно към графика' }).click()
        await expect(column(page, 'Дати')).toHaveText(shownDates([2, 1]))
        await expect(pagination(page)).toContainText('Показани 26–27 от 27')
        await expect(page.locator('#exception-window-from')).toHaveValue(day(1))
        await expect(page.getByLabel('Резултати на страница')).toHaveValue('25')
        await expect.poll(() => hashQuery(page)).toBe(restored)
      })
    } finally {
      await tracker.cleanup(ownerApi)
    }
  })

  test('each record shows its derived status and past records are never removed automatically', async () => {
    const today = businessToday()
    try {
      tracker.track((await seedClosure(ownerApi, addDays(today, -1))).id)
      tracker.track((await seedClosure(ownerApi, today)).id)
      tracker.track((await seedClosure(ownerApi, addDays(today, 1))).id)

      await withOwnerPage(async (page) => {
        const from = addDays(today, -1)
        const to = addDays(today, 1)
        await openList(page, from, to)
        await expect(column(page, 'Статус')).toHaveText(['Минала', 'В сила', 'Предстояща'])

        await sortHeader(page, 'Статус').click()
        await expect(column(page, 'Статус')).toHaveText(['В сила', 'Предстояща', 'Минала'])
        await expect.poll(() => hashQuery(page)).toBe(listQuery(from, to, { sort: 'status', direction: 'asc' }))

        await page.reload()
        await expect(recordRows(page)).toHaveCount(3)
        await expect(column(page, 'Статус')).toHaveText(['В сила', 'Предстояща', 'Минала'])
      })
    } finally {
      await tracker.cleanup(ownerApi)
    }
  })
})

test.describe('Schedule changes: creating, editing and deleting', () => {
  test('the owner creates, edits and deletes changes with the approved terminology', async () => {
    test.setTimeout(120_000)
    const today = businessToday()
    const from = today
    const to = addDays(today, 29)
    const closureFirst = addDays(today, 3)
    const closureLast = addDays(today, 5)
    const absenceDate = addDays(today, 7)
    try {
      const status = (await publicSession(ownerApi)).body?.businesses[0]?.status
      // Mutation is available in DRAFT and in ACTIVE.
      expect(['DRAFT', 'ACTIVE']).toContain(status)

      await withOwnerPage(async (page) => {
        await openList(page, from, to)

        // Business-wide non-working period over whole dates.
        await page.getByRole('button', { name: 'Добави промяна' }).click()
        await expect(page.getByRole('heading', { name: 'Нова промяна в графика', level: 1 }))
          .toBeVisible()
        await page.getByRole('radio', { name: 'Неработно време' }).check()
        await expect(page.getByText('Блокира резервациите за всички членове на екипа през избрания период.'))
          .toBeVisible()
        await page.locator('#exception-first-date').fill(closureFirst)
        await page.locator('#exception-last-date').fill(closureLast)
        await page.getByRole('button', { name: 'Добави', exact: true }).click()
        await expect(feedback(page, 'Промяната е добавена.')).toBeVisible()
        await expect(page.getByRole('heading', { name: 'Промяна в графика', level: 1 }))
          .toBeVisible()
        tracker.trackFromUrl(page)
        const details = page.locator('dl.business-details-list')
        await expect(details).toContainText('Неработно време')
        await expect(details).toContainText(shownRange(closureFirst, closureLast))
        await expect(details).toContainText('Цял ден')
        await expect(details).toContainText('Предстояща')

        // StaffMember-scoped absence for part of one date, with adjacent periods.
        await page.getByRole('button', { name: 'Обратно към графика' }).click()
        await expect.poll(() => hashQuery(page)).toBe(listQuery(from, to))
        await page.getByRole('button', { name: 'Добави промяна' }).click()
        await page.getByRole('radio', { name: 'Отсъствие' }).check()
        await page.locator('#exception-staff').selectOption({ label: STAFF_NAME })
        await page.getByRole('radio', { name: 'Част от деня' }).check()
        await page.locator('#exception-first-date').fill(absenceDate)

        const periods = page.getByRole('group', { name: 'Часове' })
        const dialog = page.getByRole('dialog', { name: 'Добавяне на период' })
        const addPeriod = async (start: string, end: string) => {
          await periods.getByRole('button', { name: '+ Добави' }).click()
          await dialog.getByLabel('Начален час').fill(start)
          await dialog.getByLabel('Краен час').fill(end)
          await dialog.getByRole('button', { name: 'Добави', exact: true }).click()
        }
        await addPeriod('09:00', '12:00')
        await expect(dialog).toHaveCount(0)
        await addPeriod('12:00', '14:00')
        // Adjacent periods are accepted.
        await expect(dialog).toHaveCount(0)
        await expect(periods.getByText('09:00–12:00')).toBeVisible()
        await expect(periods.getByText('12:00–14:00')).toBeVisible()

        // An overlap and an exact duplicate name the hours and keep the input.
        await addPeriod('11:00', '13:00')
        await expect(dialog.getByText(
          'Периодът 11:00–13:00 се застъпва със съществуващия период 09:00–12:00.',
        )).toBeVisible()
        await expect(dialog.getByLabel('Начален час')).toHaveValue('11:00')
        await expect(dialog.getByLabel('Краен час')).toHaveValue('13:00')
        await dialog.getByLabel('Начален час').fill('09:00')
        await dialog.getByLabel('Краен час').fill('12:00')
        await dialog.getByRole('button', { name: 'Добави', exact: true }).click()
        await expect(dialog.getByText('Периодът 09:00–12:00 вече е добавен.')).toBeVisible()
        await dialog.getByRole('button', { name: 'Отказ' }).click()
        await expect(dialog).toHaveCount(0)

        await page.getByRole('button', { name: 'Добави', exact: true }).click()
        await expect(feedback(page, 'Промяната е добавена.')).toBeVisible()
        const absenceId = tracker.trackFromUrl(page)
        await expect(details).toContainText('Отсъствие')
        await expect(details).toContainText(STAFF_NAME)
        await expect(details).toContainText('09:00–12:00, 12:00–14:00')

        // Persisted presentation after a full reload of the detail and the list.
        await page.reload()
        await expect(details).toContainText(shown(absenceDate))
        await expect(details).toContainText('09:00–12:00, 12:00–14:00')
        await page.getByRole('button', { name: 'Обратно към графика' }).click()
        await page.reload()
        await expect(recordRows(page)).toHaveCount(2)
        await expect(column(page, 'Вид')).toHaveText(['Неработно време', 'Отсъствие'])
        await expect(column(page, 'Дати')).toHaveText([
          shownRange(closureFirst, closureLast),
          shown(absenceDate),
        ])
        await expect(column(page, 'Член на екипа')).toHaveText(['—', STAFF_NAME])
        await expect(column(page, 'Часове')).toHaveText(['Цял ден', '09:00–12:00, 12:00–14:00'])

        // The approved terminology: no internal word and no enum name.
        await expect(page.locator('body')).not.toContainText(/изключени/i)
        await expect(page.locator('body')).not.toContainText(/BUSINESS_CLOSURE|STAFF_TIME_OFF/)

        // Edit the absence: remove one period.
        await recordRows(page).filter({ hasText: STAFF_NAME }).getByRole('link').click()
        await expect(page.getByRole('heading', { name: 'Промяна в графика', level: 1 }))
          .toBeVisible()
        await page.getByRole('button', { name: 'Редактирай', exact: true }).click()
        await page.getByRole('button', { name: 'Премахни периода 12:00–14:00' }).click()
        await page.getByRole('button', { name: 'Запази промените' }).click()
        await expect(feedback(page, 'Промените са запазени.')).toBeVisible()
        await expect(details).toContainText('09:00–12:00')
        await expect(details).not.toContainText('12:00–14:00')
        await page.reload()
        await expect(details).not.toContainText('12:00–14:00')

        // Deletion is detail-only and starts on the safe action.
        await page.getByRole('button', { name: 'Обратно към графика' }).click()
        await expect(page.getByRole('button', { name: /Изтрий/ })).toHaveCount(0)
        await recordRows(page).filter({ hasText: STAFF_NAME }).getByRole('link').click()
        await page.getByRole('button', { name: 'Изтрий', exact: true }).click()
        const confirmation = page.getByRole('alertdialog', { name: 'Изтриване на промяна' })
        await expect(confirmation).toBeVisible()
        await expect(confirmation.getByRole('button', { name: 'Отказ' })).toBeFocused()
        await confirmation.getByRole('button', { name: 'Отказ' }).click()
        await expect(confirmation).toHaveCount(0)
        await expect(page.getByRole('button', { name: 'Изтрий', exact: true })).toBeFocused()
        expect((await apiRequest(ownerApi, 'GET', `${CHANGES_PATH}/${absenceId}`)).status).toBe(200)

        await page.getByRole('button', { name: 'Изтрий', exact: true }).click()
        await page.getByRole('button', { name: 'Изтрий промяната' }).click()
        await expect(feedback(page, 'Промяната е изтрита.')).toBeVisible()
        await expect.poll(() => hashQuery(page)).toBe(listQuery(from, to))
        await expect(recordRows(page)).toHaveCount(1)
        await expect(column(page, 'Вид')).toHaveText(['Неработно време'])
        await expect(page.getByText(STAFF_NAME)).toHaveCount(0)
        const gone = await apiRequest(ownerApi, 'GET', `${CHANGES_PATH}/${absenceId}`)
        expect([gone.status, gone.code]).toEqual([404, 'SCHEDULE_EXCEPTION_NOT_FOUND'])
        await expectBrowserStorageEmpty(page)
      })
    } finally {
      await tracker.cleanup(ownerApi)
    }
  })

  test('same-kind conflicts show the kind-specific message, keep the input and allow adjacent dates', async () => {
    test.setTimeout(120_000)
    const today = businessToday()
    const day = (offset: number) => addDays(today, offset)
    try {
      tracker.track((await seedClosure(ownerApi, day(10), day(12))).id)
      tracker.track((await seedChange(ownerApi, {
        kind: 'ADDITIONAL_WORKING_PERIODS',
        staffMemberId,
        firstDate: day(9),
        allDay: false,
        periods: [{ startTime: '10:00', endTime: '12:00' }],
      })).id)

      await withOwnerPage(async (page) => {
        await page.goto('/#/business/schedule/exceptions/new')
        await expect(page.getByRole('heading', { name: 'Нова промяна в графика', level: 1 }))
          .toBeVisible()
        await page.getByRole('radio', { name: 'Неработно време' }).check()
        await page.locator('#exception-first-date').fill(day(11))
        await page.locator('#exception-last-date').fill(day(13))
        await page.getByRole('button', { name: 'Добави', exact: true }).click()

        const alert = page.getByRole('alert')
        await expect(alert).toContainText('Избраният период се застъпва с вече добавено неработно време.')
        await expect(alert).not.toContainText('вече добавена промяна')
        await expect(alert.getByRole('paragraph')).toHaveCount(1)
        expect(hashPath(page)).toBe('#/business/schedule/exceptions/new')
        await expect(page.locator('#exception-first-date')).toHaveValue(day(11))
        await expect(page.locator('#exception-last-date')).toHaveValue(day(13))
        await expect(page.getByRole('radio', { name: 'Неработно време' })).toBeChecked()

        // Dates adjacent to the existing record are accepted.
        await page.locator('#exception-first-date').fill(day(13))
        await page.locator('#exception-last-date').fill(day(14))
        await page.getByRole('button', { name: 'Добави', exact: true }).click()
        await expect(feedback(page, 'Промяната е добавена.')).toBeVisible()
        tracker.trackFromUrl(page)
        await expect(page.locator('dl.business-details-list'))
          .toContainText(shownRange(day(13), day(14)))
      })

      await withOwnerPage(async (page) => {
        await page.goto('/#/business/schedule/exceptions/new')
        await page.getByRole('radio', { name: 'Допълнителни работни часове' }).check()
        await page.locator('#exception-staff').selectOption({ label: STAFF_NAME })
        await page.locator('#exception-first-date').fill(day(9))
        await page.getByRole('button', { name: '+ Добави' }).click()
        const dialog = page.getByRole('dialog', { name: 'Добавяне на период' })
        await dialog.getByLabel('Начален час').fill('15:00')
        await dialog.getByLabel('Краен час').fill('16:00')
        await dialog.getByRole('button', { name: 'Добави', exact: true }).click()
        await page.getByRole('button', { name: 'Добави', exact: true }).click()

        const alert = page.getByRole('alert')
        const paragraphs = alert.getByRole('paragraph')
        await expect(paragraphs).toHaveText([
          'За тази дата вече има допълнителни работни часове за избрания член на екипа.',
          'Редактирайте съществуващия запис, за да добавите или промените периодите.',
        ])
        await expect(page.locator('#exception-first-date')).toHaveValue(day(9))
        await expect(page.locator('#exception-staff')).toHaveValue(staffMemberId)
        await expect(page.getByRole('group', { name: 'Часове' }).getByText('15:00–16:00'))
          .toBeVisible()
      })
    } finally {
      await tracker.cleanup(ownerApi)
    }
  })
})

test.describe('Schedule changes: lifecycle', () => {
  test('SUSPENDED keeps records readable and rejects every mutation; reactivation restores editing', async () => {
    test.setTimeout(150_000)
    const today = businessToday()
    const changeDate = addDays(today, 20)
    let suspended = false
    try {
      await openAdminBusiness()
      if (await admin.getByRole('button', { name: 'Активирай', exact: true }).count() > 0) {
        await admin.getByRole('button', { name: 'Активирай', exact: true }).click()
        await admin.getByRole('button', { name: 'Потвърди активирането' }).click()
        await expect(admin.getByRole('status')).toHaveText('Бизнесът е активиран.')
      }

      const record = await seedClosure(ownerApi, changeDate)
      tracker.track(record.id)

      await withOwnerPage(async (page) => {
        await openList(page, today, addDays(today, 29))
        await expect(page.getByRole('button', { name: 'Добави промяна' })).toBeVisible()
        await expect(recordRows(page)).toHaveCount(1)

        await admin.getByRole('button', { name: 'Спри временно' }).click()
        await admin.getByRole('button', { name: 'Потвърди спирането' }).click()
        suspended = true
        await expect(admin.getByRole('status')).toHaveText('Бизнесът е временно спрян.')

        await page.reload()
        await expect(recordRows(page)).toHaveCount(1)
        await expect(column(page, 'Дати')).toHaveText([shown(changeDate)])
        await expect(page.getByRole('status').filter({ hasText: SUSPENDED_BANNER })).toHaveCount(1)
        await expect(page.getByRole('button', { name: 'Добави промяна' })).toHaveCount(0)

        await recordRows(page).getByRole('link').click()
        await expect(page.getByRole('heading', { name: 'Промяна в графика', level: 1 }))
          .toBeVisible()
        await expect(page.locator('dl.business-details-list')).toContainText('Неработно време')
        await expect(page.getByRole('status').filter({ hasText: SUSPENDED_BANNER })).toHaveCount(1)
        for (const name of ['Редактирай', 'Изтрий']) {
          await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
        }

        await page.goto('/#/business/schedule/exceptions/new')
        await expect(page.getByText('Нови промени не могат да бъдат добавяни.')).toBeVisible()
        await expect(page.getByRole('radio')).toHaveCount(0)
      })

      // Forced private API mutations are rejected while readable data is unchanged.
      const list = await apiRequest(
        ownerApi,
        'GET',
        `${CHANGES_PATH}?from=${today}&to=${addDays(today, 29)}`,
      )
      expect(list.status).toBe(200)
      expect(list.text).toContain(record.id)
      const suspendedMessage = 'Спрян бизнес може само да преглежда данните си.'
      const create = await apiRequest(ownerApi, 'POST', CHANGES_PATH, {
        kind: 'BUSINESS_CLOSURE',
        firstDate: addDays(today, 22),
        lastDate: addDays(today, 22),
        allDay: true,
        periods: [],
      })
      expect([create.status, create.code]).toEqual([409, 'BUSINESS_SUSPENDED'])
      expect(create.text).toContain(suspendedMessage)
      const replace = await apiRequest(ownerApi, 'PUT', `${CHANGES_PATH}/${record.id}`, {
        expectedVersion: record.version,
        firstDate: changeDate,
        lastDate: addDays(changeDate, 1),
        allDay: true,
        periods: [],
      })
      expect([replace.status, replace.code]).toEqual([409, 'BUSINESS_SUSPENDED'])
      const remove = await apiRequest(
        ownerApi,
        'DELETE',
        `${CHANGES_PATH}/${record.id}?expectedVersion=${record.version}`,
      )
      expect([remove.status, remove.code]).toEqual([409, 'BUSINESS_SUSPENDED'])
      const unchanged = await apiRequest(ownerApi, 'GET', `${CHANGES_PATH}/${record.id}`)
      expect(unchanged.status).toBe(200)
      expect(JSON.parse(unchanged.text)).toMatchObject({
        firstDate: changeDate,
        lastDate: changeDate,
        version: record.version,
      })
    } finally {
      if (suspended) {
        await admin.getByRole('button', { name: 'Активирай отново' }).click()
        await admin.getByRole('button', { name: 'Потвърди активирането' }).click()
        await expect(admin.getByRole('status')).toHaveText('Бизнесът е активиран отново.')
      }
    }

    // Editing is restored after reactivation.
    try {
      await withOwnerPage(async (page) => {
        await openList(page, today, addDays(today, 29))
        await expect(page.getByRole('status').filter({ hasText: SUSPENDED_BANNER })).toHaveCount(0)
        await expect(page.getByRole('button', { name: 'Добави промяна' })).toBeVisible()
        await recordRows(page).getByRole('link').click()
        await expect(page.getByRole('button', { name: 'Редактирай', exact: true })).toBeVisible()
        await expect(page.getByRole('button', { name: 'Изтрий', exact: true })).toBeVisible()
      })
    } finally {
      await tracker.cleanup(ownerApi)
    }
  })
})

test.describe('Schedule changes: authorization and tenant isolation', () => {
  let otherBusiness: BusinessFixture
  let otherOwner: OwnerSession
  let otherBusinessId: string

  test.beforeAll(async ({ browser }) => {
    test.setTimeout(150_000)
    otherBusiness = uniqueBusinessFixture(OTHER_BUSINESS_TEMPLATE)
    otherBusinessId = await createDraftBusiness(admin, otherBusiness)
    await inviteOwner(admin, otherBusinessId, otherBusiness.ownerEmail)
    otherOwner = await onboardOwner(browser, adminContext, otherBusiness)
  })

  test.afterAll(async () => {
    await otherOwner?.context.close()
  })

  test('another Business owner can neither read nor mutate the first Business changes', async () => {
    const today = businessToday()
    const changeDate = addDays(today, 15)
    try {
      const closure = await seedClosure(ownerApi, changeDate)
      tracker.track(closure.id)
      const path = `${CHANGES_PATH}/${closure.id}`

      const session = await publicSession(otherOwner.page)
      expect(session.body?.businesses[0]?.id).toBe(otherBusinessId)

      const list = await apiRequest(
        otherOwner.page,
        'GET',
        `${CHANGES_PATH}?from=${today}&to=${addDays(today, 29)}`,
      )
      expect(list.status).toBe(200)
      expect(JSON.parse(list.text)).toMatchObject({ exceptions: [] })
      expect(list.text).not.toContain(closure.id)

      const attempts = [
        await apiRequest(otherOwner.page, 'GET', path),
        await apiRequest(otherOwner.page, 'PUT', path, {
          expectedVersion: closure.version,
          firstDate: changeDate,
          lastDate: addDays(changeDate, 2),
          allDay: true,
          periods: [],
        }),
        await apiRequest(otherOwner.page, 'DELETE', `${path}?expectedVersion=${closure.version}`),
      ]
      for (const attempt of attempts) {
        expect([attempt.status, attempt.code]).toEqual([404, 'SCHEDULE_EXCEPTION_NOT_FOUND'])
        expect(attempt.text).not.toContain(shown(changeDate))
        expect(attempt.text).not.toContain(businessId)
      }
      const foreignStaff = await apiRequest(otherOwner.page, 'POST', CHANGES_PATH, {
        kind: 'STAFF_TIME_OFF',
        staffMemberId,
        firstDate: changeDate,
        lastDate: changeDate,
        allDay: true,
        periods: [],
      })
      expect([foreignStaff.status, foreignStaff.code]).toEqual([404, 'STAFF_MEMBER_NOT_FOUND'])
      expect(foreignStaff.text).not.toContain(STAFF_NAME)

      const page = await otherOwner.context.newPage()
      try {
        await page.goto(`/#/business/schedule/exceptions/${encodeURIComponent(closure.id)}`)
        await expect(page.getByRole('alert')).toContainText('не е намерена')
        await expect(page.getByText(shown(changeDate))).toHaveCount(0)
        await openList(page, today, addDays(today, 29))
        await expect(page.getByText('Няма промени за избрания период.')).toBeVisible()
      } finally {
        await page.close()
      }

      const untouched = await apiRequest(ownerApi, 'GET', path)
      expect(untouched.status).toBe(200)
      expect(JSON.parse(untouched.text)).toMatchObject({
        firstDate: changeDate,
        lastDate: changeDate,
        version: closure.version,
      })
    } finally {
      await tracker.cleanup(ownerApi)
    }
  })

  test('a platform administrator without a Membership has no Schedule Changes access', async () => {
    const today = businessToday()
    const page = await adminContext.newPage()
    try {
      await page.goto('/')
      await expect(page.getByRole('link', { name: 'Бизнеси' }).first()).toBeVisible()
      const session = await publicSession(page)
      expect(session.body?.platformAdmin).toBe(true)
      expect(session.body?.businesses).toHaveLength(0)

      const list = await apiRequest(
        page,
        'GET',
        `${CHANGES_PATH}?from=${today}&to=${addDays(today, 29)}`,
      )
      const create = await apiRequest(page, 'POST', CHANGES_PATH, {
        kind: 'BUSINESS_CLOSURE',
        firstDate: today,
        lastDate: today,
        allDay: true,
        periods: [],
      })
      expect([list.status, create.status]).toEqual([403, 403])

      await page.goto('/#/business/schedule/exceptions')
      await expect(page.getByRole('heading', { name: 'Работно време', level: 1 })).toHaveCount(0)
      await expect(page.getByRole('button', { name: 'Добави промяна' })).toHaveCount(0)
      const navigation = page.getByRole('navigation').filter({ visible: true })
      await expect(navigation.getByRole('link', { name: 'Работно време' })).toHaveCount(0)
    } finally {
      await page.close()
    }
  })
})

test.describe('Schedule changes: mobile smoke', () => {
  test('Pixel 7 shows the tabs, cards, sorting, pagination, create form and delete confirmation without overflow', async ({ browser }) => {
    test.setTimeout(150_000)
    const today = businessToday()
    const day = (offset: number) => addDays(today, offset)
    const context = await browser.newContext({ ...devices['Pixel 7'] })
    try {
      for (let offset = 1; offset <= 12; offset += 1) {
        tracker.track((await seedClosure(ownerApi, day(offset))).id)
      }

      const page = await context.newPage()
      await signIn(
        page,
        business.ownerEmail,
        requiredEnvironment('E2E_OWNER_PASSWORD'),
        SERVICES_HEADING,
      )
      await page.goto('/#/business/schedule')
      await expect(page.getByRole('heading', { name: 'Работно време', level: 1, exact: true }))
        .toBeVisible()
      const tabs = page.getByRole('navigation', { name: 'Работно време' })
      await expect(tabs.getByRole('link', { name: 'Седмични графици' })).toBeInViewport()
      await expect(tabs.getByRole('link', { name: 'Промени в графика' })).toBeInViewport()
      await expectNoHorizontalOverflow(page)

      await tabs.getByRole('link', { name: 'Промени в графика' }).click()
      await expect(pagination(page)).toContainText('Показани 1–10 от 12')
      const cards = page.getByRole('link', { name: /^Отвори: Неработно време, / })
      await expect(cards).toHaveCount(10)
      await expect(column(page, 'Дати').first()).toHaveText(shown(day(1)))
      await expectNoHorizontalOverflow(page)

      // The sorting control replaces the column headers on a narrow screen.
      const sorting = page.getByLabel('Подреди по')
      await expect(sorting).toBeVisible()
      await expectInsideViewport(page, sorting)
      await sorting.selectOption({ label: 'Дати (най-късни първо)' })
      await expect(column(page, 'Дати').first()).toHaveText(shown(day(12)))
      await expect.poll(() => hashQuery(page)).toBe(
        listQuery(today, day(29), { sort: 'dates', direction: 'desc' }),
      )
      await expectNoHorizontalOverflow(page)

      await expect(pagination(page)).toBeVisible()
      await expectInsideViewport(page, pagination(page))
      await pagination(page).getByRole('button', { name: 'Следваща' }).click()
      await expect(pagination(page)).toContainText('Показани 11–12 от 12')
      await expect(cards).toHaveCount(2)
      await pagination(page).getByRole('button', { name: 'Предишна' }).click()
      await expect(cards).toHaveCount(10)
      await expectNoHorizontalOverflow(page)

      // Create form: progressive fields fit the viewport.
      await page.getByRole('button', { name: 'Добави промяна' }).click()
      await expect(page.getByRole('heading', { name: 'Нова промяна в графика', level: 1 }))
        .toBeVisible()
      await expectNoHorizontalOverflow(page)
      await page.getByRole('radio', { name: 'Отсъствие' }).check()
      await page.getByRole('radio', { name: 'Част от деня' }).check()
      await expect(page.locator('#exception-first-date')).toBeVisible()
      await expectInsideViewport(page, page.locator('#exception-first-date'))
      await expectNoHorizontalOverflow(page)
      await page.getByRole('button', { name: 'Обратно към графика' }).click()
      const discard = page.getByRole('alertdialog', { name: 'Незапазени промени' })
      await expect(discard).toBeVisible()
      await expectInsideViewport(page, discard)
      await expectNoHorizontalOverflow(page)
      await discard.getByRole('button', { name: 'Напусни' }).click()
      await expect(cards.first()).toBeVisible()

      // Delete confirmation from the detail page; cancel keeps the record.
      await cards.first().click()
      await expect(page.getByRole('heading', { name: 'Промяна в графика', level: 1 })).toBeVisible()
      await page.getByRole('button', { name: 'Изтрий', exact: true }).click()
      const confirmation = page.getByRole('alertdialog', { name: 'Изтриване на промяна' })
      await expect(confirmation).toBeVisible()
      await expect(confirmation.getByRole('button', { name: 'Отказ' })).toBeFocused()
      await expectInsideViewport(page, confirmation)
      await expectNoHorizontalOverflow(page)
      await confirmation.getByRole('button', { name: 'Отказ' }).click()
      await expect(confirmation).toHaveCount(0)
    } finally {
      await context.close()
      await tracker.cleanup(ownerApi)
    }
  })
})
