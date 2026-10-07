import { devices, expect, test, type Browser, type BrowserContext, type Page, type Route } from '@playwright/test'
import {
  SERVICES_HEADING,
  apiRequest,
  expectBrowserStorageEmpty,
  expectNoHorizontalOverflow,
  expectNoSessionCookie,
  publicSession,
  signIn,
} from './support/browser'
import { API_ORIGIN, requiredEnvironment } from './support/environment'
import { signInAsPlatformAdmin } from './support/provisioning'
import {
  ADDRESS_KEYS,
  BOOKING_ENTRY,
  FAILURE_HEADING,
  FRONTEND_ORIGIN,
  LOADING_TEXT,
  NO_SERVICES_TEXT,
  PROFILE_KEYS,
  SERVICE_KEYS,
  SHELL_DESCRIPTION,
  UNAVAILABLE_HEADING,
  UNAVAILABLE_TITLE,
  asProfile,
  expectLogicalHeadingHierarchy,
  expectNoClippedContent,
  expectWithinViewportWidth,
  getBusiness,
  headMetadata,
  headingLevels,
  openPublicPage,
  provisionBusiness,
  readPublicApi,
  recordApiRequests,
  transitionBusiness,
  updateBusiness,
  type BusinessSpec,
  type ProvisionedBusiness,
} from './support/publicProfile'

// ---------------------------------------------------------------- fixtures

const FULL_DESCRIPTION = 'Уютен фризьорски салон в центъра с над десет години опит.'
const FULL_PHONE = '+359 32 555 018'
const FULL_TEL = 'tel:+35932555018'
const FULL_CONTACT_EMAIL = 'private-contact-full@example.invalid'

const FULL_SPEC: BusinessSpec = {
  name: 'Салон Аврора Публичен',
  slugBase: 'salon-avrora-public',
  businessType: 'HAIR_SALON',
  description: FULL_DESCRIPTION,
  phone: FULL_PHONE,
  contactEmail: FULL_CONTACT_EMAIL,
  address: {
    street: 'Улица Аврора',
    streetNumber: '12',
    postalCode: '1000',
    city: 'София',
    details: 'Вход от двора, етаж 2',
  },
  services: [
    {
      name: 'Дамско подстригване',
      description: 'Измиване, подстригване и оформяне.',
      durationMinutes: 45,
      price: '35.5',
    },
    { name: 'Боядисване на коса', durationMinutes: 120, price: '90' },
    {
      name: 'Маска за коса',
      description: 'Дълбоко подхранваща процедура.',
      durationMinutes: 30,
      price: '12.9',
    },
    {
      name: 'Архивна процедура',
      description: 'Тази услуга вече не се предлага.',
      durationMinutes: 30,
      price: '10',
      active: false,
    },
  ],
  finalState: 'ACTIVE',
}
const FULL_ACTIVE = ['Дамско подстригване', 'Боядисване на коса', 'Маска за коса']
const FULL_INACTIVE = 'Архивна процедура'
const FULL_DISPLAYED: Record<string, { duration: string, price: string, description: string | null }> = {
  'Дамско подстригване': {
    duration: '45 мин.',
    price: '35.50 €',
    description: 'Измиване, подстригване и оформяне.',
  },
  'Боядисване на коса': { duration: '120 мин.', price: '90.00 €', description: null },
  'Маска за коса': {
    duration: '30 мин.',
    price: '12.90 €',
    description: 'Дълбоко подхранваща процедура.',
  },
}

const MINIMAL_SPEC: BusinessSpec = {
  name: 'Бръснарница Минимум',
  slugBase: 'brasnarnitsa-minimum',
  businessType: 'BARBERSHOP',
  services: [{ name: 'Класическо бръснене', durationMinutes: 30, price: '15' }],
  finalState: 'ACTIVE',
}

const EMPTY_SPEC: BusinessSpec = {
  name: 'Студио Без Услуги',
  slugBase: 'studio-bez-uslugi',
  businessType: 'NAIL_STUDIO',
  services: [{ name: 'Скрита услуга', durationMinutes: 40, price: '25', active: false }],
  finalState: 'ACTIVE',
}

const SUSPENDED_SPEC: BusinessSpec = {
  name: 'Масажно Студио Спряно',
  slugBase: 'masazhno-spryano',
  businessType: 'MASSAGE_STUDIO',
  description: 'Спряно студио с поверително описание СУСПЕНДИРАНО-МАРКЕР.',
  phone: '+359 2 987 6543',
  services: [{ name: 'Спрян масаж', durationMinutes: 60, price: '50' }],
  finalState: 'SUSPENDED',
}

const DRAFT_SPEC: BusinessSpec = {
  name: 'Грим Студио Чернова',
  slugBase: 'grim-chernova',
  businessType: 'MAKEUP_STUDIO',
  description: 'Чернова с поверително описание ЧЕРНОВА-МАРКЕР.',
  phone: '+359 2 123 4567',
  finalState: 'DRAFT',
}

const OTHER_SPEC: BusinessSpec = {
  name: 'Козметичен Център Втори',
  slugBase: 'kozmetichen-vtori',
  businessType: 'BEAUTY_STUDIO',
  description: 'Съвсем различен бизнес на друг собственик.',
  phone: '+359 56 700 800',
  contactEmail: 'private-contact-other@example.invalid',
  address: {
    street: 'Булевард Различен',
    streetNumber: '99',
    postalCode: '8000',
    city: 'Бургас',
    details: 'Партер',
  },
  services: [
    {
      name: 'Почистване на лице',
      description: 'Дълбоко почистване.',
      durationMinutes: 75,
      price: '60',
    },
    { name: 'Микродермабразио', durationMinutes: 50, price: '80' },
  ],
  finalState: 'ACTIVE',
}

const LONG_WORD = 'Свръхдългоименнаорганизациязаобслужванеибезпробелиработиотлично'
const LONG_NAME = `${LONG_WORD} ${LONG_WORD} Студио`.slice(0, 200)
const LONG_DESCRIPTION = `${'Подробно описание на студиото и неговите процедури. '.repeat(33)}${LONG_WORD}`
  .slice(0, 1_990)
