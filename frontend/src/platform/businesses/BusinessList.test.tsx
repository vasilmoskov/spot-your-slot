import '@testing-library/jest-dom/vitest'
import { StrictMode, useState } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { BUSINESSES_DEFAULT_LIST, type ListQueryState } from '../../navigation'
import { listBusinesses, type BusinessPage } from './api'
import { BusinessList } from './BusinessList'

vi.mock('./api', async (importOriginal) => {
  const original = await importOriginal<typeof import('./api')>()
  return { ...original, listBusinesses: vi.fn() }
})

const mockedListBusinesses = vi.mocked(listBusinesses)

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

const populatedPage: BusinessPage = {
  businesses: [
    {
      id: 'business-a',
      slug: 'studio-a',
      displayName: 'Студио А',
      businessType: 'NAIL_STUDIO',
      status: 'ACTIVE',
      timezone: 'Europe/Sofia',
      version: 3,
      createdAt: '2026-08-01T09:00:00Z',
      updatedAt: '2026-08-20T12:30:00Z',
    },
  ],
  page: 0,
  size: 10,
  totalElements: 1,
}

type StatefulProps = {
  onAuthenticationRequired: (detail: string) => void
  onCreate?: () => void
  onOpen?: (businessId: string) => void
  initialList?: ListQueryState
}

function StatefulBusinessList({ initialList = BUSINESSES_DEFAULT_LIST, ...props }: StatefulProps) {
  const [list, setList] = useState(initialList)
  return <BusinessList list={list} onListChange={setList} {...props} />
}

beforeEach(() => {
  mockedListBusinesses.mockReset()
})

