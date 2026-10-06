import { randomUUID } from 'node:crypto'
import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { apiRequest, SERVICES_HEADING } from './support/browser'
import {
  CUSTOMERS_PATH,
  CUSTOMER_SUSPENDED_NOTICE,
  customerRows,
  customerTotal,
  expectNoSentinelInBrowser,
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
  shortTag,
  shownPhone,
  signInOwner,
  visibleNames,
  type CustomerPageBody,
  type CustomerRecord,
} from './support/customers'
import {
  createDraftBusiness,
  inviteOwner,
  onboardOwner,
  signInAsPlatformAdmin,
  uniqueBusinessFixture,
  type BusinessFixture,
  type OwnerSession,
} from './support/provisioning'
import { readPublicApi, transitionBusiness } from './support/publicProfile'

const TAG = shortTag()

const LIFECYCLE_TEMPLATE: BusinessFixture = {
  name: `Жизнен цикъл Клиенти ${TAG}`,
  slug: 'customers-lifecycle-e2e',
  ownerEmail: 'owner-customers-lifecycle@example.invalid',
  ownerFirstName: 'Лилия',
  ownerLastName: 'Цикълова',
}

let adminContext: BrowserContext
let admin: Page
let lifecycleOwner: OwnerSession
let lifecycleId = ''
let ownerA: { context: BrowserContext, page: Page }
let ownerB: { context: BrowserContext, page: Page }
let slugA = ''

test.beforeAll(async ({ browser }) => {
  test.setTimeout(240_000)
  adminContext = await browser.newContext()
  admin = await adminContext.newPage()
  await signInAsPlatformAdmin(admin)

  const lifecycle = uniqueBusinessFixture(LIFECYCLE_TEMPLATE)
  lifecycleId = await createDraftBusiness(admin, lifecycle)
  await inviteOwner(admin, lifecycleId, lifecycle.ownerEmail)
  lifecycleOwner = await onboardOwner(browser, adminContext, lifecycle)

  const a = await provisionOwnerBusiness(browser, adminContext, admin, {
    name: `Изолация А ${TAG}`,
    slugBase: 'customers-isolation-a-e2e',
    businessType: 'HAIR_SALON',
    finalState: 'ACTIVE',
  })
  slugA = a.slug
  ownerA = await signInOwner(browser, a.ownerEmail, SERVICES_HEADING)
  const b = await provisionOwnerBusiness(browser, adminContext, admin, {
    name: `Изолация Б ${TAG}`,
    slugBase: 'customers-isolation-b-e2e',
    businessType: 'BARBERSHOP',
    finalState: 'ACTIVE',
  })
  ownerB = await signInOwner(browser, b.ownerEmail, SERVICES_HEADING)
})

test.afterAll(async () => {
  await lifecycleOwner?.context.close()
  await ownerA?.context.close()
  await ownerB?.context.close()
  await adminContext?.close()
})

const suspendedNotice = (page: Page) =>
  page.getByRole('status').filter({ hasText: CUSTOMER_SUSPENDED_NOTICE })

