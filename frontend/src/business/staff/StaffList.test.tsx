import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { STAFF_DEFAULT_LIST, type ListQueryState } from '../../navigation'
import { listStaffMembers, type StaffMemberPage } from './api'
import { StaffList } from './StaffList'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  listStaffMembers: vi.fn(),
}))

const mockedListStaffMembers = vi.mocked(listStaffMembers)

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

const populatedPage: StaffMemberPage = {
  staffMembers: [
    {
      id: 'staff-a',
      displayName: 'Анна Иванова',
      contactEmail: 'anna@example.invalid',
      contactPhone: null,
      active: true,
      version: 0,
      createdAt: '2026-08-19T09:00:00Z',
      updatedAt: '2026-08-19T09:00:00Z',
    },
  ],
  page: 0,
  size: 10,
  totalElements: 1,
}

function renderStaffList(overrides: {
  readOnly?: boolean
  list?: ListQueryState
  onAuthenticationRequired?: (detail: string) => void
  onListChange?: (next: ListQueryState) => void
} = {}) {
  const onListChange = overrides.onListChange ?? vi.fn()
  const onAuthenticationRequired = overrides.onAuthenticationRequired ?? vi.fn()
  render(
    <StaffList
      readOnly={overrides.readOnly ?? false}
      list={overrides.list ?? STAFF_DEFAULT_LIST}
      onListChange={onListChange}
      onAuthenticationRequired={onAuthenticationRequired}
    />,
  )
  return { onListChange, onAuthenticationRequired }
}

