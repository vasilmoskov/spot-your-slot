import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { SERVICES_DEFAULT_LIST, type ListQueryState } from '../../navigation'
import { listServices, type ServicePage } from './api'
import { ServiceList } from './ServiceList'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  listServices: vi.fn(),
}))

const mockedListServices = vi.mocked(listServices)

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const populatedPage: ServicePage = {
  services: [
    {
      id: 'service-a',
      name: 'Подстригване',
      description: null,
      durationMinutes: 10,
      price: 19.9,
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

function renderServiceList(overrides: {
  readOnly?: boolean
  list?: ListQueryState
  onAuthenticationRequired?: (detail: string) => void
  onListChange?: (next: ListQueryState) => void
} = {}) {
  const onListChange = overrides.onListChange ?? vi.fn()
  const onAuthenticationRequired = overrides.onAuthenticationRequired ?? vi.fn()
  render(
    <ServiceList
      readOnly={overrides.readOnly ?? false}
      list={overrides.list ?? SERVICES_DEFAULT_LIST}
      onListChange={onListChange}
      onAuthenticationRequired={onAuthenticationRequired}
    />,
  )
  return { onListChange, onAuthenticationRequired }
}

describe('ServiceList', () => {
  beforeEach(() => {
    mockedListServices.mockReset()
  })

  it('shows a loading state before the list resolves', async () => {
    const { promise } = deferred<ServicePage>()
    mockedListServices.mockReturnValue(promise)
    renderServiceList()
    expect(screen.getByText('Зареждане на услугите…')).toBeInTheDocument()
  })

  it('requests page 0 and size 10 by default', async () => {
    mockedListServices.mockResolvedValue({ services: [], page: 0, size: 10, totalElements: 0 })
    renderServiceList()
    await screen.findByText('Все още няма създадени услуги.')
    expect(mockedListServices).toHaveBeenCalledWith(0, 10, 'name', 'asc', expect.any(AbortSignal))
  })

  it('shows the empty state when there are no Services', async () => {
    mockedListServices.mockResolvedValue({ services: [], page: 0, size: 10, totalElements: 0 })
    renderServiceList()
    expect(await screen.findByText('Все още няма създадени услуги.')).toBeInTheDocument()
  })

  it('renders loaded Services with active status, formatted price/duration and the summary text', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    renderServiceList()
    expect(await screen.findByText('Подстригване')).toBeInTheDocument()
    expect(screen.getByText('10 мин.')).toBeInTheDocument()
    expect(screen.getByText('19.90 €')).toBeInTheDocument()
    expect(screen.getByText('Активна')).toBeInTheDocument()
    expect(screen.getByText('1–1 от 1 услуги')).toBeInTheDocument()
    expect(screen.getByText('Страница 1 от 1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Добави нова услуга' })).toBeInTheDocument()
  })

  it('hides mutation actions and shows a notice when the Business is SUSPENDED', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    renderServiceList({ readOnly: true })
    await screen.findByText('Подстригване')
    expect(screen.queryByRole('button', { name: 'Добави нова услуга' })).not.toBeInTheDocument()
    expect(
      screen.getByText('Бизнесът е временно спрян — услугите могат само да бъдат преглеждани.'),
    ).toBeInTheDocument()
  })

  it('shows a safe error and supports retry', async () => {
    mockedListServices.mockRejectedValueOnce(new Error('boom'))
    mockedListServices.mockResolvedValueOnce(populatedPage)
    renderServiceList()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Списъкът с услуги не може да бъде зареден.',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Опитай отново' }))
    expect(await screen.findByText('Подстригване')).toBeInTheDocument()
  })

  it('redirects to authentication on a 401 response', async () => {
    mockedListServices.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    const { onAuthenticationRequired } = renderServiceList()
    await waitFor(() =>
      expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'),
    )
  })

  it('clicking a sortable header requests that field ascending and resets to page 0', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    const { onListChange } = renderServiceList({
      list: { page: 2, size: 10, sort: 'name', direction: 'asc' },
    })
    await screen.findByText('Подстригване')

    fireEvent.click(screen.getByRole('button', { name: 'Цена' }))

    expect(onListChange).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      sort: 'price',
      direction: 'asc',
    })
  })

  it('clicking the active sortable header a second time reverses direction and resets to page 0', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    const { onListChange } = renderServiceList({
      list: { page: 1, size: 10, sort: 'price', direction: 'asc' },
    })
    await screen.findByText('Подстригване')

    fireEvent.click(screen.getByRole('button', { name: 'Цена' }))

    expect(onListChange).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      sort: 'price',
      direction: 'desc',
    })
  })

  it('exposes aria-sort only on the active column header', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    renderServiceList({ list: { page: 0, size: 10, sort: 'duration', direction: 'desc' } })
    await screen.findByText('Подстригване')

    expect(screen.getByRole('columnheader', { name: 'Продължителност' }))
      .toHaveAttribute('aria-sort', 'descending')
    expect(screen.getByRole('columnheader', { name: 'Име' })).toHaveAttribute('aria-sort', 'none')
  })

  it('shows both direction arrows on every sortable header, with only the active one emphasized', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    renderServiceList({ list: { page: 0, size: 10, sort: 'duration', direction: 'desc' } })
    await screen.findByText('Подстригване')

    const headers = screen.getAllByRole('columnheader')
    expect(headers).toHaveLength(4)
    for (const header of headers) {
      expect(header.querySelectorAll('.sort-arrow')).toHaveLength(2)
    }

    const durationHeader = screen.getByRole('columnheader', { name: 'Продължителност' })
    const nameHeader = screen.getByRole('columnheader', { name: 'Име' })
    expect(durationHeader).toHaveClass('is-active')
    expect(nameHeader).not.toHaveClass('is-active')
    expect(durationHeader.querySelectorAll('.sort-arrow.is-active')).toHaveLength(1)
    expect(durationHeader.querySelector('.sort-arrow.is-active')).toHaveTextContent('▼')
    expect(nameHeader.querySelectorAll('.sort-arrow.is-active')).toHaveLength(0)
  })

  it('toggling direction on the active column keeps aria-sort and the emphasized arrow correct', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    const { onListChange } = renderServiceList({
      list: { page: 0, size: 10, sort: 'price', direction: 'asc' },
    })
    await screen.findByText('Подстригване')

    expect(screen.getByRole('columnheader', { name: 'Цена' })).toHaveAttribute(
      'aria-sort',
      'ascending',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Цена' }))
    expect(onListChange).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      sort: 'price',
      direction: 'desc',
    })
  })

  it('changing the page size selector resets to page 0', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    const { onListChange } = renderServiceList({
      list: { page: 1, size: 10, sort: 'name', direction: 'asc' },
    })
    await screen.findByText('Подстригване')

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

  it('renders exactly one page-size selector, placed inside the pagination region', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    renderServiceList()
    await screen.findByText('Подстригване')

    const selectors = screen.getAllByLabelText('Резултати на страница')
    expect(selectors).toHaveLength(1)
    const pagination = screen.getByRole('navigation', { name: 'Странициране на услугите' })
    expect(pagination).toContainElement(selectors[0]!)
  })

  it('the responsive sort control exposes the same sort/direction state and resets to page 0', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    const { onListChange } = renderServiceList({
      list: { page: 1, size: 10, sort: 'name', direction: 'asc' },
    })
    await screen.findByText('Подстригване')

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

  it('Previous/Next request only the page and leave sort/size untouched', async () => {
    mockedListServices.mockResolvedValue({
      services: populatedPage.services,
      page: 1,
      size: 10,
      totalElements: 25,
    })
    const { onListChange } = renderServiceList({
      list: { page: 1, size: 10, sort: 'price', direction: 'desc' },
    })
    await screen.findByText('Подстригване')

    fireEvent.click(screen.getByRole('button', { name: 'Следваща' }))
    expect(onListChange).toHaveBeenCalledWith({
      page: 2,
      size: 10,
      sort: 'price',
      direction: 'desc',
    })

    fireEvent.click(screen.getByRole('button', { name: 'Предишна' }))
    expect(onListChange).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      sort: 'price',
      direction: 'desc',
    })
  })

  it('recovers to the last valid page when the current page becomes empty after data shrinks', async () => {
    mockedListServices.mockResolvedValueOnce({ services: [], page: 3, size: 10, totalElements: 12 })
    const { onListChange } = renderServiceList({
      list: { page: 3, size: 10, sort: 'name', direction: 'asc' },
    })

    await waitFor(() =>
      expect(onListChange).toHaveBeenCalledWith(
        {
          page: 1,
          size: 10,
          sort: 'name',
          direction: 'asc',
        },
        'replace',
      ),
    )
  })

  it('replaces (not pushes) history when recovering, but explicit interactions still push', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    const { onListChange } = renderServiceList({
      list: { page: 1, size: 10, sort: 'name', direction: 'asc' },
    })
    await screen.findByText('Подстригване')

    fireEvent.click(screen.getByRole('button', { name: 'Цена' }))
    expect(onListChange).toHaveBeenLastCalledWith({
      page: 0,
      size: 10,
      sort: 'price',
      direction: 'asc',
    })
  })

  // The obsolete-vs-current race for this component is proven at the App
  // level (Business A -> Business B remount), which exercises a genuine
  // context change rather than an anonymous-callback rerender. See
  // App.test.tsx: "remounts Services and discards a stale Business-A
  // response after switching to Business B".
})
