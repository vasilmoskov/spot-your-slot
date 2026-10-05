import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { ApiError, request, type Session } from './identity/api'
import { businessRequest } from './identity/businessRequest'

// Only the network helper is replaced: the feature API modules, their error handling, and the
// application's session recovery all run for real.
vi.mock('./identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)

const owner: Session = {
  email: 'ivan@example.invalid',
  displayName: 'Иван Собственик',
  platformAdmin: false,
  businesses: [
    { id: 'a', displayName: 'Студио А', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
    { id: 'b', displayName: 'Студио Б', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
  ],
  activeBusinessId: 'a',
}

const contextLost = () =>
  new ApiError(403, 'ACTIVE_BUSINESS_REQUIRED', 'Изберете бизнес, за да продължите.')

function withoutSelection(session: Session): Session {
  const { activeBusinessId: _selected, ...rest } = session
  void _selected
  return rest
}

let current: Session
// How the Business endpoints answer: a function per test.
let businessAnswer: (path: string, options?: RequestInit) => unknown | Promise<unknown>
const sessionCalls = () =>
  mockedRequest.mock.calls.filter(([path]) => path === '/api/auth/session').length

beforeEach(() => {
  history.replaceState({}, '', '/')
  current = { ...owner }
  businessAnswer = () => {
    throw new Error('unexpected business request')
  }
  mockedRequest.mockReset()
  mockedRequest.mockImplementation(async (path: string, options?: RequestInit) => {
    if (path === '/api/auth/session') return current
    if (path.startsWith('/api/business/')) return businessAnswer(path, options)
    throw new Error(`unexpected request ${path}`)
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

// The server clears the selection it no longer supports, then answers 403.
function losesContext() {
  businessAnswer = () => {
    current = withoutSelection(current)
    throw contextLost()
  }
}

async function expectRecovered(historyBefore: number) {
  expect(await screen.findByRole('heading', { level: 1, name: 'Бизнеси' })).toBeInTheDocument()
  await waitFor(() => expect(window.location.hash).toBe('#/businesses'))
  expect(screen.queryByRole('group', { name: 'Студио А' })).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: 'Услуги' })).not.toBeInTheDocument()
  // The recovery replaced the entry; it added none.
  expect(history.length).toBe(historyBefore)
  // One refresh, never a loop: wait for any stray follow-up and count again.
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 30))
  })
  expect(sessionCalls()).toBe(2)
}

const staffRecord = {
  id: 's1',
  displayName: 'Анна Иванова',
  contactEmail: null,
  contactPhone: null,
  active: true,
  version: 0,
  createdAt: '2026-08-19T09:00:00Z',
  updatedAt: '2026-08-19T09:00:00Z',
}

