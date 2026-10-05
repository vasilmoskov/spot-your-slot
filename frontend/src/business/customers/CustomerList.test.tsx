import '@testing-library/jest-dom/vitest'
import { useState } from 'react'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { ApiError } from '../../identity/api'
import { CUSTOMERS_DEFAULT_LIST, type ListNavigationMode, type ListQueryState } from '../../navigation'
import { listCustomers, type CustomerPage } from './api'
import { CustomerList } from './CustomerList'
import { deferred, pageOf, summary } from './testFixtures'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  listCustomers: vi.fn(),
}))

const mockedList = vi.mocked(listCustomers)

type Spies = {
  onListChange: Mock<(next: ListQueryState, mode?: ListNavigationMode) => void>
  onSearchTermChange: Mock<(term: string) => void>
  onOpen: Mock<(customerId: string) => void>
  onCreate: Mock<() => void>
  onAuthenticationRequired: Mock<(detail: string) => void>
}

// Owns the route-like list state and the in-memory search term like the application does.
function Harness({
  spies,
  readOnly = false,
  initialList = CUSTOMERS_DEFAULT_LIST,
  initialTerm = '',
  controls,
}: {
  controls?: { setList: (next: ListQueryState) => void }
  spies: Spies
  readOnly?: boolean
  initialList?: ListQueryState
  initialTerm?: string
}) {
  const [list, setList] = useState(initialList)
  const [term, setTerm] = useState(initialTerm)
  if (controls) controls.setList = setList
  return (
    <CustomerList
      readOnly={readOnly}
      list={list}
      searchTerm={term}
      onSearchTermChange={(next) => {
        spies.onSearchTermChange(next)
        setTerm(next)
      }}
      onListChange={(next: ListQueryState, mode?: ListNavigationMode) => {
        spies.onListChange(next, mode)
        setList(next)
      }}
      onAuthenticationRequired={spies.onAuthenticationRequired}
      onCreate={spies.onCreate}
      onOpen={spies.onOpen}
    />
  )
}

function renderList(options: Partial<Parameters<typeof Harness>[0]> = {}) {
  const spies: Spies = {
    onListChange: vi.fn<(next: ListQueryState, mode?: ListNavigationMode) => void>(),
    onSearchTermChange: vi.fn<(term: string) => void>(),
    onOpen: vi.fn<(customerId: string) => void>(),
    onCreate: vi.fn<() => void>(),
    onAuthenticationRequired: vi.fn<(detail: string) => void>(),
  }
  render(<Harness spies={spies} {...options} />)
  return spies
}

const three = [
  summary({ id: 'c1', displayName: 'Анна Тестова', phone: '+359895555777', email: null }),
  summary({ id: 'c2', displayName: 'Борис Пробен', phone: null, email: 'boris@example.test' }),
  summary({ id: 'c3', displayName: 'Вера Пример', phone: '+359895555778', email: 'vera@example.test' }),
]

beforeEach(() => {
  mockedList.mockReset()
  mockedList.mockResolvedValue(pageOf(three))
})

afterEach(() => {
  window.localStorage.clear()
  window.sessionStorage.clear()
})

