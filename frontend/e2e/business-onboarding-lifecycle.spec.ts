import { devices, expect, test } from '@playwright/test'
import {
  expectNoHorizontalOverflow,
  expectNoSessionCookie,
  publicSession,
  signIn,
} from './support/browser'
import { API_ORIGIN, requiredEnvironment } from './support/environment'
import {
  invitationUrls,
  openInvitationWithoutHistoryToken,
  submitInvitation,
} from './support/mailbox'

const BUSINESS_NAME = 'Студио Орбита Е2Е'
const BUSINESS_SLUG = 'studio-orbita-e2e'
const BUSINESS_DESCRIPTION = 'Фикционално студио за проверка на onboarding.'
const BUSINESS_CITY = 'Пловдив'
const BUSINESS_STREET = 'Улица Орбита'
const BUSINESS_STREET_NUMBER = '7'
const BUSINESS_POSTAL_CODE = '4000'
const BUSINESS_PHONE = '+359 32 555 017'
const BUSINESS_EMAIL = 'contact@studio-orbita.example.invalid'
const OWNER_EMAIL = 'owner-orbita@example.invalid'
const OWNER_FIRST_NAME = 'Собственик'
const OWNER_LAST_NAME = 'Орбита'
const OWNER_DISPLAY_NAME = `${OWNER_FIRST_NAME} ${OWNER_LAST_NAME}`
const INVALID_INVITATION_MESSAGE =
  'Поканата е невалидна, изтекла или вече е използвана. Поискайте нова покана.'

