import '@testing-library/jest-dom/vitest'
import { useState } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App, AuthenticatedApplication } from './App'
import { ApiError, request, type Session } from './identity/api'
import { UnsavedChangesGuardProvider } from './ui/UnsavedChangesGuard'
import { useFeedback } from './ui/useFeedback'
import {
  createCustomer,
  getCustomer,
  listCustomers,
  updateCustomer,
  type CustomerPage,
} from './business/customers/api'
import { deferred, detail, pageOf, summary } from './business/customers/testFixtures'

vi.mock('./identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./identity/api')>()),
  request: vi.fn(),
}))
vi.mock('./business/customers/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./business/customers/api')>()),
  listCustomers: vi.fn(),
  getCustomer: vi.fn(),
  createCustomer: vi.fn(),
  updateCustomer: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
const mockedList = vi.mocked(listCustomers)
const mockedGet = vi.mocked(getCustomer)
const mockedCreate = vi.mocked(createCustomer)
const mockedUpdate = vi.mocked(updateCustomer)

const owner: Session = {
  email: 'ivan@example.invalid',
  displayName: 'Иван',
  platformAdmin: false,
  businesses: [{ id: 'a', displayName: 'Бизнес А', role: 'BUSINESS_OWNER', status: 'ACTIVE' }],
  activeBusinessId: 'a',
}

const CUSTOMERS_SUSPENDED =
  'Бизнесът е временно спрян. Можете да преглеждате и редактирате клиентите, но не можете да добавяте нови.'

const people = [
  summary({ id: 'c1', displayName: 'Анна Тестова' }),
  summary({ id: 'c2', displayName: 'Борис Пробен', phone: null }),
]

