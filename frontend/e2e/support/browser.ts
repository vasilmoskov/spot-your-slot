import { expect, type BrowserContext, type Page } from '@playwright/test'
import { API_ORIGIN } from './environment'

export type SessionBusiness = {
  id: string
  displayName: string
  status: string
  role: string
}

export type PublicSession = {
  email: string
  displayName: string
  platformAdmin: boolean
  businesses: SessionBusiness[]
  activeBusinessId?: string | null
}

export type LandingHeading = {
  name: string
  level: number
}

const PROFILE_HEADING: LandingHeading = { name: 'Профил', level: 1 }
export const SERVICES_HEADING: LandingHeading = { name: 'Услуги', level: 1 }

export async function signIn(
  page: Page,
  email: string,
  password: string,
  landing: LandingHeading = PROFILE_HEADING,
): Promise<void> {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Вход' })).toBeVisible()
  await page.getByLabel('Имейл').fill(email)
  await page.getByLabel('Парола').fill(password)
  const loginResponse = page.waitForResponse((response) =>
    response.url() === `${API_ORIGIN}/api/auth/login`
      && response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: 'Вход' }).click()
  expect((await loginResponse).status()).toBe(200)
  await expect(
    page.getByRole('heading', { name: landing.name, level: landing.level, exact: true }),
  ).toBeVisible()
}

export async function publicSession(
  page: Page,
): Promise<{ status: number, body: PublicSession | null }> {
  return page.evaluate(async (apiOrigin) => {
    const response = await fetch(`${apiOrigin}/api/auth/session`, {
      credentials: 'include',
    })
    return {
      status: response.status,
      body: response.ok ? await response.json() as PublicSession : null,
    }
  }, API_ORIGIN)
}

export async function hasSessionCookie(context: BrowserContext): Promise<boolean> {
  return (await context.cookies()).some((cookie) => cookie.name === 'SPOTYOURSESSION')
}

export async function expectNoSessionCookie(context: BrowserContext): Promise<void> {
  expect(await hasSessionCookie(context)).toBe(false)
}

export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const fitsViewport = await page.evaluate(() =>
    document.documentElement.scrollWidth <= document.documentElement.clientWidth,
  )
  expect(fitsViewport).toBe(true)
}

export async function expectBrowserStorageEmpty(page: Page): Promise<void> {
  const state = await page.evaluate(async () => ({
    local: localStorage.length,
    session: sessionStorage.length,
    indexedDatabases: (await indexedDB.databases()).length,
    scriptVisibleSessionCookie: document.cookie.includes('SPOTYOURSESSION'),
  }))
  expect(state).toEqual({
    local: 0,
    session: 0,
    indexedDatabases: 0,
    scriptVisibleSessionCookie: false,
  })
}

export type ApiResult = {
  status: number
  code: string | null
  text: string
}

/**
 * Sends a same-session API request from the page, bypassing the UI, using the
 * page's own cookies and the CSRF token fetched in-page. Only the status, the
 * public problem code and the public response text are returned; the CSRF token
 * and cookies never leave the page.
 */
export async function apiRequest(
  page: Page,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  body?: unknown,
): Promise<ApiResult> {
  return page.evaluate(async ({ apiOrigin, method: verb, path: target, body: payload }) => {
    const headers = new Headers()
    if (verb !== 'GET') {
      const csrfResponse = await fetch(`${apiOrigin}/api/auth/csrf`, { credentials: 'include' })
      const csrf = await csrfResponse.json() as { headerName: string, token: string }
      headers.set(csrf.headerName, csrf.token)
      headers.set('Content-Type', 'application/json')
    }
    const response = await fetch(`${apiOrigin}${target}`, {
      method: verb,
      headers,
      credentials: 'include',
      ...(payload === undefined ? {} : { body: JSON.stringify(payload) }),
    })
    const text = await response.text()
    let code: string | null = null
    try {
      const parsed = JSON.parse(text) as { code?: unknown }
      if (typeof parsed.code === 'string') code = parsed.code
    } catch {
      // Not a problem document; the code stays null.
    }
    return { status: response.status, code, text }
  }, { apiOrigin: API_ORIGIN, method, path, body })
}