describe('StaffList', () => {
  beforeEach(() => {
    mockedListStaffMembers.mockReset()
  })

  it('shows a loading state before the list resolves', async () => {
    const { promise } = deferred<StaffMemberPage>()
    mockedListStaffMembers.mockReturnValue(promise)
    renderStaffList()
    expect(screen.getByText('Зареждане на екипа…')).toBeInTheDocument()
  })

  it('requests page 0 and size 10 by default', async () => {
    mockedListStaffMembers.mockResolvedValue({ staffMembers: [], page: 0, size: 10, totalElements: 0 })
    renderStaffList()
    await screen.findByText('Все още няма добавени членове на екипа.')
    expect(mockedListStaffMembers).toHaveBeenCalledWith(0, 10, 'name', 'asc', expect.any(AbortSignal))
  })

  it('shows the empty state when there are no StaffMembers', async () => {
    mockedListStaffMembers.mockResolvedValue({ staffMembers: [], page: 0, size: 10, totalElements: 0 })
    renderStaffList()
    expect(
      await screen.findByText('Все още няма добавени членове на екипа.'),
    ).toBeInTheDocument()
  })

  it('renders loaded StaffMembers with active status, contact info and the summary text', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    renderStaffList()
    expect(await screen.findByText('Анна Иванова')).toBeInTheDocument()
    expect(screen.getByText('anna@example.invalid')).toBeInTheDocument()
    expect(screen.getByText('Активен')).toBeInTheDocument()
    expect(screen.getByText('1–1 от 1 членове на екипа')).toBeInTheDocument()
    expect(screen.getByText('Страница 1 от 1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Добави нов член' })).toBeInTheDocument()
  })

  it('does not show the retired resource-vs-login-account explanatory paragraph', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    renderStaffList()
    await screen.findByText('Анна Иванова')
    expect(
      screen.queryByText(/не са задължително.*профили за вход в SpotYourSlot/),
    ).not.toBeInTheDocument()
  })

  it('shows distinct Телефон and Имейл columns, in that order, with — for missing values', async () => {
    mockedListStaffMembers.mockResolvedValue({
      staffMembers: [
        {
          id: 'staff-b',
          displayName: 'Борис Петров',
          contactEmail: null,
          contactPhone: null,
          active: true,
          version: 0,
          createdAt: '2026-08-19T09:00:00Z',
          updatedAt: '2026-08-19T09:00:00Z',
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
    })
    renderStaffList()
    await screen.findByText('Борис Петров')

    const headers = screen.getAllByRole('columnheader')
    expect(headers).toHaveLength(4)
    expect(headers[0]).toHaveAccessibleName('Име')
    expect(headers[1]).toHaveAccessibleName('Статус')
    expect(headers[2]).toHaveAccessibleName('Телефон')
    expect(headers[3]).toHaveAccessibleName('Имейл')

    const row = screen.getByText('Борис Петров').closest('tr') as HTMLElement
    const phoneCell = row.querySelector('[data-label="Телефон"]') as HTMLElement
    const emailCell = row.querySelector('[data-label="Имейл"]') as HTMLElement
    expect(phoneCell).toHaveTextContent('—')
    expect(emailCell).toHaveTextContent('—')
  })

  it('shows the phone column before the email column with real values', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    renderStaffList()
    await screen.findByText('Анна Иванова')
    const row = screen.getByText('Анна Иванова').closest('tr') as HTMLElement
    const cells = within(row).getAllByRole('cell')
    // Име (name link inside the first cell), Статус, Телефон, Имейл.
    expect(cells[2]).toHaveAttribute('data-label', 'Телефон')
    expect(cells[3]).toHaveAttribute('data-label', 'Имейл')
    expect(cells[3]).toHaveTextContent('anna@example.invalid')
  })

  it('hides mutation actions and repeats no lifecycle sentence (the shared banner says it) when the Business is SUSPENDED', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    renderStaffList({ readOnly: true })
    await screen.findByText('Анна Иванова')
    expect(screen.queryByRole('button', { name: 'Добави нов член' })).not.toBeInTheDocument()
    expect(screen.queryByText(/временно спрян/)).not.toBeInTheDocument()
    // No empty action row is left behind to add blank space under the banner.
    expect(document.querySelector('.business-page-actions')).toBeNull()
  })

  it('shows a safe error and supports retry', async () => {
    mockedListStaffMembers.mockRejectedValueOnce(new Error('boom'))
    mockedListStaffMembers.mockResolvedValueOnce(populatedPage)
    renderStaffList()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Списъкът с екипа не може да бъде зареден.',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Опитай отново' }))
    expect(await screen.findByText('Анна Иванова')).toBeInTheDocument()
  })

  it('redirects to authentication on a 401 response', async () => {
    mockedListStaffMembers.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    const { onAuthenticationRequired } = renderStaffList()
    await waitFor(() =>
      expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'),
    )
  })

  it('clicking a sortable header requests that field ascending and resets to page 0', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    const { onListChange } = renderStaffList({
      list: { page: 2, size: 10, sort: 'name', direction: 'asc' },
    })
    await screen.findByText('Анна Иванова')

    fireEvent.click(screen.getByRole('button', { name: 'Статус' }))

    expect(onListChange).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      sort: 'status',
      direction: 'asc',
    })
  })

  it('clicking the Телефон or Имейл sortable header requests that field ascending', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    const { onListChange } = renderStaffList({
      list: { page: 2, size: 10, sort: 'name', direction: 'asc' },
    })
    await screen.findByText('Анна Иванова')

    fireEvent.click(screen.getByRole('button', { name: 'Телефон' }))
    expect(onListChange).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      sort: 'phone',
      direction: 'asc',
    })

    fireEvent.click(screen.getByRole('button', { name: 'Имейл' }))
    expect(onListChange).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      sort: 'email',
      direction: 'asc',
    })
  })

  it('clicking the active Телефон header a second time reverses direction', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    const { onListChange } = renderStaffList({
      list: { page: 1, size: 10, sort: 'phone', direction: 'asc' },
    })
    await screen.findByText('Анна Иванова')

    fireEvent.click(screen.getByRole('button', { name: 'Телефон' }))

    expect(onListChange).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      sort: 'phone',
      direction: 'desc',
    })
  })

  it('the responsive sort control offers all four sortable fields', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    renderStaffList()
    await screen.findByText('Анна Иванова')

    const select = screen.getByLabelText('Подреди по') as HTMLSelectElement
    const optionValues = Array.from(select.options).map((option) => option.value)
    expect(optionValues).toEqual([
      'name:asc',
      'name:desc',
      'status:asc',
      'status:desc',
      'phone:asc',
      'phone:desc',
      'email:asc',
      'email:desc',
    ])
  })

  it('clicking the active sortable header a second time reverses direction and resets to page 0', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    const { onListChange } = renderStaffList({
      list: { page: 1, size: 10, sort: 'name', direction: 'asc' },
    })
    await screen.findByText('Анна Иванова')

    fireEvent.click(screen.getByRole('button', { name: 'Име' }))

    expect(onListChange).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      sort: 'name',
      direction: 'desc',
    })
  })

  it('exposes aria-sort only on the active column header, with both arrows always shown', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    renderStaffList({ list: { page: 0, size: 10, sort: 'status', direction: 'desc' } })
    await screen.findByText('Анна Иванова')

    expect(screen.getByRole('columnheader', { name: 'Статус' })).toHaveAttribute(
      'aria-sort',
      'descending',
    )
    expect(screen.getByRole('columnheader', { name: 'Име' })).toHaveAttribute('aria-sort', 'none')
    for (const header of [
      screen.getByRole('columnheader', { name: 'Статус' }),
      screen.getByRole('columnheader', { name: 'Име' }),
    ]) {
      expect(header.querySelectorAll('.sort-arrow')).toHaveLength(2)
    }
  })

  it('changing the page size selector resets to page 0', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    const { onListChange } = renderStaffList({
      list: { page: 1, size: 10, sort: 'name', direction: 'asc' },
    })
    await screen.findByText('Анна Иванова')

    fireEvent.change(screen.getByLabelText('Резултати на страница'), {
      target: { value: '25' },
    })

    expect(onListChange).toHaveBeenCalledWith({
      page: 0,
      size: 25,
      sort: 'name',
      direction: 'asc',
    })
  })

  it('the responsive sort control exposes the same sort/direction state and resets to page 0', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    const { onListChange } = renderStaffList({
      list: { page: 1, size: 10, sort: 'name', direction: 'asc' },
    })
    await screen.findByText('Анна Иванова')

    fireEvent.change(screen.getByLabelText('Подреди по'), {
      target: { value: 'status:desc' },
    })

    expect(onListChange).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      sort: 'status',
      direction: 'desc',
    })
  })

  it('recovers to the last valid page when the current page becomes empty after data shrinks', async () => {
    mockedListStaffMembers.mockResolvedValueOnce({
      staffMembers: [],
      page: 3,
      size: 10,
      totalElements: 12,
    })
    const { onListChange } = renderStaffList({
      list: { page: 3, size: 10, sort: 'name', direction: 'asc' },
    })

    await waitFor(() =>
      expect(onListChange).toHaveBeenCalledWith(
        { page: 1, size: 10, sort: 'name', direction: 'asc' },
        'replace',
      ),
    )
  })

  it('opening a StaffMember navigates via onOpen', async () => {
    mockedListStaffMembers.mockResolvedValue(populatedPage)
    const onOpen = vi.fn()
    render(
      <StaffList
        readOnly={false}
        list={STAFF_DEFAULT_LIST}
        onListChange={vi.fn()}
        onAuthenticationRequired={vi.fn()}
        onOpen={onOpen}
      />,
    )
    fireEvent.click(await screen.findByRole('link', { name: 'Отвори Анна Иванова' }))
    expect(onOpen).toHaveBeenCalledWith('staff-a')
  })
})
