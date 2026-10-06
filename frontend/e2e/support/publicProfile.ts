import { expect, type APIRequestContext, type Request, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { apiRequest } from './browser'
import { API_ORIGIN, requiredEnvironment } from './environment'
import {
  onboardOwner,
  uniqueBusinessFixture,
  type BusinessFixture,
  type OwnerSession,
} from './provisioning'

export const FRONTEND_ORIGIN = `http://localhost:${requiredEnvironment('E2E_FRONTEND_PORT')}`

export type ServiceSeed = {
  name: string
  description?: string
  durationMinutes: number
  price: string
  active?: boolean
}

export type AddressSeed = {
  street?: string
  streetNumber?: string
  postalCode?: string
  city?: string
  details?: string
}

export type BusinessSpec = {
  name: string
  slugBase: string
  businessType: string
  description?: string
  phone?: string
  contactEmail?: string
  address?: AddressSeed
  services?: ServiceSeed[]
  // The lifecycle state the fixture ends in. A DRAFT Business has no owner.
  finalState: 'DRAFT' | 'ACTIVE' | 'SUSPENDED'
}

export type ProvisionedBusiness = {
  id: string
  slug: string
  name: string
  ownerEmail: string
  contactEmail: string | null
  serviceIds: Record<string, string>
  // Present only when the caller asked to keep the owner session open.
  owner: OwnerSession | null
}

type BusinessBody = {
  slug: string
  displayName: string
  businessType: string
  timezone: string
  description: string | null
  city: string | null
  postalCode: string | null
  street: string | null
  streetNumber: string | null
  addressDetails: string | null
  phone: string | null
  contactEmail: string | null
}

export type BusinessResponse = BusinessBody & { id: string, version: number, status: string }

export function bodyFor(spec: BusinessSpec, slug: string): BusinessBody {
  return {
    slug,
    displayName: spec.name,
    businessType: spec.businessType,
    timezone: 'Europe/Sofia',
    description: spec.description ?? null,
    city: spec.address?.city ?? null,
    postalCode: spec.address?.postalCode ?? null,
    street: spec.address?.street ?? null,
    streetNumber: spec.address?.streetNumber ?? null,
    addressDetails: spec.address?.details ?? null,
    phone: spec.phone ?? null,
    contactEmail: spec.contactEmail ?? null,
  }
}

function parse<T>(text: string): T {
  return JSON.parse(text) as T
}

export async function getBusiness(admin: Page, id: string): Promise<BusinessResponse> {
  const result = await apiRequest(admin, 'GET', `/api/platform/businesses/${id}`)
  expect(result.status, result.code ?? 'business read').toBe(200)
  return parse<BusinessResponse>(result.text)
}

export async function updateBusiness(
  admin: Page,
  id: string,
  changes: Partial<BusinessBody>,
): Promise<BusinessResponse> {
  const current = await getBusiness(admin, id)
  const result = await apiRequest(admin, 'PUT', `/api/platform/businesses/${id}`, {
    slug: current.slug,
    displayName: current.displayName,
    businessType: current.businessType,
    timezone: current.timezone,
    description: current.description,
    city: current.city,
    postalCode: current.postalCode,
    street: current.street,
    streetNumber: current.streetNumber,
    addressDetails: current.addressDetails,
    phone: current.phone,
    contactEmail: current.contactEmail,
    ...changes,
    expectedVersion: current.version,
  })
  expect(result.status, result.code ?? 'business update').toBe(200)
  return parse<BusinessResponse>(result.text)
}

export async function transitionBusiness(
  admin: Page,
  id: string,
  action: 'activate' | 'suspend' | 'reactivate',
): Promise<void> {
  const { version } = await getBusiness(admin, id)
  const result = await apiRequest(admin, 'POST', `/api/platform/businesses/${id}/${action}`, {
    expectedVersion: version,
  })
  expect(result.status, result.code ?? action).toBe(200)
}

export async function inviteOwnerThroughApi(admin: Page, id: string, email: string): Promise<void> {
  const result = await apiRequest(
    admin,
    'POST',
    `/api/platform/identity/businesses/${id}/owner-invitation`,
    { email },
  )
  expect(result.status, result.code ?? 'owner invitation').toBe(202)
}

type ServiceResponse = { id: string, version: number }

export async function createService(owner: Page, seed: ServiceSeed): Promise<ServiceResponse> {
  const created = await apiRequest(owner, 'POST', '/api/business/services', {
    name: seed.name,
    description: seed.description ?? null,
    durationMinutes: seed.durationMinutes,
    price: seed.price,
  })
  expect(created.status, created.code ?? 'service create').toBe(201)
  const service = parse<ServiceResponse>(created.text)
  if (seed.active === false) {
    const deactivated = await apiRequest(
      owner,
      'POST',
      `/api/business/services/${service.id}/deactivate`,
      { expectedVersion: service.version },
    )
    expect(deactivated.status, deactivated.code ?? 'service deactivate').toBe(200)
  }
  return service
}

/**
 * Provisions one Business through supported APIs and the invitation UI: the
 * platform administrator creates it, the owner accepts the invitation in an
 * isolated context, creates the Services, and the administrator moves the
 * Business to its final lifecycle state. Slug and owner email carry a random
 * suffix, so a restarted worker never collides with earlier data.
 */
export async function provisionBusiness(
  browser: Browser,
  adminContext: BrowserContext,
  admin: Page,
  spec: BusinessSpec,
  options: { keepOwner?: boolean } = {},
): Promise<ProvisionedBusiness> {
  const fixture: BusinessFixture = uniqueBusinessFixture({
    name: spec.name,
    slug: spec.slugBase,
    ownerEmail: `owner-${spec.slugBase}@example.invalid`,
    ownerFirstName: 'Собственик',
    ownerLastName: 'Тестов',
  })
  const created = await apiRequest(
    admin,
    'POST',
    '/api/platform/businesses',
    bodyFor(spec, fixture.slug),
  )
  expect(created.status, created.code ?? 'business create').toBe(201)
  const business = parse<BusinessResponse>(created.text)
  const serviceIds: Record<string, string> = {}
  let owner: OwnerSession | null = null

  if (spec.finalState !== 'DRAFT') {
    await inviteOwnerThroughApi(admin, business.id, fixture.ownerEmail)
    owner = await onboardOwner(browser, adminContext, fixture)
  }
  try {
    for (const seed of spec.services ?? []) {
      if (!owner) throw new Error('A Business without an owner cannot hold Services')
      serviceIds[seed.name] = (await createService(owner.page, seed)).id
    }
    if (spec.finalState !== 'DRAFT') await transitionBusiness(admin, business.id, 'activate')
    if (spec.finalState === 'SUSPENDED') await transitionBusiness(admin, business.id, 'suspend')
  } catch (error) {
    await owner?.context.close()
    throw error
  }
  if (!options.keepOwner) {
    await owner?.context.close()
    owner = null
  }
  return {
    id: business.id,
    slug: fixture.slug,
    name: spec.name,
    ownerEmail: fixture.ownerEmail,
    contactEmail: spec.contactEmail ?? null,
    serviceIds,
    owner,
  }
}

export const PROFILE_KEYS = [
  'address',
  'businessType',
  'description',
  'displayName',
  'phone',
  'services',
  'slug',
]
export const ADDRESS_KEYS = ['city', 'details', 'postalCode', 'street', 'streetNumber']
export const SERVICE_KEYS = ['description', 'durationMinutes', 'name', 'price']

export type PublicApiResult = { status: number, text: string, body: unknown }

/** Reads the public endpoint through a request context (no page, no UI). */
export async function readPublicApi(
  request: APIRequestContext,
  slug: string,
): Promise<PublicApiResult> {
  const response = await request.get(`${API_ORIGIN}/api/public/businesses/${slug}`)
  const text = await response.text()
  let body: unknown = null
  try {
    body = JSON.parse(text)
  } catch {
    // A non-JSON body leaves `body` null; callers assert on status and text.
  }
  return { status: response.status(), text, body }
}

export type PublicProfileJson = {
  slug: string
  displayName: string
  businessType: string
  description: string | null
  phone: string | null
  address: Record<string, string | null> | null
  services: { name: string, description: string | null, durationMinutes: number, price: number }[]
}

export function asProfile(result: PublicApiResult): PublicProfileJson {
  expect(result.status).toBe(200)
  return result.body as PublicProfileJson
}

export const UNAVAILABLE_HEADING = 'Страницата не е налична'
export const UNAVAILABLE_TITLE = 'Страницата не е налична – SpotYourSlot'
export const BOOKING_NOTICE = 'Онлайн запазването на час все още не е налично.'
export const NO_SERVICES_TEXT = 'В момента няма налични услуги за онлайн записване.'
export const LOADING_TEXT = 'Зареждане на страницата…'
export const FAILURE_HEADING = 'Страницата не може да бъде заредена.'
export const SHELL_DESCRIPTION = 'SpotYourSlot — платформа за онлайн записване на часове'

export async function openPublicPage(page: Page, slug: string): Promise<void> {
  await page.goto(`/${slug}`)
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
}

export type HeadMetadata = {
  title: string
  description: string | null
  canonical: string | null
  robots: string | null
}

export async function headMetadata(page: Page): Promise<HeadMetadata> {
  return page.evaluate(() => ({
    title: document.title,
    description:
      document.head.querySelector('meta[name="description"]')?.getAttribute('content') ?? null,
    canonical: document.head.querySelector('link[rel="canonical"]')?.getAttribute('href') ?? null,
    robots: document.head.querySelector('meta[name="robots"]')?.getAttribute('content') ?? null,
  }))
}

export async function headingLevels(page: Page): Promise<number[]> {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6')).map((heading) =>
      Number(heading.tagName.slice(1)),
    ),
  )
}