test.describe('Customer administration across the Business lifecycle', () => {
  test('DRAFT and ACTIVE allow creating and editing; SUSPENDED allows only viewing and editing', async () => {
    test.setTimeout(150_000)
    const page = lifecycleOwner.page
    let suspended = false
    const created: CustomerRecord[] = []
    try {
      // DRAFT: create and edit through the interface.
      await openCustomers(page)
      await page.getByRole('button', { name: 'Добави клиент' }).click()
      await page.getByLabel('Име', { exact: true }).fill(`Чернова клиент ${TAG}`)
      await page.getByLabel('Телефон', { exact: true }).fill(phoneFor(1))
      await page.getByRole('button', { name: 'Добави', exact: true }).click()
      await expect(page.getByRole('heading', { name: `Чернова клиент ${TAG}`, level: 2 })).toBeVisible()
      await page.getByRole('button', { name: 'Редактирай' }).click()
      await page.getByLabel('Име', { exact: true }).fill(`Чернова редактиран ${TAG}`)
      await page.getByRole('button', { name: 'Запази промените' }).click()
      await expect(page.getByRole('status').filter({ hasText: 'Промените са запазени.' })).toBeVisible()

      // ACTIVE: the same.
      await transitionBusiness(admin, lifecycleId, 'activate')
      await page.reload()
      await expect(page.getByRole('heading', { name: 'Клиент', level: 1, exact: true })).toBeVisible()
      await page.getByRole('button', { name: 'Редактирай' }).click()
      await page.getByLabel('Име', { exact: true }).fill(`Активен редактиран ${TAG}`)
      await page.getByRole('button', { name: 'Запази промените' }).click()
      await expect(page.getByRole('status').filter({ hasText: 'Промените са запазени.' })).toBeVisible()
      await openCustomers(page)
      await expect(page.getByRole('button', { name: 'Добави клиент' })).toBeVisible()
      await expect(suspendedNotice(page)).toHaveCount(0)

      created.push(
        ...(await seedCustomers(
          page,
          Array.from({ length: 12 }, (_, index) => ({
            displayName: `Спрян ${String(index).padStart(2, '0')} ${TAG}`,
            phone: phoneFor(100 + index),
            email: `suspended.${index}.${TAG}@example.test`,
          })),
        )),
      )
      const totalBefore = await customerTotal(page)

      // SUSPENDED
      await transitionBusiness(admin, lifecycleId, 'suspend')
      suspended = true
      await page.reload()
      await expect(page.getByRole('heading', { name: 'Клиенти', level: 1, exact: true })).toBeVisible()
      await expect(suspendedNotice(page)).toHaveCount(1)
      await expect(page.getByRole('button', { name: 'Добави клиент' })).toHaveCount(0)
      await expect(customerRows(page)).toHaveCount(10)

      await pagination(page).getByRole('button', { name: 'Следваща' }).click()
      await expect(pagination(page).getByText(`Показани 11–${totalBefore} от ${totalBefore}`)).toBeVisible()
      await searchBox(page).fill(`Спрян 07 ${TAG}`)
      await expect.poll(() => visibleNames(page)).toEqual([`Спрян 07 ${TAG}`])
      await searchBox(page).fill('')
      await expect(pagination(page).getByText(`от ${totalBefore}`)).toBeVisible()
      await page.getByRole('columnheader', { name: 'Име', exact: true }).getByRole('button').click()
      await expect.poll(() => listRoute(page).params.get('direction')).toBe('desc')
      const everyone = (
        JSON.parse(
          (await apiRequest(page, 'GET', `${CUSTOMERS_PATH}?size=50&sort=name&direction=desc`)).text,
        ) as CustomerPageBody
      ).items
      await expect.poll(() => visibleNames(page)).toEqual(
        expectedOrder(everyone, 'name', 'desc').slice(0, 10).map((c) => c.displayName),
      )
      await expect(suspendedNotice(page)).toHaveCount(1)

      // Detail and editing an existing Customer stay available.
      const target = created[3]!
      await page.goto(`/#/business/customers/${target.id}`)
      await expect(page.getByRole('heading', { name: target.displayName, level: 2 })).toBeVisible()
      await expect(suspendedNotice(page)).toHaveCount(1)
      await page.getByRole('button', { name: 'Редактирай' }).click()
      await page.getByLabel('Име', { exact: true }).fill(`Спрян редактиран ${TAG}`)
      await page.getByRole('button', { name: 'Запази промените' }).click()
      await expect(page.getByRole('status').filter({ hasText: 'Промените са запазени.' })).toBeVisible()
      await page.reload()
      await expect(page.getByRole('heading', { name: `Спрян редактиран ${TAG}`, level: 2 })).toBeVisible()
      expect((await readCustomer(page, target.id)).displayName).toBe(`Спрян редактиран ${TAG}`)

      // Creation is absent: no button, no form on the direct route, and a forced request fails.
      await page.goto('/#/business/customers/new')
      await expect(page.getByRole('heading', { name: 'Нов клиент', level: 1 })).toBeVisible()
      await expect(suspendedNotice(page)).toHaveCount(1)
      await expect(page.getByLabel('Име', { exact: true })).toHaveCount(0)
      await expect(page.getByRole('button', { name: 'Добави', exact: true })).toHaveCount(0)
      await expect(page.getByRole('button', { name: 'Обратно към клиентите' })).toBeVisible()
      const forced = await apiRequest(page, 'POST', CUSTOMERS_PATH, {
        displayName: `Забранен ${TAG}`,
        phone: phoneFor(200),
      })
      expect([forced.status, forced.code]).toEqual([409, 'BUSINESS_SUSPENDED'])
      expect(await customerTotal(page)).toBe(totalBefore)

      // Reactivation restores creation.
      await transitionBusiness(admin, lifecycleId, 'reactivate')
      suspended = false
      await openCustomers(page)
      await page.reload()
      await expect(page.getByRole('button', { name: 'Добави клиент' })).toBeVisible()
      await expect(suspendedNotice(page)).toHaveCount(0)
      const afterwards = await apiRequest(page, 'POST', CUSTOMERS_PATH, {
        displayName: `Възстановен ${TAG}`,
        phone: phoneFor(201),
      })
      expect(afterwards.status).toBe(201)
      expect(await customerTotal(page)).toBe(totalBefore + 1)
    } finally {
      if (suspended) await transitionBusiness(admin, lifecycleId, 'reactivate')
    }
  })
})