describe('a lost Business context on every Business-scoped screen', () => {
  it.each([
    ['Services list', '#/business/services?page=0&size=10&sort=name&direction=asc'],
    ['Services detail', '#/business/services/x'],
    ['Staff list', '#/business/staff?page=0&size=10&sort=name&direction=asc'],
    ['Staff detail', '#/business/staff/x'],
    ['Working Hours', '#/business/schedule'],
    ['Schedule Changes list', '#/business/schedule/exceptions?from=2026-10-01&to=2026-10-31&page=0&size=10&sort=dates&direction=asc'],
    ['Schedule Changes create', '#/business/schedule/exceptions/new'],
    ['Schedule Changes detail', '#/business/schedule/exceptions/x'],
    ['Customers list', '#/business/customers?page=0&size=10&sort=name&direction=asc'],
    ['Customers detail', '#/business/customers/x'],
  ])('%s: refreshes the session once and returns to the Business selection', async (_name, hash) => {
    history.replaceState({}, '', `/${hash}`)
    losesContext()
    render(<App />)
    const before = history.length
    await expectRecovered(before)
  })

  it('Staff service assignments: a lost context found while loading them recovers the same way', async () => {
    history.replaceState({}, '', '/#/business/staff/s1')
    businessAnswer = (path) => {
      if (path === '/api/business/staff-members/s1') return staffRecord
      current = withoutSelection(current)
      throw contextLost()
    }
    render(<App />)
    await expectRecovered(history.length)
  })

  it.each([
    [
      'Services create',
      '#/business/services/new',
      async () => {
        fireEvent.change(await screen.findByLabelText('Име на услугата'), { target: { value: 'Масаж' } })
        fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '30' } })
        fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '20' } })
        fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))
      },
    ],
    [
      'Staff create',
      '#/business/staff/new',
      async () => {
        fireEvent.change(await screen.findByLabelText('Име на члена на екипа'), { target: { value: 'Мария' } })
        fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))
      },
    ],
    [
      'Customers create',
      '#/business/customers/new',
      async () => {
        fireEvent.change(await screen.findByLabelText('Име'), { target: { value: 'Мария' } })
        fireEvent.change(screen.getByLabelText('Имейл'), { target: { value: 'maria@example.test' } })
        fireEvent.click(screen.getByRole('button', { name: 'Добави' }))
      },
    ],
  ])('%s: a mutation that finds the context lost recovers, but only after the guard allows it', async (_name, hash, submit) => {
    history.replaceState({}, '', `/${hash}`)
    losesContext()
    render(<App />)
    await submit()
    // The form holds values, so leaving is never silent: the shared dialog asks first.
    expect(await screen.findByRole('alertdialog')).toHaveTextContent('Имате незапазени промени.')
    expect(window.location.hash).toBe(hash.slice(1) === '' ? '' : hash)
    // Business-scoped links are already gone: no unauthorized navigation remains.
    expect(screen.queryByRole('link', { name: 'Клиенти' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(window.location.hash).toBe(hash)
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30))
    })
    // No loop: staying does not ask again and no further refresh happens.
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(sessionCalls()).toBe(2)

    fireEvent.click(screen.getByRole('link', { name: 'Бизнеси' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Напусни' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Бизнеси' })).toBeInTheDocument()
    expect(window.location.hash).toBe('#/businesses')
  })

  it('reports several simultaneous failures with one session refresh', async () => {
    // Schedule Changes load the window and the whole team together: both calls fail.
    history.replaceState({}, '', '/#/business/schedule/exceptions?from=2026-10-01&to=2026-10-31&page=0&size=10&sort=dates&direction=asc')
    const failing: string[] = []
    businessAnswer = (path) => {
      failing.push(path)
      current = withoutSelection(current)
      throw contextLost()
    }
    render(<App />)
    await expectRecovered(history.length)
    expect(failing.length).toBeGreaterThanOrEqual(2)
  })

  it('keeps the context and the safe message when the refreshed session still carries the Business', async () => {
    history.replaceState({}, '', '/#/business/services?page=0&size=10&sort=name&direction=asc')
    // The server answers 403 once, but the session still holds the selection.
    businessAnswer = () => {
      throw contextLost()
    }
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Изберете бизнес, за да продължите.')
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30))
    })
    expect(screen.getByRole('group', { name: 'Студио А' })).toBeInTheDocument()
    expect(window.location.hash).toMatch(/^#\/business\/services/)
    expect(sessionCalls()).toBe(2)
  })

  it('never shows data of the old Business after recovery, even if its response arrives late', async () => {
    history.replaceState({}, '', '/#/business/customers?page=0&size=10&sort=name&direction=asc')
    let resolveOld!: (value: unknown) => void
    businessAnswer = (path) => {
      if (path.startsWith('/api/business/customers')) {
        return new Promise((resolve) => {
          resolveOld = resolve
        })
      }
      throw new Error('unexpected')
    }
    render(<App />)
    await waitFor(() => expect(resolveOld).toBeDefined())
    // Another request of the same session reports the loss.
    mockedRequest.mockImplementationOnce(async () => {
      current = withoutSelection(current)
      throw contextLost()
    })
    await act(async () => {
      await businessRequest('/api/business/services').catch(() => undefined)
    })
    expect(await screen.findByRole('heading', { level: 1, name: 'Бизнеси' })).toBeInTheDocument()
    await act(async () => {
      resolveOld({
        items: [{ id: 'old', displayName: 'Клиент на стария бизнес', phone: null, email: 'old@example.test' }],
        page: 0,
        size: 10,
        total: 1,
      })
    })
    expect(screen.queryByText('Клиент на стария бизнес')).not.toBeInTheDocument()
  })
})

