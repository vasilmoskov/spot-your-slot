import { devices, expect, test, type BrowserContext, type Page } from '@playwright/test'
import {
  SERVICES_HEADING,
  expectNoHorizontalOverflow,
  signIn,
  apiRequest,
  expectBrowserStorageEmpty,
  hasSessionCookie,
  publicSession,
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

const BUSINESS_A_TEMPLATE: BusinessFixture = {
  name: 'Салон Зенит Конфигурация',
  slug: 'salon-zenit-config-e2e',
  ownerEmail: 'owner-zenit@example.invalid',
  ownerFirstName: 'Стоян',
  ownerLastName: 'Зенитов',
}

type ServiceValues = {
  name: string
  description: string
  durationMinutes: string
  price: string
}

const HAIRCUT: ServiceValues = {
  name: 'Дамско подстригване',
  description: 'Измиване, подстригване и оформяне.',
  durationMinutes: '45',
  price: '35.5',
}
const HAIRCUT_EDITED: ServiceValues = {
  name: 'Дамско подстригване и оформяне',
  description: 'Измиване, подстригване, оформяне и стилизиране.',
  durationMinutes: '60',
  price: '42',
}
const COLORING: ServiceValues = {
  name: 'Боядисване на коса',
  description: '',
  durationMinutes: '120',
  price: '90',
}
const ARCHIVED: ServiceValues = {
  name: 'Архивна услуга',
  description: '',
  durationMinutes: '30',
  price: '10',
}

const STAFF_NAME = 'Мария Петкова Тестова'
const STAFF_EMAIL = 'maria.testova@example.invalid'
const STAFF_PHONE = '+359 885 550 142'
const STAFF_EDITED_NAME = 'Мария Тестова'
const STAFF_EDITED_EMAIL = 'maria.t@example.invalid'

let adminContext: BrowserContext
let admin: Page
let owner: OwnerSession
let businessA: BusinessFixture
let businessId: string
let haircutServiceId: string
let staffMemberId: string

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000)
  businessA = uniqueBusinessFixture(BUSINESS_A_TEMPLATE)
  adminContext = await browser.newContext()
  admin = await adminContext.newPage()
  await signInAsPlatformAdmin(admin)
  businessId = await createDraftBusiness(admin, businessA)
  await inviteOwner(admin, businessId, businessA.ownerEmail)
  owner = await onboardOwner(browser, adminContext, businessA)
})

test.afterAll(async () => {
  await owner?.context.close()
  await adminContext?.close()
})

async function openSection(page: Page, name: 'Услуги' | 'Екип' | 'Работно време'): Promise<void> {
  await page
    .getByRole('navigation', { name: 'Навигация на бизнеса' })
    .getByRole('link', { name })
    .click()
  await expect(page.getByRole('heading', { name, level: 1, exact: true })).toBeVisible()
}

async function fillService(page: Page, values: ServiceValues): Promise<void> {
  await page.getByLabel('Име на услугата').fill(values.name)
  await page.getByLabel('Описание (по избор)').fill(values.description)
  await page.getByLabel('Продължителност (минути)').fill(values.durationMinutes)
  await page.getByLabel('Цена (EUR)').fill(values.price)
}

async function createService(page: Page, values: ServiceValues): Promise<void> {
  await openSection(page, 'Услуги')
  await page.getByRole('button', { name: 'Добави нова услуга' }).click()
  await expect(page.getByRole('heading', { name: 'Нова услуга', level: 1 })).toBeVisible()
  await fillService(page, values)
  await page.getByRole('button', { name: 'Създай услуга' }).click()
  await expect(page.getByRole('heading', { name: values.name, level: 2, exact: true }))
    .toBeVisible()
}

async function expectServiceDetails(
  page: Page,
  expected: { description: string, duration: string, price: string, status: string },
): Promise<void> {
  await expect(page.getByText(expected.status, { exact: true })).toBeVisible()
  await expect(page.getByText(expected.description, { exact: true })).toBeVisible()
  await expect(page.getByText(expected.duration, { exact: true })).toBeVisible()
  await expect(page.getByText(expected.price, { exact: true })).toBeVisible()
}

function idFromHash(page: Page): string {
  const id = new URL(page.url()).hash.split('/').pop()
  if (!id) throw new Error('The current route does not end in an entity id')
  return decodeURIComponent(id)
}

async function ownerPage(): Promise<Page> {
  return owner.context.newPage()
}