export function expectLogicalHeadingHierarchy(levels: number[]): void {
  expect(levels.filter((level) => level === 1)).toHaveLength(1)
  expect(levels[0]).toBe(1)
  for (let index = 1; index < levels.length; index += 1) {
    expect(levels[index]! - levels[index - 1]!).toBeLessThanOrEqual(1)
  }
}

export type RecordedRequest = { method: string, path: string, hasCookie: boolean }

/**
 * Records every request the page sends to the API origin, without header values.
 * Requests the page itself aborted are dropped: the Vite development server renders
 * under React StrictMode, which runs a mount effect twice and aborts the first fetch.
 * A production build issues one request, so only requests that were not client-aborted
 * count as sent.
 */
export function recordApiRequests(page: Page): RecordedRequest[] {
  const recorded: RecordedRequest[] = []
  const entries = new Map<Request, RecordedRequest>()
  page.on('request', (request) => {
    if (!request.url().startsWith(API_ORIGIN)) return
    const entry: RecordedRequest = {
      method: request.method(),
      path: new URL(request.url()).pathname,
      hasCookie: false,
    }
    recorded.push(entry)
    entries.set(request, entry)
    void request.allHeaders().then((headers) => {
      entry.hasCookie = 'cookie' in headers
    })
  })
  page.on('requestfailed', (request) => {
    const entry = entries.get(request)
    if (entry && request.failure()?.errorText.includes('ABORTED')) {
      recorded.splice(recorded.indexOf(entry), 1)
    }
  })
  return recorded
}

/** Whether a locator's box lies fully inside the visible viewport width. */
export async function expectWithinViewportWidth(page: Page, selector: string): Promise<void> {
  const boxes = await page.locator(selector).evaluateAll((elements) =>
    elements.map((element) => {
      const rect = element.getBoundingClientRect()
      return { left: rect.left, right: rect.right, width: rect.width }
    }),
  )
  const viewportWidth = await page.evaluate(() => document.documentElement.clientWidth)
  expect(boxes.length).toBeGreaterThan(0)
  for (const box of boxes) {
    expect(box.width).toBeGreaterThan(0)
    expect(box.left).toBeGreaterThanOrEqual(-0.5)
    expect(box.right).toBeLessThanOrEqual(viewportWidth + 0.5)
  }
}

/** Whether no element's content is wider than its own box (nothing clipped or overflowing). */
export async function expectNoClippedContent(page: Page, selector: string): Promise<void> {
  const clipped = await page.locator(selector).evaluateAll((elements) =>
    elements.filter((element) => element.scrollWidth > element.clientWidth + 1).length,
  )
  expect(clipped).toBe(0)
}
