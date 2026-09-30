import '@testing-library/jest-dom/vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoot } from './AppRoot'

const profileBody = (slug: string, name: string) => ({
  slug,
  displayName: name,
  businessType: 'BARBERSHOP',
  description: null,
  phone: null,
  address: null,
  services: [],
})

const unavailable = {
  detail: 'Страницата не е налична.',
  instance: '/api/public/businesses',
  status: 404,
  title: 'Заявката не може да бъде изпълнена.',
  code: 'BUSINESS_PAGE_UNAVAILABLE',
}

const fetchMock = vi.fn()

function requestedUrls(): string[] {
  return fetchMock.mock.calls.map((call) => String(call[0]))
}

function goTo(url: string) {
  window.history.replaceState({}, '', url)
}

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status })
}

beforeEach(() => {
  fetchMock.mockReset()
  fetchMock.mockImplementation(async (input: unknown) => {
    const url = String(input)
    const slug = url.split('/api/public/businesses/')[1]
    if (slug) return json(200, profileBody(slug, `Бизнес ${slug}`))
    return json(401, { code: 'AUTH_REQUIRED', title: 'Необходимо е влизане.' })
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  goTo('/')
})

async function renderRoot() {
  const view = render(<AppRoot />)
  await act(async () => undefined)
  return view
}

describe('public routing', () => {
  it('opens the public page for /{slug} without touching the session or CSRF endpoints', async () => {
    goTo('/example-studio')
    await renderRoot()

    expect(await screen.findByRole('heading', { level: 1, name: 'Бизнес example-studio' })).toBeInTheDocument()
    expect(requestedUrls()).toHaveLength(1)
    expect(requestedUrls()[0]).toMatch(/\/api\/public\/businesses\/example-studio$/)
    expect(requestedUrls().join()).not.toMatch(/auth/)
    const init = fetchMock.mock.calls[0]![1] as RequestInit
    expect(init.credentials).toBe('omit')
    expect(screen.queryByRole('button', { name: /вход/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/парола/i)).not.toBeInTheDocument()
  })

  it('canonicalizes an uppercase slug by replacing history, never pushing', async () => {
    const push = vi.spyOn(window.history, 'pushState')
    const replace = vi.spyOn(window.history, 'replaceState')
    goTo('/Example-Studio')
    replace.mockClear()
    const before = window.history.length

    await renderRoot()

    expect(window.location.pathname).toBe('/example-studio')
    expect(replace).toHaveBeenCalledTimes(1)
    expect(push).not.toHaveBeenCalled()
    expect(window.history.length).toBe(before)
    expect(requestedUrls()[0]).toMatch(/businesses\/example-studio$/)
  })

  it('canonicalizes a trailing slash away', async () => {
    goTo('/example-studio/')
    await renderRoot()
    expect(window.location.pathname).toBe('/example-studio')
    expect(requestedUrls()[0]).toMatch(/businesses\/example-studio$/)
  })

  it('does not rewrite an already canonical URL', async () => {
    goTo('/example-studio')
    const replace = vi.spyOn(window.history, 'replaceState')
    await renderRoot()
    expect(replace).not.toHaveBeenCalled()
  })

  it('keeps the same Business after a refresh of the same URL', async () => {
    goTo('/example-studio')
    const first = await renderRoot()
    expect(await screen.findByText('Бизнес example-studio')).toBeInTheDocument()
    first.unmount()

    await renderRoot()
    expect(await screen.findByText('Бизнес example-studio')).toBeInTheDocument()
    expect(requestedUrls().every((url) => url.endsWith('/example-studio'))).toBe(true)
  })

  it('requests a valid slug that merely contains a reserved word from the public API', async () => {
    goTo('/salon-invitation')
    await renderRoot()
    expect(await screen.findByText('Бизнес salon-invitation')).toBeInTheDocument()
    expect(screen.queryByText(/покана/i)).not.toBeInTheDocument()
  })

  it('shows the generic unavailable page for an unavailable slug', async () => {
    fetchMock.mockImplementation(async () => json(404, unavailable))
    goTo('/draft-studio')
    await renderRoot()
    expect(await screen.findByRole('heading', { name: 'Страницата не е налична' })).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('draft-studio')
  })

  it('switches between two public slugs on history navigation and ignores the first', async () => {
    goTo('/business-a')
    await renderRoot()
    expect(await screen.findByText('Бизнес business-a')).toBeInTheDocument()

    goTo('/business-b')
    await act(async () => {
      window.dispatchEvent(new PopStateEvent('popstate'))
    })

    expect(await screen.findByText('Бизнес business-b')).toBeInTheDocument()
    expect(screen.queryByText('Бизнес business-a')).not.toBeInTheDocument()
    expect(requestedUrls().map((url) => url.split('/').pop())).toEqual(['business-a', 'business-b'])
  })

  it('canonicalizes on history navigation with a replace', async () => {
    goTo('/business-a')
    await renderRoot()
    const push = vi.spyOn(window.history, 'pushState')
    goTo('/Business-B/')
    await act(async () => {
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(window.location.pathname).toBe('/business-b')
    expect(push).not.toHaveBeenCalled()
    expect(await screen.findByText('Бизнес business-b')).toBeInTheDocument()
  })

  it('reloads for an entry that belongs to the other application', async () => {
    goTo('/business-a')
    await renderRoot()
    const reload = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      pathname: '/',
      search: '',
      hash: '',
      reload,
    } as unknown as Location)
    await act(async () => {
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(reload).toHaveBeenCalledTimes(1)
  })
})

describe('existing application routes', () => {
  it.each(['/', '/login', '/forgot-password', '/invitation', '/password-reset'])('%s still opens the application and asks for the session', async (path) => {
    goTo(path)
    await renderRoot()

    expect(requestedUrls().some((url) => url.endsWith('/api/auth/session'))).toBe(true)
    expect(requestedUrls().join()).not.toMatch(/api\/public/)
  })

  it.each(['/api', '/assets', '/admin', '/platform', '/business', '/profile', '/booking'])(
    'reserved root %s stays with the application',
    async (path) => {
      goTo(path)
      await renderRoot()
      expect(requestedUrls().join()).not.toMatch(/api\/public/)
      expect(requestedUrls().some((url) => url.endsWith('/api/auth/session'))).toBe(true)
    },
  )

  it.each(['/example-studio/book', '/a/b/c', '/invalid_slug', '/with%20space'])(
    'the non-public path %s is not requested as a Business',
    async (path) => {
      goTo(path)
      await renderRoot()
      expect(requestedUrls().join()).not.toMatch(/api\/public/)
    },
  )

  it('keeps authenticated hash routes on the application, even with a slug-like hash target', async () => {
    goTo('/#/business/services')
    await renderRoot()
    expect(requestedUrls().join()).not.toMatch(/api\/public/)
    expect(requestedUrls().some((url) => url.endsWith('/api/auth/session'))).toBe(true)
  })
})