const LONG_PHONE = '+359 88 123 45 67 вътр. 1234'
const LONG_SERVICES = Array.from({ length: 10 }, (_, index) => ({
  name: `${LONG_WORD}${LONG_WORD.slice(0, 40)} процедура ${index + 1}`.slice(0, 200),
  description: `${'Описание на дългата процедура. '.repeat(20)}${LONG_WORD}${index}`,
  durationMinutes: 480,
  price: '1999.9',
}))
const LONG_SPEC: BusinessSpec = {
  name: LONG_NAME,
  slugBase: 'dalgo-ime-studio',
  businessType: 'OTHER',
  description: LONG_DESCRIPTION,
  phone: LONG_PHONE,
  address: {
    street: `Улица ${LONG_WORD}${LONG_WORD}`.slice(0, 200),
    streetNumber: '12А/кв.345-Б',
    postalCode: '1000',
    city: `Велико Търново ${LONG_WORD}`.slice(0, 100),
    details: `${'Допълнителни указания за намиране на входа. '.repeat(10)}${LONG_WORD}`.slice(0, 500),
  },
  services: LONG_SERVICES,
  finalState: 'ACTIVE',
}

const MUTABLE_SPEC: BusinessSpec = {
  name: 'Салон Промяна Публичен',
  slugBase: 'salon-promyana-public',
  businessType: 'HAIR_SALON',
  description: 'Първоначално описание преди промяната.',
  phone: '+359 32 111 222',
  services: [
    { name: 'Първа услуга', description: 'Първоначално описание на услугата.', durationMinutes: 30, price: '20' },
    { name: 'Втора услуга', durationMinutes: 45, price: '30' },
  ],
  finalState: 'ACTIVE',
}

let full: ProvisionedBusiness
let minimal: ProvisionedBusiness
let empty: ProvisionedBusiness
let suspended: ProvisionedBusiness
let draft: ProvisionedBusiness
let other: ProvisionedBusiness
let long: ProvisionedBusiness
let formerSlug: string
let unknownSlug: string

test.beforeAll(async ({ browser }) => {
  test.setTimeout(420_000)
  const adminContext = await browser.newContext()
  try {
    const admin = await adminContext.newPage()
    await signInAsPlatformAdmin(admin)
    full = await provisionBusiness(browser, adminContext, admin, FULL_SPEC)
    minimal = await provisionBusiness(browser, adminContext, admin, MINIMAL_SPEC)
    empty = await provisionBusiness(browser, adminContext, admin, EMPTY_SPEC)
    suspended = await provisionBusiness(browser, adminContext, admin, SUSPENDED_SPEC)
    other = await provisionBusiness(browser, adminContext, admin, OTHER_SPEC)
    long = await provisionBusiness(browser, adminContext, admin, LONG_SPEC)
    draft = await provisionBusiness(browser, adminContext, admin, DRAFT_SPEC)
    // A slug that once belonged to a Business: a DRAFT slug is editable (ADR-0018), so
    // the old value becomes unknown without violating the immutability of an active slug.
    formerSlug = draft.slug
    const moved = await updateBusiness(admin, draft.id, { slug: `${draft.slug}-moved` })
    draft = { ...draft, slug: moved.slug }
    unknownSlug = `no-such-business-${draft.slug.split('-').slice(-2, -1)[0]}`
  } finally {
    await adminContext.close()
  }
})

// ----------------------------------------------------------------- helpers

async function visitor(
  browser: Browser,
  options: Parameters<Browser['newContext']>[0] = {},
): Promise<{ context: BrowserContext, page: Page }> {
  const context = await browser.newContext(options)
  return { context, page: await context.newPage() }
}

function serviceItem(page: Page, name: string) {
  return page
    .getByRole('listitem')
    .filter({ has: page.getByRole('heading', { level: 3, name, exact: true }) })
}

async function mainText(page: Page): Promise<string> {
  return page.locator('body').innerText()
}

async function failPublicRead(route: Route): Promise<void> {
  await route.fulfill({
    status: 500,
    contentType: 'application/json',
    headers: { 'access-control-allow-origin': FRONTEND_ORIGIN },
    body: '{"code":"INTERNAL_ERROR"}',
  })
}

const PUBLIC_READ_PATTERN = `${API_ORIGIN}/api/public/businesses/*`

// ----------------------------------------------------------------- scenarios