describe('outcomes that are not a lost Business context', () => {
  it('a missing individual record keeps the Business context and shows the safe not-found state', async () => {
    history.replaceState({}, '', '/#/business/services/missing')
    businessAnswer = () => {
      throw new ApiError(404, 'SERVICE_NOT_FOUND', 'Услугата не е намерена.')
    }
    render(<App />)
    expect(await screen.findByText('Услугата не е намерена.')).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Студио А' })).toBeInTheDocument()
    expect(window.location.hash).toBe('#/business/services/missing')
    expect(sessionCalls()).toBe(1)
  })

  it('a missing Customer keeps the context too (unknown, foreign, and malformed IDs look alike)', async () => {
    history.replaceState({}, '', '/#/business/customers/missing')
    businessAnswer = () => {
      throw new ApiError(404, 'CUSTOMER_NOT_FOUND', 'Клиентът не е намерен.')
    }
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Клиентът не е намерен.')
    expect(screen.getByRole('group', { name: 'Студио А' })).toBeInTheDocument()
    expect(sessionCalls()).toBe(1)
  })

  it('a role problem (ACCESS_DENIED) is not mistaken for a lost context', async () => {
    history.replaceState({}, '', '/#/business/services?page=0&size=10&sort=name&direction=asc')
    businessAnswer = () => {
      throw new ApiError(403, 'ACCESS_DENIED', 'Нямате достъп до тази операция.')
    }
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Нямате достъп до тази операция.')
    expect(screen.getByRole('group', { name: 'Студио А' })).toBeInTheDocument()
    expect(sessionCalls()).toBe(1)
  })

  it('a SUSPENDED Business keeps the selection and read-only access', async () => {
    current = {
      ...owner,
      businesses: [{ ...owner.businesses[0]!, status: 'SUSPENDED' }, owner.businesses[1]!],
    }
    history.replaceState({}, '', '/#/business/services?page=0&size=10&sort=name&direction=asc')
    businessAnswer = () => ({ services: [], page: 0, size: 10, totalElements: 0 })
    render(<App />)
    await screen.findByText('Все още няма създадени услуги.')
    expect(screen.getByRole('group', { name: 'Студио А' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Добави нова услуга' })).not.toBeInTheDocument()
    expect(sessionCalls()).toBe(1)
  })

  it('a mutation that reveals the suspension keeps the selection (Customers refresh to the suspended state)', async () => {
    history.replaceState({}, '', '/#/business/customers/new')
    businessAnswer = () => {
      current = {
        ...current,
        businesses: [{ ...owner.businesses[0]!, status: 'SUSPENDED' }, owner.businesses[1]!],
      }
      throw new ApiError(409, 'BUSINESS_SUSPENDED', 'Спрян бизнес може само да преглежда данните си.')
    }
    render(<App />)
    fireEvent.change(await screen.findByLabelText('Име'), { target: { value: 'Мария' } })
    fireEvent.change(screen.getByLabelText('Имейл'), { target: { value: 'maria@example.test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Бизнесът е временно спрян. Можете да преглеждате и редактирате клиентите, но не можете да добавяте нови.',
    )
    expect(screen.getByRole('group', { name: 'Студио А' })).toBeInTheDocument()
    expect(window.location.hash).toBe('#/business/customers/new')
    expect(screen.queryByRole('heading', { level: 1, name: 'Бизнеси' })).not.toBeInTheDocument()
  })

  it('an expired login follows the existing authentication flow, not a Business recovery', async () => {
    history.replaceState({}, '', '/#/business/services?page=0&size=10&sort=name&direction=asc')
    businessAnswer = () => {
      throw new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.')
    }
    render(<App />)
    await screen.findByRole('heading', { level: 1, name: 'Услуги' })
    await waitFor(() =>
      expect(screen.queryByRole('heading', { level: 1, name: 'Услуги' })).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('heading', { name: 'Вход' })).toBeInTheDocument()
    expect(sessionCalls()).toBe(1)
  })

  it('an expired login found while refreshing the session also returns to the login', async () => {
    history.replaceState({}, '', '/#/business/services?page=0&size=10&sort=name&direction=asc')
    let sessionAnswers = 0
    mockedRequest.mockImplementation(async (path: string) => {
      if (path === '/api/auth/session') {
        sessionAnswers += 1
        if (sessionAnswers === 1) return current
        throw new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.')
      }
      throw contextLost()
    })
    render(<App />)
    await screen.findByRole('heading', { level: 1, name: 'Услуги' })
    await waitFor(() =>
      expect(screen.queryByRole('heading', { level: 1, name: 'Услуги' })).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('heading', { name: 'Вход' })).toBeInTheDocument()
  })

  it('a network or server failure shows retryable feedback and keeps the selected Business', async () => {
    history.replaceState({}, '', '/#/business/services?page=0&size=10&sort=name&direction=asc')
    businessAnswer = () => {
      throw new Error('network down')
    }
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Списъкът с услуги не може да бъде зареден.')
    expect(screen.getByRole('button', { name: 'Опитай отново' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Студио А' })).toBeInTheDocument()
    expect(sessionCalls()).toBe(1)
    businessAnswer = () => ({ services: [], page: 0, size: 10, totalElements: 0 })
    fireEvent.click(screen.getByRole('button', { name: 'Опитай отново' }))
    expect(await screen.findByText('Все още няма създадени услуги.')).toBeInTheDocument()
  })
})

describe('one lifecycle notice per SUSPENDED screen', () => {
  const suspended = (): Session => ({
    ...owner,
    businesses: [{ ...owner.businesses[0]!, status: 'SUSPENDED' }, owner.businesses[1]!],
  })
  const notices = () =>
    screen.getAllByRole('status').filter((node) => /спрян/i.test(node.textContent ?? ''))

  it.each([
    ['Services list', '#/business/services?page=0&size=10&sort=name&direction=asc', { services: [], page: 0, size: 10, totalElements: 0 }],
    ['Services create', '#/business/services/new', undefined],
    ['Staff list', '#/business/staff?page=0&size=10&sort=name&direction=asc', { staffMembers: [], page: 0, size: 10, totalElements: 0 }],
    ['Staff create', '#/business/staff/new', undefined],
    ['Customers create', '#/business/customers/new', undefined],
  ])('%s shows the shared notice once and no second lifecycle sentence', async (_name, hash, answer) => {
    current = suspended()
    history.replaceState({}, '', `/${hash}`)
    businessAnswer = () => answer
    render(<App />)
    await screen.findByRole('group', { name: 'Студио А' })
    await waitFor(() => expect(notices()).toHaveLength(1))
    expect(document.body.textContent?.match(/временно спрян/g)).toHaveLength(1)
  })
})

describe('the approved owner and administrator labels', () => {
  const link = (name: string) => screen.getByRole('link', { name })

  it('an ordinary owner sees Бизнеси for the selection page', async () => {
    history.replaceState({}, '', '/#/profile')
    render(<App />)
    await screen.findByRole('heading', { level: 1, name: 'Профил' })
    expect(link('Бизнеси')).toHaveAttribute('href', '/#/businesses')
    expect(screen.queryByRole('link', { name: 'Моите бизнеси' })).not.toBeInTheDocument()
  })

  it('a Platform Administrator sees Бизнеси for the platform list only', async () => {
    current = withoutSelection({ ...owner, platformAdmin: true, businesses: [] })
    history.replaceState({}, '', '/#/profile')
    render(<App />)
    await screen.findByRole('heading', { level: 1, name: 'Профил' })
    expect(link('Бизнеси')).toHaveAttribute('href', '/#/platform/businesses?page=0&size=10&sort=displayName&direction=asc')
    expect(screen.queryByRole('link', { name: 'Моите бизнеси' })).not.toBeInTheDocument()
  })

  it('an administrator who also owns Businesses gets Моите бизнеси as a distinct destination', async () => {
    current = { ...owner, platformAdmin: true }
    history.replaceState({}, '', '/#/profile')
    render(<App />)
    await screen.findByRole('heading', { level: 1, name: 'Профил' })
    expect(link('Бизнеси')).toHaveAttribute('href', expect.stringContaining('/#/platform/businesses'))
    expect(link('Моите бизнеси')).toHaveAttribute('href', '/#/businesses')
    fireEvent.click(link('Моите бизнеси'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Бизнеси' })).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: 'Вашите бизнеси' })).toHaveTextContent('Студио А')
    expect(document.body.textContent).not.toMatch(/BUSINESS_OWNER|MANAGER|STAFF|PLATFORM_ADMIN/)
  })
})
