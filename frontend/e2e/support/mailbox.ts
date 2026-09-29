import { expect, type BrowserContext, type Page } from '@playwright/test'
import { expectBrowserStorageEmpty } from './browser'
import { API_ORIGIN, requiredEnvironment } from './environment'

type MailboxMessage = {
  kind: string
  recipient: string
  url: string
}

export async function invitationUrls(
  context: BrowserContext,
  recipient: string,
): Promise<string[]> {
  const response = await context.request.get(`${API_ORIGIN}/api/dev/mailbox`)
  expect(response.status()).toBe(200)
  const payload: unknown = await response.json()
  if (!Array.isArray(payload)) {
    throw new Error('The protected development mailbox returned an invalid shape')
  }

  const urls = payload
    .filter((message): message is MailboxMessage => {
      if (typeof message !== 'object' || message === null) return false
      const candidate = message as Record<string, unknown>
      return candidate.kind === 'OWNER_INVITATION'
        && candidate.recipient === recipient
        && typeof candidate.url === 'string'
    })
    .map((message) => message.url)

  for (const invitationUrl of urls) {
    const parsed = new URL(invitationUrl)
    const tokens = parsed.searchParams.getAll('token')
    if (parsed.pathname !== '/invitation' || tokens.length !== 1 || !tokens[0]) {
      throw new Error('An owner invitation must contain exactly one nonblank token')
    }
  }
  return urls
}

export async function openInvitationWithoutHistoryToken(
  page: Page,
  invitationUrl: string,
): Promise<void> {
  await page.goto(invitationUrl)
  await expect(page.getByRole('heading', { name: 'Приемане на покана' })).toBeVisible()
  await page.evaluate(() => {
    history.replaceState({}, '', location.pathname)
  })
  expect(new URL(page.url()).search).toBe('')
  await expectBrowserStorageEmpty(page)
}

export async function submitInvitation(
  page: Page,
  firstName: string,
  lastName: string,
): Promise<void> {
  const password = requiredEnvironment('E2E_OWNER_PASSWORD')
  await page.getByLabel('Име').fill(firstName)
  await page.getByLabel('Фамилия').fill(lastName)
  await page.getByLabel('Парола', { exact: true }).fill(password)
  await page.getByLabel('Потвърди паролата').fill(password)
  await page.getByRole('button', { name: 'Приеми поканата' }).click()
}
