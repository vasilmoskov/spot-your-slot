import '@testing-library/jest-dom/vitest'
import { cleanup, configure, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { request, type Session } from './identity/api'
import { listServices } from './business/services/api'
import { listCustomers } from './business/customers/api'
import { pageOf, summary } from './business/customers/testFixtures'

vi.mock('./identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./identity/api')>()),
  request: vi.fn(),
}))
vi.mock('./business/services/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./business/services/api')>()),
  listServices: vi.fn(),
}))
vi.mock('./business/customers/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./business/customers/api')>()),
  listCustomers: vi.fn(),
  getCustomer: vi.fn(),
  createCustomer: vi.fn(),
}))

// These tests render whole application screens; under a loaded full run the default one-second
// ceiling of the async queries can be reached. It is only a ceiling: nothing sleeps.
configure({ asyncUtilTimeout: 4000 })

const mockedRequest = vi.mocked(request)
const mockedServices = vi.mocked(listServices)
const mockedCustomers = vi.mocked(listCustomers)

const base: Session = {
  email: 'ivan@example.invalid',
  displayName: 'Иван Собственик',
  platformAdmin: false,
  businesses: [
    { id: 'draft', displayName: 'Студио Чернова', role: 'BUSINESS_OWNER', status: 'DRAFT' },
    { id: 'active', displayName: 'Студио Активно', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
    { id: 'suspended', displayName: 'Студио Спряно', role: 'BUSINESS_OWNER', status: 'SUSPENDED' },
    // Not a Business this user manages: never offered.
    { id: 'foreign', displayName: 'Чужд бизнес', role: 'MANAGER', status: 'ACTIVE' },
  ],
}

const customersOf: Record<string, string[]> = {
  draft: ['Клиент на чернова'],
  active: ['Клиент на активния'],
  suspended: ['Клиент на спрения'],
}

let current: Session

beforeEach(() => {
  history.replaceState({}, '', '/')
  current = { ...base }
  mockedRequest.mockReset()
  mockedRequest.mockImplementation(async (path: string, options?: RequestInit) => {
    if (path === '/api/auth/session') return current
    if (path === '/api/auth/business') {
      const { businessId } = JSON.parse(String(options?.body)) as { businessId: string }
      current = { ...current, activeBusinessId: businessId }
      return current
    }
    throw new Error(`unexpected request ${path}`)
  })
  mockedServices.mockReset()
  mockedServices.mockResolvedValue({ services: [], page: 0, size: 10, totalElements: 0 })
  mockedCustomers.mockReset()
  mockedCustomers.mockImplementation(async () =>
    pageOf(
      (customersOf[current.activeBusinessId ?? ''] ?? []).map((name, index) =>
        summary({ id: `${current.activeBusinessId}-${index}`, displayName: name }),
      ),
    ),
  )
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

const linkNames = () => screen.getAllByRole('link').map((link) => link.textContent)

describe('without a selected Business', () => {
  it('opens the Business selection with only the global links and no Business-scoped link', async () => {
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Бизнеси' })).toBeInTheDocument()
    expect(window.location.hash).toBe('#/businesses')
    expect(linkNames()).toEqual(['Бизнеси', 'Профил'])
    for (const name of ['Услуги', 'Екип', 'Работно време', 'Клиенти']) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument()
    }
    expect(screen.queryByRole('group')).not.toBeInTheDocument()
  })

  it('lists only the Businesses the user manages, with lifecycle wording and no technical role', async () => {
    render(<App />)
    const list = await screen.findByRole('list', { name: 'Вашите бизнеси' })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(list).toHaveTextContent('Студио Чернова')
    expect(list).toHaveTextContent('Предстои активиране')
    expect(list).toHaveTextContent('Активен')
    expect(list).toHaveTextContent('Временно спрян')
    expect(screen.queryByText('Чужд бизнес')).not.toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/BUSINESS_OWNER|MANAGER|STAFF|DRAFT|SUSPENDED/)
    expect(screen.queryByText('Избран')).not.toBeInTheDocument()
    expect(screen.queryByText('Управлявай')).not.toBeInTheDocument()
    // Each card is named by its Business heading and carries a plain "Покажи" button.
    for (const name of ['Студио Чернова', 'Студио Активно', 'Студио Спряно']) {
      const card = screen.getByRole('article', { name })
      expect(within(card).getByRole('heading', { level: 2, name })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: 'Покажи' })).toBeInTheDocument()
      expect(card).not.toHaveAttribute('aria-current')
    }
    expect(screen.getAllByRole('button', { name: 'Покажи' })).toHaveLength(3)
  })

  it('has no Business selector on the Profile', async () => {
    render(<App />)
    await screen.findByRole('heading', { name: 'Бизнеси' })
    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Профил' })).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Избери бизнес')).not.toBeInTheDocument()
    expect(screen.queryByText('Студио Активно')).not.toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/BUSINESS_OWNER|MANAGER|STAFF/)
  })

  it('keeps a platform administrator who manages nothing out of the owner selection', async () => {
    current = { ...base, platformAdmin: true, businesses: [] }
    history.replaceState({}, '', '/#/businesses')
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Профил' })).toBeInTheDocument()
    expect(window.location.hash).toBe('#/profile')
    expect(screen.queryByRole('link', { name: 'Моите бизнеси' })).not.toBeInTheDocument()
  })
})

