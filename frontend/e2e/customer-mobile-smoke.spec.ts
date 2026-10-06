import { devices, expect, test, type BrowserContext, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow } from './support/browser'
import {
  CUSTOMER_SUSPENDED_NOTICE,
  addBusinessForOwner,
  businessCard,
  customerRows,
  expectedOrder,
  openCustomers,
  pagination,
  phoneFor,
  provisionOwnerBusiness,
  searchBox,
  seedCustomer,
  seedCustomers,
  selectBusinessViaApi,
  shortTag,
  signInOwner,
  visibleNames,
  type CustomerRecord,
} from './support/customers'
import { signInAsPlatformAdmin } from './support/provisioning'
import { transitionBusiness } from './support/publicProfile'

const TAG = shortTag()
const ACTIVE_NAME = `Мобилен Клиенти ${TAG}`
const SUSPENDED_NAME = `Мобилен Спрян ${TAG}`
const LONG_NAME = `${'Длъжиниславов'.repeat(9)} Клиентова ${TAG}`
const LONG_EMAIL = `${'d'.repeat(58)}@example.test`

let adminContext: BrowserContext
let mobileContext: BrowserContext
let page: Page
let catalog: CustomerRecord[] = []
let suspendedCustomer: CustomerRecord

test.beforeAll(async ({ browser }) => {
  test.setTimeout(240_000)
  adminContext = await browser.newContext()
  const admin = await adminContext.newPage()
  await signInAsPlatformAdmin(admin)

  const active = await provisionOwnerBusiness(browser, adminContext, admin, {
    name: ACTIVE_NAME,
    slugBase: 'customers-mobile-e2e',
    businessType: 'HAIR_SALON',
    finalState: 'ACTIVE',
  })
  const suspendedId = await addBusinessForOwner(browser, adminContext, admin, active.ownerEmail, {
    name: SUSPENDED_NAME,
    slugBase: 'customers-mobile-suspended-e2e',
    businessType: 'HAIR_SALON',
    finalState: 'ACTIVE',
  })

  const seeding = await signInOwner(browser, active.ownerEmail, { name: 'Бизнеси', level: 1 })
  try {
    await selectBusinessViaApi(seeding.page, active.id)
    catalog = await seedCustomers(seeding.page, [
      ...Array.from({ length: 12 }, (_, index) => ({
        displayName: `Мобилен ${String(index).padStart(2, '0')} Тестов`,
        phone: phoneFor(500 + index),
        email: `mobile.${String(index).padStart(2, '0')}.${TAG}@example.test`,
      })),
      { displayName: LONG_NAME, email: LONG_EMAIL },
    ])
    await selectBusinessViaApi(seeding.page, suspendedId)
    suspendedCustomer = await seedCustomer(seeding.page, {
      displayName: `Спрян Мобилен ${TAG}`,
      phone: phoneFor(520),
    })
  } finally {
    await seeding.context.close()
  }
  await transitionBusiness(admin, suspendedId, 'suspend')

  const mobile = await signInOwner(browser, active.ownerEmail, { name: 'Бизнеси', level: 1 }, {
    ...devices['Pixel 7'],
  })
  mobileContext = mobile.context
  page = mobile.page
})

test.afterAll(async () => {
  await mobileContext?.close()
  await adminContext?.close()
})

const menuButton = () => page.getByRole('button', { name: /навигацията/ })

async function openMenu(): Promise<void> {
  if ((await menuButton().getAttribute('aria-expanded')) !== 'true') await menuButton().click()
  await expect(menuButton()).toHaveAttribute('aria-expanded', 'true')
}

async function menuLink(name: string): Promise<void> {
  await openMenu()
  await page.getByRole('navigation', { name: /навигация/i }).getByRole('link', { name, exact: true }).click()
}

