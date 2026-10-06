import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { apiRequest } from './support/browser'
import {
  CUSTOMERS_PATH,
  addBusinessForOwner,
  businessCard,
  customerRows,
  customerTotal,
  expectedOrder,
  listRoute,
  openCustomers,
  pagination,
  phoneFor,
  provisionOwnerBusiness,
  readCustomer,
  searchBox,
  seedCustomer,
  seedCustomers,
  selectBusinessViaApi,
  shortTag,
  showBusiness,
  shownPhone,
  signInOwner,
  sortHeader,
  visibleEmails,
  visibleNames,
  visiblePhones,
  type CustomerRecord,
  type CustomerSeed,
} from './support/customers'
import { signInAsPlatformAdmin } from './support/provisioning'

const TAG = shortTag()
const CATALOG_NAME = `Каталог Клиенти ${TAG}`
const FORMS_NAME = `Форми Клиенти ${TAG}`
const EMPTY_NAME = `Празен Клиенти ${TAG}`

const SPECIAL_SEEDS: CustomerSeed[] = [
  { displayName: 'Мария Иванова-Петрова', phone: '0895555777', email: 'Maria.Ivanova@Example.TEST' },
  { displayName: 'Златан Йорданов', email: 'zlatan.yordanov@example.test' },
  { displayName: 'Петър Димитров Стоянов', phone: '+359888123456' },
  { displayName: 'Анна Тестова', phone: '+4915123456789', email: 'anna.t@example.test' },
]

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

// 50 bulk customers: phones for four fifths, emails for two thirds, in a permuted order so that
// the name, phone and email orders all differ.
function bulkSeeds(): CustomerSeed[] {
  const seeds: CustomerSeed[] = []
  for (let index = 0; index < 50; index += 1) {
    const seed: CustomerSeed = { displayName: `Пробен ${pad(index)} Клиентов` }
    if (index % 5 !== 0) seed.phone = phoneFor(49 - index)
    if (index % 3 !== 0 || index % 5 === 0) seed.email = `k${pad((index * 7) % 50)}@example.test`
    seeds.push(seed)
  }
  return seeds
}

let adminContext: BrowserContext
let admin: Page
let ownerContext: BrowserContext
let page: Page
let catalog: CustomerRecord[] = []
let formsId = ''

test.beforeAll(async ({ browser }) => {
  test.setTimeout(240_000)
  adminContext = await browser.newContext()
  admin = await adminContext.newPage()
  await signInAsPlatformAdmin(admin)

  const first = await provisionOwnerBusiness(browser, adminContext, admin, {
    name: CATALOG_NAME,
    slugBase: 'customers-catalog-e2e',
    businessType: 'HAIR_SALON',
    finalState: 'ACTIVE',
  })
  formsId = await addBusinessForOwner(browser, adminContext, admin, first.ownerEmail, {
    name: FORMS_NAME,
    slugBase: 'customers-forms-e2e',
    businessType: 'HAIR_SALON',
    finalState: 'ACTIVE',
  })
  await addBusinessForOwner(browser, adminContext, admin, first.ownerEmail, {
    name: EMPTY_NAME,
    slugBase: 'customers-empty-e2e',
    businessType: 'HAIR_SALON',
    finalState: 'ACTIVE',
  })

  const owner = await signInOwner(browser, first.ownerEmail, { name: 'Бизнеси', level: 1 })
  ownerContext = owner.context
  page = owner.page
  await selectBusinessViaApi(page, first.id)
  catalog = await seedCustomers(page, [...bulkSeeds(), ...SPECIAL_SEEDS])
  expect(catalog).toHaveLength(54)
  await selectBusinessViaApi(page, formsId)
  await seedCustomer(page, { displayName: `Формов Сентинел ${TAG}`, email: `forms.${TAG}@example.test` })
})

test.afterAll(async () => {
  await ownerContext?.close()
  await adminContext?.close()
})

function nav(target: Page) {
  // The label is "Основна навигация" until a Business is selected, then "Навигация на бизнеса".
  return target.getByRole('navigation', { name: /навигация/i })
}

function idFromHash(target: Page): string {
  return decodeURIComponent(listRoute(target).path.split('/').pop() ?? '')
}