test.describe('Services and Staff configuration journey', () => {
  test.describe.configure({ mode: 'serial' })

  test('owner lands on Services for the sole Business without a manual selection', async () => {
    const { page, context } = owner
    await expect(page).toHaveURL(/#\/business\/services/)
    await expect(page.getByRole('heading', SERVICES_HEADING)).toBeVisible()
    await expect(page.getByText('Все още няма създадени услуги.')).toBeVisible()
    await expect(page.getByLabel('Избери бизнес')).toHaveCount(0)

    const navigation = page.getByRole('navigation', { name: 'Навигация на бизнеса' })
    for (const name of ['Услуги', 'Екип', 'Работно време', 'Профил']) {
      await expect(navigation.getByRole('link', { name })).toBeVisible()
    }
    await expect(
      page.getByText(businessA.name, { exact: true }).filter({ visible: true }),
    ).toBeVisible()

    const session = await publicSession(page)
    expect(session.status).toBe(200)
    expect(session.body?.platformAdmin).toBe(false)
    expect(session.body?.businesses).toHaveLength(1)
    expect(session.body?.businesses[0]).toMatchObject({
      id: businessId,
      displayName: businessA.name,
      role: 'BUSINESS_OWNER',
    })
    expect(session.body?.activeBusinessId).toBe(businessId)
    expect(await hasSessionCookie(context)).toBe(true)
    await expectBrowserStorageEmpty(page)
  })

  test('owner creates, edits, deactivates and reactivates a Service in a DRAFT Business', async () => {
    const { page } = owner
    await createService(page, HAIRCUT)
    haircutServiceId = idFromHash(page)
    await expectServiceDetails(page, {
      status: 'Активна',
      description: HAIRCUT.description,
      duration: '45 мин.',
      price: '35.50 €',
    })

    await page.getByRole('button', { name: 'Обратно към услугите' }).click()
    const row = page.getByRole('row').filter({ hasText: HAIRCUT.name })
    await expect(row).toContainText('45 мин.')
    await expect(row).toContainText('35.50 €')
    await expect(row).toContainText('Активна')

    await row.getByRole('link', { name: `Отвори ${HAIRCUT.name}` }).click()
    await page.getByRole('button', { name: 'Редактирай' }).click()
    await fillService(page, HAIRCUT_EDITED)
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Промените са запазени.' }),
    ).toBeVisible()
    await expect(page.getByRole('heading', { name: HAIRCUT_EDITED.name, level: 2, exact: true }))
      .toBeVisible()
    const edited = {
      description: HAIRCUT_EDITED.description,
      duration: '60 мин.',
      price: '42.00 €',
    }
    await expectServiceDetails(page, { ...edited, status: 'Активна' })

    await page.getByRole('button', { name: 'Деактивирай' }).click()
    await expect(page.getByRole('alertdialog')).toContainText('Потвърдете деактивирането')
    await page.getByRole('button', { name: 'Потвърди деактивирането' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Услугата е деактивирана.' }),
    ).toBeVisible()
    await expectServiceDetails(page, { ...edited, status: 'Неактивна' })

    await page.reload()
    await expect(page.getByRole('heading', { name: HAIRCUT_EDITED.name, level: 2, exact: true }))
      .toBeVisible()
    await expectServiceDetails(page, { ...edited, status: 'Неактивна' })

    await page.getByRole('button', { name: 'Активирай отново' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Услугата е активирана отново.' }),
    ).toBeVisible()
    await expectServiceDetails(page, { ...edited, status: 'Активна' })
  })

  test('owner prepares a second active Service and an inactive Service', async () => {
    const { page } = owner
    await createService(page, COLORING)
    await expect(page.getByText('Няма описание', { exact: true })).toBeVisible()
    await expectServiceDetails(page, {
      status: 'Активна',
      description: 'Няма описание',
      duration: '120 мин.',
      price: '90.00 €',
    })

    await createService(page, ARCHIVED)
    await page.getByRole('button', { name: 'Деактивирай' }).click()
    await page.getByRole('button', { name: 'Потвърди деактивирането' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Услугата е деактивирана.' }),
    ).toBeVisible()

    await openSection(page, 'Услуги')
    await expect(page.getByRole('row').filter({ hasText: ARCHIVED.name })).toContainText(
      'Неактивна',
    )
    await expect(page.getByRole('row').filter({ hasText: COLORING.name })).toContainText(
      'Активна',
    )
  })

  test('owner creates a StaffMember with validated optional contact details', async () => {
    const { page } = owner
    await openSection(page, 'Екип')
    await expect(page.getByText('Все още няма добавени членове на екипа.')).toBeVisible()

    await page.getByRole('button', { name: 'Добави нов член' }).click()
    await expect(page.getByRole('heading', { name: 'Нов член на екипа', level: 1 }))
      .toBeVisible()

    await page.getByLabel('Имейл за връзка (по избор)').fill('not-an-email')
    await page.getByLabel('Телефон за връзка (по избор)').fill('123')
    await page.getByRole('button', { name: 'Добави член на екипа' }).click()
    await expect(page.getByText('Въведете име на члена на екипа.')).toBeVisible()
    await expect(page.getByText('Въведете валиден имейл, например ime@primer.bg.')).toBeVisible()
    await expect(page.getByText(/^Въведете (валиден )?телефон/)).toBeVisible()
    await expect(page.getByLabel('Име на члена на екипа')).toBeFocused()
    await expect(page.getByLabel('Име на члена на екипа')).toHaveAttribute('aria-invalid', 'true')
    await expect(page.getByLabel('Имейл за връзка (по избор)')).toHaveValue('not-an-email')

    await page.getByLabel('Име на члена на екипа').fill(STAFF_NAME)
    await page.getByLabel('Имейл за връзка (по избор)').fill(STAFF_EMAIL)
    await page.getByLabel('Телефон за връзка (по избор)').fill(STAFF_PHONE)
    await page.getByRole('button', { name: 'Добави член на екипа' }).click()

    await expect(page.getByRole('heading', { name: STAFF_NAME, level: 2, exact: true }))
      .toBeVisible()
    await expect(page.getByText('Активен', { exact: true })).toBeVisible()
    await expect(page.getByText(STAFF_EMAIL, { exact: true })).toBeVisible()
    await expect(page.getByText(STAFF_PHONE, { exact: true })).toBeVisible()
    staffMemberId = idFromHash(page)

    await page.reload()
    await expect(page.getByRole('heading', { name: STAFF_NAME, level: 2, exact: true }))
      .toBeVisible()
    await expect(page.getByText(STAFF_PHONE, { exact: true })).toBeVisible()
  })

  test('owner edits the StaffMember and the list reflects the change', async () => {
    const { page } = owner
    await page.getByRole('button', { name: 'Редактирай', exact: true }).first().click()
    await page.getByLabel('Име на члена на екипа').fill(STAFF_EDITED_NAME)
    await page.getByLabel('Имейл за връзка (по избор)').fill(STAFF_EDITED_EMAIL)
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Промените са запазени.' }),
    ).toBeVisible()
    await expect(page.getByRole('heading', { name: STAFF_EDITED_NAME, level: 2, exact: true }))
      .toBeVisible()
    await expect(page.getByText(STAFF_EDITED_EMAIL, { exact: true })).toBeVisible()

    await page.getByRole('button', { name: 'Обратно към екипа' }).click()
    const row = page.getByRole('row').filter({ hasText: STAFF_EDITED_NAME })
    await expect(row).toContainText(STAFF_EDITED_EMAIL)
    await expect(row).toContainText(STAFF_PHONE)
    await expect(row).toContainText('Активен')
    await row.getByRole('link', { name: `Отвори ${STAFF_EDITED_NAME}` }).click()
    await expect(page.getByRole('heading', { name: STAFF_EDITED_NAME, level: 2, exact: true }))
      .toBeVisible()
  })

  test('owner assigns, removes and restores Service assignments', async () => {
    const { page } = owner
    const assignments = page.getByRole('table', { name: 'Услуги' })
    const assigned = (name: string) =>
      assignments.getByRole('row').filter({ hasText: name }).getByRole('cell', {
        name: 'Да',
        exact: true,
      })
    const unassigned = (name: string) =>
      assignments.getByRole('row').filter({ hasText: name }).getByRole('cell', {
        name: 'Не',
        exact: true,
      })

    await expect(unassigned(HAIRCUT_EDITED.name)).toBeVisible()
    await expect(unassigned(COLORING.name)).toBeVisible()
    await expect(assignments.getByRole('row').filter({ hasText: ARCHIVED.name })).toHaveCount(0)

    await page.getByRole('button', { name: 'Редактирай услугите' }).click()
    await expect(
      page.getByRole('table', { name: 'Назначаване на услуги' })
        .getByRole('row').filter({ hasText: ARCHIVED.name }),
    ).toHaveCount(0)
    await page.getByRole('checkbox', { name: HAIRCUT_EDITED.name }).check()
    await page.getByRole('checkbox', { name: COLORING.name }).check()
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Назначените услуги са запазени.' }),
    ).toBeVisible()
    await expect(assigned(HAIRCUT_EDITED.name)).toBeVisible()
    await expect(assigned(COLORING.name)).toBeVisible()

    await page.getByRole('button', { name: 'Редактирай услугите' }).click()
    await page.getByRole('checkbox', { name: COLORING.name }).uncheck()
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Назначените услуги са запазени.' }),
    ).toBeVisible()
    await expect(assigned(HAIRCUT_EDITED.name)).toBeVisible()
    await expect(unassigned(COLORING.name)).toBeVisible()

    await page.reload()
    await expect(assigned(HAIRCUT_EDITED.name)).toBeVisible()
    await expect(unassigned(COLORING.name)).toBeVisible()

    await page.getByRole('button', { name: 'Редактирай услугите' }).click()
    await page.getByRole('checkbox', { name: COLORING.name }).check()
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(assigned(COLORING.name)).toBeVisible()
    await expect(assigned(HAIRCUT_EDITED.name)).toBeVisible()
  })

  test('owner deactivates and reactivates the StaffMember with data preserved', async () => {
    const { page } = owner
    const assignments = page.getByRole('table', { name: 'Услуги' })

    await page.getByRole('button', { name: 'Деактивирай' }).click()
    await expect(page.getByRole('alertdialog')).toContainText('Потвърдете деактивирането')
    await page.getByRole('button', { name: 'Потвърди деактивирането' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Членът на екипа е деактивиран.' }),
    ).toBeVisible()
    await expect(page.getByText('Неактивен', { exact: true })).toBeVisible()
    await expect(page.getByText(STAFF_EDITED_EMAIL, { exact: true })).toBeVisible()
    await expect(page.getByText(STAFF_PHONE, { exact: true })).toBeVisible()
    await expect(
      assignments.getByRole('row').filter({ hasText: HAIRCUT_EDITED.name })
        .getByRole('cell', { name: 'Да', exact: true }),
    ).toBeVisible()

    await page.reload()
    await expect(page.getByText('Неактивен', { exact: true })).toBeVisible()

    await page.getByRole('button', { name: 'Активирай отново' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Членът на екипа е активиран отново.' }),
    ).toBeVisible()
    await expect(page.getByText('Активен', { exact: true })).toBeVisible()
    await expect(page.getByText(STAFF_EDITED_EMAIL, { exact: true })).toBeVisible()
    await expect(
      assignments.getByRole('row').filter({ hasText: COLORING.name })
        .getByRole('cell', { name: 'Да', exact: true }),
    ).toBeVisible()
  })
})

const SCHEDULE_DAYS = {
  monday: { label: 'Понеделник', sentence: 'понеделник' },
  tuesday: { label: 'Вторник', sentence: 'вторник' },
  wednesday: { label: 'Сряда', sentence: 'сряда' },
  thursday: { label: 'Четвъртък', sentence: 'четвъртък' },
  friday: { label: 'Петък', sentence: 'петък' },
  saturday: { label: 'Събота', sentence: 'събота' },
  sunday: { label: 'Неделя', sentence: 'неделя' },
} as const

type ScheduleDay = keyof typeof SCHEDULE_DAYS
type ExpectedSchedule = Record<ScheduleDay, string[]>

const INACTIVE_STAFF_NAME = 'Ян Неактивен'
const SUSPENDED_BANNER =
  'Бизнесът е временно спрян. Данните са видими, но конфигурацията не може да бъде променяна.'

function scheduleDay(page: Page, day: ScheduleDay) {
  return page.getByRole('region', { name: SCHEDULE_DAYS[day].label })
}

async function addSchedulePeriod(
  page: Page,
  day: ScheduleDay,
  start: string,
  end: string,
): Promise<void> {
  await scheduleDay(page, day).getByRole('button', { name: '+ Добави' }).click()
  const dialog = page.getByRole('dialog', {
    name: `Добавяне на работно време за ${SCHEDULE_DAYS[day].sentence}`,
  })
  await dialog.getByLabel('Начален час').fill(start)
  await dialog.getByLabel('Краен час').fill(end)
  await dialog.getByRole('button', { name: 'Добави', exact: true }).click()
  await expect(dialog).toHaveCount(0)
}

async function expectSchedule(page: Page, expected: ExpectedSchedule): Promise<void> {
  for (const day of Object.keys(SCHEDULE_DAYS) as ScheduleDay[]) {
    const region = scheduleDay(page, day)
    if (expected[day].length === 0) {
      await expect(region.getByText('Почивен ден')).toBeVisible()
    } else {
      await expect(region.getByText('Почивен ден')).toHaveCount(0)
    }
    await expect(region.getByText(/^\d\d:\d\d–\d\d:\d\d$/)).toHaveText(expected[day])
  }
}

async function openAdminBusiness(): Promise<void> {
  await admin.goto(`/#/platform/businesses/${encodeURIComponent(businessId)}`)
  await expect(admin.getByRole('heading', { name: businessA.name, exact: true })).toBeVisible()
  await admin.getByText('Активиране', { exact: true }).click()
}

async function expectSuspendedBanner(page: Page, visible: boolean): Promise<void> {
  const banner = page.getByRole('status').filter({ hasText: SUSPENDED_BANNER })
  if (visible) {
    await expect(banner).toBeVisible()
  } else {
    await expect(banner).toHaveCount(0)
  }
}

const CONFIGURED_SCHEDULE: ExpectedSchedule = {
  monday: ['09:00–13:00', '14:00–18:00'],
  tuesday: [],
  wednesday: ['10:00–16:00'],
  thursday: [],
  friday: ['09:00–12:30'],
  saturday: [],
  sunday: [],
}
const EDITED_SCHEDULE: ExpectedSchedule = {
  ...CONFIGURED_SCHEDULE,
  monday: ['09:00–13:00', '15:00–19:00'],
  friday: [],
}

test.describe('Working schedule, unsaved changes and lifecycle journey', () => {
  test.describe.configure({ mode: 'serial' })

  test('owner configures multiple weekdays and a split day in deterministic order', async () => {
    const { page } = owner
    await openSection(page, 'Работно време')
    await expect(page.getByLabel('Член на екипа')).toHaveValue(staffMemberId)
    await expect(page.getByText('Почивен ден')).toHaveCount(7)

    await page.getByRole('button', { name: 'Редактирай графика' }).click()
    // Entered out of order on purpose: the later Monday period first.
    await addSchedulePeriod(page, 'monday', '14:00', '18:00')
    await addSchedulePeriod(page, 'monday', '09:00', '13:00')
    await addSchedulePeriod(page, 'friday', '09:00', '12:30')
    await addSchedulePeriod(page, 'wednesday', '10:00', '16:00')

    await scheduleDay(page, 'monday').getByRole('button', { name: '+ Добави' }).click()
    const overlapDialog = page.getByRole('dialog', {
      name: 'Добавяне на работно време за понеделник',
    })
    await overlapDialog.getByLabel('Начален час').fill('12:00')
    await overlapDialog.getByLabel('Краен час').fill('15:00')
    await overlapDialog.getByRole('button', { name: 'Добави', exact: true }).click()
    await expect(overlapDialog.getByText('Периодът се припокрива с друг период за същия ден.'))
      .toBeVisible()
    await expect(overlapDialog.getByLabel('Начален час')).toHaveAttribute('aria-invalid', 'true')
    await overlapDialog.getByRole('button', { name: 'Отказ' }).click()
    await expect(overlapDialog).toHaveCount(0)

    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Работният график е запазен.' }),
    ).toBeVisible()
    await expectSchedule(page, CONFIGURED_SCHEDULE)
  })

  test('the schedule persists across route navigation and a full reload', async () => {
    const { page } = owner
    await openSection(page, 'Екип')
    await openSection(page, 'Работно време')
    await expect(page.getByLabel('Член на екипа')).toHaveValue(staffMemberId)
    await expectSchedule(page, CONFIGURED_SCHEDULE)

    await page.reload()
    await expect(page.getByRole('heading', { name: 'Работно време', level: 1 })).toBeVisible()
    await expect(page.getByLabel('Член на екипа')).toHaveValue(staffMemberId)
    await expectSchedule(page, CONFIGURED_SCHEDULE)
  })

  test('owner edits one period and removes another', async () => {
    const { page } = owner
    await page.getByRole('button', { name: 'Редактирай графика' }).click()
    await page
      .getByRole('button', { name: /Редактирай периода 14:00–18:00 за понеделник/ })
      .click()
    const dialog = page.getByRole('dialog', { name: 'Редактиране на работно време за понеделник' })
    await dialog.getByLabel('Начален час').fill('15:00')
    await dialog.getByLabel('Краен час').fill('19:00')
    await dialog.getByRole('button', { name: 'Запази', exact: true }).click()
    await expect(dialog).toHaveCount(0)
    await page.getByRole('button', { name: 'Премахни периода 09:00–12:30 за петък' }).click()
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Работният график е запазен.' }),
    ).toBeVisible()
    await expectSchedule(page, EDITED_SCHEDULE)

    await page.reload()
    await expect(page.getByLabel('Член на екипа')).toHaveValue(staffMemberId)
    await expectSchedule(page, EDITED_SCHEDULE)
  })

  test('an inactive StaffMember schedule is read-only and rejected by the backend', async () => {
    const { page } = owner
    await openSection(page, 'Екип')
    await page.getByRole('button', { name: 'Добави нов член' }).click()
    await page.getByLabel('Име на члена на екипа').fill(INACTIVE_STAFF_NAME)
    await page.getByRole('button', { name: 'Добави член на екипа' }).click()
    await expect(page.getByRole('heading', { name: INACTIVE_STAFF_NAME, level: 2, exact: true }))
      .toBeVisible()
    const inactiveStaffId = idFromHash(page)
    await page.getByRole('button', { name: 'Деактивирай' }).click()
    await page.getByRole('button', { name: 'Потвърди деактивирането' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Членът на екипа е деактивиран.' }),
    ).toBeVisible()

    await openSection(page, 'Работно време')
    await page.getByLabel('Член на екипа').selectOption({
      label: `${INACTIVE_STAFF_NAME} (неактивен)`,
    })
    await expect(
      page.getByText('Работният график на неактивен член на екипа може само да бъде преглеждан.'),
    ).toBeVisible()
    await expect(page.getByRole('button', { name: 'Редактирай графика' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Изчисти графика' })).toHaveCount(0)
    await expect(page.getByText('Почивен ден')).toHaveCount(7)

    const schedulePath = `/api/business/staff-members/${encodeURIComponent(inactiveStaffId)}/working-schedule`
    const current = await apiRequest(page, 'GET', schedulePath)
    expect(current.status).toBe(200)
    const { version } = JSON.parse(current.text) as { version: number }
    const rejected = await apiRequest(page, 'PUT', schedulePath, {
      expectedVersion: version,
      periods: [{ weekday: 'MONDAY', startTime: '09:00', endTime: '10:00' }],
    })
    expect(rejected.status).toBe(409)
    expect(rejected.code).toBe('STAFF_MEMBER_INACTIVE')
    expect(rejected.text).toContain('Неактивен член на екипа не може да получи работен график.')
    const unchanged = await apiRequest(page, 'GET', schedulePath)
    expect((JSON.parse(unchanged.text) as { periods: unknown[] }).periods).toHaveLength(0)
  })

  test('the shared unsaved-changes dialog protects a dirty Service form', async () => {
    const { page } = owner
    await openSection(page, 'Услуги')
    await page.getByRole('link', { name: `Отвори ${COLORING.name}` }).click()
    await page.getByRole('button', { name: 'Редактирай' }).click()
    await page.getByLabel('Описание (по избор)').fill('Незапазено описание')

    const navigation = page.getByRole('navigation', { name: 'Навигация на бизнеса' })
    await navigation.getByRole('link', { name: 'Екип' }).click()
    const dialog = page.getByRole('alertdialog', { name: 'Незапазени промени' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'Продължи редактирането' })).toBeFocused()

    await dialog.getByRole('button', { name: 'Продължи редактирането' }).click()
    await expect(dialog).toHaveCount(0)
    await expect(page.getByLabel('Описание (по избор)')).toHaveValue('Незапазено описание')
    await expect(page).toHaveURL(new RegExp('#/business/services/'))

    await navigation.getByRole('link', { name: 'Екип' }).click()
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: 'Откажи промените' }).click()
    await expect(page.getByRole('heading', { name: 'Екип', level: 1, exact: true })).toBeVisible()
    await expect(page).toHaveURL(/#\/business\/staff/)

    await openSection(page, 'Услуги')
    await page.getByRole('link', { name: `Отвори ${COLORING.name}` }).click()
    await expect(page.getByText('Няма описание', { exact: true })).toBeVisible()
  })

  test('activating the Business keeps configuration editable', async () => {
    const { page } = owner
    await openAdminBusiness()
    await admin.getByRole('button', { name: 'Активирай' }).click()
    await admin.getByRole('button', { name: 'Потвърди активирането' }).click()
    await expect(admin.getByRole('status')).toHaveText('Бизнесът е активиран.')

    await page.reload()
    await openSection(page, 'Услуги')
    await expectSuspendedBanner(page, false)
    await page.getByRole('link', { name: `Отвори ${COLORING.name}` }).click()
    await page.getByRole('button', { name: 'Редактирай' }).click()
    await page.getByLabel('Описание (по избор)').fill('Обновено след активиране.')
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Промените са запазени.' }),
    ).toBeVisible()
    await expect(page.getByText('Обновено след активиране.', { exact: true })).toBeVisible()
  })

  test('a SUSPENDED Business is readable but rejects every configuration mutation', async () => {
    const { page } = owner
    await admin.getByRole('button', { name: 'Спри временно' }).click()
    await admin.getByRole('button', { name: 'Потвърди спирането' }).click()
    await expect(admin.getByRole('status')).toHaveText('Бизнесът е временно спрян.')

    await page.reload()
    await openSection(page, 'Услуги')
    await expectSuspendedBanner(page, true)
    await expect(page.getByRole('button', { name: 'Добави нова услуга' })).toHaveCount(0)
    await expect(page.getByRole('row').filter({ hasText: HAIRCUT_EDITED.name })).toBeVisible()
    await expect(page.getByRole('row').filter({ hasText: COLORING.name })).toBeVisible()

    await page.getByRole('link', { name: `Отвори ${COLORING.name}` }).click()
    await expectSuspendedBanner(page, true)
    await expect(page.getByText('Обновено след активиране.', { exact: true })).toBeVisible()
    for (const name of ['Редактирай', 'Деактивирай', 'Активирай отново']) {
      await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
    }

    await page.goto('/#/business/services/new')
    await expect(
      page.getByText('Бизнесът е временно спрян — нови услуги не могат да бъдат създавани.'),
    ).toBeVisible()
    await expect(page.getByRole('button', { name: 'Създай услуга' })).toHaveCount(0)

    await openSection(page, 'Екип')
    await expect(page.getByRole('button', { name: 'Добави нов член' })).toHaveCount(0)
    await page.getByRole('link', { name: `Отвори ${STAFF_EDITED_NAME}` }).click()
    await expect(page.getByText(STAFF_EDITED_EMAIL, { exact: true })).toBeVisible()
    for (const name of ['Редактирай', 'Деактивирай', 'Редактирай услугите']) {
      await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
    }
    await expect(
      page.getByRole('table', { name: 'Услуги' }).getByRole('row')
        .filter({ hasText: HAIRCUT_EDITED.name }).getByRole('cell', { name: 'Да', exact: true }),
    ).toBeVisible()

    await openSection(page, 'Работно време')
    await expectSuspendedBanner(page, true)
    await expectSchedule(page, EDITED_SCHEDULE)
    await expect(page.getByRole('button', { name: 'Редактирай графика' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Изчисти графика' })).toHaveCount(0)

    const suspended = 'Спрян бизнес може само да преглежда данните си.'
    const create = await apiRequest(page, 'POST', '/api/business/services', {
      name: 'Забранена услуга',
      durationMinutes: 10,
      price: '1.00',
    })
    expect([create.status, create.code]).toEqual([409, 'BUSINESS_SUSPENDED'])
    expect(create.text).toContain(suspended)
    const deactivate = await apiRequest(
      page,
      'POST',
      `/api/business/services/${encodeURIComponent(haircutServiceId)}/deactivate`,
      { expectedVersion: 0 },
    )
    expect([deactivate.status, deactivate.code]).toEqual([409, 'BUSINESS_SUSPENDED'])
    const schedulePath = `/api/business/staff-members/${encodeURIComponent(staffMemberId)}/working-schedule`
    const currentSchedule = await apiRequest(page, 'GET', schedulePath)
    expect(currentSchedule.status).toBe(200)
    const { version } = JSON.parse(currentSchedule.text) as { version: number }
    const replace = await apiRequest(page, 'PUT', schedulePath, {
      expectedVersion: version,
      periods: [],
    })
    expect([replace.status, replace.code]).toEqual([409, 'BUSINESS_SUSPENDED'])

    await expectSchedule(page, EDITED_SCHEDULE)
    await openSection(page, 'Услуги')
    await expect(page.getByRole('row').filter({ hasText: 'Забранена услуга' })).toHaveCount(0)
    await expect(page.getByRole('row').filter({ hasText: HAIRCUT_EDITED.name }))
      .toContainText('Активна')

    const navigation = page.getByRole('navigation', { name: 'Навигация на бизнеса' })
    await navigation.getByRole('link', { name: 'Профил' }).click()
    await expect(page.getByRole('region', { name: 'Настройки на профила' })).toBeVisible()
    await page.getByRole('button', { name: 'Смяна на парола' }).click()
    await expect(page.getByRole('heading', { name: 'Смяна на парола', level: 2 })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Изход' }).filter({ visible: true }))
      .toBeEnabled()
  })

  test('reactivating the Business restores editing and preserves all data', async () => {
    const { page } = owner
    await admin.getByRole('button', { name: 'Активирай отново' }).click()
    await admin.getByRole('button', { name: 'Потвърди активирането' }).click()
    await expect(admin.getByRole('status')).toHaveText('Бизнесът е активиран отново.')

    await page.goto('/#/business/services')
    await page.reload()
    await expect(page.getByRole('heading', SERVICES_HEADING)).toBeVisible()
    await expectSuspendedBanner(page, false)
    await expect(page.getByRole('button', { name: 'Добави нова услуга' })).toBeVisible()
    await expect(page.getByRole('row').filter({ hasText: HAIRCUT_EDITED.name }))
      .toContainText('60 мин.')
    await expect(page.getByRole('row').filter({ hasText: ARCHIVED.name }))
      .toContainText('Неактивна')

    await page.getByRole('link', { name: `Отвори ${COLORING.name}` }).click()
    await page.getByRole('button', { name: 'Редактирай' }).click()
    await page.getByLabel('Описание (по избор)').fill('Възстановено след повторно активиране.')
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Промените са запазени.' }),
    ).toBeVisible()

    await openSection(page, 'Екип')
    await page.getByRole('link', { name: `Отвори ${STAFF_EDITED_NAME}` }).click()
    await expect(page.getByRole('button', { name: 'Редактирай услугите' })).toBeVisible()
    await expect(
      page.getByRole('table', { name: 'Услуги' }).getByRole('row')
        .filter({ hasText: COLORING.name }).getByRole('cell', { name: 'Да', exact: true }),
    ).toBeVisible()

    await openSection(page, 'Работно време')
    await expectSchedule(page, EDITED_SCHEDULE)
    await expect(page.getByRole('button', { name: 'Редактирай графика' })).toBeVisible()
  })
})

test.describe('Focused Service negative coverage', () => {
  test('blank and out-of-range Service input shows local Bulgarian feedback', async () => {
    const page = await ownerPage()
    try {
      await page.goto('/#/business/services/new')
      await expect(page.getByRole('heading', { name: 'Нова услуга', level: 1 })).toBeVisible()
      await page.getByRole('button', { name: 'Създай услуга' }).click()
      await expect(page.getByText('Въведете име на услугата.')).toBeVisible()
      await expect(page.getByText('Въведете продължителност в минути.')).toBeVisible()
      await expect(page.getByText('Въведете цена.')).toBeVisible()
      await expect(page.getByLabel('Име на услугата')).toBeFocused()

      await page.getByLabel('Име на услугата').fill('Невалидна услуга')
      await page.getByLabel('Продължителност (минути)').fill('481')
      await page.getByLabel('Цена (EUR)').fill('-1')
      await page.getByRole('button', { name: 'Създай услуга' }).click()
      await expect(page.getByText('Продължителността трябва да бъде между 1 и 480 минути.'))
        .toBeVisible()
      await expect(page.getByText('Цената не може да бъде отрицателна.')).toBeVisible()
      await expect(page.getByLabel('Име на услугата')).toHaveValue('Невалидна услуга')
      await expect(page.getByRole('heading', { name: 'Нова услуга', level: 1 })).toBeVisible()
    } finally {
      await page.close()
    }
  })

  test('a duplicate active Service name is rejected safely by the backend', async () => {
    const page = await ownerPage()
    const duplicate: ServiceValues = {
      name: 'Дублирана услуга',
      description: '',
      durationMinutes: '20',
      price: '15',
    }
    try {
      await page.goto('/#/business/services/new')
      await fillService(page, duplicate)
      await page.getByRole('button', { name: 'Създай услуга' }).click()
      await expect(page.getByRole('heading', { name: duplicate.name, level: 2, exact: true }))
        .toBeVisible()

      await page.goto('/#/business/services/new')
      await expect(page.getByRole('heading', { name: 'Нова услуга', level: 1 })).toBeVisible()
      await fillService(page, duplicate)
      await page.getByRole('button', { name: 'Създай услуга' }).click()
      await expect(page.getByRole('alert')).toContainText('Вече съществува услуга с това име.')
      await expect(page.getByLabel('Име на услугата')).toHaveValue(duplicate.name)
      await expectBrowserStorageEmpty(page)
    } finally {
      await page.close()
    }
  })

  test('the owner session stays independent of the administrator context', async () => {
    expect(await hasSessionCookie(adminContext)).toBe(true)
    expect(await hasSessionCookie(owner.context)).toBe(true)
    const adminSession = await publicSession(admin)
    const ownerSession = await publicSession(owner.page)
    expect(adminSession.body?.platformAdmin).toBe(true)
    expect(ownerSession.body?.platformAdmin).toBe(false)
    expect(adminSession.body?.email).not.toBe(ownerSession.body?.email)
    await expect(admin.getByRole('link', { name: 'Услуги' })).toHaveCount(0)
  })
})

const BUSINESS_B_TEMPLATE: BusinessFixture = {
  name: 'Студио Хоризонт Конфигурация',
  slug: 'studio-horizont-config-e2e',
  ownerEmail: 'owner-horizont@example.invalid',
  ownerFirstName: 'Радка',
  ownerLastName: 'Хоризонтова',
}

async function sessionCookieValue(context: BrowserContext): Promise<string> {
  const cookie = (await context.cookies()).find((candidate) => candidate.name === 'SPOTYOURSESSION')
  if (!cookie) throw new Error('The context has no session cookie')
  return cookie.value
}

test.describe('Business isolation and browser-context separation', () => {
  let businessB: BusinessFixture
  let ownerB: OwnerSession
  let businessBId: string

  test.beforeAll(async ({ browser }) => {
    test.setTimeout(120_000)
    businessB = uniqueBusinessFixture(BUSINESS_B_TEMPLATE)
    businessBId = await createDraftBusiness(admin, businessB)
    await inviteOwner(admin, businessBId, businessB.ownerEmail)
    ownerB = await onboardOwner(browser, adminContext, businessB)
  })

  test.afterAll(async () => {
    await ownerB?.context.close()
  })

  test('owner B receives only Business B and none of the Business A records', async () => {
    const { page } = ownerB
    const session = await publicSession(page)
    expect(session.body?.platformAdmin).toBe(false)
    expect(session.body?.businesses).toHaveLength(1)
    expect(session.body?.businesses[0]).toMatchObject({
      id: businessBId,
      displayName: businessB.name,
      role: 'BUSINESS_OWNER',
    })
    expect(session.body?.activeBusinessId).toBe(businessBId)
    await expect(page.getByRole('heading', SERVICES_HEADING)).toBeVisible()
    await expect(page.getByText('Все още няма създадени услуги.')).toBeVisible()
    await expect(page.getByText(HAIRCUT_EDITED.name)).toHaveCount(0)

    await openSection(page, 'Екип')
    await expect(page.getByText('Все още няма добавени членове на екипа.')).toBeVisible()
    await expect(page.getByText(STAFF_EDITED_NAME)).toHaveCount(0)
  })

  test('owner B cannot read or mutate Business A entities by identifier', async () => {
    const { page } = ownerB
    await page.goto(`/#/business/services/${encodeURIComponent(haircutServiceId)}`)
    await expect(page.getByText('Услугата не е намерена.').first()).toBeVisible()
    await expect(page.getByText(HAIRCUT_EDITED.name)).toHaveCount(0)
    await page.goto(`/#/business/staff/${encodeURIComponent(staffMemberId)}`)
    await expect(page.getByText('Членът на екипа не е намерен.').first()).toBeVisible()
    await expect(page.getByText(STAFF_EDITED_NAME)).toHaveCount(0)

    const servicePath = `/api/business/services/${encodeURIComponent(haircutServiceId)}`
    const staffPath = `/api/business/staff-members/${encodeURIComponent(staffMemberId)}`
    const attempts = [
      { result: await apiRequest(page, 'GET', servicePath), code: 'SERVICE_NOT_FOUND' },
      {
        result: await apiRequest(page, 'POST', `${servicePath}/deactivate`, { expectedVersion: 0 }),
        code: 'SERVICE_NOT_FOUND',
      },
      { result: await apiRequest(page, 'GET', staffPath), code: 'STAFF_MEMBER_NOT_FOUND' },
      {
        result: await apiRequest(page, 'GET', `${staffPath}/working-schedule`),
        code: 'STAFF_MEMBER_NOT_FOUND',
      },
      {
        result: await apiRequest(page, 'PUT', `${staffPath}/working-schedule`, {
          expectedVersion: 0,
          periods: [],
        }),
        code: 'STAFF_MEMBER_NOT_FOUND',
      },
    ]
    for (const { result, code } of attempts) {
      expect([result.status, result.code]).toEqual([404, code])
      expect(result.text).not.toContain(HAIRCUT_EDITED.name)
      expect(result.text).not.toContain(STAFF_EDITED_NAME)
    }

    // Business A is unchanged after the rejected attempts.
    const ownerA = owner.page
    await openSection(ownerA, 'Услуги')
    await expect(ownerA.getByRole('row').filter({ hasText: HAIRCUT_EDITED.name }))
      .toContainText('Активна')
    await openSection(ownerA, 'Работно време')
    await expect(ownerA.getByLabel('Член на екипа')).toHaveValue(staffMemberId)
    await expectSchedule(ownerA, EDITED_SCHEDULE)
  })

  test('a platform administrator without a Membership cannot use private configuration', async () => {
    const session = await publicSession(admin)
    expect(session.body?.platformAdmin).toBe(true)
    expect(session.body?.businesses).toHaveLength(0)

    const staffPath = `/api/business/staff-members/${encodeURIComponent(staffMemberId)}`
    const attempts = [
      await apiRequest(admin, 'GET', '/api/business/services'),
      await apiRequest(admin, 'GET', '/api/business/staff-members'),
      await apiRequest(admin, 'GET', `${staffPath}/working-schedule`),
      await apiRequest(admin, 'POST', '/api/business/services', {
        name: 'Административна услуга',
        durationMinutes: 10,
        price: '1.00',
      }),
    ]
    for (const attempt of attempts) {
      expect(attempt.status).toBe(403)
      expect(attempt.text).not.toContain(HAIRCUT_EDITED.name)
      expect(attempt.text).not.toContain(STAFF_EDITED_NAME)
    }

    const navigation = admin.getByRole('navigation').filter({ visible: true })
    await expect(navigation.getByRole('link', { name: 'Бизнеси' })).toBeVisible()
    for (const name of ['Услуги', 'Екип', 'Работно време']) {
      await expect(navigation.getByRole('link', { name })).toHaveCount(0)
    }
    await admin.goto('/#/business/services')
    await expect(admin.getByRole('link', { name: 'Бизнеси' }).first()).toBeVisible()
    await expect(admin.getByRole('heading', SERVICES_HEADING)).toHaveCount(0)
  })

  test('the owner shell exposes no platform-administrator controls', async () => {
    const { page } = owner
    await openSection(page, 'Услуги')
    const navigation = page.getByRole('navigation', { name: 'Навигация на бизнеса' })
    await expect(navigation.getByRole('link')).toHaveText([
      'Услуги',
      'Екип',
      'Работно време',
      'Профил',
    ])
    await expect(page.getByRole('link', { name: 'Бизнеси' })).toHaveCount(0)
    await page.goto('/#/platform/businesses')
    await expect(page.getByRole('heading', { name: 'Бизнеси', level: 1 })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Нов бизнес' })).toHaveCount(0)

    const platform = await apiRequest(page, 'GET', '/api/platform/businesses')
    expect(platform.status).toBe(403)
    expect(platform.text).not.toContain(businessB.name)
  })

  test('browser storage holds no authentication or Business context and cookies stay isolated', async () => {
    for (const page of [admin, owner.page, ownerB.page]) {
      await expectBrowserStorageEmpty(page)
    }
    const sessions = [
      await sessionCookieValue(adminContext),
      await sessionCookieValue(owner.context),
      await sessionCookieValue(ownerB.context),
    ]
    expect(new Set(sessions).size).toBe(3)
    const ownerASession = await publicSession(owner.page)
    const ownerBSession = await publicSession(ownerB.page)
    expect(ownerASession.body?.activeBusinessId).toBe(businessId)
    expect(ownerBSession.body?.activeBusinessId).toBe(businessBId)
  })
})

test.describe('Business-owner mobile smoke', () => {
  test('Pixel 7 owner reaches every destination by keyboard without horizontal overflow', async ({ browser }) => {
    test.setTimeout(90_000)
    const context = await browser.newContext({ ...devices['Pixel 7'] })
    try {
      const page = await context.newPage()
      await signIn(
        page,
        businessA.ownerEmail,
        requiredEnvironment('E2E_OWNER_PASSWORD'),
        SERVICES_HEADING,
      )
      await expectNoHorizontalOverflow(page)

      const menu = page.getByRole('button', { name: /навигацията/ })
      const navigation = page.getByRole('navigation', { name: 'Навигация на бизнеса' })
      await menu.focus()
      await menu.press('Enter')
      await expect(menu).toHaveAttribute('aria-expanded', 'true')
      await expect(navigation.getByRole('link', { name: 'Услуги' })).toBeFocused()
      await expect(
        navigation.getByText(businessA.name, { exact: true }).filter({ visible: true }),
      ).toBeVisible()
      await navigation.getByRole('link', { name: 'Услуги' }).press('Escape')
      await expect(menu).toHaveAttribute('aria-expanded', 'false')
      await expect(menu).toBeFocused()

      await expect(page.getByRole('row').filter({ hasText: HAIRCUT_EDITED.name }))
        .toContainText('60 мин.')
      await expect(page.getByRole('row').filter({ hasText: HAIRCUT_EDITED.name }))
        .toContainText('42.00 €')
      await expectNoHorizontalOverflow(page)

      await menu.press('Enter')
      const staffLink = navigation.getByRole('link', { name: 'Екип' })
      await staffLink.focus()
      await staffLink.press('Enter')
      await expect(page.getByRole('heading', { name: 'Екип', level: 1, exact: true }))
        .toBeVisible()
      const staffRow = page.getByRole('row').filter({ hasText: STAFF_EDITED_NAME })
      await expect(staffRow).toContainText(STAFF_EDITED_EMAIL)
      await expect(staffRow).toContainText(STAFF_PHONE)
      await expectNoHorizontalOverflow(page)

      await menu.press('Enter')
      const scheduleLink = navigation.getByRole('link', { name: 'Работно време' })
      await scheduleLink.focus()
      await scheduleLink.press('Enter')
      await expect(page.getByRole('heading', { name: 'Работно време', level: 1 })).toBeVisible()
      await expect(page.getByLabel('Член на екипа')).toHaveValue(staffMemberId)
      await expectSchedule(page, EDITED_SCHEDULE)
      await expect(
        scheduleDay(page, 'monday').getByText('15:00–19:00'),
      ).toBeInViewport()
      await expectNoHorizontalOverflow(page)

      try {
        await openAdminBusiness()
        await admin.getByRole('button', { name: 'Спри временно' }).click()
        await admin.getByRole('button', { name: 'Потвърди спирането' }).click()
        await expect(admin.getByRole('status')).toHaveText('Бизнесът е временно спрян.')

        await page.reload()
        await expect(page.getByRole('heading', { name: 'Работно време', level: 1 })).toBeVisible()
        await expectSuspendedBanner(page, true)
        await expect(page.getByRole('button', { name: 'Редактирай графика' })).toHaveCount(0)
        await expectSchedule(page, EDITED_SCHEDULE)
        await expectNoHorizontalOverflow(page)
      } finally {
        await admin.getByRole('button', { name: 'Активирай отново' }).click()
        await admin.getByRole('button', { name: 'Потвърди активирането' }).click()
        await expect(admin.getByRole('status')).toHaveText('Бизнесът е активиран отново.')
      }
    } finally {
      await context.close()
    }
  })
})