test.describe('Public Business profile: direct access and allowlist', () => {
  test('an unauthenticated visitor reaches the stable URL, reloads and re-opens it without side effects', async ({ browser }) => {
    const { context, page } = await visitor(browser)
    try {
      const requests = recordApiRequests(page)
      await openPublicPage(page, full.slug)
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(full.name)
      expect(new URL(page.url()).pathname).toBe(`/${full.slug}`)

      await page.reload()
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(full.name)
      expect(new URL(page.url()).pathname).toBe(`/${full.slug}`)
      await page.waitForLoadState('networkidle')

      // Browser evidence of "no side effect": the only API traffic is the public GET,
      // sent without a cookie, and no session, cookie or storage state appears. There is
      // no safe public count endpoint, so "no Customer, Membership or Appointment rows"
      // is proven by PublicProfileApiIntegrationTests (the read creates no data), not here.
      expect(requests.map((request) => `${request.method} ${request.path}`)).toEqual([
        `GET /api/public/businesses/${full.slug}`,
        `GET /api/public/businesses/${full.slug}`,
      ])
      expect(requests.every((request) => !request.hasCookie)).toBe(true)
      await expectNoSessionCookie(context)
      expect(await context.cookies()).toHaveLength(0)
      await expectBrowserStorageEmpty(page)
    } finally {
      await context.close()
    }

    const second = await visitor(browser)
    try {
      await openPublicPage(second.page, full.slug)
      await expect(second.page.getByRole('heading', { level: 1 })).toHaveText(full.name)
    } finally {
      await second.context.close()
    }
  })

  test('the page shows exactly the approved public information of the full Business', async ({ browser }) => {
    const { context, page } = await visitor(browser)
    try {
      await openPublicPage(page, full.slug)
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(full.name)
      await expect(page.getByText('Фризьорски салон', { exact: true })).toBeVisible()
      await expect(page.getByText(FULL_DESCRIPTION, { exact: true })).toBeVisible()
      const phone = page.getByRole('link', { name: FULL_PHONE, exact: true })
      await expect(phone).toBeVisible()
      await expect(phone).toHaveAttribute('href', FULL_TEL)
      for (const line of ['Улица Аврора 12', '1000 София', 'Вход от двора, етаж 2']) {
        await expect(page.getByText(line, { exact: true })).toBeVisible()
      }
      await expect(page.getByRole('button', { name: BOOKING_ENTRY, exact: true })).toBeVisible()
      await expect(page.getByRole('heading', { level: 2, name: 'Услуги', exact: true })).toBeVisible()

      for (const name of FULL_ACTIVE) {
        const item = serviceItem(page, name)
        await expect(item).toHaveCount(1)
        const expected = FULL_DISPLAYED[name]!
        await expect(item.getByText(expected.duration, { exact: true })).toBeVisible()
        await expect(item.getByText(expected.price, { exact: true })).toBeVisible()
        if (expected.description) {
          await expect(item.getByText(expected.description, { exact: true })).toBeVisible()
        }
      }
      await expect(page.getByRole('heading', { level: 3 })).toHaveCount(FULL_ACTIVE.length)
      await expect(page.getByText(FULL_INACTIVE)).toHaveCount(0)
      await expect(page.getByText('Тази услуга вече не се предлага.')).toHaveCount(0)

      // The telephone is the only link; the only actions are the booking entries (the general one and one per Service).
      await expect(page.getByRole('link')).toHaveCount(1)
      await expect(page.getByRole('button')).toHaveCount(1 + FULL_ACTIVE.length)
      const text = await mainText(page)
      expect(text).not.toContain(FULL_CONTACT_EMAIL)
      expect(text).not.toContain(full.ownerEmail)
      expect(text).not.toContain(full.id)
      // The Service reference is part of the API contract but is never rendered.
      for (const id of Object.values(full.serviceIds)) expect(text).not.toContain(id)

      // The API response matches the approved allowlist, key for key.
      const api = await readPublicApi(context.request, full.slug)
      const profile = asProfile(api)
      expect(Object.keys(profile).sort()).toEqual(PROFILE_KEYS)
      expect(Object.keys(profile.address ?? {}).sort()).toEqual(ADDRESS_KEYS)
      expect(profile.services).toHaveLength(FULL_ACTIVE.length)
      for (const service of profile.services) {
        expect(Object.keys(service).sort()).toEqual(SERVICE_KEYS)
      }
      // The Service reference is the one identifier (ADR-0026): the ACTIVE Services' own, and never
      // the inactive Service's.
      expect(profile.services.map((service) => service.id).sort())
        .toEqual(FULL_ACTIVE.map((name) => full.serviceIds[name]!).sort())
      expect(profile.slug).toBe(full.slug)
      expect(profile.businessType).toBe('HAIR_SALON')
      expect(profile.services.map((service) => service.name).sort())
        .toEqual([...FULL_ACTIVE].sort())
      // The page lists the Services in the order the contract returns them.
      await expect(page.getByRole('heading', { level: 3 }))
        .toHaveText(profile.services.map((service) => service.name))

      // Nothing outside the allowlist is serialized: no identifier of the Business or its owner,
      // no identifier of an inactive Service, no private contact, no inactive Service, no owner data.
      const forbidden = [
        full.id,
        full.serviceIds[FULL_INACTIVE]!,
        full.ownerEmail,
        FULL_CONTACT_EMAIL,
        FULL_INACTIVE,
        'Тази услуга вече не се предлага.',
        'Europe/Sofia',
      ]
      for (const value of forbidden) expect(api.text).not.toContain(value)
      // Exactly one `"id"` per active Service (its reference) and no other identifier member.
      expect(api.text.split('"id"').length - 1).toBe(FULL_ACTIVE.length)
      for (const key of ['"status"', '"version"', '"createdAt"', '"updatedAt"', '"active"',
        '"contactEmail"', '"timezone"', '"memberships"', '"owner"', '"customer"', '"notes"',
        '"normalizedName"', '"currency"']) {
        expect(api.text).not.toContain(key)
      }
      expect(api.text).not.toMatch(/ACTIVE|DRAFT|SUSPENDED/)
    } finally {
      await context.close()
    }
  })

  test('a minimal Business shows no empty optional containers and one coherent hero', async ({ browser }) => {
    const { context, page } = await visitor(browser)
    try {
      await openPublicPage(page, minimal.slug)
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(minimal.name)
      await expect(page.getByText('Бръснарница', { exact: true })).toBeVisible()
      expect(await headingLevels(page)).toEqual([1, 2, 3])
      // Only the Services list exists: no contacts list, telephone, address or description.
      await expect(page.getByRole('list')).toHaveCount(1)
      await expect(page.getByRole('link')).toHaveCount(0)
      await expect(page.getByText('Телефон')).toHaveCount(0)
      await expect(page.getByText('Адрес')).toHaveCount(0)
      await expect(page.locator('.public-description')).toHaveCount(0)
      expect(await mainText(page)).not.toMatch(/публичната страница/)
      await expect(page.getByRole('button', { name: BOOKING_ENTRY, exact: true })).toBeVisible()
      await expect(serviceItem(page, 'Класическо бръснене').getByText('15.00 €')).toBeVisible()

      const profile = asProfile(await readPublicApi(context.request, minimal.slug))
      expect(profile.description).toBeNull()
      expect(profile.phone).toBeNull()
      expect(profile.address).toBeNull()
      expect(profile.services[0]?.description).toBeNull()
    } finally {
      await context.close()
    }
  })

  test('an active Business without active Services shows the approved empty state, not an error', async ({ browser }) => {
    const { context, page } = await visitor(browser)
    try {
      await openPublicPage(page, empty.slug)
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(empty.name)
      await expect(page.getByText(NO_SERVICES_TEXT, { exact: true })).toBeVisible()
      await expect(page.getByRole('alert')).toHaveCount(0)
      await expect(page.getByRole('heading', { level: 3 })).toHaveCount(0)
      await expect(page.getByRole('listitem')).toHaveCount(0)
      await expect(page.getByRole('button')).toHaveCount(0)
      await expect(page.getByRole('link')).toHaveCount(0)
      await expect(page.getByText('Скрита услуга')).toHaveCount(0)
      // Nothing to book: no booking entry and no booking statement.
      await expect(page.getByRole('button', { name: BOOKING_ENTRY })).toHaveCount(0)
      const profile = asProfile(await readPublicApi(context.request, empty.slug))
      expect(profile.services).toEqual([])
    } finally {
      await context.close()
    }
  })
})