test.describe('Version conflict', () => {
  test('a stale edit is rejected, keeps the entered values and never overwrites the saved Customer', async () => {
    const page = ownerA.page
    const seed = await seedCustomer(page, {
      displayName: `Конфликт ${TAG}`,
      phone: phoneFor(300),
      email: `conflict.${TAG}@example.test`,
    })
    await page.goto(`/#/business/customers/${seed.id}`)
    await page.getByRole('button', { name: 'Редактирай' }).click()
    await page.getByLabel('Име', { exact: true }).fill(`Остарял ${TAG}`)

    // A second session of the same owner saves first (a supported flow: the owner API).
    const other = await ownerA.context.newPage()
    try {
      await other.goto('/')
      await expect(other.getByRole('heading', SERVICES_HEADING)).toBeVisible()
      const saved = await apiRequest(other, 'PUT', `${CUSTOMERS_PATH}/${seed.id}`, {
        displayName: `Свеж ${TAG}`,
        phone: seed.phone,
        email: seed.email,
        expectedVersion: seed.version,
      })
      expect(saved.status).toBe(200)
    } finally {
      await other.close()
    }

    await page.getByRole('button', { name: 'Запази промените' }).click()
    await expect(page.getByRole('alert')).toContainText(
      'Данните за клиента са променени. Обновете данните и опитайте отново.',
    )
    await expect(page.getByLabel('Име', { exact: true })).toHaveValue(`Остарял ${TAG}`)
    const current = await readCustomer(page, seed.id)
    expect([current.displayName, current.version]).toEqual([`Свеж ${TAG}`, seed.version + 1])

    const dialog = page.getByRole('alertdialog', { name: 'Незапазени промени' })
    await page.getByRole('button', { name: 'Зареди актуалните данни' }).click()
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: 'Остани' }).click()
    await expect(page.getByLabel('Име', { exact: true })).toHaveValue(`Остарял ${TAG}`)

    await page.getByRole('button', { name: 'Зареди актуалните данни' }).click()
    await dialog.getByRole('button', { name: 'Напусни' }).click()
    await expect(page.getByRole('heading', { name: `Свеж ${TAG}`, level: 2 })).toBeVisible()
    await expect(page.getByLabel('Име', { exact: true })).toHaveCount(0)
    const final = await readCustomer(page, seed.id)
    expect([final.displayName, final.version]).toEqual([`Свеж ${TAG}`, seed.version + 1])
  })
})