async function summary(target: Page, text: string): Promise<void> {
  await expect(pagination(target).getByText(text)).toBeVisible()
}

test.describe('Navigation and Business context', () => {
  test('Business selection and Profile are distinct and selection is scoped and persistent', async () => {
    await page.goto('/#/businesses')
    await expect(page.getByRole('heading', { name: 'Бизнеси', level: 1, exact: true })).toBeVisible()
    await expect(nav(page).getByRole('link')).toHaveText(['Бизнеси', 'Профил'])
    await expect(page.locator('article.business-choice')).toHaveCount(3)

    await businessCard(page, FORMS_NAME).getByRole('button', { name: 'Покажи', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Услуги', level: 1, exact: true })).toBeVisible()

    const group = nav(page).getByRole('group', { name: FORMS_NAME })
    await expect(group.getByRole('link')).toHaveText(['Услуги', 'Екип', 'Работно време', 'Клиенти'])
    const headingBox = await group.locator('.navigation-context-heading').boundingBox()
    const firstLinkBox = await group.getByRole('link').first().boundingBox()
    expect(headingBox!.y).toBeLessThan(firstLinkBox!.y)

    await nav(page).getByRole('link', { name: 'Бизнеси', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Бизнеси', level: 1, exact: true })).toBeVisible()
    const forms = businessCard(page, FORMS_NAME)
    await expect(forms).toHaveAttribute('aria-current', 'true')
    await expect(forms.getByText('✓')).toBeVisible()
    await expect(forms.getByText('Текущо избран бизнес')).toBeAttached()
    await expect(page.locator('article[aria-current="true"]')).toHaveCount(1)
    await expect(page.locator('.business-choice-check')).toHaveCount(1)
    await expect(businessCard(page, CATALOG_NAME)).not.toHaveAttribute('aria-current', /.*/)
    await expect(page.getByText('Избран', { exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Покажи', exact: true })).toHaveCount(3)
    const mainText = await page.getByRole('main').innerText()
    expect(/BUSINESS_OWNER|PLATFORM_ADMIN|MANAGER|\bDRAFT\b|\bACTIVE\b|\bSUSPENDED\b/.test(mainText)).toBe(false)

    await page.reload()
    await expect(businessCard(page, FORMS_NAME)).toHaveAttribute('aria-current', 'true')
    await expect(nav(page).getByRole('group', { name: FORMS_NAME })).toBeVisible()

    await nav(page).getByRole('link', { name: 'Профил', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Профил', level: 1, exact: true })).toBeVisible()
    await expect(page.getByLabel('Избери бизнес')).toHaveCount(0)
    await expect(page.getByRole('combobox')).toHaveCount(0)
    const profileText = await page.getByRole('main').innerText()
    for (const name of [CATALOG_NAME, FORMS_NAME, EMPTY_NAME]) {
      expect(profileText.includes(name)).toBe(false)
    }
  })

  test('switching Businesses changes the visible Customers and clears search and list state', async () => {
    await showBusiness(page, CATALOG_NAME)
    await openCustomers(page, 'page=0&size=25&sort=email&direction=desc')
    await searchBox(page).fill('Пробен')
    await expect.poll(async () => (await visibleNames(page)).length).toBe(25)

    await showBusiness(page, FORMS_NAME)
    await nav(page).getByRole('link', { name: 'Клиенти', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Клиенти', level: 1, exact: true })).toBeVisible()
    await expect(searchBox(page)).toHaveValue('')
    await expect(page.getByRole('link', { name: `Отвори Формов Сентинел ${TAG}` })).toBeVisible()
    expect((await visibleNames(page)).some((name) => name.startsWith('Пробен'))).toBe(false)
    const route = listRoute(page)
    expect(Object.fromEntries(route.params)).toEqual({
      page: '0', size: '10', sort: 'name', direction: 'asc',
    })

    await showBusiness(page, CATALOG_NAME)
    await nav(page).getByRole('link', { name: 'Клиенти', exact: true }).click()
    await expect(searchBox(page)).toHaveValue('')
    expect(Object.fromEntries(listRoute(page).params)).toEqual({
      page: '0', size: '10', sort: 'name', direction: 'asc',
    })
    await summary(page, 'Показани 1–10 от 54')
  })
})

test.describe('Live search', () => {
  test('search is live, matches inside names, emails and phones, and never touches the route', async () => {
    await showBusiness(page, CATALOG_NAME)
    await openCustomers(page, 'page=2&size=10&sort=name&direction=asc')
    await expect.poll(() => listRoute(page).params.get('page')).toBe('2')

    const searchRequests: Array<{ url: string, search: string }> = []
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith(`${CUSTOMERS_PATH}/search`)) {
        searchRequests.push({
          url: request.url(),
          search: (request.postDataJSON() as { search: string }).search,
        })
      }
    })

    await expect(page.getByRole('button', { name: 'Търси' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Изчисти' })).toHaveCount(0)

    await searchBox(page).fill('Петрова')
    await expect.poll(() => visibleNames(page)).toEqual(['Мария Иванова-Петрова'])
    expect(listRoute(page).params.get('page')).toBe('0')

    for (const term of ['нова-Пет', 'ИВАНОВА-ПЕТРОВА', 'ова-петр']) {
      await searchBox(page).fill(term)
      await expect.poll(() => visibleNames(page)).toEqual(['Мария Иванова-Петрова'])
    }

    await searchBox(page).fill('zlatan')
    await expect.poll(() => visibleNames(page)).toEqual(['Златан Йорданов'])
    await searchBox(page).fill('YORDANOV@EXAMPLE')
    await expect.poll(() => visibleNames(page)).toEqual(['Златан Йорданов'])

    await searchBox(page).fill('0895555')
    await expect.poll(() => visibleNames(page)).toEqual(['Мария Иванова-Петрова'])
    await searchBox(page).fill('+359888123')
    await expect.poll(() => visibleNames(page)).toEqual(['Петър Димитров Стоянов'])
    await searchBox(page).fill('0888 123 456')
    await expect.poll(() => visibleNames(page)).toEqual(['Петър Димитров Стоянов'])

    // Typing, deleting one character, and clearing all re-search without any button.
    await searchBox(page).fill('Пробен 1')
    await expect.poll(async () => (await visibleNames(page)).length).toBe(10)
    await summary(page, 'Показани 1–10 от 10')
    await searchBox(page).press('Backspace')
    await summary(page, 'Показани 1–10 от 50')
    await searchBox(page).fill('')
    await summary(page, 'Показани 1–10 от 54')
    await searchBox(page).fill('Пробен')
    await summary(page, 'Показани 1–10 от 50')
    await searchBox(page).press('Escape')
    await expect(searchBox(page)).toHaveValue('')
    await summary(page, 'Показани 1–10 от 54')

    // A changed search resets the page.
    await pagination(page).getByRole('button', { name: 'Следваща' }).click()
    await expect.poll(() => listRoute(page).params.get('page')).toBe('1')
    await searchBox(page).fill('Пробен')
    await expect.poll(() => listRoute(page).params.get('page')).toBe('0')
    await summary(page, 'Показани 1–10 от 50')

    // No match is distinct from an empty catalog.
    await searchBox(page).fill('несъществуващо')
    await expect(page.getByText('Не са намерени клиенти по това търсене.')).toBeVisible()
    await expect(page.getByText('Все още няма добавени клиенти.')).toHaveCount(0)

    expect([...listRoute(page).params.keys()].sort()).toEqual(['direction', 'page', 'size', 'sort'])
    expect(decodeURIComponent(page.url()).includes('несъществуващо')).toBe(false)
    expect(searchRequests.length).toBeGreaterThan(0)
    for (const request of searchRequests) {
      expect(request.url.includes(encodeURIComponent(request.search))).toBe(false)
      expect(new URL(request.url).search).toBe('')
    }
    page.removeAllListeners('request')

    await showBusiness(page, EMPTY_NAME)
    await nav(page).getByRole('link', { name: 'Клиенти', exact: true }).click()
    await expect(page.getByText('Все още няма добавени клиенти.')).toBeVisible()
    await searchBox(page).fill('нещо')
    await expect(page.getByText('Не са намерени клиенти по това търсене.')).toBeVisible()
    await searchBox(page).fill('')
    await expect(page.getByText('Все още няма добавени клиенти.')).toBeVisible()
  })
})

test.describe('Sorting, pagination, detail and stale pages', () => {
  test('server-side pagination with sizes 10, 25 and 50 and the page range', async () => {
    await showBusiness(page, CATALOG_NAME)
    await openCustomers(page)
    await expect.poll(() => Object.fromEntries(listRoute(page).params)).toEqual({
      page: '0', size: '10', sort: 'name', direction: 'asc',
    })
    const sizeSelect = pagination(page).getByLabel('Резултати на страница')
    await expect(sizeSelect.locator('option')).toHaveText(['10', '25', '50'])
    await expect(sizeSelect).toHaveValue('10')
    const ordered = expectedOrder(catalog, 'name', 'asc')
    await expect.poll(() => visibleNames(page)).toEqual(ordered.slice(0, 10).map((c) => c.displayName))
    await summary(page, 'Показани 1–10 от 54')
    await expect(pagination(page).getByRole('button', { name: 'Предишна' })).toBeDisabled()

    await pagination(page).getByRole('button', { name: 'Следваща' }).click()
    await summary(page, 'Показани 11–20 от 54')
    await expect.poll(() => visibleNames(page)).toEqual(ordered.slice(10, 20).map((c) => c.displayName))
    expect(listRoute(page).params.get('page')).toBe('1')
    for (let step = 0; step < 4; step += 1) {
      await pagination(page).getByRole('button', { name: 'Следваща' }).click()
    }
    await summary(page, 'Показани 51–54 от 54')
    await expect(pagination(page).getByRole('button', { name: 'Следваща' })).toBeDisabled()
    await expect.poll(() => visibleNames(page)).toEqual(ordered.slice(50).map((c) => c.displayName))
    await pagination(page).getByRole('button', { name: 'Предишна' }).click()
    await summary(page, 'Показани 41–50 от 54')

    await sizeSelect.selectOption('25')
    await expect.poll(() => listRoute(page).params.get('page')).toBe('0')
    expect(listRoute(page).params.get('size')).toBe('25')
    await summary(page, 'Показани 1–25 от 54')
    await expect.poll(() => visibleNames(page)).toEqual(ordered.slice(0, 25).map((c) => c.displayName))
    await sizeSelect.selectOption('50')
    await summary(page, 'Показани 1–50 от 54')
    await pagination(page).getByRole('button', { name: 'Следваща' }).click()
    await summary(page, 'Показани 51–54 от 54')
  })

  test('name, phone and email sort in both directions with the promised order', async () => {
    await showBusiness(page, CATALOG_NAME)
    await openCustomers(page, 'page=1&size=10&sort=name&direction=asc')
    await expect.poll(() => listRoute(page).params.get('page')).toBe('1')
    await sortHeader(page, 'Телефон').click()
    await expect.poll(() => listRoute(page).params.get('page')).toBe('0')

    const sizeSelect = pagination(page).getByLabel('Резултати на страница')
    await sizeSelect.selectOption('50')
    await summary(page, 'Показани 1–50 от 54')

    const steps: Array<{ click: string, sort: 'name' | 'phone' | 'email', direction: 'asc' | 'desc' }> = [
      { click: 'Телефон', sort: 'phone', direction: 'asc' },
      { click: 'Телефон', sort: 'phone', direction: 'desc' },
      { click: 'Имейл', sort: 'email', direction: 'asc' },
      { click: 'Имейл', sort: 'email', direction: 'desc' },
      { click: 'Име', sort: 'name', direction: 'asc' },
      { click: 'Име', sort: 'name', direction: 'desc' },
    ]
    // The first click on Телефон already ran above (ascending), so start by verifying it.
    const check = async (sort: 'name' | 'phone' | 'email', direction: 'asc' | 'desc') => {
      await expect.poll(() => listRoute(page).params.get('sort')).toBe(sort)
      await expect.poll(() => listRoute(page).params.get('direction')).toBe(direction)
      const expected = expectedOrder(catalog, sort, direction).slice(0, 50)
      await expect.poll(() => visibleNames(page)).toEqual(expected.map((c) => c.displayName))
      await expect.poll(() => visiblePhones(page)).toEqual(
        expected.map((c) => (c.phone === null ? null : shownPhone(c.phone))),
      )
      await expect.poll(() => visibleEmails(page)).toEqual(expected.map((c) => c.email))
      await expect(
        page.getByRole('columnheader', {
          name: sort === 'name' ? 'Име' : sort === 'phone' ? 'Телефон' : 'Имейл',
          exact: true,
        }),
      ).toHaveAttribute('aria-sort', direction === 'asc' ? 'ascending' : 'descending')
    }
    await check('phone', 'asc')
    for (const step of steps.slice(1)) {
      await sortHeader(page, step.click).click()
      await check(step.sort, step.direction)
    }
  })

  test('detail and Back keep the list state and the in-memory search, and a stale page recovers', async () => {
    await showBusiness(page, CATALOG_NAME)
    await openCustomers(page, 'page=0&size=25&sort=email&direction=desc')
    await searchBox(page).fill('Пробен')
    await summary(page, 'Показани 1–25 от 50')
    await pagination(page).getByRole('button', { name: 'Следваща' }).click()
    await summary(page, 'Показани 26–50 от 50')
    const expected = expectedOrder(
      catalog.filter((c) => c.displayName.startsWith('Пробен')),
      'email',
      'desc',
    )
    const names = await visibleNames(page)
    expect(names).toEqual(expected.slice(25).map((c) => c.displayName))

    await page.getByRole('link', { name: `Отвори ${names[0]}` }).click()
    await expect(page.getByRole('heading', { name: names[0]!, level: 2, exact: true })).toBeVisible()
    expect(decodeURIComponent(page.url()).includes('Пробен')).toBe(false)
    await page.getByRole('button', { name: 'Обратно към клиентите' }).click()
    await expect(page.getByRole('heading', { name: 'Клиенти', level: 1, exact: true })).toBeVisible()
    expect(Object.fromEntries(listRoute(page).params)).toEqual({
      page: '1', size: '25', sort: 'email', direction: 'desc',
    })
    await expect(searchBox(page)).toHaveValue('Пробен')
    await expect.poll(() => visibleNames(page)).toEqual(names)

    // Stale-page recovery through supported updates: two matches are renamed away while the
    // user is on page 0 of a 12-result search, then the user asks for page 1.
    await showBusiness(page, FORMS_NAME)
    const tag = shortTag()
    const group = await seedCustomers(
      page,
      Array.from({ length: 12 }, (_, index) => ({
        displayName: `Рекавъри${tag} ${pad(index)}`,
        email: `recovery.${tag}.${pad(index)}@example.test`,
      })),
    )
    await openCustomers(page)
    await searchBox(page).fill(`Рекавъри${tag}`)
    await summary(page, 'Показани 1–10 от 12')
    for (const record of group.slice(0, 2)) {
      const renamed = await apiRequest(page, 'PUT', `${CUSTOMERS_PATH}/${record.id}`, {
        displayName: `Преименуван${tag} ${record.displayName.slice(-2)}`,
        email: record.email,
        expectedVersion: record.version,
      })
      expect(renamed.status, renamed.code ?? 'rename').toBe(200)
    }
    await pagination(page).getByRole('button', { name: 'Следваща' }).click()
    await expect.poll(() => listRoute(page).params.get('page')).toBe('0')
    await summary(page, 'Показани 1–10 от 10')
    await expect(customerRows(page)).toHaveCount(10)
    await expect(page.getByText('Не са намерени клиенти по това търсене.')).toHaveCount(0)
    await expect(searchBox(page)).toHaveValue(`Рекавъри${tag}`)
  })
})

async function fillCustomerForm(
  target: Page,
  values: { name?: string, phone?: string, email?: string },
): Promise<void> {
  if (values.name !== undefined) await target.getByLabel('Име', { exact: true }).fill(values.name)
  if (values.phone !== undefined) await target.getByLabel('Телефон', { exact: true }).fill(values.phone)
  if (values.email !== undefined) await target.getByLabel('Имейл', { exact: true }).fill(values.email)
}

async function openCreateForm(target: Page): Promise<void> {
  await openCustomers(target)
  await target.getByRole('button', { name: 'Добави клиент' }).click()
  await expect(target.getByRole('heading', { name: 'Нов клиент', level: 1 })).toBeVisible()
}

async function createViaForm(
  target: Page,
  values: { name: string, phone?: string, email?: string },
): Promise<string> {
  await openCreateForm(target)
  await fillCustomerForm(target, values)
  await target.getByRole('button', { name: 'Добави', exact: true }).click()
  await expect(target.getByRole('heading', { name: values.name, level: 2, exact: true })).toBeVisible()
  await expect(target.getByRole('status').filter({ hasText: 'Клиентът е добавен.' })).toBeVisible()
  return idFromHash(target)
}

const details = (target: Page) => target.locator('dl.business-details-list')

test.describe('Customer creation, editing and validation', () => {
  test.beforeEach(async () => {
    await showBusiness(page, FORMS_NAME)
  })

  test('creates Customers with phone only, email only and both, canonicalized and persistent', async () => {
    const phoneOnly = await createViaForm(page, { name: `Телефонен ${TAG}`, phone: '0888 123 456' })
    await expect(details(page)).toContainText('+359 888 123 456')
    await expect(details(page).getByText('Имейл')).toHaveCount(0)
    await page.reload()
    await expect(details(page)).toContainText('+359 888 123 456')
    expect((await readCustomer(page, phoneOnly)).phone).toBe('+359888123456')

    const emailOnly = await createViaForm(page, { name: `Имейлов ${TAG}`, email: ' Ime.Prezime@Primer.BG ' })
    await expect(details(page)).toContainText('ime.prezime@primer.bg')
    await expect(details(page).getByText('Телефон')).toHaveCount(0)
    await page.reload()
    await expect(details(page)).toContainText('ime.prezime@primer.bg')
    const emailRecord = await readCustomer(page, emailOnly)
    expect([emailRecord.phone, emailRecord.email]).toEqual([null, 'ime.prezime@primer.bg'])

    const both = await createViaForm(page, {
      name: `Двоен ${TAG}`,
      phone: '+359 (895) 555-777',
      email: 'both@example.test',
    })
    await page.reload()
    await expect(details(page)).toContainText('+359 895 555 777')
    await expect(details(page)).toContainText('both@example.test')
    expect((await readCustomer(page, both)).phone).toBe('+359895555777')

    await openCustomers(page)
    await searchBox(page).fill(TAG)
    await expect.poll(() => visibleNames(page)).toEqual(
      expect.arrayContaining([`Телефонен ${TAG}`, `Имейлов ${TAG}`, `Двоен ${TAG}`]),
    )
  })

  test('requires a name and a contact and rejects invalid contacts inline', async () => {
    await openCreateForm(page)
    await page.getByRole('button', { name: 'Добави', exact: true }).click()
    await expect(page.getByText('Въведете име на клиента до 200 знака.')).toBeVisible()
    await expect(page.getByText('Въведете телефон или имейл.')).toBeVisible()
    await expect(page.getByLabel('Име', { exact: true })).toBeFocused()

    await fillCustomerForm(page, { name: `Без контакт ${TAG}` })
    await page.getByRole('button', { name: 'Добави', exact: true }).click()
    await expect(page.getByText('Въведете име на клиента до 200 знака.')).toHaveCount(0)
    await expect(page.getByText('Въведете телефон или имейл.')).toBeVisible()

    for (const phone of ['abc', '+359895555777 ext 5', '895555777']) {
      await fillCustomerForm(page, { phone })
      await page.getByLabel('Телефон', { exact: true }).blur()
      await expect(page.getByText('Въведеният телефонен номер не е валиден.')).toBeVisible()
    }
    await fillCustomerForm(page, { phone: '', email: 'x@' })
    await page.getByLabel('Имейл', { exact: true }).blur()
    await expect(page.getByText('Въведеният имейл адрес не е валиден.')).toBeVisible()
    await expect(page.getByLabel('Име', { exact: true })).toHaveValue(`Без контакт ${TAG}`)

    await page.getByRole('button', { name: 'Обратно към клиентите' }).click()
    await page.getByRole('button', { name: 'Напусни' }).click()
    await expect(page.getByRole('heading', { name: 'Клиенти', level: 1, exact: true })).toBeVisible()
  })

  test('edits name and contacts, enforces contact presence and keeps the stored data on failure', async () => {
    const seed = await seedCustomer(page, {
      displayName: `Редактиран ${TAG}`,
      phone: phoneFor(900),
      email: `edit.${TAG}@example.test`,
    })
    await page.goto(`/#/business/customers/${seed.id}`)
    await expect(page.getByRole('heading', { name: seed.displayName, level: 2, exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Редактирай' }).click()
    await fillCustomerForm(page, { name: `Редактиран нов ${TAG}`, phone: '0888 777 666', email: '' })
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Промените са запазени.' })).toBeVisible()
    await expect(details(page)).toContainText('+359 888 777 666')
    await page.reload()
    await expect(page.getByRole('heading', { name: `Редактиран нов ${TAG}`, level: 2 })).toBeVisible()
    await expect(details(page).getByText('Имейл')).toHaveCount(0)
    const saved = await readCustomer(page, seed.id)
    expect([saved.phone, saved.email, saved.version]).toEqual(['+359888777666', null, seed.version + 1])

    await page.getByRole('button', { name: 'Редактирай' }).click()
    await fillCustomerForm(page, { phone: '' })
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(page.getByText('Въведете телефон или имейл.')).toBeVisible()
    expect((await readCustomer(page, seed.id)).version).toBe(saved.version)
    await page.getByRole('button', { name: 'Отказ' }).click()
    await page.getByRole('button', { name: 'Напусни' }).click()
    await expect(page.getByRole('button', { name: 'Редактирай' })).toBeVisible()
  })

  test('duplicate phone and email give safe inline feedback and change nothing; names may repeat', async () => {
    const holder = await seedCustomer(page, {
      displayName: `Притежател ${TAG}`,
      phone: phoneFor(901),
      email: `holder.${TAG}@example.test`,
    })
    const other = await seedCustomer(page, { displayName: `Друг ${TAG}`, phone: phoneFor(902) })
    const total = await customerTotal(page)

    await openCreateForm(page)
    await fillCustomerForm(page, { name: `Дубликат ${TAG}`, phone: shownPhone(phoneFor(901)) })
    await page.getByRole('button', { name: 'Добави', exact: true }).click()
    await expect(page.getByText('Този телефонен номер вече е записан за друг клиент.')).toBeVisible()
    await expect(page.getByLabel('Име', { exact: true })).toHaveValue(`Дубликат ${TAG}`)
    await expect(page.getByRole('alert')).toHaveCount(0)

    await fillCustomerForm(page, { phone: '', email: `HOLDER.${TAG}@EXAMPLE.TEST` })
    await page.getByRole('button', { name: 'Добави', exact: true }).click()
    await expect(page.getByText('Този имейл адрес вече е записан за друг клиент.')).toBeVisible()

    await fillCustomerForm(page, { phone: phoneFor(902) })
    await page.getByRole('button', { name: 'Добави', exact: true }).click()
    await expect(page.getByText('Този телефонен номер вече е записан за друг клиент.')).toBeVisible()
    await expect(page.getByText('Този имейл адрес вече е записан за друг клиент.')).toBeVisible()
    const bodyText = await page.getByRole('main').innerText()
    expect(bodyText.includes(holder.displayName) || bodyText.includes(other.displayName)).toBe(false)

    expect(await customerTotal(page)).toBe(total)
    expect((await readCustomer(page, holder.id)).version).toBe(holder.version)
    expect((await readCustomer(page, other.id)).version).toBe(other.version)

    await page.getByRole('button', { name: 'Обратно към клиентите' }).click()
    await page.getByRole('button', { name: 'Напусни' }).click()

    // Editing onto another Customer's phone is rejected and leaves the stored record intact.
    await page.goto(`/#/business/customers/${other.id}`)
    await page.getByRole('button', { name: 'Редактирай' }).click()
    await fillCustomerForm(page, { phone: phoneFor(901) })
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(page.getByText('Този телефонен номер вече е записан за друг клиент.')).toBeVisible()
    expect((await readCustomer(page, other.id)).version).toBe(other.version)
    await page.getByRole('button', { name: 'Отказ' }).click()
    await page.getByRole('button', { name: 'Напусни' }).click()

    // The same name is allowed: names are not identity keys.
    const shared = `Еднакво Име ${TAG}`
    await seedCustomer(page, { displayName: shared, phone: phoneFor(903) })
    await createViaForm(page, { name: shared, phone: phoneFor(904) })
    await openCustomers(page)
    await searchBox(page).fill(shared)
    await expect.poll(() => visibleNames(page)).toEqual([shared, shared])
  })
})

test.describe('Unsaved changes', () => {
  test.beforeEach(async () => {
    await showBusiness(page, FORMS_NAME)
  })

  test('a dirty create form prompts on global navigation, Остани keeps it, Напусни leaves', async () => {
    await openCreateForm(page)
    await fillCustomerForm(page, { name: `Чернова ${TAG}`, email: `draft.${TAG}@example.test` })
    const businessesLink = nav(page).getByRole('link', { name: 'Бизнеси', exact: true })
    await businessesLink.click()
    const dialog = page.getByRole('alertdialog', { name: 'Незапазени промени' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'Остани' })).toBeFocused()
    await dialog.getByRole('button', { name: 'Остани' }).click()
    await expect(dialog).toHaveCount(0)
    expect(listRoute(page).path).toBe('#/business/customers/new')
    await expect(page.getByLabel('Име', { exact: true })).toHaveValue(`Чернова ${TAG}`)
    await expect(page.getByLabel('Имейл', { exact: true })).toHaveValue(`draft.${TAG}@example.test`)
    await expect(businessesLink).toBeFocused()

    await businessesLink.click()
    await dialog.getByRole('button', { name: 'Напусни' }).click()
    await expect(page.getByRole('heading', { name: 'Бизнеси', level: 1, exact: true })).toBeVisible()
    await businessCard(page, EMPTY_NAME).getByRole('button', { name: 'Покажи', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Услуги', level: 1, exact: true })).toBeVisible()
    await expect(nav(page).getByRole('group', { name: EMPTY_NAME })).toBeVisible()
  })

  test('a dirty edit form prompts on Back, Отказ and logout; saving shows no false prompt', async () => {
    const seed = await seedCustomer(page, { displayName: `Защитен ${TAG}`, phone: phoneFor(910) })
    const dialog = page.getByRole('alertdialog', { name: 'Незапазени промени' })
    await page.goto(`/#/business/customers/${seed.id}`)
    await page.getByRole('button', { name: 'Редактирай' }).click()
    await fillCustomerForm(page, { name: `Защитен променен ${TAG}` })

    await page.getByRole('button', { name: 'Обратно към клиентите' }).click()
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: 'Остани' }).click()
    await expect(page.getByLabel('Име', { exact: true })).toHaveValue(`Защитен променен ${TAG}`)

    await page.getByRole('button', { name: 'Изход' }).filter({ visible: true }).first().click()
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: 'Остани' }).click()
    await expect(page.getByLabel('Име', { exact: true })).toHaveValue(`Защитен променен ${TAG}`)

    await page.getByRole('button', { name: 'Отказ' }).click()
    await dialog.getByRole('button', { name: 'Напусни' }).click()
    await expect(page.getByRole('heading', { name: seed.displayName, level: 2 })).toBeVisible()

    await page.getByRole('button', { name: 'Редактирай' }).click()
    await fillCustomerForm(page, { name: `Защитен записан ${TAG}` })
    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Промените са запазени.' })).toBeVisible()
    await nav(page).getByRole('link', { name: 'Бизнеси', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Бизнеси', level: 1, exact: true })).toBeVisible()
    await expect(dialog).toHaveCount(0)

    // A successful create leaves without a false prompt as well.
    await showBusiness(page, FORMS_NAME)
    await createViaForm(page, { name: `Чист ${TAG}`, phone: phoneFor(911) })
    await nav(page).getByRole('link', { name: 'Бизнеси', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Бизнеси', level: 1, exact: true })).toBeVisible()
    await expect(dialog).toHaveCount(0)
  })
})
