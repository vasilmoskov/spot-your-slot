import { expect, test } from '@playwright/test'

test('isolated stack serves authenticated React application', async ({ page }) => {
  const loginResponse = page.waitForResponse((response) =>
    response.url().endsWith('/api/auth/login') && response.request().method() === 'POST',
  )

  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Вход' })).toBeVisible()
  await page.getByLabel('Имейл').fill(process.env.E2E_ADMIN_EMAIL!)
  await page.getByLabel('Парола').fill(process.env.E2E_ADMIN_PASSWORD!)
  await page.getByRole('button', { name: 'Вход' }).click()

  const response = await loginResponse
  expect(response.status()).toBe(200)
  expect(response.headers()['access-control-allow-origin']).toBe(
    `http://localhost:${process.env.E2E_FRONTEND_PORT}`,
  )
  await expect(page.getByRole('heading', { name: 'Профил' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Бизнеси' })).toBeVisible()
  const profile = page.getByRole('region', { name: 'Настройки на профила' })
  await expect(profile.getByText(process.env.E2E_ADMIN_DISPLAY_NAME!, { exact: true }))
    .toBeVisible()

  const hasSessionCookie = (await page.context().cookies())
    .some((cookie) => cookie.name === 'SPOTYOURSESSION')
  expect(hasSessionCookie).toBe(true)
  expect(await page.evaluate(() => ({
    local: localStorage.length,
    session: sessionStorage.length,
  }))).toEqual({ local: 0, session: 0 })
})
