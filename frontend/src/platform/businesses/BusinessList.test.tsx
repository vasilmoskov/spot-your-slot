import '@testing-library/jest-dom/vitest'
import { StrictMode } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
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
  size: 50,
  totalElements: 1,
}

beforeEach(() => {
  mockedListBusinesses.mockReset()
})

describe('BusinessList', () => {
  it('loads page zero with size 50 and renders approved summary metadata once', async () => {
    mockedListBusinesses.mockResolvedValue(populatedPage)
    const onCreate = vi.fn()
    const onOpen = vi.fn()

    render(
      <BusinessList
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
      50,
      expect.any(AbortSignal),
    )
    expect(screen.getAllByText('Студио А')).toHaveLength(1)
    expect(
      screen.getAllByRole('columnheader').map((heading) => heading.textContent),
    ).toEqual([
      'Име',
      'Идентификатор в уеб адреса',
      'Дейност',
      'Статус',
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
    expect(screen.getByText('Страница 1')).toBeInTheDocument()
    expect(screen.getByText('Общо бизнеси: 1')).toBeInTheDocument()
    expect(
      screen.getByRole('navigation', { name: 'Странициране на бизнесите' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Предишна' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Следваща' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Нов бизнес' }))
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

    render(<BusinessList onAuthenticationRequired={vi.fn()} />)

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
      size: 50,
      totalElements: 0,
    })

    render(<BusinessList onAuthenticationRequired={vi.fn()} />)

    expect(await screen.findByText('Все още няма създадени бизнеси.'))
      .toBeInTheDocument()
    expect(screen.getByText('Страница 1')).toBeInTheDocument()
    expect(screen.getByText('Общо бизнеси: 0')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Предишна' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Следваща' })).toBeDisabled()
  })

  it('requests exact next and previous pages while clearing stale rows', async () => {
    mockedListBusinesses.mockResolvedValueOnce({
      ...populatedPage,
      totalElements: 101,
    })
    const nextRequest = deferred<BusinessPage>()
    mockedListBusinesses.mockImplementationOnce(() => nextRequest.promise)

    render(<BusinessList onAuthenticationRequired={vi.fn()} />)

    const next = await screen.findByRole('button', { name: 'Следваща' })
    const previous = screen.getByRole('button', { name: 'Предишна' })
    expect(previous).toBeDisabled()
    expect(next).toBeEnabled()

    fireEvent.click(next)
    fireEvent.click(next)
    expect(screen.getByText('Зареждане на бизнесите…')).toBeInTheDocument()
    expect(screen.queryByText('Студио А')).not.toBeInTheDocument()
    expect(mockedListBusinesses).toHaveBeenCalledTimes(2)
    expect(mockedListBusinesses).toHaveBeenLastCalledWith(
      1,
      50,
      expect.any(AbortSignal),
    )

    nextRequest.resolve({
      ...populatedPage,
      businesses: [{ ...populatedPage.businesses[0]!, displayName: 'Студио Б' }],
      page: 1,
      totalElements: 101,
    })
    expect(await screen.findByText('Студио Б')).toBeInTheDocument()
    expect(screen.getByText('Страница 2')).toBeInTheDocument()
    expect(screen.getByText('Общо бизнеси: 101')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Предишна' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Следваща' })).toBeEnabled()

    mockedListBusinesses.mockResolvedValueOnce({
      ...populatedPage,
      totalElements: 101,
    })
    fireEvent.click(screen.getByRole('button', { name: 'Предишна' }))
    expect(mockedListBusinesses).toHaveBeenLastCalledWith(
      0,
      50,
      expect.any(AbortSignal),
    )
    expect(await screen.findByText('Студио А')).toBeInTheDocument()
  })

  it('disables Next on the final page', async () => {
    mockedListBusinesses.mockResolvedValue({
      ...populatedPage,
      page: 2,
      size: 50,
      totalElements: 101,
    })

    render(<BusinessList onAuthenticationRequired={vi.fn()} />)

    expect(await screen.findByText('Страница 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Предишна' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Следваща' })).toBeDisabled()
  })

  it('allows only Previous from an empty out-of-range page', async () => {
    mockedListBusinesses.mockResolvedValue({
      businesses: [],
      page: 2,
      size: 50,
      totalElements: 51,
    })

    render(<BusinessList onAuthenticationRequired={vi.fn()} />)

    expect(await screen.findByText('Няма бизнеси на тази страница.'))
      .toBeInTheDocument()
    expect(screen.getByText('Страница 3')).toBeInTheDocument()
    expect(screen.getByText('Общо бизнеси: 51')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Предишна' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Следваща' })).toBeDisabled()
  })

  it('notifies App of a stale authenticated session on 401', async () => {
    const onAuthenticationRequired = vi.fn()
    mockedListBusinesses.mockRejectedValue(
      new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'),
    )

    render(<BusinessList onAuthenticationRequired={onAuthenticationRequired} />)

    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('keeps the authenticated view and renders a dedicated safe 403 state', async () => {
    mockedListBusinesses.mockRejectedValue(
      new ApiError(403, 'ACCESS_DENIED', 'Нямате достъп до тази операция.'),
    )

    render(<BusinessList onAuthenticationRequired={vi.fn()} />)

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

    render(<BusinessList onAuthenticationRequired={vi.fn()} />)
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
      .mockResolvedValueOnce({ ...populatedPage, totalElements: 51 })
      .mockRejectedValueOnce(new Error('temporary failure'))

    render(<BusinessList onAuthenticationRequired={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Следваща' }))
    const retry = await screen.findByRole('button', { name: 'Опитай отново' })
    mockedListBusinesses.mockResolvedValueOnce({
      ...populatedPage,
      businesses: [{ ...populatedPage.businesses[0]!, displayName: 'Втора страница' }],
      page: 1,
      totalElements: 51,
    })
    fireEvent.click(retry)

    expect(mockedListBusinesses).toHaveBeenLastCalledWith(
      1,
      50,
      expect.any(AbortSignal),
    )
    expect(await screen.findByText('Втора страница')).toBeInTheDocument()
    expect(screen.getByText('Страница 2')).toBeInTheDocument()
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
      <BusinessList onAuthenticationRequired={vi.fn()} />,
    )
    rerender(<BusinessList onAuthenticationRequired={vi.fn()} />)

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

  it('replaces an active page load without losing the requested page', async () => {
    mockedListBusinesses.mockResolvedValueOnce({
      ...populatedPage,
      totalElements: 51,
    })
    const obsoleteRequest = deferred<BusinessPage>()
    const currentRequest = deferred<BusinessPage>()
    const pageSignals: AbortSignal[] = []
    mockedListBusinesses
      .mockImplementationOnce((_page, _size, signal) => {
        pageSignals.push(signal!)
        return obsoleteRequest.promise
      })
      .mockImplementationOnce((_page, _size, signal) => {
        pageSignals.push(signal!)
        return currentRequest.promise
      })
    const onAuthenticationRequired = vi.fn()
    const { rerender } = render(
      <BusinessList onAuthenticationRequired={onAuthenticationRequired} />,
    )

    fireEvent.click(await screen.findByRole('button', { name: 'Следваща' }))
    rerender(<BusinessList onAuthenticationRequired={vi.fn()} />)

    expect(mockedListBusinesses).toHaveBeenNthCalledWith(
      2,
      1,
      50,
      pageSignals[0],
    )
    expect(mockedListBusinesses).toHaveBeenNthCalledWith(
      3,
      1,
      50,
      pageSignals[1],
    )
    expect(pageSignals[0]).toHaveProperty('aborted', true)
    expect(pageSignals[1]).toHaveProperty('aborted', false)

    currentRequest.resolve({
      ...populatedPage,
      businesses: [{ ...populatedPage.businesses[0]!, displayName: 'Текуща страница' }],
      page: 1,
      totalElements: 51,
    })
    expect(await screen.findByText('Текуща страница')).toBeInTheDocument()
    expect(screen.getByText('Страница 2')).toBeInTheDocument()

    obsoleteRequest.resolve({
      ...populatedPage,
      businesses: [{ ...populatedPage.businesses[0]!, displayName: 'Остаряла страница' }],
      page: 1,
      totalElements: 51,
    })
    await waitFor(() => {
      expect(screen.getByText('Текуща страница')).toBeInTheDocument()
      expect(screen.queryByText('Остаряла страница')).not.toBeInTheDocument()
      expect(onAuthenticationRequired).not.toHaveBeenCalled()
    })
  })

  it('allows only the active StrictMode request to render data', async () => {
    const replayedRequest = deferred<BusinessPage>()
    const activeRequest = deferred<BusinessPage>()
    const signals: AbortSignal[] = []
    const onAuthenticationRequired = vi.fn()
    mockedListBusinesses
      .mockImplementationOnce((_page, _size, signal) => {
        signals.push(signal!)
        return replayedRequest.promise
      })
      .mockImplementationOnce((_page, _size, signal) => {
        signals.push(signal!)
        return activeRequest.promise
      })

    render(
      <StrictMode>
        <BusinessList onAuthenticationRequired={onAuthenticationRequired} />
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
      .mockImplementationOnce((_page, _size, signal) => {
        signals.push(signal!)
        return replayedRequest.promise
      })
      .mockImplementationOnce((_page, _size, signal) => {
        signals.push(signal!)
        return activeRequest.promise
      })

    render(
      <StrictMode>
        <BusinessList onAuthenticationRequired={onAuthenticationRequired} />
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
})