test.describe('Public Business profile: lifecycle and isolation', () => {
  test('DRAFT, SUSPENDED, unknown and former slugs share one safe unavailable presentation', async ({ browser }) => {
    const slugs = {
      draft: draft.slug,
      former: formerSlug,
      suspended: suspended.slug,
      unknown: unknownSlug,
      reservedWord: `salon-invitation-${unknownSlug.slice(-8)}`,
    }
    const { context, page } = await visitor(browser)
    try {
      const snapshots: Record<string, unknown> = {}
      for (const [label, slug] of Object.entries(slugs)) {
        await page.goto(`/${slug}`)
        await expect(page.getByRole('heading', { level: 1, name: UNAVAILABLE_HEADING, exact: true }))
          .toBeVisible()
        const text = await mainText(page)
        for (const secret of [
          draft.name,
          suspended.name,
          'ЧЕРНОВА-МАРКЕР',
          'СУСПЕНДИРАНО-МАРКЕР',
          'Спрян масаж',
          slug,
          ...Object.values(slugs),
        ]) {
          expect(text).not.toContain(secret)
        }
        await expect(page.getByRole('link')).toHaveCount(0)
        await expect(page.getByRole('button')).toHaveCount(0)
        expect(text).not.toMatch(/запазване|резервиране|Услуги/)
        const metadata = await headMetadata(page)
        expect(metadata.robots).toBe('noindex')
        expect(metadata.canonical).toBeNull()
        snapshots[label] = {
          metadata,
          markup: await page.locator('main').innerHTML(),
        }
      }
      const reference = snapshots.unknown
      for (const snapshot of Object.values(snapshots)) expect(snapshot).toEqual(reference)
      expect((reference as { metadata: { title: string } }).metadata.title).toBe(UNAVAILABLE_TITLE)

      // The backend answers the same body for every case; nothing reveals which one it was.
      const bodies = new Set<string>()
      for (const slug of Object.values(slugs)) {
        const result = await readPublicApi(context.request, slug)
        expect(result.status).toBe(404)
        expect((result.body as { code: string }).code).toBe('BUSINESS_PAGE_UNAVAILABLE')
        bodies.add(result.text)
      }
      expect(bodies.size).toBe(1)
    } finally {
      await context.close()
    }
  })

  test('two Businesses never show each other\'s data, even to authenticated callers', async ({ browser }) => {
    const { context, page } = await visitor(browser)
    try {
      const foreignStrings = (own: ProvisionedBusiness, foreign: ProvisionedBusiness) =>
        own === full
          ? [other.name, '+359 56 700 800', 'Булевард Различен', 'Бургас', 'Почистване на лице',
            'Микродермабразио', 'Съвсем различен бизнес', other.id, other.ownerEmail]
          : [full.name, FULL_PHONE, 'Улица Аврора', 'София', ...FULL_ACTIVE, FULL_DESCRIPTION,
            foreign.id, foreign.ownerEmail]
      for (const own of [full, other]) {
        await openPublicPage(page, own.slug)
        await expect(page.getByRole('heading', { level: 1 })).toHaveText(own.name)
        const text = await mainText(page)
        const api = await readPublicApi(context.request, own.slug)
        for (const value of foreignStrings(own, own === full ? other : full)) {
          expect(text).not.toContain(value)
          expect(api.text).not.toContain(value)
        }
      }
      await openPublicPage(page, other.slug)
      await expect(page.getByRole('heading', { level: 3 })).toHaveText(['Микродермабразио', 'Почистване на лице'])

      // Cross-Business identifiers through supported public requests yield only safe results.
      const unknown = await readPublicApi(context.request, unknownSlug)
      const byIdentifier = await readPublicApi(context.request, other.id)
      expect([byIdentifier.status, byIdentifier.text]).toEqual([404, unknown.text])
      const withForeignId = await readPublicApi(
        context.request,
        `${full.slug}?businessId=${other.id}&slug=${other.slug}`,
      )
      expect(asProfile(withForeignId).displayName).toBe(full.name)
      expect(withForeignId.text).not.toContain(other.name)
      const deeper = await readPublicApi(context.request, `${full.slug}/services`)
      expect(deeper.status).toBe(401)
      expect(deeper.text).not.toContain(full.name)
      await page.goto(`/${other.id}`)
      await expect(page.getByRole('heading', { level: 1, name: UNAVAILABLE_HEADING, exact: true }))
        .toBeVisible()
      await expect(page.getByText(other.name)).toHaveCount(0)
    } finally {
      await context.close()
    }

    // An authenticated owner or administrator receives byte-identical public responses.
    const anonymousContext = await browser.newContext()
    const ownerContext = await browser.newContext()
    const adminContext = await browser.newContext()
    try {
      const ownerPage = await ownerContext.newPage()
      await signIn(ownerPage, other.ownerEmail, requiredEnvironment('E2E_OWNER_PASSWORD'), SERVICES_HEADING)
      const adminPage = await adminContext.newPage()
      await signInAsPlatformAdmin(adminPage)
      for (const slug of [full.slug, other.slug, draft.slug]) {
        const anonymous = await readPublicApi(anonymousContext.request, slug)
        expect((await readPublicApi(ownerContext.request, slug)).text).toBe(anonymous.text)
        expect((await readPublicApi(adminContext.request, slug)).text).toBe(anonymous.text)
      }
    } finally {
      await anonymousContext.close()
      await ownerContext.close()
      await adminContext.close()
    }
  })
})