async function showOnMobile(name: string): Promise<void> {
  await menuLink('Бизнеси')
  await expect(page.getByRole('heading', { name: 'Бизнеси', level: 1, exact: true })).toBeVisible()
  await expectNoHorizontalOverflow(page)
  await businessCard(page, name).getByRole('button', { name: 'Покажи', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Услуги', level: 1, exact: true })).toBeVisible()
}

test.describe('Customer administration on Pixel 7', () => {
  test.describe.configure({ mode: 'serial' })

  test('Business selection, scoped navigation, cards, sorting, pagination and live search', async () => {
    expect(await page.evaluate(() => navigator.maxTouchPoints)).toBeGreaterThan(0)
    await expect(page.getByRole('heading', { name: 'Бизнеси', level: 1, exact: true })).toBeVisible()
    await expect(page.locator('article.business-choice')).toHaveCount(2)
    await expectNoHorizontalOverflow(page)
    await businessCard(page, ACTIVE_NAME).getByRole('button', { name: 'Покажи', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Услуги', level: 1, exact: true })).toBeVisible()

    await openMenu()
    const group = page.getByRole('navigation', { name: /навигация/i }).getByRole('group', { name: ACTIVE_NAME })
    await expect(group).toBeVisible()
    await group.getByRole('link', { name: 'Клиенти', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Клиенти', level: 1, exact: true })).toBeVisible()
    await expect(menuButton()).toHaveAttribute('aria-expanded', 'false')
    await expect(customerRows(page)).toHaveCount(10)
    await expectNoHorizontalOverflow(page)

    // The table presents as cards: the sort select replaces the column headers.
    const sort = page.getByLabel('Подреди по')
    await expect(sort).toBeVisible()
    await sort.selectOption('name:desc')
    const descending = expectedOrder(catalog, 'name', 'desc')
    await expect.poll(() => visibleNames(page)).toEqual(descending.slice(0, 10).map((c) => c.displayName))
    await sort.selectOption('email:asc')
    await expect.poll(() => visibleNames(page)).toEqual(
      expectedOrder(catalog, 'email', 'asc').slice(0, 10).map((c) => c.displayName),
    )
    await expectNoHorizontalOverflow(page)

    await pagination(page).getByRole('button', { name: 'Следваща' }).click()
    await expect(pagination(page).getByText('Показани 11–13 от 13')).toBeVisible()
    await expect(pagination(page).getByRole('button', { name: 'Следваща' })).toBeDisabled()
    await expectNoHorizontalOverflow(page)

    await searchBox(page).fill('Мобилен 07')
    await expect.poll(() => visibleNames(page)).toEqual(['Мобилен 07 Тестов'])
    await searchBox(page).fill(`${'Длъжиниславов'.repeat(2)}`)
    await expect.poll(() => visibleNames(page)).toEqual([LONG_NAME])
    await expectNoHorizontalOverflow(page)
    await searchBox(page).fill('')
    await expect(pagination(page).getByText('от 13')).toBeVisible()
  })

  test('create and edit forms, long values, and the unsaved-changes dialog with focus return', async () => {
    await openCustomers(page)
    await page.getByRole('button', { name: 'Добави клиент' }).click()
    await expect(page.getByRole('heading', { name: 'Нов клиент', level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)

    // Validation errors wrap without overflow.
    await page.getByRole('button', { name: 'Добави', exact: true }).click()
    await expect(page.getByText('Въведете телефон или имейл.')).toBeVisible()
    await expectNoHorizontalOverflow(page)

    await page.getByLabel('Име', { exact: true }).fill(`Мобилна чернова ${TAG}`)
    await menuLink('Бизнеси')
    const dialog = page.getByRole('alertdialog', { name: 'Незапазени промени' })
    await expect(dialog).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await dialog.getByRole('button', { name: 'Остани' }).click()
    await expect(dialog).toHaveCount(0)
    await expect(page.getByLabel('Име', { exact: true })).toHaveValue(`Мобилна чернова ${TAG}`)
    // The invoking link sits in the now-closed menu, so focus returns to the menu button.
    await expect(menuButton()).toBeFocused()

    await page.getByLabel('Телефон', { exact: true }).fill(phoneFor(530))
    await page.getByRole('button', { name: 'Добави', exact: true }).click()
    await expect(page.getByRole('heading', { name: `Мобилна чернова ${TAG}`, level: 2 })).toBeVisible()
    await expectNoHorizontalOverflow(page)

    await page.getByRole('button', { name: 'Редактирай' }).click()
    await page.getByLabel('Име', { exact: true }).fill(LONG_NAME.replace(TAG, `ново${TAG}`))
    await page.getByLabel('Имейл', { exact: true }).fill(`${'e'.repeat(58)}@example.test`)
    await expectNoHorizontalOverflow(page)
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Промените са запазени.' })).toBeVisible()
    await expectNoHorizontalOverflow(page)

    await page.getByRole('button', { name: 'Редактирай' }).click()
    await page.getByLabel('Име', { exact: true }).fill(`Отказана промяна ${TAG}`)
    await page.getByRole('button', { name: 'Отказ' }).click()
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: 'Остани' }).click()
    await expect(page.getByRole('button', { name: 'Отказ' })).toBeFocused()
    await page.getByRole('button', { name: 'Отказ' }).click()
    await dialog.getByRole('button', { name: 'Напусни' }).click()
    await expect(page.getByRole('button', { name: 'Редактирай' })).toBeVisible()

    await openCustomers(page)
    await expectNoHorizontalOverflow(page)
  })

  test('a SUSPENDED Business can be viewed and edited on mobile but offers no creation', async () => {
    await showOnMobile(SUSPENDED_NAME)
    await menuLink('Клиенти')
    await expect(page.getByRole('heading', { name: 'Клиенти', level: 1, exact: true })).toBeVisible()
    await expect(page.getByRole('status').filter({ hasText: CUSTOMER_SUSPENDED_NOTICE })).toHaveCount(1)
    await expect(page.getByRole('button', { name: 'Добави клиент' })).toHaveCount(0)
    await expect(customerRows(page)).toHaveCount(1)
    await expectNoHorizontalOverflow(page)

    await page.getByRole('link', { name: `Отвори ${suspendedCustomer.displayName}` }).click()
    await page.getByRole('button', { name: 'Редактирай' }).click()
    await page.getByLabel('Име', { exact: true }).fill(`Спрян редактиран ${TAG}`)
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Промените са запазени.' })).toBeVisible()
    await expect(page.getByRole('status').filter({ hasText: CUSTOMER_SUSPENDED_NOTICE })).toHaveCount(1)
    await expectNoHorizontalOverflow(page)

    await page.goto('/#/business/customers/new')
    await expect(page.getByRole('heading', { name: 'Нов клиент', level: 1 })).toBeVisible()
    await expect(page.getByLabel('Име', { exact: true })).toHaveCount(0)
    await expect(page.getByRole('status').filter({ hasText: CUSTOMER_SUSPENDED_NOTICE })).toHaveCount(1)
    await expectNoHorizontalOverflow(page)
  })
})