test.describe('Tenant isolation and authorization', () => {
  test('owner B cannot read, search or update Business A Customers; the 404 is byte-identical', async () => {
    const a = ownerA.page
    const b = ownerB.page
    const secret = await seedCustomer(a, {
      displayName: `Тайна Фамилия ${TAG}`,
      phone: '+359895555777',
      email: `tajna.${TAG}@example.test`,
    })
    await seedCustomer(b, { displayName: `Собствен на Б ${TAG}`, phone: phoneFor(400) })

    const foreign = await apiRequest(b, 'GET', `${CUSTOMERS_PATH}/${secret.id}`)
    const unknown = await apiRequest(b, 'GET', `${CUSTOMERS_PATH}/${randomUUID()}`)
    expect([foreign.status, foreign.code]).toEqual([404, 'CUSTOMER_NOT_FOUND'])
    expect([unknown.status, unknown.code]).toEqual([404, 'CUSTOMER_NOT_FOUND'])
    expect(foreign.text === unknown.text).toBe(true)

    const update = await apiRequest(b, 'PUT', `${CUSTOMERS_PATH}/${secret.id}`, {
      displayName: `Нападение ${TAG}`,
      phone: phoneFor(401),
      expectedVersion: secret.version,
    })
    expect([update.status, update.code]).toEqual([404, 'CUSTOMER_NOT_FOUND'])
    const intact = await readCustomer(a, secret.id)
    expect([intact.displayName, intact.phone, intact.version]).toEqual([
      secret.displayName,
      '+359895555777',
      secret.version,
    ])

    const list = await apiRequest(b, 'GET', `${CUSTOMERS_PATH}?size=50`)
    expect(list.status).toBe(200)
    expect(list.text.includes(secret.id)).toBe(false)
    expect(list.text.includes('Тайна')).toBe(false)
    for (const term of ['Тайна', 'tajna', '0895555', '+359895555777']) {
      const search = await apiRequest(b, 'POST', `${CUSTOMERS_PATH}/search`, { search: term })
      expect(search.status).toBe(200)
      expect((JSON.parse(search.text) as CustomerPageBody).total).toBe(0)
    }

    await b.goto(`/#/business/customers/${secret.id}`)
    await expect(b.getByRole('alert')).toContainText('Клиентът не е намерен.')
    expect((await b.getByRole('main').innerText()).includes('Тайна')).toBe(false)
    await openCustomers(b)
    await searchBox(b).fill('Тайна')
    await expect(b.getByText('Не са намерени клиенти по това търсене.')).toBeVisible()
    expect((await b.getByRole('main').innerText()).includes('Тайна')).toBe(false)
  })

  test('a Platform Administrator without an owner Membership and anonymous callers are rejected', async ({ browser }) => {
    const target = (await seedCustomer(ownerA.page, {
      displayName: `Администраторска цел ${TAG}`,
      phone: phoneFor(402),
    }))
    const path = `${CUSTOMERS_PATH}/${target.id}`
    const attempts = [
      await apiRequest(admin, 'GET', CUSTOMERS_PATH),
      await apiRequest(admin, 'POST', `${CUSTOMERS_PATH}/search`, { search: 'Администраторска' }),
      await apiRequest(admin, 'GET', path),
      await apiRequest(admin, 'POST', CUSTOMERS_PATH, { displayName: 'Х', phone: phoneFor(403) }),
      await apiRequest(admin, 'PUT', path, {
        displayName: 'Х',
        phone: phoneFor(403),
        expectedVersion: target.version,
      }),
    ]
    for (const attempt of attempts) {
      expect([attempt.status, attempt.code]).toEqual([403, 'ACTIVE_BUSINESS_REQUIRED'])
      expect(attempt.text.includes('Администраторска')).toBe(false)
    }
    expect((await readCustomer(ownerA.page, target.id)).version).toBe(target.version)

    const anonymousContext = await browser.newContext()
    try {
      const anonymous = await anonymousContext.newPage()
      await anonymous.goto('/')
      await expect(anonymous.getByRole('heading', { name: 'Вход' })).toBeVisible()
      const results = [
        await apiRequest(anonymous, 'GET', CUSTOMERS_PATH),
        await apiRequest(anonymous, 'POST', `${CUSTOMERS_PATH}/search`, { search: 'Администраторска' }),
        await apiRequest(anonymous, 'GET', path),
        await apiRequest(anonymous, 'POST', CUSTOMERS_PATH, { displayName: 'Х', phone: phoneFor(404) }),
        await apiRequest(anonymous, 'PUT', path, {
          displayName: 'Х',
          phone: phoneFor(404),
          expectedVersion: target.version,
        }),
      ]
      for (const result of results) {
        expect([result.status, result.code]).toEqual([401, 'AUTH_REQUIRED'])
        expect(result.text.includes('Администраторска')).toBe(false)
      }
    } finally {
      await anonymousContext.close()
    }
  })
})