test.describe('Public Business profile: owner and administrator changes', () => {
  test('saved changes appear publicly only after the save, and a deactivated Service disappears', async ({ browser }) => {
    test.setTimeout(240_000)
    const adminContext = await browser.newContext()
    const guest = await visitor(browser)
    let mutable: ProvisionedBusiness | null = null
    let admin: Page | null = null
    try {
      admin = await adminContext.newPage()
      await signInAsPlatformAdmin(admin)
      mutable = await provisionBusiness(browser, adminContext, admin, MUTABLE_SPEC, { keepOwner: true })
      const owner = mutable.owner!.page
      const publicPage = guest.page
      await openPublicPage(publicPage, mutable.slug)
      await expect(publicPage.getByText('Първоначално описание преди промяната.', { exact: true })).toBeVisible()

      // Business profile fields are edited only by the Platform Administrator (ADR-0017).
      const NEW_DESCRIPTION = 'Обновено описание след успешно запазване.'
      await admin.goto(`/#/platform/businesses/${mutable.id}`)
      await expect(admin.getByRole('heading', { name: mutable.name, exact: true })).toBeVisible()
      await admin.getByText('Данни за бизнеса', { exact: true }).click()
      await admin.getByRole('button', { name: 'Редактирай' }).click()
      await admin.getByLabel('Описание (по избор)').fill(NEW_DESCRIPTION)
      await publicPage.reload()
      await expect(publicPage.getByText('Първоначално описание преди промяната.', { exact: true })).toBeVisible()
      await expect(publicPage.getByText(NEW_DESCRIPTION)).toHaveCount(0)

      const saved = admin.waitForResponse((response) =>
        response.url() === `${API_ORIGIN}/api/platform/businesses/${mutable!.id}`
          && response.request().method() === 'PUT',
      )
      await admin.getByRole('button', { name: 'Запази промените' }).click()
      expect((await saved).status()).toBe(200)
      await publicPage.reload()
      await expect(publicPage.getByText(NEW_DESCRIPTION, { exact: true })).toBeVisible()
      await expect(publicPage.getByText('Първоначално описание преди промяната.')).toHaveCount(0)

      // The owner edits an active Service through the owner UI.
      await owner.reload()
      await owner
        .getByRole('row')
        .filter({ hasText: 'Първа услуга' })
        .getByRole('link', { name: 'Отвори Първа услуга' })
        .click()
      await owner.getByRole('button', { name: 'Редактирай' }).click()
      await owner.getByLabel('Описание (по избор)').fill('Обновено описание на услугата.')
      await owner.getByLabel('Продължителност (минути)').fill('50')
      await owner.getByLabel('Цена (EUR)').fill('27.5')
      await publicPage.reload()
      await expect(serviceItem(publicPage, 'Първа услуга').getByText('20.00 €')).toBeVisible()
      await owner.getByRole('button', { name: 'Запази промените' }).click()
      await expect(owner.getByRole('status').filter({ hasText: 'Промените са запазени.' })).toBeVisible()
      await publicPage.reload()
      const edited = serviceItem(publicPage, 'Първа услуга')
      await expect(edited.getByText('27.50 €', { exact: true })).toBeVisible()
      await expect(edited.getByText('50 мин.', { exact: true })).toBeVisible()
      await expect(edited.getByText('Обновено описание на услугата.', { exact: true })).toBeVisible()

      // Deactivating a Service removes only that Service from the public page.
      await owner.getByRole('button', { name: 'Деактивирай' }).click()
      await owner.getByRole('button', { name: 'Потвърди деактивирането' }).click()
      await expect(owner.getByRole('status').filter({ hasText: 'Услугата е деактивирана.' })).toBeVisible()
      await publicPage.reload()
      await expect(publicPage.getByRole('heading', { level: 3 })).toHaveText(['Втора услуга'])
      await expect(publicPage.getByText('Първа услуга')).toHaveCount(0)
    } finally {
      // Restore what this test changed; the Business itself belongs to this test alone.
      if (mutable?.owner && admin) {
        try {
          const serviceId = mutable.serviceIds['Първа услуга']!
          const current = await apiRequest(mutable.owner.page, 'GET', `/api/business/services/${serviceId}`)
          const { version, active } = JSON.parse(current.text) as { version: number, active: boolean }
          if (!active) {
            await apiRequest(mutable.owner.page, 'POST', `/api/business/services/${serviceId}/reactivate`, {
              expectedVersion: version,
            })
          }
          await updateBusiness(admin, mutable.id, { description: MUTABLE_SPEC.description ?? null })
        } catch {
          // Best effort: the disposable environment is removed after the run.
        }
      }
      await mutable?.owner?.context.close()
      await guest.context.close()
      await adminContext.close()
    }
  })

  test('a fixture lifecycle change is visible publicly and restored by the test that made it', async ({ browser }) => {
    test.setTimeout(120_000)
    const adminContext = await browser.newContext()
    const guest = await visitor(browser)
    try {
      const admin = await adminContext.newPage()
      await signInAsPlatformAdmin(admin)
      const own = await provisionBusiness(browser, adminContext, admin, {
        ...MINIMAL_SPEC,
        slugBase: 'brasnarnitsa-zhiznen-tsikal',
        name: 'Бръснарница Жизнен Цикъл',
      })
      try {
        await openPublicPage(guest.page, own.slug)
        await expect(guest.page.getByRole('heading', { level: 1 })).toHaveText(own.name)
        await transitionBusiness(admin, own.id, 'suspend')
        await guest.page.reload()
        await expect(guest.page.getByRole('heading', { level: 1, name: UNAVAILABLE_HEADING })).toBeVisible()
        await expect(guest.page.getByText(own.name)).toHaveCount(0)
      } finally {
        if ((await getBusiness(admin, own.id)).status === 'SUSPENDED') {
          await transitionBusiness(admin, own.id, 'reactivate')
        }
      }
      await guest.page.reload()
      await expect(guest.page.getByRole('heading', { level: 1 })).toHaveText(own.name)
    } finally {
      await guest.context.close()
      await adminContext.close()
    }
  })
})