describe('BusinessList', () => {
  it('loads page zero with size 10 and renders approved summary metadata once', async () => {
    mockedListBusinesses.mockResolvedValue(populatedPage)
    const onCreate = vi.fn()
    const onOpen = vi.fn()

    render(
      <StatefulBusinessList
        onAuthenticationRequired={vi.fn()}
        onCreate={onCreate}
        onOpen={onOpen}
      />,
    )

    expect(screen.getByText('Зареждане на бизнесите…')).toBeInTheDocument()
    expect(await screen.findByRole('table', { name: 'Списък с бизнеси' }))
      .toBeInTheDocument()
    expect(mockedListBusinesses).toHaveBeenCalledWith(
      0,
      10,
      'displayName',
      'asc',
      expect.any(AbortSignal),
    )
    expect(screen.getAllByText('Студио А')).toHaveLength(1)
    expect(
      screen.getAllByRole('columnheader').map((heading) => heading.textContent),
    ).toEqual([
      'Име▲▼',
      'Идентификатор в уеб адреса▲▼',
      'Дейност▲▼',
      'Статус▲▼',
    ])
    expect(
      Array.from(document.querySelectorAll('tbody td')).map((cell) =>
        cell.getAttribute('data-label'),
      ),
    ).toEqual([
      'Име',
      'Идентификатор в уеб адреса',
      'Дейност',
      'Статус',
    ])
    expect(screen.getByText('studio-a')).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent('spotyourslot.bg/studio-a')
    expect(screen.getByText('Студио за маникюр')).toBeInTheDocument()
    expect(screen.getByText('Активен')).toHaveClass('status-badge-success')
    expect(screen.queryByRole('columnheader', { name: 'Часова зона' }))
      .not.toBeInTheDocument()
    expect(document.querySelector('[data-label="Часова зона"]')).toBeNull()
    expect(screen.queryByText('Europe/Sofia')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('columnheader', { name: 'Дата на последно обновяване' }),
    ).not.toBeInTheDocument()
    expect(
      document.querySelector('[data-label="Дата на последно обновяване"]'),
    ).toBeNull()
    expect(document.querySelector('time')).toBeNull()
    expect(document.body).not.toHaveTextContent('2026-08-20T12:30:00Z')
    expect(screen.queryByText('business-a')).not.toBeInTheDocument()
    expect(screen.queryByText('3')).not.toBeInTheDocument()
    expect(screen.queryByText('01.08.2026')).not.toBeInTheDocument()
    expect(screen.getByText('1–1 от 1 бизнеса')).toBeInTheDocument()
    expect(screen.getByText('Страница 1 от 1')).toBeInTheDocument()
    expect(
      screen.getByRole('navigation', { name: 'Странициране на бизнесите' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Предишна' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Следваща' })).toBeDisabled()
    const create = screen.getByRole('button', { name: 'Нов бизнес' })
    expect(create.parentElement).toHaveClass('business-page-actions')
    expect(create.parentElement).not.toHaveClass('business-page-actions-end')
    fireEvent.click(create)
    expect(onCreate).toHaveBeenCalledOnce()
    const open = screen.getByRole('link', { name: 'Отвори Студио А' })
    expect(open).toHaveAttribute('href', '/#/platform/businesses/business-a')
    fireEvent.click(open)
    expect(onOpen).toHaveBeenCalledWith('business-a')
  })

  it('presents BARBERSHOP and DRAFT with the approved Bulgarian labels', async () => {
    mockedListBusinesses.mockResolvedValue({
      ...populatedPage,
      businesses: [
        {
          ...populatedPage.businesses[0]!,
          businessType: 'BARBERSHOP',
          status: 'DRAFT',
        },
      ],
    })

    render(<StatefulBusinessList onAuthenticationRequired={vi.fn()} />)

    expect(await screen.findByText('Бръснарница')).toBeInTheDocument()
    expect(await screen.findByText('Предстои активиране')).toHaveClass(
      'status-badge-neutral',
    )
    expect(screen.queryByText('Чернова')).not.toBeInTheDocument()
  })

  it('renders an accessible empty state', async () => {
    mockedListBusinesses.mockResolvedValue({
      businesses: [],
      page: 0,
      size: 10,
      totalElements: 0,
    })

    render(<StatefulBusinessList onAuthenticationRequired={vi.fn()} />)

    expect(await screen.findByText('Все още няма създадени бизнеси.'))
      .toBeInTheDocument()
    expect(screen.getByText('0–0 от 0 бизнеса')).toBeInTheDocument()
    expect(screen.getByText('Страница 1 от 1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Предишна' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Следваща' })).toBeDisabled()
  })

  it('requests exact next and previous pages while clearing stale rows', async () => {
    mockedListBusinesses.mockResolvedValueOnce({
      ...populatedPage,
      totalElements: 21,
    })
    const nextRequest = deferred<BusinessPage>()
    mockedListBusinesses.mockImplementationOnce(() => nextRequest.promise)

    render(<StatefulBusinessList onAuthenticationRequired={vi.fn()} />)

    const next = await screen.findByRole('button', { name: 'Следваща' })
    const previous = screen.getByRole('button', { name: 'Предишна' })
    expect(previous).toBeDisabled()
    expect(next).toBeEnabled()

    fireEvent.click(next)
    expect(screen.getByText('Зареждане на бизнесите…')).toBeInTheDocument()
    expect(screen.queryByText('Студио А')).not.toBeInTheDocument()
    expect(mockedListBusinesses).toHaveBeenCalledTimes(2)
    expect(mockedListBusinesses).toHaveBeenLastCalledWith(
      1,
      10,
      'displayName',
      'asc',
      expect.any(AbortSignal),
    )

    nextRequest.resolve({
      ...populatedPage,
      businesses: [{ ...populatedPage.businesses[0]!, displayName: 'Студио Б' }],
      page: 1,
      totalElements: 21,
    })
    expect(await screen.findByText('Студио Б')).toBeInTheDocument()
    expect(screen.getByText('Страница 2 от 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Предишна' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Следваща' })).toBeEnabled()

    mockedListBusinesses.mockResolvedValueOnce({
      ...populatedPage,
      totalElements: 21,
    })
    fireEvent.click(screen.getByRole('button', { name: 'Предишна' }))
    expect(mockedListBusinesses).toHaveBeenLastCalledWith(
      0,
      10,
      'displayName',
      'asc',
      expect.any(AbortSignal),
    )
    expect(await screen.findByText('Студио А')).toBeInTheDocument()
  })

  it('disables Next on the final page', async () => {
    mockedListBusinesses.mockResolvedValue({
      ...populatedPage,
      page: 2,
      size: 10,
      totalElements: 21,
    })

    render(
      <StatefulBusinessList
        onAuthenticationRequired={vi.fn()}
        initialList={{ page: 2, size: 10, sort: 'displayName', direction: 'asc' }}
      />,
    )

    expect(await screen.findByText('Страница 3 от 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Предишна' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Следваща' })).toBeDisabled()
  })

  it('recovers to the last valid page when the current page becomes empty after data shrinks', async () => {
    mockedListBusinesses.mockResolvedValueOnce({
      businesses: [],
      page: 2,
      size: 10,
      totalElements: 11,
    })
    mockedListBusinesses.mockResolvedValueOnce({
      ...populatedPage,
      page: 1,
      totalElements: 11,
    })

    render(
      <StatefulBusinessList
        onAuthenticationRequired={vi.fn()}
        initialList={{ page: 2, size: 10, sort: 'displayName', direction: 'asc' }}
      />,
    )

    expect(await screen.findByText('Студио А')).toBeInTheDocument()
    expect(mockedListBusinesses).toHaveBeenLastCalledWith(
      1,
      10,
      'displayName',
      'asc',
      expect.any(AbortSignal),
    )
  })

  it('notifies App of a stale authenticated session on 401', async () => {
    const onAuthenticationRequired = vi.fn()
    mockedListBusinesses.mockRejectedValue(
      new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'),
    )

    render(<StatefulBusinessList onAuthenticationRequired={onAuthenticationRequired} />)

    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('keeps the authenticated view and renders a dedicated safe 403 state', async () => {
    mockedListBusinesses.mockRejectedValue(
      new ApiError(403, 'ACCESS_DENIED', 'Нямате достъп до тази операция.'),
    )

    render(<StatefulBusinessList onAuthenticationRequired={vi.fn()} />)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Нямате достъп до тази операция.',
    )
  })

  it('hides malformed failure details and prevents duplicate retries', async () => {
    let rejectFirstRequest: ((reason: unknown) => void) | undefined
    mockedListBusinesses.mockImplementationOnce(
      () =>
        new Promise((_resolve, reject) => {
          rejectFirstRequest = reject
        }),
    )

    render(<StatefulBusinessList onAuthenticationRequired={vi.fn()} />)
    expect(mockedListBusinesses).toHaveBeenCalledTimes(1)
    rejectFirstRequest?.(new Error('SQL select secret_table'))
    const retry = await screen.findByRole('button', { name: 'Опитай отново' })
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Списъкът с бизнеси не може да бъде зареден.',
    )
    expect(screen.queryByText(/SQL|secret_table/)).not.toBeInTheDocument()

    let resolveRetry: ((page: BusinessPage) => void) | undefined
    mockedListBusinesses.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveRetry = resolve
        }),
    )
    fireEvent.click(retry)
    fireEvent.click(retry)
    expect(mockedListBusinesses).toHaveBeenCalledTimes(2)
    resolveRetry?.(populatedPage)
    expect(await screen.findByText('Студио А')).toBeInTheDocument()
  })

  it('retries the page that failed', async () => {
    mockedListBusinesses
      .mockResolvedValueOnce({ ...populatedPage, totalElements: 11 })
      .mockRejectedValueOnce(new Error('temporary failure'))

    render(<StatefulBusinessList onAuthenticationRequired={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Следваща' }))
    const retry = await screen.findByRole('button', { name: 'Опитай отново' })
    mockedListBusinesses.mockResolvedValueOnce({
      ...populatedPage,
      businesses: [{ ...populatedPage.businesses[0]!, displayName: 'Втора страница' }],
      page: 1,
      totalElements: 11,
    })
    fireEvent.click(retry)

    expect(mockedListBusinesses).toHaveBeenLastCalledWith(
      1,
      10,
      'displayName',
      'asc',
      expect.any(AbortSignal),
    )
    expect(await screen.findByText('Втора страница')).toBeInTheDocument()
    expect(screen.getByText('Страница 2 от 2')).toBeInTheDocument()
  })

  it('does not let an obsolete response replace newer list state', async () => {
    let resolveObsolete: ((page: BusinessPage) => void) | undefined
    let resolveCurrent: ((page: BusinessPage) => void) | undefined
    mockedListBusinesses
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveObsolete = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveCurrent = resolve
          }),
      )

    const { rerender } = render(
      <BusinessList
        list={BUSINESSES_DEFAULT_LIST}
        onListChange={vi.fn()}
        onAuthenticationRequired={vi.fn()}
      />,
    )
    rerender(
      <BusinessList
        list={BUSINESSES_DEFAULT_LIST}
        onListChange={vi.fn()}
        onAuthenticationRequired={vi.fn()}
      />,
    )

    const currentPage: BusinessPage = {
      ...populatedPage,
      businesses: [{ ...populatedPage.businesses[0]!, displayName: 'Текущ бизнес' }],
    }
    resolveCurrent?.(currentPage)
    expect(await screen.findByText('Текущ бизнес')).toBeInTheDocument()

    resolveObsolete?.(populatedPage)
    await waitFor(() => {
      expect(screen.getByText('Текущ бизнес')).toBeInTheDocument()
      expect(screen.queryByText('Студио А')).not.toBeInTheDocument()
    })
  })

  it('allows only the active StrictMode request to render data', async () => {
    const replayedRequest = deferred<BusinessPage>()
    const activeRequest = deferred<BusinessPage>()
    const signals: AbortSignal[] = []
    const onAuthenticationRequired = vi.fn()
    mockedListBusinesses
      .mockImplementationOnce((_page, _size, _sort, _direction, signal) => {
        signals.push(signal!)
        return replayedRequest.promise
      })
      .mockImplementationOnce((_page, _size, _sort, _direction, signal) => {
        signals.push(signal!)
        return activeRequest.promise
      })

    render(
      <StrictMode>
        <BusinessList
          list={BUSINESSES_DEFAULT_LIST}
          onListChange={vi.fn()}
          onAuthenticationRequired={onAuthenticationRequired}
        />
      </StrictMode>,
    )

    expect(mockedListBusinesses).toHaveBeenCalledTimes(2)
    expect(signals[0]).toHaveProperty('aborted', true)
    expect(signals[1]).toHaveProperty('aborted', false)

    const currentPage: BusinessPage = {
      ...populatedPage,
      businesses: [{ ...populatedPage.businesses[0]!, displayName: 'Активна заявка' }],
    }
    activeRequest.resolve(currentPage)
    expect(await screen.findByText('Активна заявка')).toBeInTheDocument()

    replayedRequest.resolve(populatedPage)
    await waitFor(() => {
      expect(screen.getByText('Активна заявка')).toBeInTheDocument()
      expect(screen.queryByText('Студио А')).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(onAuthenticationRequired).not.toHaveBeenCalled()
    })
  })

  it('ignores authentication errors from canceled StrictMode work', async () => {
    const replayedRequest = deferred<BusinessPage>()
    const activeRequest = deferred<BusinessPage>()
    const signals: AbortSignal[] = []
    const onAuthenticationRequired = vi.fn()
    mockedListBusinesses
      .mockImplementationOnce((_page, _size, _sort, _direction, signal) => {
        signals.push(signal!)
        return replayedRequest.promise
      })
      .mockImplementationOnce((_page, _size, _sort, _direction, signal) => {
        signals.push(signal!)
        return activeRequest.promise
      })

    render(
      <StrictMode>
        <BusinessList
          list={BUSINESSES_DEFAULT_LIST}
          onListChange={vi.fn()}
          onAuthenticationRequired={onAuthenticationRequired}
        />
      </StrictMode>,
    )

    expect(signals[0]).toHaveProperty('aborted', true)
    expect(signals[1]).toHaveProperty('aborted', false)
    activeRequest.resolve(populatedPage)
    expect(await screen.findByText('Студио А')).toBeInTheDocument()

    replayedRequest.reject(
      new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'),
    )
    await waitFor(() => {
      expect(screen.getByText('Студио А')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(onAuthenticationRequired).not.toHaveBeenCalled()
    })
  })

  it('clicking a sortable header requests that field ascending and resets to page 0', async () => {
    mockedListBusinesses.mockResolvedValue(populatedPage)
    render(
      <StatefulBusinessList
        onAuthenticationRequired={vi.fn()}
        initialList={{ page: 1, size: 10, sort: 'displayName', direction: 'asc' }}
      />,
    )
    await screen.findByText('Студио А')

    fireEvent.click(screen.getByRole('button', { name: 'Дейност' }))

    await waitFor(() =>
      expect(mockedListBusinesses).toHaveBeenLastCalledWith(
        0,
        10,
        'businessType',
        'asc',
        expect.any(AbortSignal),
      ),
    )
  })

  it('clicking the active header a second time reverses direction', async () => {
    mockedListBusinesses.mockResolvedValue(populatedPage)
    render(
      <StatefulBusinessList
        onAuthenticationRequired={vi.fn()}
        initialList={{ page: 0, size: 10, sort: 'slug', direction: 'asc' }}
      />,
    )
    await screen.findByText('Студио А')

    fireEvent.click(screen.getByRole('button', { name: 'Идентификатор в уеб адреса' }))

    await waitFor(() =>
      expect(mockedListBusinesses).toHaveBeenLastCalledWith(
        0,
        10,
        'slug',
        'desc',
        expect.any(AbortSignal),
      ),
    )
  })

  it('exposes aria-sort only on the active column header', async () => {
    mockedListBusinesses.mockResolvedValue(populatedPage)
    render(
      <StatefulBusinessList
        onAuthenticationRequired={vi.fn()}
        initialList={{ page: 0, size: 10, sort: 'status', direction: 'desc' }}
      />,
    )
    await screen.findByText('Студио А')

    expect(screen.getByRole('columnheader', { name: 'Статус' }))
      .toHaveAttribute('aria-sort', 'descending')
    expect(screen.getByRole('columnheader', { name: 'Име' })).toHaveAttribute('aria-sort', 'none')
  })

  it('shows both direction arrows on every sortable header, with only the active one emphasized', async () => {
    mockedListBusinesses.mockResolvedValue(populatedPage)
    render(
      <StatefulBusinessList
        onAuthenticationRequired={vi.fn()}
        initialList={{ page: 0, size: 10, sort: 'status', direction: 'desc' }}
      />,
    )
    await screen.findByText('Студио А')

    const headers = screen.getAllByRole('columnheader')
    expect(headers).toHaveLength(4)
    for (const header of headers) {
      expect(header.querySelectorAll('.sort-arrow')).toHaveLength(2)
    }

    const statusHeader = screen.getByRole('columnheader', { name: 'Статус' })
    const nameHeader = screen.getByRole('columnheader', { name: 'Име' })
    expect(statusHeader).toHaveClass('is-active')
    expect(nameHeader).not.toHaveClass('is-active')
    expect(statusHeader.querySelectorAll('.sort-arrow.is-active')).toHaveLength(1)
    expect(statusHeader.querySelector('.sort-arrow.is-active')).toHaveTextContent('▼')
    expect(nameHeader.querySelectorAll('.sort-arrow.is-active')).toHaveLength(0)
  })

  it('renders exactly one page-size selector, placed inside the pagination region', async () => {
    mockedListBusinesses.mockResolvedValue(populatedPage)
    render(<StatefulBusinessList onAuthenticationRequired={vi.fn()} />)
    await screen.findByText('Студио А')

    const selectors = screen.getAllByLabelText('Резултати на страница')
    expect(selectors).toHaveLength(1)
    const pagination = screen.getByRole('navigation', { name: 'Странициране на бизнесите' })
    expect(pagination).toContainElement(selectors[0]!)
  })

  it('changing the page size selector resets to page 0', async () => {
    mockedListBusinesses.mockResolvedValue(populatedPage)
    render(
      <StatefulBusinessList
        onAuthenticationRequired={vi.fn()}
        initialList={{ page: 1, size: 10, sort: 'displayName', direction: 'asc' }}
      />,
    )
    await screen.findByText('Студио А')

    fireEvent.change(screen.getByLabelText('Резултати на страница'), {
      target: { value: '50' },
    })

    await waitFor(() =>
      expect(mockedListBusinesses).toHaveBeenLastCalledWith(
        0,
        50,
        'displayName',
        'asc',
        expect.any(AbortSignal),
      ),
    )
  })

  it('the responsive sort control exposes the same sort/direction state', async () => {
    mockedListBusinesses.mockResolvedValue(populatedPage)
    render(
      <StatefulBusinessList
        onAuthenticationRequired={vi.fn()}
        initialList={{ page: 1, size: 10, sort: 'displayName', direction: 'asc' }}
      />,
    )
    await screen.findByText('Студио А')

    fireEvent.change(screen.getByLabelText('Подреди по'), {
      target: { value: 'status:desc' },
    })

    await waitFor(() =>
      expect(mockedListBusinesses).toHaveBeenLastCalledWith(
        0,
        10,
        'status',
        'desc',
        expect.any(AbortSignal),
      ),
    )
  })
})
