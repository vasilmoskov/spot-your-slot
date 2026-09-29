import { randomBytes } from 'node:crypto'
import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { SERVICES_HEADING, signIn } from './browser'
import { API_ORIGIN, requiredEnvironment } from './environment'
import { invitationUrls, openInvitationWithoutHistoryToken, submitInvitation } from './mailbox'

export type BusinessFixture = {
  name: string
  slug: string
  ownerEmail: string
  ownerFirstName: string
  ownerLastName: string
}

/**
 * Returns a copy of the fixture whose slug and owner email carry a short random
 * suffix, so provisioning never collides with an earlier setup attempt (for
 * example after Playwright restarts a worker) in the same disposable database.
 * The display name stays stable and human-readable.
 */
export function uniqueBusinessFixture(fixture: BusinessFixture): BusinessFixture {
  const suffix = randomBytes(4).toString('hex')
  const [localPart, domain] = fixture.ownerEmail.split('@')
  if (!localPart || !domain) throw new Error('The owner email fixture must contain one @')
  return {
    ...fixture,
    slug: `${fixture.slug}-${suffix}`,
    ownerEmail: `${localPart}-${suffix}@${domain}`,
  }
}

export type OwnerSession = {
  context: BrowserContext
  page: Page
}

export async function signInAsPlatformAdmin(page: Page): Promise<void> {
  await signIn(
    page,
    requiredEnvironment('E2E_ADMIN_EMAIL'),
    requiredEnvironment('E2E_ADMIN_PASSWORD'),
  )
}

/** Creates a DRAFT Business through the platform-administrator UI and returns its id. */
export async function createDraftBusiness(
  adminPage: Page,
  fixture: BusinessFixture,
): Promise<string> {
  await adminPage.getByRole('link', { name: 'Бизнеси' }).click()
  await expect(adminPage.getByRole('heading', { name: 'Бизнеси' })).toBeVisible()
  await adminPage.getByRole('button', { name: 'Нов бизнес' }).click()
  await expect(adminPage.getByRole('heading', { name: 'Нов бизнес' })).toBeVisible()
  await adminPage.getByLabel('Име на бизнеса').fill(fixture.name)
  await adminPage.getByLabel('Идентификатор в уеб адреса').fill(fixture.slug)
  await adminPage.getByLabel('Дейност').selectOption({ label: 'Козметично студио' })

  const createResponse = adminPage.waitForResponse((response) =>
    response.url() === `${API_ORIGIN}/api/platform/businesses`
      && response.request().method() === 'POST',
  )
  await adminPage.getByRole('button', { name: 'Създай бизнес' }).click()
  const created = await createResponse
  expect(created.status()).toBe(201)
  const body = await created.json() as { id: string, status: string }
  expect(body.status).toBe('DRAFT')
  await expect(adminPage.getByRole('heading', { name: fixture.name, exact: true })).toBeVisible()
  return body.id
}

/** Sends the initial owner invitation from the open Business detail page. */
export async function inviteOwner(
  adminPage: Page,
  businessId: string,
  ownerEmail: string,
): Promise<void> {
  await adminPage.getByText('Покана', { exact: true }).click()
  await adminPage.getByLabel('Имейл на собственика').fill(ownerEmail)
  const invitationResponse = adminPage.waitForResponse((response) =>
    response.url().endsWith(`/businesses/${businessId}/owner-invitation`)
      && response.request().method() === 'POST',
  )
  await adminPage.getByRole('button', { name: 'Изпрати покана' }).click()
  expect((await invitationResponse).status()).toBe(202)
  await expect(adminPage.getByRole('status')).toHaveText('Заявката за покана е изпратена.')
}

/**
 * Accepts the single pending invitation in a new isolated context, signs the owner
 * in and waits for the automatic Services landing of the sole Business.
 */
export async function onboardOwner(
  browser: Browser,
  adminContext: BrowserContext,
  fixture: BusinessFixture,
  options: { viewport?: { width: number, height: number } } = {},
): Promise<OwnerSession> {
  const urls = await invitationUrls(adminContext, fixture.ownerEmail)
  expect(urls).toHaveLength(1)

  const context = await browser.newContext(options)
  try {
    const page = await context.newPage()
    await openInvitationWithoutHistoryToken(page, urls[0]!)
    await submitInvitation(page, fixture.ownerFirstName, fixture.ownerLastName)
    await expect(page.getByRole('status')).toHaveText(
      'Поканата е приета успешно. Бизнесът очаква активиране от администратор.',
    )
    await page.getByRole('link', { name: 'Към вход' }).click()
    await signIn(
      page,
      fixture.ownerEmail,
      requiredEnvironment('E2E_OWNER_PASSWORD'),
      SERVICES_HEADING,
    )
    return { context, page }
  } catch (error) {
    await context.close()
    throw error
  }
}