test.describe('Public Business profile: routing', () => {
  test('uppercase and trailing-slash URLs canonicalize by history replacement', async ({ browser }) => {
    const { context, page } = await visitor(browser)
    try {
      const canonical = await context.newPage()
      await canonical.goto(`/${full.slug}`)
      const baselineLength = await canonical.evaluate(() => history.length)

      const requests = recordApiRequests(page)
      await page.goto(`/${full.slug.toUpperCase()}/?ref=card#top`)
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(full.name)
      const url = new URL(page.url())
      expect(url.pathname).toBe(`/${full.slug}`)
      expect(url.search).toBe('?ref=card')
      expect(url.hash).toBe('#top')
      // Replacement, not an extra step: the history is as long as for the canonical URL,
      // and Back leaves the page instead of returning to a non-canonical spelling.
      expect(await page.evaluate(() => history.length)).toBe(baselineLength)
      expect(requests.map((request) => request.path)).toEqual([`/api/public/businesses/${full.slug}`])
      await page.goBack()
      expect(page.url()).toBe('about:blank')

      const trailing = await context.newPage()
      await trailing.goto(`/${full.slug}/`)
      await expect(trailing.getByRole('heading', { level: 1 })).toHaveText(full.name)
      expect(new URL(trailing.url()).pathname).toBe(`/${full.slug}`)
      expect(await trailing.evaluate(() => history.length)).toBe(baselineLength)
    } finally {
      await context.close()
    }
  })

  test('Back and Forward move sensibly between Businesses and the administration application', async ({ browser }) => {
    const { context, page } = await visitor(browser)
    try {
      await openPublicPage(page, full.slug)
      await openPublicPage(page, other.slug)
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(other.name)
      await page.goBack()
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(full.name)
      expect(new URL(page.url()).pathname).toBe(`/${full.slug}`)
      await page.goForward()
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(other.name)

      // Crossing into the administration application is a full load and returns cleanly.
      await page.goto('/')
      await expect(page.getByRole('heading', { name: 'Вход' })).toBeVisible()
      await page.goBack()
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(other.name)
      await page.goForward()
      await expect(page.getByRole('heading', { name: 'Вход' })).toBeVisible()
    } finally {
      await context.close()
    }
  })

  test('deeper paths, reserved roots and hash routes stay with the authenticated application', async ({ browser }) => {
    const { context, page } = await visitor(browser)
    try {
      const requests = recordApiRequests(page)
      const authenticatedPaths = [
        `/${full.slug}/book`,
        '/login',
        '/business',
        '/platform',
        '/profile',
        '/booking',
        '/#/business/services',
        '/#/platform/businesses',
      ]
      for (const path of authenticatedPaths) {
        await page.goto(path)
        await expect(page.getByRole('heading', { name: 'Вход' })).toBeVisible()
        await expect(page.getByText(full.name)).toHaveCount(0)
        await expect(page.getByRole('heading', { name: UNAVAILABLE_HEADING })).toHaveCount(0)
      }
      // None of them asked the public endpoint for a Business.
      expect(requests.filter((request) => request.path.startsWith('/api/public/'))).toEqual([])
      await expectNoSessionCookie(context)

      // A slug that merely contains a reserved word is a public Business route.
      await page.goto(`/salon-invitation-${unknownSlug.slice(-8)}`)
      await expect(page.getByRole('heading', { level: 1, name: UNAVAILABLE_HEADING })).toBeVisible()
    } finally {
      await context.close()
    }
  })
})

test.describe('Public Business profile: metadata', () => {
  test('an available page sets its title, canonical URL and description; defaults never leak', async ({ browser }) => {
    const { context, page } = await visitor(browser)
    try {
      await openPublicPage(page, full.slug)
      expect(await headMetadata(page)).toEqual({
        title: `${full.name} – SpotYourSlot`,
        description: FULL_DESCRIPTION,
        canonical: `${FRONTEND_ORIGIN}/${full.slug}`,
        robots: null,
      })

      // The committed fallback when no description exists.
      await openPublicPage(page, minimal.slug)
      expect(await headMetadata(page)).toEqual({
        title: `${minimal.name} – SpotYourSlot`,
        description: `Информация и услуги на ${minimal.name}.`,
        canonical: `${FRONTEND_ORIGIN}/${minimal.slug}`,
        robots: null,
      })

      // A long description is cut at a word boundary.
      await openPublicPage(page, long.slug)
      const metadata = await headMetadata(page)
      expect(metadata.title).toBe(`${LONG_NAME} – SpotYourSlot`)
      expect(Array.from(metadata.description ?? '').length).toBeLessThanOrEqual(160)
      expect(metadata.description?.endsWith('…')).toBe(true)

      // An uppercase request still publishes the canonical lowercase URL.
      await openPublicPage(page, full.slug.toUpperCase())
      expect((await headMetadata(page)).canonical).toBe(`${FRONTEND_ORIGIN}/${full.slug}`)
    } finally {
      await context.close()
    }
  })

  test('metadata never leaks between Businesses and unavailable states in one page', async ({ browser }) => {
    const { context, page } = await visitor(browser)
    try {
      await openPublicPage(page, full.slug)
      // Build one history of three entries, then traverse it without reloading the document.
      await page.evaluate(
        ([second, third]) => {
          history.pushState({}, '', `/${second}`)
          history.pushState({}, '', `/${third}`)
        },
        [other.slug, draft.slug],
      )
      const expectBusiness = async (business: ProvisionedBusiness) => {
        await expect(page.getByRole('heading', { level: 1 })).toHaveText(business.name)
        const metadata = await headMetadata(page)
        expect(metadata.title).toBe(`${business.name} – SpotYourSlot`)
        expect(metadata.canonical).toBe(`${FRONTEND_ORIGIN}/${business.slug}`)
        expect(metadata.robots).toBeNull()
      }
      const expectUnavailable = async () => {
        await expect(page.getByRole('heading', { level: 1, name: UNAVAILABLE_HEADING })).toBeVisible()
        expect(await headMetadata(page)).toEqual({
          title: UNAVAILABLE_TITLE,
          description: SHELL_DESCRIPTION,
          canonical: null,
          robots: 'noindex',
        })
      }
      await page.goBack()
      await expectBusiness(other)
      await page.goBack()
      await expectBusiness(full)
      await page.goForward()
      await expectBusiness(other)
      await page.goForward()
      await expectUnavailable()
      await page.goBack()
      await expectBusiness(other)
      await page.goForward()
      await expectUnavailable()
    } finally {
      await context.close()
    }
  })
})

