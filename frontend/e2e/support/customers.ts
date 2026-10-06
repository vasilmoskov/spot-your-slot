import { randomBytes } from 'node:crypto'
import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { apiRequest, signIn } from './browser'
import { API_ORIGIN, requiredEnvironment } from './environment'
import { invitationUrls, openInvitationWithoutHistoryToken, submitInvitation } from './mailbox'
import {
  bodyFor,
  inviteOwnerThroughApi,
  provisionBusiness,
  transitionBusiness,
  type BusinessResponse,
  type BusinessSpec,
} from './publicProfile'

export const CUSTOMERS_PATH = '/api/business/customers'
export const CUSTOMER_SUSPENDED_NOTICE =
  'Бизнесът е временно спрян. Можете да преглеждате и редактирате клиентите, но не можете да добавяте нови.'

export type CustomerSeed = {
  displayName: string
  phone?: string
  email?: string
}

export type CustomerRecord = {
  id: string
  displayName: string
  phone: string | null
  email: string | null
  version: number
}

export type CustomerPageBody = {
  items: Array<{ id: string, displayName: string, phone: string | null, email: string | null }>
  page: number
  size: number
  total: number
}

export function shortTag(): string {
  return randomBytes(3).toString('hex')
}

/** A canonical compact Bulgarian mobile number from a small index (synthetic range). */
export function phoneFor(index: number): string {
  return `+359885550${String(index).padStart(3, '0')}`
}

/** The grouped presentation of a canonical Bulgarian mobile number, as the interface shows it. */
export function shownPhone(canonical: string): string {
  const match = /^\+359(\d{3})(\d{3})(\d{3})$/.exec(canonical)
  return match ? `+359 ${match[1]} ${match[2]} ${match[3]}` : canonical
}

export function normalizedName(name: string): string {
  return name.normalize('NFKC').replace(/\s+/gu, ' ').trim().toLowerCase()
}

type SortField = 'name' | 'phone' | 'email'

/** The deterministic order the approved contract promises (NULLS LAST, name tie-break). */
export function expectedOrder<T extends { displayName: string, phone: string | null, email: string | null }>(
  customers: readonly T[],
  sort: SortField,
  direction: 'asc' | 'desc',
): T[] {
  const sign = direction === 'asc' ? 1 : -1
  const byName = (a: T, b: T) => {
    const left = normalizedName(a.displayName)
    const right = normalizedName(b.displayName)
    return left < right ? -1 : left > right ? 1 : 0
  }
  if (sort === 'name') return [...customers].sort((a, b) => sign * byName(a, b))
  const value = (customer: T) => (sort === 'phone' ? customer.phone : customer.email)
  return [...customers].sort((a, b) => {
    const left = value(a)
    const right = value(b)
    if (left === null && right === null) return byName(a, b)
    if (left === null) return 1
    if (right === null) return -1
    if (left === right) return byName(a, b)
    return sign * (left < right ? -1 : 1)
  })
}

export async function selectBusinessViaApi(page: Page, businessId: string): Promise<void> {
  const result = await apiRequest(page, 'POST', '/api/auth/business', { businessId })
  expect(result.status, result.code ?? 'select business').toBe(200)
}

export async function seedCustomer(page: Page, seed: CustomerSeed): Promise<CustomerRecord> {
  const result = await apiRequest(page, 'POST', CUSTOMERS_PATH, seed)
  expect(result.status, `seed ${seed.displayName}: ${result.code ?? ''} ${result.text.slice(0, 200)}`).toBe(201)
  return JSON.parse(result.text) as CustomerRecord
}

export async function seedCustomers(page: Page, seeds: readonly CustomerSeed[]): Promise<CustomerRecord[]> {
  const records: CustomerRecord[] = []
  for (const seed of seeds) records.push(await seedCustomer(page, seed))
  return records
}

export async function readCustomer(page: Page, id: string): Promise<CustomerRecord> {
  const result = await apiRequest(page, 'GET', `${CUSTOMERS_PATH}/${encodeURIComponent(id)}`)
  expect(result.status, result.code ?? 'read customer').toBe(200)
  return JSON.parse(result.text) as CustomerRecord
}

export async function customerTotal(page: Page): Promise<number> {
  const result = await apiRequest(page, 'GET', `${CUSTOMERS_PATH}?size=10`)
  expect(result.status, result.code ?? 'customer total').toBe(200)
  return (JSON.parse(result.text) as CustomerPageBody).total
}

export function customerRows(page: Page) {
  return page.locator('table.customer-table tbody tr')
}

async function columnValues(page: Page, label: 'Име' | 'Телефон' | 'Имейл'): Promise<string[]> {
  return page
    .locator(`table.customer-table tbody td[data-label="${label}"]`)
    .evaluateAll((cells) => cells.map((cell) => (cell.textContent ?? '').trim()))
}

export async function visibleNames(page: Page): Promise<string[]> {
  return columnValues(page, 'Име')
}

/** Phone cells as shown; a missing value is `null`. */
export async function visiblePhones(page: Page): Promise<Array<string | null>> {
  return (await columnValues(page, 'Телефон')).map((v) => (v.includes('не е посочен') ? null : v))
}