describe('CustomerList table', () => {
  it('requests the canonical ordinary list with a blank term', async () => {
    renderList()
    await screen.findByText('Анна Тестова')
    expect(mockedList).toHaveBeenCalledTimes(1)
    expect(mockedList).toHaveBeenCalledWith(
      { page: 0, size: 10, sort: 'name', direction: 'asc' },
      '',
      expect.any(AbortSignal),
    )
  })

  it('shows exactly the Име, Телефон and Имейл columns and no actions, status or metadata', async () => {
    renderList()
    await screen.findByText('Анна Тестова')
    const headers = screen.getAllByRole('columnheader')
    expect(headers.map((header) => header.textContent?.replace(/[▲▼]/g, ''))).toEqual([
      'Име',
      'Телефон',
      'Имейл',
    ])
    const table = screen.getByRole('table')
    expect(within(table).queryByRole('button', { name: /изтрий|delete|действия/i })).toBeNull()
    expect(table).not.toHaveTextContent(/customer-|c1|версия|статус/i)
    // The page heading belongs to the shell, never to the list.
    expect(screen.queryAllByRole('heading', { level: 1 })).toHaveLength(0)
  })

  it('renders a missing phone or email as an accessible placeholder', async () => {
    renderList()
    await screen.findByText('Анна Тестова')
    const row = screen.getByText('Анна Тестова').closest('tr') as HTMLElement
    const emailCell = row.querySelector('[data-label="Имейл"]') as HTMLElement
    expect(emailCell).toHaveTextContent('—')
    expect(emailCell).toHaveTextContent('не е посочен')
    expect(within(emailCell).getByText('—')).toHaveAttribute('aria-hidden', 'true')
  })

  it('opens the detail through an ordinary link without nested interactive controls', async () => {
    const spies = renderList()
    const link = await screen.findByRole('link', { name: 'Отвори Борис Пробен' })
    expect(link).toHaveAttribute(
      'href',
      '/#/business/customers/c2?page=0&size=10&sort=name&direction=asc',
    )
    expect(link.closest('tr')?.querySelectorAll('a, button')).toHaveLength(1)
    fireEvent.click(link)
    expect(spies.onOpen).toHaveBeenCalledWith('c2')
  })

  it('offers the create action only when mutations are allowed', async () => {
    const spies = renderList()
    fireEvent.click(await screen.findByRole('button', { name: 'Добави клиент' }))
    expect(spies.onCreate).toHaveBeenCalledOnce()
  })

  it('hides the create action for a suspended Business but keeps list and search', async () => {
    renderList({ readOnly: true })
    await screen.findByText('Анна Тестова')
    expect(screen.queryByRole('button', { name: 'Добави клиент' })).not.toBeInTheDocument()
    expect(screen.getByRole('search', { name: 'Търсене на клиенти' })).toBeInTheDocument()
    expect(screen.queryByText(/спрян/i)).not.toBeInTheDocument()
  })
})

describe('CustomerList sorting', () => {
  it('exposes both arrows and aria-sort and sorts server-side in both directions', async () => {
    const spies = renderList()
    await screen.findByText('Анна Тестова')
    const [name, phone, email] = screen.getAllByRole('columnheader')
    expect(name).toHaveAttribute('aria-sort', 'ascending')
    expect(phone).toHaveAttribute('aria-sort', 'none')
    expect(email).toHaveAttribute('aria-sort', 'none')
    expect(phone!.textContent).toContain('▲▼')

    fireEvent.click(within(phone!).getByRole('button'))
    expect(spies.onListChange).toHaveBeenLastCalledWith(
      { page: 0, size: 10, sort: 'phone', direction: 'asc' },
      undefined,
    )
    await waitFor(() =>
      expect(mockedList).toHaveBeenLastCalledWith(
        { page: 0, size: 10, sort: 'phone', direction: 'asc' },
        '',
        expect.any(AbortSignal),
      ),
    )
    await screen.findByText('Анна Тестова')
    fireEvent.click(within(screen.getAllByRole('columnheader')[1]!).getByRole('button'))
    await waitFor(() =>
      expect(mockedList).toHaveBeenLastCalledWith(
        { page: 0, size: 10, sort: 'phone', direction: 'desc' },
        '',
        expect.any(AbortSignal),
      ),
    )
    await screen.findByText('Анна Тестова')
    expect(screen.getAllByRole('columnheader')[1]).toHaveAttribute('aria-sort', 'descending')

    fireEvent.click(within(screen.getAllByRole('columnheader')[2]!).getByRole('button'))
    await waitFor(() =>
      expect(mockedList).toHaveBeenLastCalledWith(
        { page: 0, size: 10, sort: 'email', direction: 'asc' },
        '',
        expect.any(AbortSignal),
      ),
    )
  })

  it('resets to page 0 when the sort changes on a later page', async () => {
    const spies = renderList({ initialList: { page: 2, size: 10, sort: 'name', direction: 'asc' } })
    mockedList.mockResolvedValue(pageOf(three, { page: 2, total: 25 }))
    await screen.findByText('Анна Тестова')
    fireEvent.click(within(screen.getAllByRole('columnheader')[2]!).getByRole('button'))
    expect(spies.onListChange).toHaveBeenLastCalledWith(
      { page: 0, size: 10, sort: 'email', direction: 'asc' },
      undefined,
    )
  })

  it('offers the shared responsive sort select with the same three fields', async () => {
    const spies = renderList()
    await screen.findByText('Анна Тестова')
    const select = screen.getByLabelText('Подреди по')
    expect(Array.from((select as HTMLSelectElement).options).map((option) => option.value)).toEqual([
      'name:asc',
      'name:desc',
      'phone:asc',
      'phone:desc',
      'email:asc',
      'email:desc',
    ])
    fireEvent.change(select, { target: { value: 'email:desc' } })
    expect(spies.onListChange).toHaveBeenLastCalledWith(
      { page: 0, size: 10, sort: 'email', direction: 'desc' },
      undefined,
    )
  })
})