test.describe('Public Business profile: failure and retry', () => {
  test('a failed read shows safe copy and each keyboard activation sends exactly one retry', async ({ browser }) => {
    const { context, page } = await visitor(browser)
    try {
      // The development server renders under StrictMode, which may send the very first read
      // twice (the first is aborted by the page). Retries are therefore measured as a delta
      // from the settled state: one activation must add exactly one read.
      let phase: 'abort' | 'error' | 'pass' = 'abort'
      let reads = 0
      await page.route(PUBLIC_READ_PATTERN, async (route) => {
        reads += 1
        if (phase === 'abort') await route.abort('failed')
        else if (phase === 'error') await failPublicRead(route)
        else await route.continue()
      })
      await page.goto(`/${full.slug}`)
      const failure = page.getByRole('heading', { level: 1, name: FAILURE_HEADING, exact: true })
      await expect(failure).toBeVisible()
      await expect(failure).toBeFocused()
      await expect(page.getByText('Проверете връзката си и опитайте отново.')).toBeVisible()
      expect(await mainText(page)).not.toMatch(/INTERNAL_ERROR|500|failed/)
      expect(await headMetadata(page)).toMatchObject({ title: UNAVAILABLE_TITLE, robots: 'noindex', canonical: null })
      await page.waitForLoadState('networkidle')

      const retry = page.getByRole('button', { name: 'Опитайте отново' })
      const afterLoad = reads
      phase = 'error'
      await page.keyboard.press('Tab')
      await expect(retry).toBeFocused()
      await page.keyboard.press('Enter')
      // The retry fails again with a server error and shows the same safe copy.
      await expect.poll(() => reads).toBe(afterLoad + 1)
      await expect(failure).toBeFocused()
      await page.waitForLoadState('networkidle')
      expect(reads).toBe(afterLoad + 1)

      phase = 'pass'
      await page.keyboard.press('Tab')
      await expect(retry).toBeFocused()
      await page.keyboard.press('Space')
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(full.name)
      await page.waitForLoadState('networkidle')
      expect(reads).toBe(afterLoad + 2)
      await expect(page.getByRole('button', { name: 'Опитайте отново' })).toHaveCount(0)
      expect((await headMetadata(page)).robots).toBeNull()
    } finally {
      await context.close()
    }
  })

  test('nothing of a previously viewed Business shows while another loads or fails', async ({ browser }) => {
    const { context, page } = await visitor(browser)
    try {
      let mode: 'pass' | 'hold' = 'pass'
      let release: () => void = () => undefined
      const held = new Promise<void>((resolve) => {
        release = resolve
      })
      await page.route(PUBLIC_READ_PATTERN, async (route) => {
        if (mode === 'hold' && route.request().url().endsWith(`/${other.slug}`)) {
          await held
          await failPublicRead(route)
          return
        }
        await route.continue()
      })

      await openPublicPage(page, full.slug)
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(full.name)
      await page.evaluate((slug) => history.pushState({}, '', `/${slug}`), other.slug)
      await page.goBack()
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(full.name)
      mode = 'hold'
      await page.goForward()

      // Loading: no heading, no Business A text, the shell metadata only.
      await expect(page.getByRole('status')).toHaveText(LOADING_TEXT)
      await expect(page.getByRole('heading')).toHaveCount(0)
      expect(await mainText(page)).not.toContain(full.name)
      expect(await mainText(page)).not.toContain(FULL_PHONE)
      expect((await headMetadata(page)).title).not.toContain(full.name)

      release()
      await expect(page.getByRole('heading', { level: 1, name: FAILURE_HEADING })).toBeVisible()
      expect(await mainText(page)).not.toContain(full.name)
      expect((await headMetadata(page)).canonical).toBeNull()

      mode = 'pass'
      await page.keyboard.press('Tab')
      await page.keyboard.press('Enter')
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(other.name)
      expect(await mainText(page)).not.toContain(full.name)
    } finally {
      await context.close()
    }
  })
})

type ViewportCase = {
  label: string
  options: Parameters<Browser['newContext']>[0]
}

const VIEWPORT_CASES: ViewportCase[] = [
  { label: 'desktop 1280px', options: { viewport: { width: 1280, height: 800 } } },
  { label: 'Pixel 7', options: { ...devices['Pixel 7'] } },
  { label: '200% zoom equivalent (640px)', options: { viewport: { width: 640, height: 400 } } },
]

