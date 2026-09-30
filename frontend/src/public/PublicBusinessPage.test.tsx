import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  PublicProfileLoadError,
  type PublicBusinessProfile,
  type PublicProfileResult,
} from './api'
import { PublicBusinessPage } from './PublicBusinessPage'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  fetchPublicBusinessProfile: vi.fn(),
}))

const api = await import('./api')
const fetchProfile = vi.mocked(api.fetchPublicBusinessProfile)

const profile: PublicBusinessProfile = {
  slug: 'example-studio',
  displayName: 'Примерно студио',
  businessType: 'HAIR_SALON',
  description: 'Уютно студио в центъра.',
  phone: '+359 88 000 0000',
  address: {
    city: 'София',
    postalCode: '1000',
    street: 'Примерна улица',
    streetNumber: '1',
    details: 'вход Б',
  },
  services: [
    { name: 'Подстригване', description: 'Измиване и оформяне.', durationMinutes: 45, price: 25 },
    { name: 'Боядисване', description: null, durationMinutes: 120, price: 80.5 },
  ],
}

type Pending = {
  slug: string
  signal: AbortSignal
  resolve: (result: PublicProfileResult) => void
  reject: (error: unknown) => void
}

let pending: Pending[]

// Every request stays open until the test settles it, so races are driven
// deterministically and never with timers.
beforeEach(() => {
  document.title = 'SpotYourSlot'
  pending = []
  fetchProfile.mockReset()
  fetchProfile.mockImplementation(
    (slug, signal) =>
      new Promise<PublicProfileResult>((resolve, reject) => {
        pending.push({ slug, signal: signal as AbortSignal, resolve, reject })
      }),
  )
})

afterEach(() => cleanup())

async function settle(index: number, result: PublicProfileResult) {
  await act(async () => pending[index]!.resolve(result))
}

async function renderLoaded(overrides: Partial<PublicBusinessProfile> = {}) {
  const view = render(<PublicBusinessPage slug="example-studio" />)
  await settle(0, { kind: 'profile', profile: { ...profile, ...overrides } })
  return view
}

function head(selector: string) {
  return document.head.querySelector(selector)
}

describe('loading', () => {
  it('shows one status and no profile, heading or action', () => {
    render(<PublicBusinessPage slug="example-studio" />)

    expect(screen.getByRole('status')).toHaveTextContent('Зареждане на страницата…')
    expect(screen.queryByRole('heading')).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(fetchProfile).toHaveBeenCalledTimes(1)
    expect(fetchProfile).toHaveBeenCalledWith('example-studio', expect.any(AbortSignal))
    expect(document.title).toBe('SpotYourSlot')
  })
})