test('Business onboarding and lifecycle through isolated browser contexts', async ({ browser, page }) => {
  test.setTimeout(90_000)

  const adminEmail = requiredEnvironment('E2E_ADMIN_EMAIL')
  const adminDisplayName = requiredEnvironment('E2E_ADMIN_DISPLAY_NAME')
  const adminPassword = requiredEnvironment('E2E_ADMIN_PASSWORD')

  await signIn(page, adminEmail, adminPassword)
  await page.getByRole('link', { name: 'Бизнеси' }).click()
  await expect(page.getByRole('heading', { name: 'Бизнеси' })).toBeVisible()
  await page.getByRole('button', { name: 'Нов бизнес' }).click()
  await expect(page.getByRole('heading', { name: 'Нов бизнес' })).toBeVisible()

  await page.getByLabel('Име на бизнеса').fill(BUSINESS_NAME)
  await page.getByLabel('Идентификатор в уеб адреса').fill(BUSINESS_SLUG)
  await page.getByLabel('Дейност').selectOption({ label: 'Козметично студио' })
  await page.getByLabel('Телефон (по избор)').fill(BUSINESS_PHONE)
  await page.getByLabel('Имейл за контакт (по избор)').fill(BUSINESS_EMAIL)
  await page.getByLabel('Улица (по избор)').fill(BUSINESS_STREET)
  await page.getByLabel('Номер (по избор)').fill(BUSINESS_STREET_NUMBER)
  await page.getByLabel('Пощенски код (по избор)').fill(BUSINESS_POSTAL_CODE)
  await page.getByLabel('Град (по избор)').fill(BUSINESS_CITY)
  await page.getByLabel('Описание (по избор)').fill(BUSINESS_DESCRIPTION)

  const createResponse = page.waitForResponse((response) =>
    response.url() === `${API_ORIGIN}/api/platform/businesses`
      && response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: 'Създай бизнес' }).click()
  const createdResponse = await createResponse
  expect(createdResponse.status()).toBe(201)
  const created = await createdResponse.json() as { id: string, status: string, version: number }
  expect(created.status).toBe('DRAFT')
  expect(created.version).toBe(0)
  const businessId = created.id

  await expect(page.getByRole('heading', { name: BUSINESS_NAME, exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Обратно към бизнесите' }).click()
  const businessRow = page.getByRole('row').filter({ hasText: BUSINESS_NAME })
  await expect(businessRow).toContainText('Предстои активиране')
  await businessRow.getByRole('link', { name: `Отвори ${BUSINESS_NAME}` }).click()

  await expect(page.getByRole('heading', { name: BUSINESS_NAME, exact: true })).toBeVisible()
  await page.getByText('Данни за бизнеса', { exact: true }).click()
  await expect(page.getByText(BUSINESS_DESCRIPTION, { exact: true })).toBeVisible()
  await expect(page.getByText(BUSINESS_CITY, { exact: true })).toBeVisible()
  await expect(page.getByText(BUSINESS_STREET, { exact: true })).toBeVisible()
  await expect(page.getByText(BUSINESS_PHONE, { exact: true })).toBeVisible()
  await expect(page.getByText(BUSINESS_EMAIL, { exact: true })).toBeVisible()

  await page.getByText('Активиране', { exact: true }).click()
  await page.getByRole('button', { name: 'Активирай' }).click()
  await expect(page.getByRole('alertdialog')).toContainText('Потвърдете активирането')
  await page.getByRole('button', { name: 'Потвърди активирането' }).click()
  await expect(page.getByRole('alert')).toHaveText(
    'За да активирате бизнеса, собственикът трябва първо да приеме поканата.',
  )

  await page.getByText('Покана', { exact: true }).click()
  await page.getByLabel('Имейл на собственика').fill(OWNER_EMAIL)
  const firstInvitationResponse = page.waitForResponse((response) =>
    response.url().endsWith(`/businesses/${businessId}/owner-invitation`)
      && response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: 'Изпрати покана' }).click()
  expect((await firstInvitationResponse).status()).toBe(202)
  await expect(page.getByRole('status')).toHaveText('Заявката за покана е изпратена.')

  const firstMailboxUrls = await invitationUrls(page.context(), OWNER_EMAIL)
  expect(firstMailboxUrls.length).toBe(1)
  const firstInvitationUrl = firstMailboxUrls[0]!

  await page.getByRole('button', { name: 'Изпрати покана' }).click()
  const replacementDialog = page.getByRole('alertdialog', { name: 'Изпращане на нова покана' })
  await expect(replacementDialog).toContainText(
    'Предишната активна покана за този имейл ще бъде заменена.',
  )
  const replacementResponse = page.waitForResponse((response) =>
    response.url().endsWith(`/businesses/${businessId}/owner-invitation`)
      && response.request().method() === 'POST',
  )
  await replacementDialog.getByRole('button', { name: 'Потвърди изпращането' }).click()
  expect((await replacementResponse).status()).toBe(202)
  await expect(page.getByRole('status')).toHaveText('Заявката за покана е изпратена.')

  const replacementMailboxUrls = await invitationUrls(page.context(), OWNER_EMAIL)
  expect(replacementMailboxUrls.length).toBe(2)
  const replacementInvitationUrl = replacementMailboxUrls[1]!
  expect(firstInvitationUrl === replacementInvitationUrl).toBe(false)

  const replacedContext = await browser.newContext()
  try {
    const replacedPage = await replacedContext.newPage()
    await openInvitationWithoutHistoryToken(replacedPage, firstInvitationUrl)
    await submitInvitation(replacedPage, OWNER_FIRST_NAME, OWNER_LAST_NAME)
    await expect(replacedPage.getByRole('alert')).toHaveText(INVALID_INVITATION_MESSAGE)
    expect((await publicSession(replacedPage)).status).toBe(401)
    await expectNoSessionCookie(replacedContext)
  } finally {
    await replacedContext.close()
  }

  const ownerContext = await browser.newContext()
  try {
    const ownerPage = await ownerContext.newPage()
    await openInvitationWithoutHistoryToken(ownerPage, replacementInvitationUrl)
    await submitInvitation(ownerPage, OWNER_FIRST_NAME, OWNER_LAST_NAME)
    await expect(ownerPage.getByRole('status')).toHaveText(
      'Поканата е приета успешно. Бизнесът очаква активиране от администратор.',
    )
    await ownerPage.getByRole('link', { name: 'Към вход' }).click()
    await signIn(ownerPage, OWNER_EMAIL, requiredEnvironment('E2E_OWNER_PASSWORD'))

    const ownerProfile = ownerPage.getByRole('region', { name: 'Настройки на профила' })
    await expect(ownerProfile.getByText(OWNER_DISPLAY_NAME, { exact: true })).toBeVisible()
    await expect(ownerProfile.getByText(OWNER_EMAIL, { exact: true })).toBeVisible()
    // The owner's "Бизнеси" is the Business selection, not the platform list.
    await expect(ownerPage.getByRole('link', { name: 'Бизнеси' }))
      .toHaveAttribute('href', '/#/businesses')
    await expect(ownerPage.getByRole('button', { name: 'Нов бизнес' })).toHaveCount(0)

    const ownerSessionResponse = await publicSession(ownerPage)
    expect(ownerSessionResponse.status).toBe(200)
    const ownerSession = ownerSessionResponse.body!
    expect(ownerSession.platformAdmin).toBe(false)
    expect(ownerSession.email).toBe(OWNER_EMAIL)
    expect(ownerSession.businesses).toHaveLength(1)
    expect(ownerSession.businesses[0]).toMatchObject({
      id: businessId,
      displayName: BUSINESS_NAME,
      status: 'DRAFT',
      role: 'BUSINESS_OWNER',
    })

    const ownerHasSessionCookie = (await ownerContext.cookies())
      .some((cookie) => cookie.name === 'SPOTYOURSESSION')
    expect(ownerHasSessionCookie).toBe(true)
  } finally {
    await ownerContext.close()
  }

  await expect(page.getByRole('heading', { name: BUSINESS_NAME, exact: true })).toBeVisible()
  const adminSessionResponse = await publicSession(page)
  expect(adminSessionResponse.status).toBe(200)
  expect(adminSessionResponse.body?.platformAdmin).toBe(true)
  expect(adminSessionResponse.body?.email).toBe(adminEmail)
  expect(adminSessionResponse.body?.displayName).toBe(adminDisplayName)
  const adminHasSessionCookie = (await page.context().cookies())
    .some((cookie) => cookie.name === 'SPOTYOURSESSION')
  expect(adminHasSessionCookie).toBe(true)

  await page.getByRole('button', { name: 'Активирай' }).click()
  await page.getByRole('button', { name: 'Потвърди активирането' }).click()
  await expect(page.getByRole('status')).toHaveText('Бизнесът е активиран.')
  await expect(page.getByText('Текущ статус: Активен', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'Спри временно' }).click()
  await expect(page.getByRole('alertdialog')).toContainText('Потвърдете временното спиране')
  await page.getByRole('button', { name: 'Потвърди спирането' }).click()
  await expect(page.getByRole('status')).toHaveText('Бизнесът е временно спрян.')
  await expect(page.getByText('Текущ статус: Временно спрян', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'Активирай отново' }).click()
  await expect(page.getByRole('alertdialog')).toContainText('Потвърдете повторното активиране')
  await page.getByRole('button', { name: 'Потвърди активирането' }).click()
  await expect(page.getByRole('status')).toHaveText('Бизнесът е активиран отново.')
  await expect(page.getByText('Текущ статус: Активен', { exact: true })).toBeVisible()

  const replayContext = await browser.newContext()
  try {
    const replayPage = await replayContext.newPage()
    await openInvitationWithoutHistoryToken(replayPage, replacementInvitationUrl)
    await submitInvitation(replayPage, OWNER_FIRST_NAME, OWNER_LAST_NAME)
    await expect(replayPage.getByRole('alert')).toHaveText(INVALID_INVITATION_MESSAGE)
    expect((await publicSession(replayPage)).status).toBe(401)
    await expectNoSessionCookie(replayContext)
  } finally {
    await replayContext.close()
  }

  const mobileContext = await browser.newContext({ ...devices['Pixel 7'] })
  try {
    const mobilePage = await mobileContext.newPage()
    await signIn(mobilePage, adminEmail, adminPassword)
    const menu = mobilePage.getByRole('button', { name: /навигацията/ })
    await expect(menu).toBeVisible()
    await menu.focus()
    await menu.press('Enter')
    await expect(menu).toHaveAttribute('aria-expanded', 'true')
    const mobileBusinesses = mobilePage.getByRole('link', { name: 'Бизнеси' })
    await expect(mobileBusinesses).toBeFocused()
    await mobileBusinesses.press('Escape')
    await expect(menu).toHaveAttribute('aria-expanded', 'false')
    await expect(menu).toBeFocused()
    await menu.press('Enter')
    await mobilePage.getByRole('link', { name: 'Бизнеси' }).click()

    const mobileBusinessRow = mobilePage.getByRole('row').filter({ hasText: BUSINESS_NAME })
    await expect(mobileBusinessRow).toContainText('Активен')
    await expectNoHorizontalOverflow(mobilePage)
    await mobileBusinessRow.getByRole('link', { name: `Отвори ${BUSINESS_NAME}` }).click()
    await expect(mobilePage.getByRole('heading', { name: BUSINESS_NAME, exact: true })).toBeVisible()
    await mobilePage.getByText('Активиране', { exact: true }).click()
    const suspendAction = mobilePage.getByRole('button', { name: 'Спри временно' })
    await expect(suspendAction).toBeVisible()
    await expect(suspendAction).toBeEnabled()
    await expect(suspendAction).toBeInViewport()
    await expectNoHorizontalOverflow(mobilePage)
  } finally {
    await mobileContext.close()
  }
})