describe('CustomerList pagination', () => {
  it('uses the shared pagination region with the range, page summary and one size selector', async () => {
    mockedList.mockResolvedValue(pageOf(three, { total: 27 }))
    renderList()
    await screen.findByText('Анна Тестова')
    const region = screen.getByRole('navigation', { name: 'Странициране на клиентите' })
    expect(within(region).getByText('Показани 1–10 от 27')).toBeInTheDocument()
    expect(within(region).getByText('Страница 1 от 3')).toBeInTheDocument()
    expect(within(region).getAllByRole('combobox')).toHaveLength(1)
    expect(within(region).getByRole('button', { name: 'Предишна' })).toBeDisabled()
    expect(screen.getAllByLabelText('Резултати на страница')).toHaveLength(1)
    const sizes = Array.from(
      (within(region).getByRole('combobox') as HTMLSelectElement).options,
    ).map((option) => option.value)
    expect(sizes).toEqual(['10', '25', '50'])
  })

  it('moves to the next page and back keeping the sort', async () => {
    mockedList.mockResolvedValue(pageOf(three, { total: 27 }))
    const spies = renderList()
    await screen.findByText('Анна Тестова')
    fireEvent.click(screen.getByRole('button', { name: 'Следваща' }))
    expect(spies.onListChange).toHaveBeenLastCalledWith(
      { page: 1, size: 10, sort: 'name', direction: 'asc' },
      undefined,
    )
    await waitFor(() =>
      expect(mockedList).toHaveBeenLastCalledWith(
        { page: 1, size: 10, sort: 'name', direction: 'asc' },
        '',
        expect.any(AbortSignal),
      ),
    )
  })

  it.each([25, 50])('resets to page 0 when the size changes to %i', async (size) => {
    mockedList.mockResolvedValue(pageOf(three, { page: 1, total: 27 }))
    const spies = renderList({ initialList: { page: 1, size: 10, sort: 'name', direction: 'asc' } })
    await screen.findByText('Анна Тестова')
    fireEvent.change(screen.getByLabelText('Резултати на страница'), {
      target: { value: String(size) },
    })
    expect(spies.onListChange).toHaveBeenLastCalledWith(
      { page: 0, size, sort: 'name', direction: 'asc' },
      undefined,
    )
  })

  it('shows the partial last page range and disables Next there', async () => {
    mockedList.mockResolvedValue(pageOf(three, { page: 2, total: 23 }))
    renderList({ initialList: { page: 2, size: 10, sort: 'name', direction: 'asc' } })
    await screen.findByText('Анна Тестова')
    expect(screen.getByText('Показани 21–23 от 23')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Следваща' })).toBeDisabled()
  })

  it('recovers from an out-of-range page by replacing history with the last valid page', async () => {
    mockedList.mockImplementation((query) =>
      Promise.resolve(
        query.page === 9 ? pageOf([], { page: 9, total: 23 }) : pageOf(three, { page: 2, total: 23 }),
      ),
    )
    const spies = renderList({ initialList: { page: 9, size: 10, sort: 'name', direction: 'asc' } })
    await screen.findByText('Анна Тестова')
    expect(spies.onListChange).toHaveBeenCalledWith(
      { page: 2, size: 10, sort: 'name', direction: 'asc' },
      'replace',
    )
  })

  describe('an empty result reached from a stale later page', () => {
    const stale: ListQueryState = { page: 2, size: 25, sort: 'email', direction: 'desc' }
    const emptyAt = (page: number) => pageOf([], { page, size: 25, total: 0 })

    it('replaces the route with page 0, never renders the stale empty page, and shows the catalog-empty state', async () => {
      mockedList.mockResolvedValue(emptyAt(0))
      const spies = renderList({ initialList: stale })
      await screen.findByText('Все още няма добавени клиенти.')
      expect(spies.onListChange).toHaveBeenCalledTimes(1)
      expect(spies.onListChange).toHaveBeenCalledWith(
        { page: 0, size: 25, sort: 'email', direction: 'desc' },
        'replace',
      )
      expect(mockedList).toHaveBeenCalledTimes(2)
      expect(mockedList.mock.calls.map((call) => call[0].page)).toEqual([2, 0])
      expect(screen.queryByText('Не са намерени клиенти по това търсене.')).toBeNull()
    })

    it('keeps the size, sort, direction and the in-memory search and shows the search-empty state', async () => {
      mockedList.mockResolvedValue(emptyAt(0))
      const spies = renderList({ initialList: stale, initialTerm: 'Анна' })
      await screen.findByText('Не са намерени клиенти по това търсене.')
      expect(spies.onListChange).toHaveBeenCalledTimes(1)
      expect(spies.onSearchTermChange).not.toHaveBeenCalled()
      expect(mockedList.mock.calls.map((call) => [call[0], call[1]])).toEqual([
        [stale, 'Анна'],
        [{ page: 0, size: 25, sort: 'email', direction: 'desc' }, 'Анна'],
      ])
      expect(screen.getByLabelText('Търсене', { exact: true })).toHaveValue('Анна')
      expect(screen.queryByText('Все още няма добавени клиенти.')).toBeNull()
    })

    it('does not show the stale empty state before the corrected request settles', async () => {
      const second = deferred<CustomerPage>()
      mockedList.mockResolvedValueOnce(emptyAt(2)).mockReturnValueOnce(second.promise)
      renderList({ initialList: stale })
      await waitFor(() => expect(mockedList).toHaveBeenCalledTimes(2))
      expect(screen.getByText('Зареждане на клиентите…')).toBeInTheDocument()
      expect(screen.queryByText('Все още няма добавени клиенти.')).toBeNull()
      await act(async () => second.resolve(emptyAt(0)))
      expect(await screen.findByText('Все още няма добавени клиенти.')).toBeInTheDocument()
    })

    it('never loops: page 0 with no data is final and asks for no further correction', async () => {
      mockedList.mockResolvedValue(emptyAt(0))
      const spies = renderList()
      await screen.findByText('Все още няма добавени клиенти.')
      expect(spies.onListChange).not.toHaveBeenCalled()
      expect(mockedList).toHaveBeenCalledTimes(1)
    })

    it('still recovers to the last valid page when data remains', async () => {
      mockedList.mockImplementation((query) =>
        Promise.resolve(
          query.page === 2
            ? pageOf([], { page: 2, size: 25, total: 30 })
            : pageOf(three, { page: 1, size: 25, total: 30 }),
        ),
      )
      const spies = renderList({ initialList: stale })
      await screen.findByText('Анна Тестова')
      expect(spies.onListChange).toHaveBeenCalledWith(
        { page: 1, size: 25, sort: 'email', direction: 'desc' },
        'replace',
      )
    })
  })

  it('shows no pagination for an empty result', async () => {
    mockedList.mockResolvedValue(pageOf([]))
    renderList()
    await screen.findByText('Все още няма добавени клиенти.')
    expect(screen.queryByRole('navigation', { name: 'Странициране на клиентите' })).toBeNull()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('shows a one-page summary for a result that fits on one page', async () => {
    renderList()
    await screen.findByText('Анна Тестова')
    expect(screen.getByText('Страница 1 от 1')).toBeInTheDocument()
  })
})

describe('CustomerList live search', () => {
  const DEBOUNCE = 300
  const input = () => screen.getByLabelText('Търсене', { exact: true })
  const typeSearch = (value: string) => fireEvent.change(input(), { target: { value } })
  const advance = (ms: number) =>
    act(async () => {
      await vi.advanceTimersByTimeAsync(ms)
    })
  const settle = () => advance(0)

  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  async function renderLoaded(options: Partial<Parameters<typeof Harness>[0]> = {}) {
    const spies = renderList(options)
    await settle()
    expect(screen.getByText('Анна Тестова')).toBeInTheDocument()
    mockedList.mockClear()
    return spies
  }

  it('has a labelled search field with the approved placeholder and no Търси or Изчисти buttons', async () => {
    await renderLoaded()
    expect(input()).toHaveAttribute('placeholder', 'Име, телефон или имейл')
    expect(input()).toHaveAttribute('autocomplete', 'off')
    expect(screen.getByRole('search', { name: 'Търсене на клиенти' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Търси' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Изчисти' })).toBeNull()
    typeSearch('Анна')
    expect(screen.queryByRole('button', { name: 'Изчисти' })).toBeNull()
  })

  it('sends no request until the debounce expires, then exactly one for the final value', async () => {
    await renderLoaded()
    typeSearch('А')
    await advance(100)
    typeSearch('Ан')
    await advance(100)
    typeSearch('Анн')
    await advance(100)
    typeSearch('Анна')
    await advance(DEBOUNCE - 1)
    expect(mockedList).not.toHaveBeenCalled()
    await advance(1)
    expect(mockedList).toHaveBeenCalledTimes(1)
    expect(mockedList).toHaveBeenCalledWith(CUSTOMERS_DEFAULT_LIST, 'Анна', expect.any(AbortSignal))
  })

  it('searches again when characters are removed', async () => {
    await renderLoaded()
    typeSearch('Анна Т')
    await advance(DEBOUNCE)
    expect(mockedList).toHaveBeenLastCalledWith(CUSTOMERS_DEFAULT_LIST, 'Анна Т', expect.any(AbortSignal))
    typeSearch('Анн')
    await advance(DEBOUNCE)
    expect(mockedList).toHaveBeenCalledTimes(2)
    expect(mockedList).toHaveBeenLastCalledWith(CUSTOMERS_DEFAULT_LIST, 'Анн', expect.any(AbortSignal))
  })

  it('restores the unfiltered list automatically when the field becomes empty', async () => {
    await renderLoaded({ initialTerm: 'Анна' })
    mockedList.mockClear()
    typeSearch('')
    await advance(DEBOUNCE)
    expect(mockedList).toHaveBeenCalledTimes(1)
    expect(mockedList).toHaveBeenLastCalledWith(CUSTOMERS_DEFAULT_LIST, '', expect.any(AbortSignal))
    // A field of only whitespace is the same as an empty one.
    typeSearch('Борис')
    await advance(DEBOUNCE)
    typeSearch('   ')
    await advance(DEBOUNCE)
    expect(mockedList).toHaveBeenLastCalledWith(CUSTOMERS_DEFAULT_LIST, '', expect.any(AbortSignal))
  })

  it('sends one request, not one per pause, when the effective term did not change', async () => {
    await renderLoaded()
    typeSearch('Анна')
    await advance(DEBOUNCE)
    typeSearch('Анна ')
    await advance(DEBOUNCE)
    typeSearch(' Анна')
    await advance(DEBOUNCE)
    expect(mockedList).toHaveBeenCalledTimes(1)
  })

  it('trims the approved whitespace and resets to page 0 with one push, keeping size, sort and direction', async () => {
    mockedList.mockResolvedValue(pageOf(three, { page: 2, size: 25, total: 70 }))
    const spies = renderList({ initialList: { page: 2, size: 25, sort: 'email', direction: 'desc' } })
    await settle()
    mockedList.mockClear()
    mockedList.mockResolvedValue(pageOf(three, { page: 0, size: 25, total: 70 }))
    typeSearch('  Анна  ')
    await advance(DEBOUNCE)
    expect(mockedList).toHaveBeenLastCalledWith(
      { page: 0, size: 25, sort: 'email', direction: 'desc' },
      'Анна',
      expect.any(AbortSignal),
    )
    expect(spies.onSearchTermChange).toHaveBeenCalledWith('Анна')
    expect(spies.onListChange).toHaveBeenCalledTimes(1)
    expect(spies.onListChange).toHaveBeenCalledWith(
      { page: 0, size: 25, sort: 'email', direction: 'desc' },
      undefined,
    )
    // From page 0 a changed search needs no further history entry.
    spies.onListChange.mockClear()
    typeSearch('Борис')
    await advance(DEBOUNCE)
    expect(spies.onListChange).not.toHaveBeenCalled()
  })

  it('applies the typed value at once on Enter, and clears at once on Escape', async () => {
    const spies = await renderLoaded()
    typeSearch('Анна')
    fireEvent.submit(screen.getByRole('search', { name: 'Търсене на клиенти' }))
    await settle()
    expect(mockedList).toHaveBeenCalledTimes(1)
    expect(spies.onSearchTermChange).toHaveBeenLastCalledWith('Анна')

    fireEvent.keyDown(input(), { key: 'Escape' })
    await settle()
    expect(input()).toHaveValue('')
    expect(mockedList).toHaveBeenLastCalledWith(CUSTOMERS_DEFAULT_LIST, '', expect.any(AbortSignal))
    // Escape on an empty field does nothing.
    mockedList.mockClear()
    fireEvent.keyDown(input(), { key: 'Escape' })
    await advance(DEBOUNCE)
    expect(mockedList).not.toHaveBeenCalled()
  })

  it('keeps sorting and page size working over the searched result', async () => {
    mockedList.mockResolvedValue(pageOf(three, { total: 27 }))
    const spies = await renderLoaded()
    typeSearch('Тест')
    await advance(DEBOUNCE)
    fireEvent.click(screen.getByRole('button', { name: 'Следваща' }))
    await settle()
    expect(mockedList).toHaveBeenLastCalledWith(
      { page: 1, size: 10, sort: 'name', direction: 'asc' },
      'Тест',
      expect.any(AbortSignal),
    )
    fireEvent.click(within(screen.getAllByRole('columnheader')[1]!).getByRole('button'))
    await settle()
    expect(mockedList).toHaveBeenLastCalledWith(
      { page: 0, size: 10, sort: 'phone', direction: 'asc' },
      'Тест',
      expect.any(AbortSignal),
    )
    fireEvent.change(screen.getByLabelText('Резултати на страница'), { target: { value: '25' } })
    await settle()
    expect(mockedList).toHaveBeenLastCalledWith(
      { page: 0, size: 25, sort: 'phone', direction: 'asc' },
      'Тест',
      expect.any(AbortSignal),
    )
    expect(spies.onSearchTermChange).toHaveBeenCalledTimes(1)
  })

  it.each(['Иванова', 'етр', 'я Петр', 'а', 'ИВАНОВА'])(
    'sends a surname or middle fragment such as %j verbatim, with no prefix rule and no minimum length',
    async (term) => {
      await renderLoaded()
      typeSearch(term)
      await advance(DEBOUNCE)
      expect(mockedList).toHaveBeenCalledTimes(1)
      expect(mockedList).toHaveBeenCalledWith(CUSTOMERS_DEFAULT_LIST, term, expect.any(AbortSignal))
    },
  )

  it('accepts exactly 100 code points', async () => {
    await renderLoaded()
    typeSearch('😀'.repeat(100))
    await advance(DEBOUNCE)
    expect(mockedList).toHaveBeenCalledTimes(1)
  })

  it('rejects 101 code points at once with an inline error, sends no request and keeps the last result', async () => {
    await renderLoaded()
    typeSearch('😀'.repeat(101))
    expect(screen.getByText('Търсенето може да съдържа най-много 100 знака.')).toBeInTheDocument()
    expect(input()).toHaveAttribute('aria-invalid', 'true')
    expect(input()).toHaveAccessibleDescription('Търсенето може да съдържа най-много 100 знака.')
    await advance(DEBOUNCE * 3)
    expect(mockedList).not.toHaveBeenCalled()
    expect(screen.getByText('Анна Тестова')).toBeInTheDocument()
    typeSearch('Анна')
    expect(screen.queryByText(/най-много 100/)).not.toBeInTheDocument()
    await advance(DEBOUNCE)
    expect(mockedList).toHaveBeenCalledTimes(1)
  })

  it('aborts the superseded request and never lets a slower old response replace the newer result', async () => {
    await renderLoaded()
    const first = deferred<CustomerPage>()
    const second = deferred<CustomerPage>()
    mockedList.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    typeSearch('Бо')
    await advance(DEBOUNCE)
    typeSearch('Борис')
    await advance(DEBOUNCE)
    expect(mockedList).toHaveBeenCalledTimes(2)
    expect((mockedList.mock.calls[0]![2] as AbortSignal).aborted).toBe(true)
    expect((mockedList.mock.calls[1]![2] as AbortSignal).aborted).toBe(false)
    await act(async () => second.resolve(pageOf([summary({ id: 'new', displayName: 'Нов резултат' })])))
    expect(screen.getByText('Нов резултат')).toBeInTheDocument()
    await act(async () => first.resolve(pageOf([summary({ id: 'old', displayName: 'Остарял резултат' })])))
    expect(screen.queryByText('Остарял резултат')).not.toBeInTheDocument()
    expect(screen.getByText('Нов резултат')).toBeInTheDocument()
  })

  it('keeps the previous result visible and marked busy while a newer one loads', async () => {
    await renderLoaded()
    const pending = deferred<CustomerPage>()
    mockedList.mockReturnValueOnce(pending.promise)
    typeSearch('Борис')
    await advance(DEBOUNCE)
    expect(screen.getByText('Анна Тестова')).toBeInTheDocument()
    expect(document.querySelector('.customer-results')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('status')).toHaveTextContent('Зареждане на клиентите…')
    await act(async () => pending.resolve(pageOf([summary({ id: 'b', displayName: 'Борис Пробен' })])))
    expect(screen.queryByText('Анна Тестова')).not.toBeInTheDocument()
    expect(document.querySelector('.customer-results')).toHaveAttribute('aria-busy', 'false')
  })

  it('keeps the field focused and its text intact while results change', async () => {
    await renderLoaded()
    input().focus()
    typeSearch('Борис')
    await advance(DEBOUNCE)
    expect(input()).toHaveFocus()
    expect(input()).toHaveValue('Борис')
  })

  it('distinguishes an empty search result from an empty catalog, following the term that was searched', async () => {
    mockedList.mockResolvedValue(pageOf([]))
    renderList()
    await settle()
    expect(screen.getByText('Все още няма добавени клиенти.')).toBeInTheDocument()
    mockedList.mockClear()
    const pending = deferred<CustomerPage>()
    mockedList.mockReturnValueOnce(pending.promise)
    typeSearch('няма')
    await advance(DEBOUNCE)
    // Still the catalog state until the searched result arrives.
    expect(screen.getByText('Все още няма добавени клиенти.')).toBeInTheDocument()
    await act(async () => pending.resolve(pageOf([])))
    expect(screen.getByText('Не са намерени клиенти по това търсене.')).toBeInTheDocument()
    expect(screen.queryByText('Все още няма добавени клиенти.')).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Странициране на клиентите' })).toBeNull()
  })

  it('keeps the term out of the URL, storage, cookies and document title', async () => {
    await renderLoaded()
    const title = document.title
    typeSearch('Тайно име 0895555777')
    await advance(DEBOUNCE)
    const everything = [
      window.location.href,
      JSON.stringify({ ...window.localStorage }),
      JSON.stringify({ ...window.sessionStorage }),
      document.cookie,
      document.title,
    ].join('|')
    expect(everything).not.toContain('Тайно')
    expect(everything).not.toContain('0895555777')
    expect(document.title).toBe(title)
    const link = screen.getByRole('link', { name: 'Отвори Борис Пробен' }) as HTMLAnchorElement
    expect(link.getAttribute('href')).not.toContain('Тайно')
  })
})

describe('CustomerList states', () => {
  it('shows a loading state with a live region before the list resolves', () => {
    mockedList.mockReturnValue(deferred<CustomerPage>().promise)
    renderList()
    expect(screen.getByText('Зареждане на клиентите…')).toBeInTheDocument()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('offers one retry per activation after a load failure', async () => {
    mockedList.mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'SQLState 23505 leak'))
    renderList()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Списъкът с клиенти не може да бъде зареден.',
    )
    expect(screen.queryByText(/SQLState/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Опитай отново' }))
    await screen.findByText('Анна Тестова')
    expect(mockedList).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('shows a safe unavailable state for an access problem', async () => {
    mockedList.mockRejectedValue(new ApiError(403, 'ACCESS_DENIED', 'raw'))
    renderList()
    expect(await screen.findByRole('alert')).toHaveTextContent('Нямате достъп до тази операция.')
    expect(screen.queryByRole('button', { name: 'Добави клиент' })).toBeNull()
  })

  it('hands an expired session to the application', async () => {
    mockedList.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    const spies = renderList()
    await waitFor(() => expect(spies.onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'))
  })
})