describe('profile', () => {
  it('presents every approved field', async () => {
    await renderLoaded()

    expect(screen.getByRole('heading', { level: 1, name: 'Примерно студио' })).toBeInTheDocument()
    expect(screen.getByText('Фризьорски салон')).toBeInTheDocument()
    expect(screen.getByText('Уютно студио в центъра.')).toBeInTheDocument()

    const phone = screen.getByRole('link', { name: '+359 88 000 0000' })
    expect(phone).toHaveAttribute('href', 'tel:+359880000000')

    const contacts = screen.getByRole('region', { name: 'Контакти' })
    expect(contacts).toHaveTextContent('Телефон')
    expect(contacts).toHaveTextContent('Адрес')
    expect(contacts).toHaveTextContent('Примерна улица 1')
    expect(contacts).toHaveTextContent('1000 София')
    expect(contacts).toHaveTextContent('вход Б')

    const services = screen.getByRole('list')
    const items = screen.getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(services).toContainElement(items[0]!)
    expect(items[0]).toHaveTextContent('Подстригване')
    expect(items[0]).toHaveTextContent('Измиване и оформяне.')
    expect(items[0]).toHaveTextContent('Продължителност45 мин.')
    expect(items[0]).toHaveTextContent('Цена25.00 €')
    expect(items[1]).toHaveTextContent('Боядисване')
    expect(items[1]).toHaveTextContent('Продължителност120 мин.')
    expect(items[1]).toHaveTextContent('Цена80.50 €')

    expect(screen.getByText('Онлайн запазването на час все още не е налично.')).toBeInTheDocument()
  })

  it('has exactly one h1 and a logical heading order', async () => {
    await renderLoaded()

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(
      screen.getAllByRole('heading').map((heading) => [heading.tagName, heading.textContent]),
    ).toEqual([
      ['H1', 'Примерно студио'],
      ['H2', 'Контакти'],
      ['H2', 'Услуги'],
      ['H3', 'Подстригване'],
      ['H3', 'Боядисване'],
    ])
    expect(screen.getByRole('main')).toBeInTheDocument()
  })

  it('moves focus to the heading once loaded', async () => {
    await renderLoaded()
    expect(screen.getByRole('heading', { level: 1 })).toHaveFocus()
  })

  it('offers only the telephone link: no booking, availability or administration', async () => {
    await renderLoaded()

    expect(screen.getAllByRole('link')).toHaveLength(1)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(
      /HAIR_SALON|ACTIVE|DRAFT|SUSPENDED|Запази час|Резервирай|свободн|слот/i,
    )
  })

  it('omits the whole contacts section when neither telephone nor address exists', async () => {
    await renderLoaded({ phone: null, address: null, description: null })

    expect(screen.queryByRole('region', { name: 'Контакти' })).not.toBeInTheDocument()
    expect(screen.queryByText('Телефон')).not.toBeInTheDocument()
    expect(screen.queryByText('Адрес')).not.toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(document.querySelector('.public-profile-header .public-description')).toBeNull()
  })

  it('shows only the telephone when there is no address, and only the address otherwise', async () => {
    const { unmount } = await renderLoaded({ address: null })
    expect(screen.getByText('Телефон')).toBeInTheDocument()
    expect(screen.queryByText('Адрес')).not.toBeInTheDocument()
    unmount()
    pending = []

    render(<PublicBusinessPage slug="example-studio" />)
    await settle(0, { kind: 'profile', profile: { ...profile, phone: null } })
    expect(screen.queryByText('Телефон')).not.toBeInTheDocument()
    expect(screen.getByText('Адрес')).toBeInTheDocument()
  })

  it('renders a partial address without separators or placeholders', async () => {
    await renderLoaded({
      address: { city: 'Пловдив', postalCode: null, street: null, streetNumber: null, details: null },
    })
    const contacts = screen.getByRole('region', { name: 'Контакти' })
    expect(contacts).toHaveTextContent('АдресПловдив')
    expect(contacts.textContent).not.toMatch(/[—–]|null|undefined|,\s*,/)
  })

  it('shows a telephone with nothing dialable as plain text', async () => {
    await renderLoaded({ phone: 'обадете се сутрин' })
    expect(screen.getByText('обадете се сутрин')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('never puts unsafe telephone text into a link', async () => {
    await renderLoaded({ phone: 'javascript:alert(1)' })
    const link = screen.queryByRole('link')
    expect(link?.getAttribute('href') ?? 'tel:').toMatch(/^tel:\+?\d*$/)
  })

  it('shows the empty-Services state while keeping identity and contacts', async () => {
    await renderLoaded({ services: [] })

    expect(screen.getByRole('heading', { level: 1, name: 'Примерно студио' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '+359 88 000 0000' })).toBeInTheDocument()
    expect(screen.getByText('В момента няма налични услуги за онлайн записване.')).toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
    expect(screen.getByText('Онлайн запазването на час все още не е налично.')).toBeInTheDocument()
  })

  it('omits an empty Service description container', async () => {
    await renderLoaded()
    const second = screen.getAllByRole('listitem')[1]!
    expect(second.querySelector('.public-description')).toBeNull()
  })

  it('keeps every Service in the order the API returned, however many', async () => {
    const services = Array.from({ length: 30 }, (_, index) => ({
      name: `Услуга ${30 - index}`,
      description: null,
      durationMinutes: 15,
      price: 1,
    }))
    await renderLoaded({ services })
    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(
      services.map((service) => service.name),
    )
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('renders API text as text, never as markup', async () => {
    await renderLoaded({ displayName: '<img src=x onerror=alert(1)>', description: '<b>смело</b>' })
    expect(document.querySelector('img')).toBeNull()
    expect(document.querySelector('.public-description b')).toBeNull()
    expect(screen.getByText('<b>смело</b>')).toBeInTheDocument()
  })

  it('degrades an unknown Business type without exposing it', async () => {
    await renderLoaded({ businessType: 'SPACE_STATION' })
    expect(screen.getByText('Друг')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('SPACE_STATION')
  })
})

describe('unavailable', () => {
  it('shows the single generic page without echoing the slug or linking anywhere', async () => {
    render(<PublicBusinessPage slug="secret-draft" />)
    await settle(0, { kind: 'unavailable' })

    const heading = screen.getByRole('heading', { level: 1, name: 'Страницата не е налична' })
    expect(heading).toHaveFocus()
    expect(screen.getByText('Проверете адреса или опитайте по-късно.')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('secret-draft')
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/BUSINESS_PAGE_UNAVAILABLE|404|DRAFT|SUSPENDED|вход/i)
  })
})

describe('temporary failure', () => {
  it('is distinct from unavailable and never shows raw details', async () => {
    render(<PublicBusinessPage slug="example-studio" />)
    await act(async () => pending[0]!.reject(new PublicProfileLoadError()))

    const heading = screen.getByRole('heading', {
      level: 1,
      name: 'Страницата не може да бъде заредена.',
    })
    expect(heading).toHaveFocus()
    expect(screen.getByText('Проверете връзката си и опитайте отново.')).toBeInTheDocument()
    expect(screen.queryByText('Страницата не е налична')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Опитайте отново' })).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/PublicProfileLoadError|could not|500|TypeError/)
  })

  it('treats any unexpected rejection as a failure', async () => {
    render(<PublicBusinessPage slug="example-studio" />)
    await act(async () => pending[0]!.reject(new TypeError('Failed to fetch: secret.internal')))
    expect(screen.getByRole('button', { name: 'Опитайте отново' })).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('secret.internal')
  })

  it('issues exactly one new request per retry and shows the result', async () => {
    render(<PublicBusinessPage slug="example-studio" />)
    await act(async () => pending[0]!.reject(new PublicProfileLoadError()))
    expect(fetchProfile).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Опитайте отново' }))
    expect(fetchProfile).toHaveBeenCalledTimes(2)
    expect(screen.getByRole('status')).toHaveTextContent('Зареждане на страницата…')
    expect(screen.queryByRole('button', { name: 'Опитайте отново' })).not.toBeInTheDocument()

    await settle(1, { kind: 'profile', profile })
    expect(screen.getByRole('heading', { level: 1, name: 'Примерно студио' })).toBeInTheDocument()
    expect(fetchProfile).toHaveBeenCalledTimes(2)
  })

  it('does not retry on its own', async () => {
    render(<PublicBusinessPage slug="example-studio" />)
    await act(async () => pending[0]!.reject(new PublicProfileLoadError()))
    await act(async () => undefined)
    expect(fetchProfile).toHaveBeenCalledTimes(1)
  })

  it('fails again visibly when the retry fails', async () => {
    render(<PublicBusinessPage slug="example-studio" />)
    await act(async () => pending[0]!.reject(new PublicProfileLoadError()))
    fireEvent.click(screen.getByRole('button', { name: 'Опитайте отново' }))
    await act(async () => pending[1]!.reject(new PublicProfileLoadError()))
    expect(screen.getByRole('button', { name: 'Опитайте отново' })).toBeInTheDocument()
    expect(fetchProfile).toHaveBeenCalledTimes(2)
  })
})

describe('slug changes and stale responses', () => {
  it('aborts the previous request and ignores its late answer', async () => {
    const view = render(<PublicBusinessPage slug="business-a" />)
    view.rerender(<PublicBusinessPage slug="business-b" />)

    expect(pending).toHaveLength(2)
    expect(pending[0]!.signal.aborted).toBe(true)
    expect(pending[1]!.signal.aborted).toBe(false)

    await settle(1, { kind: 'profile', profile: { ...profile, slug: 'business-b', displayName: 'Бизнес Б' } })
    await settle(0, { kind: 'profile', profile: { ...profile, slug: 'business-a', displayName: 'Бизнес А' } })

    expect(screen.getByRole('heading', { level: 1, name: 'Бизнес Б' })).toBeInTheDocument()
    expect(screen.queryByText('Бизнес А')).not.toBeInTheDocument()
    expect(document.title).toBe('Бизнес Б – SpotYourSlot')
  })

  it('shows loading, not the previous Business, while the next one loads', async () => {
    const view = render(<PublicBusinessPage slug="business-a" />)
    await settle(0, { kind: 'profile', profile: { ...profile, displayName: 'Бизнес А' } })
    expect(screen.getByText('Бизнес А')).toBeInTheDocument()
    expect(document.title).toBe('Бизнес А – SpotYourSlot')

    view.rerender(<PublicBusinessPage slug="business-b" />)

    expect(screen.queryByText('Бизнес А')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(document.title).toBe('SpotYourSlot')
    expect(head('link[rel="canonical"]')).toBeNull()

    await settle(1, { kind: 'unavailable' })
    expect(screen.getByRole('heading', { name: 'Страницата не е налична' })).toBeInTheDocument()
    expect(screen.queryByText('Бизнес А')).not.toBeInTheDocument()
  })

  it('aborts on unmount and never updates afterwards', async () => {
    const view = render(<PublicBusinessPage slug="example-studio" />)
    view.unmount()
    expect(pending[0]!.signal.aborted).toBe(true)
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    await settle(0, { kind: 'profile', profile })
    expect(errors).not.toHaveBeenCalled()
    errors.mockRestore()
    expect(document.title).toBe('SpotYourSlot')
  })

  it('ignores a failure of a superseded request', async () => {
    const view = render(<PublicBusinessPage slug="business-a" />)
    view.rerender(<PublicBusinessPage slug="business-b" />)
    await act(async () => pending[0]!.reject(new DOMException('Aborted', 'AbortError')))
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Опитайте отново' })).not.toBeInTheDocument()
  })

  it('ignores the answer of the attempt replaced by a retry', async () => {
    render(<PublicBusinessPage slug="example-studio" />)
    await act(async () => pending[0]!.reject(new PublicProfileLoadError()))
    fireEvent.click(screen.getByRole('button', { name: 'Опитайте отново' }))
    await settle(1, { kind: 'unavailable' })
    expect(screen.getByRole('heading', { name: 'Страницата не е налична' })).toBeInTheDocument()
    expect(fetchProfile).toHaveBeenCalledTimes(2)
  })
})

describe('metadata', () => {
  beforeEach(() => {
    document.head.querySelectorAll('meta[name="robots"], link[rel="canonical"]').forEach((e) => e.remove())
    document.head.querySelector('meta[name="description"]')?.remove()
    const shell = document.createElement('meta')
    shell.setAttribute('name', 'description')
    shell.setAttribute('content', 'Описание на обвивката')
    document.head.appendChild(shell)
    document.title = 'SpotYourSlot'
  })

  it('sets the title, description and canonical URL for a Business', async () => {
    await renderLoaded()
    expect(document.title).toBe('Примерно студио – SpotYourSlot')
    expect(head('meta[name="description"]')).toHaveAttribute('content', 'Уютно студио в центъра.')
    expect(head('link[rel="canonical"]')).toHaveAttribute(
      'href',
      `${window.location.origin}/example-studio`,
    )
    expect(head('meta[name="robots"]')).toBeNull()
  })

  it('generates a description when the Business has none and truncates a long one', async () => {
    const { unmount } = await renderLoaded({ description: null })
    expect(head('meta[name="description"]')).toHaveAttribute(
      'content',
      'Информация и услуги на Примерно студио.',
    )
    unmount()
    pending = []

    render(<PublicBusinessPage slug="example-studio" />)
    await settle(0, { kind: 'profile', profile: { ...profile, description: 'дума '.repeat(100) } })
    const content = head('meta[name="description"]')!.getAttribute('content')!
    expect(Array.from(content).length).toBeLessThanOrEqual(160)
    expect(content.endsWith('…')).toBe(true)
  })

  it.each([
    ['unavailable', async () => settle(0, { kind: 'unavailable' })],
    ['failure', async () => act(async () => pending[0]!.reject(new PublicProfileLoadError()))],
  ])('marks the %s state noindex with the generic title and no canonical URL', async (_n, act_) => {
    render(<PublicBusinessPage slug="example-studio" />)
    await act_()
    expect(document.title).toBe('Страницата не е налична – SpotYourSlot')
    expect(head('meta[name="robots"]')).toHaveAttribute('content', 'noindex')
    expect(head('link[rel="canonical"]')).toBeNull()
    expect(head('meta[name="description"]')).toHaveAttribute('content', 'Описание на обвивката')
    expect(document.title).not.toContain('example-studio')
  })

  it('restores the shell defaults when the page unmounts', async () => {
    const view = await renderLoaded()
    view.unmount()
    expect(document.title).toBe('SpotYourSlot')
    expect(head('meta[name="description"]')).toHaveAttribute('content', 'Описание на обвивката')
    expect(head('link[rel="canonical"]')).toBeNull()
    expect(head('meta[name="robots"]')).toBeNull()
  })

  it('leaves no Open Graph, structured-data or other advanced tags', async () => {
    await renderLoaded()
    expect(document.head.querySelector('meta[property^="og:"], script[type="application/ld+json"]')).toBeNull()
  })
})

describe('side effects', () => {
  it('writes no browser storage and sets no cookie', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const before = document.cookie
    await renderLoaded()
    expect(setItem).not.toHaveBeenCalled()
    expect(document.cookie).toBe(before)
    setItem.mockRestore()
    await waitFor(() => expect(fetchProfile).toHaveBeenCalledTimes(1))
  })
})