describe('selecting a Business', () => {
  async function manage(name: string) {
    const card = await screen.findByRole('article', { name })
    fireEvent.click(within(card).getByRole('button', { name: 'Покажи' }))
  }

  it('establishes the context, opens Услуги and shows the Business-scoped links under its name', async () => {
    render(<App />)
    await manage('Студио Активно')
    expect(await screen.findByRole('heading', { level: 1, name: 'Услуги' })).toBeInTheDocument()
    expect(mockedRequest).toHaveBeenCalledWith(
      '/api/auth/business',
      expect.objectContaining({ body: JSON.stringify({ businessId: 'active' }) }),
    )
    expect(window.location.hash).toBe('#/business/services?page=0&size=10&sort=name&direction=asc')
    const group = screen.getByRole('group', { name: 'Студио Активно' })
    expect(within(group).getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Услуги',
      'Екип',
      'Работно време',
      'Клиенти',
    ])
    expect(linkNames().slice(0, 2)).toEqual(['Бизнеси', 'Профил'])
    expect(document.querySelector('.sidebar-account')).toHaveTextContent('Иван Собственик')
    expect(document.body.textContent).not.toMatch(/BUSINESS_OWNER|MANAGER|STAFF/)
  })

  it('marks the selected Business on the selection page and opens its Услуги without a new request', async () => {
    render(<App />)
    await manage('Студио Активно')
    await screen.findByRole('heading', { name: 'Услуги' })
    fireEvent.click(screen.getByRole('link', { name: 'Бизнеси' }))
    const selected = await screen.findByRole('article', { name: 'Студио Активно' })
    expect(selected).toHaveAttribute('aria-current', 'true')
    expect(selected).toHaveClass('is-selected')
    expect(within(selected).getByText('Текущо избран бизнес')).toBeInTheDocument()
    // No visible "Избран" badge; exactly one card is marked as the current one.
    expect(screen.queryByText('Избран')).not.toBeInTheDocument()
    expect(document.querySelectorAll('[aria-current="true"]')).toHaveLength(1)
    expect(screen.getByRole('article', { name: 'Студио Спряно' })).not.toHaveAttribute('aria-current')

    mockedRequest.mockClear()
    fireEvent.click(within(selected).getByRole('button', { name: 'Покажи' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Услуги' })).toBeInTheDocument()
    expect(mockedRequest).not.toHaveBeenCalledWith('/api/auth/business', expect.anything())
  })

  it.each([
    ['Студио Чернова', 'draft', false],
    ['Студио Активно', 'active', false],
    ['Студио Спряно', 'suspended', true],
  ])('can select %s and shows the matching lifecycle state', async (name, id, suspended) => {
    render(<App />)
    await manage(name)
    await screen.findByRole('heading', { name: 'Услуги' })
    expect(current.activeBusinessId).toBe(id)
    expect(screen.getByRole('group', { name })).toBeInTheDocument()
    expect(screen.queryAllByRole('status').some((node) => /спрян/i.test(node.textContent ?? ''))).toBe(
      suspended,
    )
  })

  it('never shows one Business\'s Customers in another after switching', async () => {
    render(<App />)
    await manage('Студио Активно')
    await screen.findByRole('heading', { name: 'Услуги' })
    fireEvent.click(screen.getByRole('link', { name: 'Клиенти' }))
    expect(await screen.findByText('Клиент на активния')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('link', { name: 'Бизнеси' }))
    await manage('Студио Спряно')
    await screen.findByRole('heading', { name: 'Услуги' })
    fireEvent.click(screen.getByRole('link', { name: 'Клиенти' }))
    expect(await screen.findByText('Клиент на спрения')).toBeInTheDocument()
    expect(screen.queryByText('Клиент на активния')).not.toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Студио Спряно' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Добави клиент' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('link', { name: 'Бизнеси' }))
    await manage('Студио Чернова')
    await screen.findByRole('heading', { name: 'Услуги' })
    fireEvent.click(screen.getByRole('link', { name: 'Клиенти' }))
    expect(await screen.findByText('Клиент на чернова')).toBeInTheDocument()
    expect(screen.queryByText('Клиент на спрения')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Добави клиент' })).toBeInTheDocument()
  })

  it('does not select anything when the request fails, and shows a safe message', async () => {
    mockedRequest.mockImplementation(async (path: string) => {
      if (path === '/api/auth/session') return current
      throw new Error('network failure')
    })
    render(<App />)
    await manage('Студио Активно')
    expect(await screen.findByRole('alert')).toHaveTextContent('Възникна грешка. Опитайте отново.')
    expect(screen.getByRole('heading', { level: 1, name: 'Бизнеси' })).toBeInTheDocument()
    expect(screen.queryByRole('group')).not.toBeInTheDocument()
  })
})

describe('refresh and unavailable Business', () => {
  it('keeps the selected Business across a refresh through the session', async () => {
    const first = render(<App />)
    fireEvent.click(
      within(await screen.findByRole('article', { name: 'Студио Активно' })).getByRole('button', {
        name: 'Покажи',
      }),
    )
    await screen.findByRole('heading', { name: 'Услуги' })
    first.unmount()

    history.replaceState({}, '', '/#/business/customers')
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Клиенти' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Студио Активно' })).toBeInTheDocument()
    expect(await screen.findByText('Клиент на активния')).toBeInTheDocument()
  })

  it('opens the selection instead of Business screens when a direct link has no Business context', async () => {
    history.replaceState({}, '', '/#/business/staff')
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Бизнеси' })).toBeInTheDocument()
    expect(window.location.hash).toBe('#/businesses')
  })
})

describe('the unsaved-changes guard around Business context', () => {
  beforeEach(() => {
    current = { ...base, activeBusinessId: 'active' }
  })

  it('guards sidebar navigation to the Business selection from a dirty Customer form', async () => {
    history.replaceState({}, '', '/#/business/customers/new')
    render(<App />)
    fireEvent.change(await screen.findByLabelText('Име'), { target: { value: 'Мария' } })

    fireEvent.click(screen.getByRole('link', { name: 'Бизнеси' }))
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Имате незапазени промени.')
    expect(screen.getByRole('button', { name: 'Остани' })).toHaveFocus()
    expect(window.location.hash).toMatch(/customers\/new/)
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(screen.getByLabelText('Име')).toHaveValue('Мария')

    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Профил' })).toBeInTheDocument()
  })

  it('guards logout from a dirty Customer form', async () => {
    history.replaceState({}, '', '/#/business/customers/new')
    render(<App />)
    fireEvent.change(await screen.findByLabelText('Име'), { target: { value: 'Мария' } })
    fireEvent.click(screen.getAllByRole('button', { name: 'Изход' })[0]!)
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(mockedRequest).not.toHaveBeenCalledWith('/api/auth/logout', expect.anything())
  })
})