export async function visibleEmails(page: Page): Promise<Array<string | null>> {
  return (await columnValues(page, 'Имейл')).map((v) => (v.includes('не е посочен') ? null : v))
}

export function searchBox(page: Page) {
  return page.getByLabel('Търсене', { exact: true })
}

export function pagination(page: Page) {
  return page.getByRole('navigation', { name: 'Странициране на клиентите' })
}

export function listRoute(page: Page): { path: string, params: URLSearchParams } {
  const hash = new URL(page.url()).hash
  const [path, query] = hash.split('?')
  return { path: path ?? '', params: new URLSearchParams(query ?? '') }
}

export function sortHeader(page: Page, label: string) {
  return page.getByRole('columnheader', { name: label, exact: true }).getByRole('button')
}

export async function openCustomers(page: Page, query = ''): Promise<void> {
  await page.goto(`/#/business/customers${query ? `?${query}` : ''}`)
  await expect(page.getByRole('heading', { name: 'Клиенти', level: 1, exact: true })).toBeVisible()
}

export function businessCard(page: Page, name: string) {
  return page
    .locator('article.business-choice')
    .filter({ has: page.getByRole('heading', { name, level: 2, exact: true }) })
}

export async function showBusiness(page: Page, name: string): Promise<void> {
  await page.getByRole('link', { name: 'Бизнеси', exact: true }).first().click()
  await expect(page.getByRole('heading', { name: 'Бизнеси', level: 1, exact: true })).toBeVisible()
  await businessCard(page, name).getByRole('button', { name: 'Покажи', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Услуги', level: 1, exact: true })).toBeVisible()
}

/**
 * Asserts the sentinel never reached the URL, browser storage, script-visible cookies or the
 * document title. Only booleans are compared, so no value is printed on failure.
 */
export async function expectNoSentinelInBrowser(page: Page, sentinels: readonly string[]): Promise<void> {
  const state = await page.evaluate(async (terms) => {
    const lower = terms.map((t) => t.toLowerCase())
    const has = (text: string) => lower.some((t) => text.toLowerCase().includes(t))
    const local = Object.keys(localStorage).concat(Object.values(localStorage))
    const session = Object.keys(sessionStorage).concat(Object.values(sessionStorage))
    const databases = await indexedDB.databases()
    return {
      url: has(decodeURIComponent(location.href)),
      local: local.some(has),
      session: session.some(has),
      indexedDatabases: databases.length,
      cookie: has(decodeURIComponent(document.cookie)),
      title: has(document.title),
    }
  }, [...sentinels])
  expect(state).toEqual({
    url: false,
    local: false,
    session: false,
    indexedDatabases: 0,
    cookie: false,
    title: false,
  })
}

export type OwnerOfBusinesses = {
  ownerEmail: string
  context: BrowserContext
  page: Page
  businessIds: Record<string, string>
}

/** Provisions an ACTIVE (or other final state) Business with its own owner and closes that session. */
export async function provisionOwnerBusiness(
  browser: Browser,
  adminContext: BrowserContext,
  admin: Page,
  spec: BusinessSpec,
) {
  return provisionBusiness(browser, adminContext, admin, spec)
}

/**
 * Creates another Business for an existing owner: the administrator creates it and invites the
 * same email, and the owner accepts with the existing password (a supported flow).
 */
export async function addBusinessForOwner(
  browser: Browser,
  adminContext: BrowserContext,
  admin: Page,
  ownerEmail: string,
  spec: BusinessSpec,
): Promise<string> {
  const slug = `${spec.slugBase}-${shortTag()}`
  const created = await apiRequest(admin, 'POST', '/api/platform/businesses', bodyFor(spec, slug))
  expect(created.status, created.code ?? 'business create').toBe(201)
  const business = JSON.parse(created.text) as BusinessResponse
  await inviteOwnerThroughApi(admin, business.id, ownerEmail)
  const urls = await invitationUrls(adminContext, ownerEmail)
  const context = await browser.newContext()
  try {
    const page = await context.newPage()
    await openInvitationWithoutHistoryToken(page, urls[urls.length - 1]!)
    await submitInvitation(page, 'Собственик', 'Тестов')
    await expect(page.getByRole('status')).toBeVisible()
  } finally {
    await context.close()
  }
  if (spec.finalState !== 'DRAFT') await transitionBusiness(admin, business.id, 'activate')
  if (spec.finalState === 'SUSPENDED') await transitionBusiness(admin, business.id, 'suspend')
  return business.id
}

/** Signs the owner in on a new context; a multi-Business owner lands on the selection. */
export async function signInOwner(
  browser: Browser,
  ownerEmail: string,
  landing: { name: string, level: number },
  options: Parameters<Browser['newContext']>[0] = {},
): Promise<{ context: BrowserContext, page: Page }> {
  const context = await browser.newContext(options)
  try {
    const page = await context.newPage()
    await signIn(page, ownerEmail, requiredEnvironment('E2E_OWNER_PASSWORD'), landing)
    return { context, page }
  } catch (error) {
    await context.close()
    throw error
  }
}

export { API_ORIGIN }