test.describe('Customer privacy in the browser', () => {
  test('search terms and Customer data never reach the URL, storage, cookies, title or public surfaces', async ({ request }) => {
    const page = ownerA.page
    const name = `Сентинел Приватност ${TAG}`
    const email = `sentinel.private.${TAG}@example.test`
    const customer = await seedCustomer(page, { displayName: name, phone: '+359888123456', email })

    const seen: Array<{ url: string, headers: string }> = []
    page.on('request', (outgoing) => {
      if (outgoing.url().includes(CUSTOMERS_PATH)) {
        void outgoing.allHeaders().then((headers) => {
          seen.push({ url: decodeURIComponent(outgoing.url()), headers: JSON.stringify(headers) })
        })
      }
    })
    try {
      await openCustomers(page)
      for (const term of ['Сентинел Приватност', `sentinel.private.${TAG}`, '0888123']) {
        await searchBox(page).fill(term)
        await expect.poll(() => visibleNames(page)).toEqual([name])
        await expectNoSentinelInBrowser(page, [term, 'Сентинел', 'sentinel.private'])
      }
      await page.getByRole('link', { name: `Отвори ${name}` }).click()
      await expect(page.getByRole('heading', { name, level: 2 })).toBeVisible()
      await expectNoSentinelInBrowser(page, [name, email, '888123456', '888 123 456'])
      await page.getByRole('button', { name: 'Обратно към клиентите' }).click()
      await expectNoSentinelInBrowser(page, [name, email, 'Сентинел', 'Приватност'])
      await expect.poll(() => seen.length).toBeGreaterThan(0)
    } finally {
      page.removeAllListeners('request')
    }
    for (const entry of seen) {
      expect(/Сентинел|sentinel\.private|0888123|888123456/i.test(entry.url)).toBe(false)
      expect(/Сентинел|sentinel\.private|0888123|888123456/i.test(entry.headers)).toBe(false)
    }

    // The public Business surfaces carry none of it.
    const publicApi = await readPublicApi(request, slugA)
    expect(publicApi.status).toBe(200)
    for (const value of [name, email, '888123456', customer.id]) {
      expect(publicApi.text.includes(value)).toBe(false)
    }
    const anonymous = await ownerA.context.browser()!.newContext()
    try {
      const publicPage = await anonymous.newPage()
      await publicPage.goto(`/${slugA}`)
      await expect(publicPage.getByRole('heading', { level: 1 })).toBeVisible()
      const text = await publicPage.locator('body').innerText()
      expect(text.includes('Сентинел') || text.includes(email)).toBe(false)
    } finally {
      await anonymous.close()
    }
    // Phone display used by the interface stays a presentation concern only.
    expect(shownPhone('+359888123456')).toBe('+359 888 123 456')
  })
})