test.describe('Public Business profile: responsive and accessibility smoke', () => {
  for (const viewportCase of VIEWPORT_CASES) {
    test(`${viewportCase.label}: layout, headings and keyboard`, async ({ browser }) => {
      test.setTimeout(90_000)
      const { context, page } = await visitor(browser, viewportCase.options)
      try {
        for (const business of [full, long, minimal, empty]) {
          await openPublicPage(page, business.slug)
          await expect(page.getByRole('heading', { level: 1 })).toHaveText(business.name)
          expectLogicalHeadingHierarchy(await headingLevels(page))
          await expectNoHorizontalOverflow(page)
          await expectWithinViewportWidth(page, 'main h1')
          await expectNoClippedContent(page, '.public-hero, .public-booking, .public-service')
        }

        for (const business of [full, long]) {
          await openPublicPage(page, business.slug)
          await expectWithinViewportWidth(page, '.public-phone')
          await expectWithinViewportWidth(page, '.public-address')
          await expectWithinViewportWidth(page, '.public-service-facts dd')
          await expectWithinViewportWidth(page, '.public-service h3')
          await expectNoClippedContent(page, '.public-phone, .public-address, .public-service-facts dd')

          // Real keyboard: the first Tab stop is the telephone link, with a visible focus ring.
          await page.keyboard.press('Tab')
          const phone = page.locator('.public-phone')
          await expect(phone).toBeFocused()
          const ring = await phone.evaluate((element) => {
            const style = getComputedStyle(element)
            return { style: style.outlineStyle, width: parseFloat(style.outlineWidth) }
          })
          expect(ring.style).not.toBe('none')
          expect(ring.width).toBeGreaterThanOrEqual(2)
          await expect(page.getByRole('link')).toHaveCount(1)
        }

        // Meaning never depends on colour alone: every fact carries a text label.
        await openPublicPage(page, full.slug)
        for (const name of FULL_ACTIVE) {
          const item = serviceItem(page, name)
          await expect(item.getByText('Продължителност', { exact: true })).toBeVisible()
          await expect(item.getByText('Цена', { exact: true })).toBeVisible()
        }
        await expect(page.getByRole('button', { name: BOOKING_ENTRY, exact: true })).toBeVisible()

        // The long fixture shows every one of its ten Services.
        await openPublicPage(page, long.slug)
        await expect(page.getByRole('heading', { level: 3 })).toHaveCount(LONG_SERVICES.length)

        // Unavailable state.
        await openPublicPage(page, unknownSlug)
        await expect(page.getByRole('heading', { level: 1, name: UNAVAILABLE_HEADING })).toBeVisible()
        await expectNoHorizontalOverflow(page)

        // Failure state: the retry control is reachable by keyboard and inside the viewport.
        await page.route(PUBLIC_READ_PATTERN, failPublicRead)
        await page.goto(`/${long.slug}`)
        await expect(page.getByRole('heading', { level: 1, name: FAILURE_HEADING })).toBeVisible()
        await page.keyboard.press('Tab')
        const retry = page.getByRole('button', { name: 'Опитайте отново' })
        await expect(retry).toBeFocused()
        await expectWithinViewportWidth(page, 'button')
        await expectNoHorizontalOverflow(page)
        const failureRing = await retry.evaluate((element) => parseFloat(getComputedStyle(element).outlineWidth))
        expect(failureRing).toBeGreaterThanOrEqual(2)
      } finally {
        await context.close()
      }
    })
  }
})

test.describe('Administration regression boundary', () => {
  test('administration stays reachable, the retired form notes stay absent and public visits create no session', async ({ browser }) => {
    test.setTimeout(120_000)
    const retired = [
      'Телефонът се показва на публичната страница на бизнеса само ако е попълнен.',
      'Адресът се показва на публичната страница на бизнеса само ако е попълнено поне едно от адресните полета.',
    ]
    const adminContext = await browser.newContext()
    const ownerContext = await browser.newContext()
    try {
      const admin = await adminContext.newPage()
      await signInAsPlatformAdmin(admin)
      await admin.getByRole('link', { name: 'Бизнеси' }).click()
      await expect(admin.getByRole('heading', { name: 'Бизнеси' })).toBeVisible()
      await admin.getByRole('button', { name: 'Нов бизнес' }).click()
      await expect(admin.getByRole('heading', { name: 'Нов бизнес' })).toBeVisible()
      for (const sentence of retired) await expect(admin.getByText(sentence)).toHaveCount(0)
      await expect(admin.getByText(/публичната страница/)).toHaveCount(0)

      await admin.goto(`/#/platform/businesses/${full.id}`)
      await expect(admin.getByRole('heading', { name: full.name, exact: true })).toBeVisible()
      await admin.getByText('Данни за бизнеса', { exact: true }).click()
      await admin.getByRole('button', { name: 'Редактирай' }).click()
      await expect(admin.getByLabel('Телефон (по избор)')).toHaveValue(FULL_PHONE)
      for (const sentence of retired) await expect(admin.getByText(sentence)).toHaveCount(0)
      await expect(admin.getByText(/публичната страница/)).toHaveCount(0)

      const owner = await ownerContext.newPage()
      await signIn(owner, full.ownerEmail, requiredEnvironment('E2E_OWNER_PASSWORD'), SERVICES_HEADING)
      const navigation = owner.getByRole('navigation', { name: 'Навигация на бизнеса' })
      for (const name of ['Услуги', 'Екип', 'Работно време', 'Профил']) {
        await expect(navigation.getByRole('link', { name })).toBeVisible()
      }

      // Visiting the public page in the signed-in context does not alter the session or
      // the hash-route behaviour of the authenticated application.
      const ownerRequests = recordApiRequests(owner)
      await openPublicPage(owner, full.slug)
      await expect(owner.getByRole('heading', { level: 1 })).toHaveText(full.name)
      await owner.waitForLoadState('networkidle')
      expect(ownerRequests.map((request) => request.path)).toEqual([`/api/public/businesses/${full.slug}`])
      expect(ownerRequests.every((request) => !request.hasCookie)).toBe(true)
      await owner.goto('/#/business/services')
      await expect(owner.getByRole('heading', SERVICES_HEADING)).toBeVisible()
      expect((await publicSession(owner)).body?.email).toBe(full.ownerEmail)
    } finally {
      await adminContext.close()
      await ownerContext.close()
    }

    // A fresh visitor of the public page never gets a session or any browser state.
    const guest = await visitor(browser)
    try {
      const requests = recordApiRequests(guest.page)
      await openPublicPage(guest.page, full.slug)
      await guest.page.waitForLoadState('networkidle')
      expect(requests.some((request) => request.path.startsWith('/api/auth/'))).toBe(false)
      expect(await guest.context.cookies()).toHaveLength(0)
      await expectBrowserStorageEmpty(guest.page)
      await guest.page.goto('/#/business/services')
      await expect(guest.page.getByRole('heading', { name: 'Вход' })).toBeVisible()
    } finally {
      await guest.context.close()
    }
  })
})