beforeEach(() => {
  history.replaceState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
  mockedRequest.mockReset()
  mockedRequest.mockResolvedValue(owner)
  mockedList.mockReset()
  mockedList.mockResolvedValue(pageOf(people))
  mockedGet.mockReset()
  mockedGet.mockResolvedValue(detail({ id: 'c1', displayName: 'Анна Тестова' }))
  mockedCreate.mockReset()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('customer navigation and routes', () => {
  it('shows Клиенти in the owner navigation and opens the list with one page heading', async () => {
    history.replaceState({}, '', '/#/business/customers')
    render(<App />)
    expect(await screen.findByText('Анна Тестова')).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Клиенти')
    const links = screen.getAllByRole('link').map((link) => link.textContent)
    expect(links).toEqual(
      expect.arrayContaining(['Услуги', 'Екип', 'Работно време', 'Клиенти', 'Профил']),
    )
    expect(screen.getByRole('link', { name: 'Клиенти' })).toHaveAttribute('aria-current', 'page')
  })

  it('navigates from the sidebar to the canonical list route', async () => {
    history.replaceState({}, '', '/#/profile')
    render(<App />)
    fireEvent.click(await screen.findByRole('link', { name: 'Клиенти' }))
    await screen.findByText('Анна Тестова')
    expect(window.location.hash).toBe('#/business/customers?page=0&size=10&sort=name&direction=asc')
  })

  it('canonicalizes an invalid list URL by replacing history', async () => {
    history.replaceState({}, '', '/#/business/customers?page=-3&size=7&sort=zzz&direction=up')
    const before = history.length
    render(<App />)
    await screen.findByText('Анна Тестова')
    await waitFor(() =>
      expect(window.location.hash).toBe('#/business/customers?page=0&size=10&sort=name&direction=asc'),
    )
    expect(history.length).toBe(before)
    expect(mockedList).toHaveBeenCalledWith(
      { page: 0, size: 10, sort: 'name', direction: 'asc' },
      '',
      expect.any(AbortSignal),
    )
  })

  it('restores a direct link and a refresh with the same list state', async () => {
    history.replaceState({}, '', '/#/business/customers?page=1&size=25&sort=phone&direction=desc')
    mockedList.mockResolvedValue(pageOf(people, { page: 1, size: 25, total: 40 }))
    render(<App />)
    await screen.findByText('Анна Тестова')
    expect(mockedList).toHaveBeenCalledWith(
      { page: 1, size: 25, sort: 'phone', direction: 'desc' },
      '',
      expect.any(AbortSignal),
    )
    expect(window.location.hash).toBe('#/business/customers?page=1&size=25&sort=phone&direction=desc')
  })

  it('keeps page, size and sort across list, detail and back, and pushes history', async () => {
    history.replaceState({}, '', '/#/business/customers?page=1&size=25&sort=email&direction=desc')
    mockedList.mockResolvedValue(pageOf(people, { page: 1, size: 25, total: 40 }))
    render(<App />)
    fireEvent.click(await screen.findByRole('link', { name: 'Отвори Анна Тестова' }))
    await screen.findByRole('button', { name: 'Редактирай' })
    expect(window.location.hash).toBe(
      '#/business/customers/c1?page=1&size=25&sort=email&direction=desc',
    )
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Клиент')
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към клиентите' }))
    await screen.findByText('Анна Тестова')
    expect(window.location.hash).toBe('#/business/customers?page=1&size=25&sort=email&direction=desc')
  })

  it('keeps the list state through create and cancel', async () => {
    history.replaceState({}, '', '/#/business/customers?page=0&size=50&sort=phone&direction=asc')
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Добави клиент' }))
    await screen.findByLabelText('Име')
    expect(window.location.hash).toBe(
      '#/business/customers/new?page=0&size=50&sort=phone&direction=asc',
    )
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Нов клиент')
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към клиентите' }))
    await screen.findByText('Анна Тестова')
    expect(window.location.hash).toBe('#/business/customers?page=0&size=50&sort=phone&direction=asc')
  })

  it('supports browser Back and Forward between the list and a detail', async () => {
    history.replaceState({}, '', '/#/business/customers')
    render(<App />)
    fireEvent.click(await screen.findByRole('link', { name: 'Отвори Анна Тестова' }))
    await screen.findByRole('button', { name: 'Редактирай' })
    act(() => history.back())
    await screen.findByRole('link', { name: 'Отвори Борис Пробен' })
    expect(window.location.hash).toMatch(/^#\/business\/customers\?/)
    act(() => history.forward())
    await screen.findByRole('button', { name: 'Редактирай' })
  })

  it('recovers a stale later page of an empty catalog by replacing history, without a trap or a loop', async () => {
    history.replaceState({}, '', '/#/business/customers?page=2&size=25&sort=email&direction=desc')
    const before = history.length
    mockedList.mockResolvedValue(pageOf([], { page: 0, size: 25, total: 0 }))
    render(<App />)
    expect(await screen.findByText('Все още няма добавени клиенти.')).toBeInTheDocument()
    expect(window.location.hash).toBe('#/business/customers?page=0&size=25&sort=email&direction=desc')
    expect(history.length).toBe(before)
    expect(mockedList.mock.calls.map((call) => call[0].page)).toEqual([2, 0])
  })

  it('keeps the in-memory search when an empty search result is recovered to page 0, and the recovery adds no history entry', async () => {
    history.replaceState({}, '', '/#/business/customers')
    mockedList.mockResolvedValue(pageOf(people, { page: 0, total: 30 }))
    render(<App />)
    await screen.findByText('Анна Тестова')
    fireEvent.change(screen.getByLabelText('Търсене', { exact: true }), { target: { value: 'Анна' } })
    fireEvent.submit(screen.getByRole('search', { name: 'Търсене на клиенти' }))
    await screen.findByText('Анна Тестова')
    const beforePush = history.length
    // The next page no longer exists and nothing matches any more.
    mockedList.mockImplementation((query) =>
      Promise.resolve(pageOf([], { page: query.page, total: 0 })),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Следваща' }))
    expect(await screen.findByText('Не са намерени клиенти по това търсене.')).toBeInTheDocument()
    expect(window.location.hash).toBe('#/business/customers?page=0&size=10&sort=name&direction=asc')
    // One pushed entry (the user's click), no second one for the recovery.
    expect(history.length).toBe(beforePush + 1)
    expect(screen.getByLabelText('Търсене', { exact: true })).toHaveValue('Анна')
    expect(mockedList).toHaveBeenLastCalledWith(
      { page: 0, size: 10, sort: 'name', direction: 'asc' },
      'Анна',
      expect.any(AbortSignal),
    )
    expect(window.location.href).not.toContain('Анна')
  })

  it('writes a sort change as a pushed history entry', async () => {
    history.replaceState({}, '', '/#/business/customers')
    render(<App />)
    await screen.findByText('Анна Тестова')
    const before = history.length
    fireEvent.click(within(screen.getAllByRole('columnheader')[1]!).getByRole('button'))
    await waitFor(() => expect(window.location.hash).toContain('sort=phone'))
    expect(history.length).toBe(before + 1)
  })

  it('shows the same safe unavailable state for an unknown or malformed Customer link', async () => {
    mockedGet.mockRejectedValue(
      Object.assign(new Error('x'), { name: 'ApiError', status: 404, code: 'CUSTOMER_NOT_FOUND' }),
    )
    history.replaceState({}, '', '/#/business/customers/not-a-uuid')
    render(<App />)
    // A non-ApiError failure is retryable and still never leaks the raw message.
    expect(await screen.findByRole('alert')).not.toHaveTextContent('x')
  })
})

describe('access to the Customer area', () => {
  it('does not expose Клиенти or the route to a Platform Administrator without an owner Membership', async () => {
    mockedRequest.mockResolvedValue({
      email: 'admin@example.invalid',
      displayName: 'Админ',
      platformAdmin: true,
      businesses: [],
    })
    history.replaceState({}, '', '/#/business/customers')
    render(<App />)
    await screen.findByRole('heading', { name: 'Профил' }).catch(() => undefined)
    await waitFor(() => expect(window.location.hash).toBe('#/profile'))
    expect(screen.queryByRole('link', { name: 'Клиенти' })).toBeNull()
    expect(mockedList).not.toHaveBeenCalled()
  })

  it.each(['MANAGER', 'STAFF'] as const)('does not expose the area to a %s Membership', async (role) => {
    mockedRequest.mockResolvedValue({
      ...owner,
      businesses: [{ id: 'a', displayName: 'Бизнес А', role, status: 'ACTIVE' }],
    })
    history.replaceState({}, '', '/#/business/customers/new')
    render(<App />)
    await waitFor(() => expect(window.location.hash).toBe('#/profile'))
    expect(screen.queryByRole('link', { name: 'Клиенти' })).toBeNull()
    expect(mockedList).not.toHaveBeenCalled()
  })

  it('does not render the Customer area before sign-in', async () => {
    mockedRequest.mockRejectedValue(new Error('no session'))
    history.replaceState({}, '', '/#/business/customers')
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Вход' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Клиенти' })).toBeNull()
    expect(mockedList).not.toHaveBeenCalled()
  })
})

describe('in-memory customer search', () => {
  async function searchFor(term: string) {
    fireEvent.change(await screen.findByLabelText('Търсене'), { target: { value: term } })
    fireEvent.submit(screen.getByRole('search', { name: 'Търсене на клиенти' }))
    await waitFor(() =>
      expect(mockedList).toHaveBeenLastCalledWith(expect.anything(), term, expect.any(AbortSignal)),
    )
    await screen.findByText('Анна Тестова')
  }

  it('survives list, detail and return in the mounted session without entering the URL or storage', async () => {
    history.replaceState({}, '', '/#/business/customers')
    render(<App />)
    await searchFor('Тайно 0895555777')
    const leakCheck = () => {
      const everything = [
        window.location.href,
        JSON.stringify({ ...window.localStorage }),
        JSON.stringify({ ...window.sessionStorage }),
        document.cookie,
        document.title,
      ].join('|')
      expect(everything).not.toContain('Тайно')
      expect(everything).not.toContain('0895555777')
    }
    leakCheck()
    fireEvent.click(screen.getByRole('link', { name: 'Отвори Анна Тестова' }))
    await screen.findByRole('button', { name: 'Редактирай' })
    leakCheck()
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към клиентите' }))
    await screen.findByText('Анна Тестова')
    expect(screen.getByLabelText('Търсене')).toHaveValue('Тайно 0895555777')
    await waitFor(() =>
      expect(mockedList).toHaveBeenLastCalledWith(
        { page: 0, size: 10, sort: 'name', direction: 'asc' },
        'Тайно 0895555777',
        expect.any(AbortSignal),
      ),
    )
    leakCheck()
  })

  it('survives create and cancel', async () => {
    history.replaceState({}, '', '/#/business/customers')
    render(<App />)
    await searchFor('Анна')
    fireEvent.click(screen.getByRole('button', { name: 'Добави клиент' }))
    await screen.findByLabelText('Име')
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към клиентите' }))
    await screen.findByText('Анна Тестова')
    expect(screen.getByLabelText('Търсене')).toHaveValue('Анна')
  })

  it('is not reconstructed after a refresh or a direct link', async () => {
    history.replaceState({}, '', '/#/business/customers')
    const first = render(<App />)
    await searchFor('Анна')
    first.unmount()
    mockedList.mockClear()
    render(<App />)
    await screen.findByText('Анна Тестова')
    expect(screen.getByLabelText('Търсене')).toHaveValue('')
    expect(mockedList).toHaveBeenCalledWith(expect.anything(), '', expect.any(AbortSignal))
  })

  it('is dropped when the user leaves the Customer area', async () => {
    history.replaceState({}, '', '/#/business/customers')
    render(<App />)
    await searchFor('Анна')
    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    await waitFor(() => expect(window.location.hash).toBe('#/profile'))
    fireEvent.click(screen.getByRole('link', { name: 'Клиенти' }))
    await screen.findByLabelText('Търсене')
    expect(screen.getByLabelText('Търсене')).toHaveValue('')
  })
})

describe('Business switching', () => {
  function Harness({ session }: { session: Session }) {
    const [busy, setBusy] = useState(false)
    const { feedback, setFeedback, beginFeedback } = useFeedback('harness')
    return (
      <UnsavedChangesGuardProvider>
        <AuthenticatedApplication
          session={session}
          setSession={() => undefined}
          busy={busy}
          setBusy={setBusy}
          feedback={feedback}
          setFeedback={setFeedback}
          beginFeedback={beginFeedback}
        />
      </UnsavedChangesGuardProvider>
    )
  }

  const two: Session = {
    ...owner,
    businesses: [
      { id: 'a', displayName: 'Бизнес А', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
      { id: 'b', displayName: 'Бизнес Б', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
    ],
    activeBusinessId: 'a',
  }

  it('clears the search, page, size, sort and direction and never shows Business A data in B', async () => {
    history.replaceState({}, '', '/#/business/customers?page=2&size=25&sort=email&direction=desc')
    const aData = deferred<CustomerPage>()
    mockedList.mockReset()
    mockedList.mockResolvedValueOnce(pageOf(people, { page: 2, size: 25, total: 70 }))
    const { rerender } = render(<Harness session={two} />)
    await screen.findByText('Анна Тестова')
    fireEvent.change(screen.getByLabelText('Търсене'), { target: { value: 'Тайно' } })
    mockedList.mockReturnValueOnce(aData.promise)
    fireEvent.submit(screen.getByRole('search', { name: 'Търсене на клиенти' }))
    await waitFor(() => expect(mockedList).toHaveBeenCalledTimes(2))
    expect(mockedList).toHaveBeenLastCalledWith(
      { page: 0, size: 25, sort: 'email', direction: 'desc' },
      'Тайно',
      expect.any(AbortSignal),
    )

    mockedList.mockResolvedValueOnce(
      pageOf([summary({ id: 'b1', displayName: 'Клиент на Б' })]),
    )
    rerender(<Harness session={{ ...two, activeBusinessId: 'b' }} />)
    expect(await screen.findByText('Клиент на Б')).toBeInTheDocument()
    expect(screen.getByLabelText('Търсене')).toHaveValue('')
    expect(mockedList).toHaveBeenLastCalledWith(
      { page: 0, size: 10, sort: 'name', direction: 'asc' },
      '',
      expect.any(AbortSignal),
    )
    expect(window.location.hash).toBe('#/business/customers?page=0&size=10&sort=name&direction=asc')

    await act(async () => {
      aData.resolve(pageOf([summary({ id: 'old', displayName: 'Данни от А' })]))
      await Promise.resolve()
    })
    expect(screen.queryByText('Данни от А')).toBeNull()
    expect(screen.queryByText('Анна Тестова')).toBeNull()
  })

  it('drops the carried list state of a detail route and does not show the previous Customer', async () => {
    history.replaceState({}, '', '/#/business/customers/c1?page=3&size=50&sort=phone&direction=desc')
    const { rerender } = render(<Harness session={two} />)
    await screen.findByRole('button', { name: 'Редактирай' })
    mockedGet.mockRejectedValue(
      Object.assign(new Error('nf'), { name: 'ApiError' }),
    )
    rerender(<Harness session={{ ...two, activeBusinessId: 'b' }} />)
    await waitFor(() => expect(window.location.hash).toBe('#/business/customers/c1'))
    expect(screen.queryByText('Анна Тестова')).toBeNull()
  })
})

describe('create flow in the application', () => {
  async function openCreate() {
    history.replaceState({}, '', '/#/business/customers/new')
    render(<App />)
    return screen.findByLabelText('Име')
  }

  it('guards every route-changing action when the form has unsaved changes', async () => {
    const name = await openCreate()
    fireEvent.change(name, { target: { value: 'Мария' } })

    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    expect(await screen.findByRole('alertdialog')).toHaveTextContent('Имате незапазени промени.')
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Ако напуснете, те ще бъдат загубени.')
    expect(screen.getByRole('button', { name: 'Остани' })).toHaveFocus()
    expect(window.location.hash).toMatch(/customers\/new/)
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(screen.getByLabelText('Име')).toHaveValue('Мария')

    fireEvent.click(screen.getByRole('button', { name: 'Обратно към клиентите' }))
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' })
    expect(screen.queryByRole('alertdialog')).toBeNull()

    fireEvent.click(screen.getAllByRole('button', { name: 'Изход' })[0]!)
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(mockedRequest).not.toHaveBeenCalledWith('/api/auth/logout', expect.anything())

    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Напусни' }))
    await waitFor(() => expect(window.location.hash).toBe('#/profile'))
  })

  it('guards browser Back and keeps the form route until the user confirms', async () => {
    history.replaceState({}, '', '/#/business/customers')
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Добави клиент' }))
    fireEvent.change(await screen.findByLabelText('Име'), { target: { value: 'Мария' } })
    act(() => history.back())
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
    expect(screen.getByLabelText('Име')).toHaveValue('Мария')
    // Staying restores the form route the browser had already left.
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    await waitFor(() => expect(window.location.hash).toMatch(/customers\/new/))
    expect(screen.getByLabelText('Име')).toHaveValue('Мария')

  })

  it('does not guard a clean form', async () => {
    await openCreate()
    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    await waitFor(() => expect(window.location.hash).toBe('#/profile'))
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('opens the created Customer and shows the success message only after the detail has loaded', async () => {
    mockedCreate.mockResolvedValue(detail({ id: 'c9', displayName: 'Мария Тестова' }))
    const pending = deferred<ReturnType<typeof detail>>()
    mockedGet.mockReturnValue(pending.promise)
    const name = await openCreate()
    fireEvent.change(name, { target: { value: 'Мария Тестова' } })
    fireEvent.change(screen.getByLabelText('Имейл'), { target: { value: 'maria@example.test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))
    expect(await screen.findByText('Зареждане на клиента…')).toBeInTheDocument()
    expect(screen.queryByText('Клиентът е добавен.')).toBeNull()
    expect(window.location.hash).toBe('#/business/customers/c9')
    expect(screen.queryByRole('alertdialog')).toBeNull()
    await act(async () => pending.resolve(detail({ id: 'c9', displayName: 'Мария Тестова' })))
    expect(await screen.findByText('Клиентът е добавен.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Редактирай' })).toBeInTheDocument()
  })
})

describe('suspended Business', () => {
  const suspended: Session = {
    ...owner,
    businesses: [{ id: 'a', displayName: 'Бизнес А', role: 'BUSINESS_OWNER', status: 'SUSPENDED' }],
  }

  beforeEach(() => mockedRequest.mockResolvedValue(suspended))

  it('keeps the list and search readable with one shared banner and no create action', async () => {
    history.replaceState({}, '', '/#/business/customers')
    render(<App />)
    await screen.findByText('Анна Тестова')
    const notices = screen.getAllByRole('status').filter((node) => /спрян/i.test(node.textContent ?? ''))
    expect(notices).toHaveLength(1)
    expect(notices[0]).toHaveTextContent(CUSTOMERS_SUSPENDED)
    expect(document.body.textContent).not.toContain('конфигурацията')
    expect(screen.queryByRole('button', { name: 'Добави клиент' })).toBeNull()
    expect(screen.getByRole('search', { name: 'Търсене на клиенти' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Профил' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Изход' }).length).toBeGreaterThan(0)
  })

  it('shows the detail with the edit action and exactly one notice', async () => {
    history.replaceState({}, '', '/#/business/customers/c1')
    render(<App />)
    await screen.findByText('Анна Тестова')
    expect(screen.getByRole('button', { name: 'Редактирай' })).toBeEnabled()
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent(CUSTOMERS_SUSPENDED)
    expect(document.body.textContent).not.toContain('конфигурацията')
    expect(screen.queryByRole('button', { name: 'Добави клиент' })).toBeNull()
  })

  async function startEditing() {
    history.replaceState({}, '', '/#/business/customers/c1')
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Редактирай' }))
    return screen.findByLabelText('Име')
  }

  it('edits and saves an existing Customer with the current version', async () => {
    mockedUpdate.mockResolvedValue(detail({ id: 'c1', displayName: 'Анна Нова', version: 4 }))
    const name = await startEditing()
    fireEvent.change(name, { target: { value: 'Анна Нова' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(await screen.findByText('Промените са запазени.')).toBeInTheDocument()
    expect(mockedUpdate).toHaveBeenCalledWith('c1', {
      displayName: 'Анна Нова',
      phone: '+359895555777',
      email: 'maria@example.test',
      expectedVersion: 3,
    })
    expect(screen.getByRole('heading', { level: 2, name: 'Анна Нова' })).toBeInTheDocument()
    // Still read-only for everything else: one notice, no create action, edit stays available.
    expect(screen.getAllByRole('status').filter((n) => /спрян/i.test(n.textContent ?? ''))).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Редактирай' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Добави клиент' })).toBeNull()
  })

  it('still handles duplicate-contact and validation errors inline when saving', async () => {
    mockedUpdate.mockRejectedValue(
      new ApiError(409, 'CUSTOMER_CONTACT_CONFLICT', 'x', { phone: 'a', email: 'b' }),
    )
    const name = await startEditing()
    fireEvent.change(name, { target: { value: 'Друго име' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(
      await screen.findByText('Този телефонен номер вече е записан за друг клиент.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Този имейл адрес вече е записан за друг клиент.')).toBeInTheDocument()
    expect(screen.getByLabelText('Име')).toHaveValue('Друго име')
    // A locally invalid value is stopped before any request.
    mockedUpdate.mockClear()
    fireEvent.change(screen.getByLabelText('Телефон'), { target: { value: 'abc' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(mockedUpdate).not.toHaveBeenCalled()
  })

  it('still reports a stale version and reloads only after the guard confirms', async () => {
    mockedUpdate.mockRejectedValue(new ApiError(409, 'CUSTOMER_CONCURRENT_UPDATE', 'x'))
    const name = await startEditing()
    fireEvent.change(name, { target: { value: 'Моята промяна' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Данните за клиента са променени. Обновете данните и опитайте отново.',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Зареди актуалните данни' }))
    expect(await screen.findByRole('alertdialog')).toHaveTextContent('Имате незапазени промени.')
    mockedGet.mockResolvedValue(detail({ id: 'c1', displayName: 'Име от сървъра', version: 8 }))
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))
    expect(await screen.findByRole('heading', { level: 2, name: 'Име от сървъра' })).toBeInTheDocument()
  })

  it('keeps the unsaved-changes guard on every way out of a dirty edit', async () => {
    const name = await startEditing()
    fireEvent.change(name, { target: { value: 'Незапазено' } })
    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    expect(await screen.findByRole('alertdialog')).toHaveTextContent('Имате незапазени промени.')
    expect(window.location.hash).toMatch(/customers\/c1/)
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(screen.getByLabelText('Име')).toHaveValue('Незапазено')
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
  })

  it('lets a permitted update through even when the suspension began during the edit', async () => {
    // The page started in an ACTIVE Business; the session changes to SUSPENDED mid-edit.
    mockedRequest.mockResolvedValue(owner)
    mockedUpdate.mockResolvedValue(detail({ id: 'c1', displayName: 'Анна Нова', version: 4 }))
    const name = await startEditing()
    mockedRequest.mockResolvedValue(suspended)
    fireEvent.change(name, { target: { value: 'Анна Нова' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(await screen.findByText('Промените са запазени.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByRole('button', { name: 'Редактирай' })).toBeInTheDocument()
  })

  it('renders no form on the create route and explains the state once through the shared notice', async () => {
    history.replaceState({}, '', '/#/business/customers/new')
    render(<App />)
    await screen.findByRole('button', { name: 'Обратно към клиентите' })
    expect(screen.queryByLabelText('Име')).toBeNull()
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent(CUSTOMERS_SUSPENDED)
  })

  it('enters the read-only presentation when a mutation reveals the suspension', async () => {
    mockedRequest.mockResolvedValue(owner)
    history.replaceState({}, '', '/#/business/customers/new')
    render(<App />)
    fireEvent.change(await screen.findByLabelText('Име'), { target: { value: 'Мария' } })
    fireEvent.change(screen.getByLabelText('Имейл'), { target: { value: 'maria@example.test' } })
    mockedCreate.mockRejectedValue(new ApiError(409, 'BUSINESS_SUSPENDED', 'x'))
    mockedRequest.mockResolvedValue(suspended)
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Спрян бизнес може само да преглежда данните си.',
    )
    await waitFor(() => expect(screen.queryByLabelText('Име')).toBeNull())
    expect(screen.queryByRole('button', { name: 'Добави' })).toBeNull()
    expect(screen.getByRole('status')).toHaveTextContent('временно спрян')
  })
})

describe('privacy of the visible page', () => {
  it('shows no Business ID, internal ID, normalized name or operational metadata', async () => {
    history.replaceState({}, '', '/#/business/customers')
    render(<App />)
    await screen.findByText('Анна Тестова')
    const text = document.body.textContent ?? ''
    expect(text).not.toMatch(/normalized|businessId|SQLState|CUSTOMER_|c1\b|version|createdAt/i)
  })
})
