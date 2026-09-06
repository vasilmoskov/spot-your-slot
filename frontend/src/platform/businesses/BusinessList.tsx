import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../../identity/api'
import {
  PLATFORM_BUSINESS_NEW_ROUTE,
  pushRoute,
  routeHref,
} from '../../navigation'
import { listBusinesses, type BusinessPage } from './api'
import {
  BUSINESS_STATUS_PRESENTATION,
  businessTypeLabel,
} from './presentation'

type BusinessListProps = {
  onAuthenticationRequired: (detail: string) => void
  onCreate?: () => void
  onOpen?: (businessId: string) => void
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; page: BusinessPage }
  | { kind: 'forbidden'; detail: string }
  | { kind: 'error'; page: number }

const GENERIC_ERROR = 'Списъкът с бизнеси не може да бъде зареден.'
const PAGE_SIZE = 50

export function BusinessList({
  onAuthenticationRequired,
  onCreate = () => pushRoute(PLATFORM_BUSINESS_NEW_ROUTE),
  onOpen = (businessId) =>
    pushRoute({ kind: 'platform-business-detail', businessId }),
}: BusinessListProps) {
  const [state, setState] = useState<ListState>({ kind: 'loading' })
  const requestSequence = useRef(0)
  const requestedPage = useRef(0)
  const activeRequest = useRef<{
    id: number
    page: number
    controller: AbortController
  } | null>(null)

  const load = useCallback(async (page: number) => {
    if (activeRequest.current && !activeRequest.current.controller.signal.aborted) {
      if (activeRequest.current.page === page) {
        return
      }
      activeRequest.current.controller.abort()
    }

    const request = {
      id: ++requestSequence.current,
      page,
      controller: new AbortController(),
    }
    requestedPage.current = page
    activeRequest.current = request
    setState({ kind: 'loading' })

    try {
      const response = await listBusinesses(page, PAGE_SIZE, request.controller.signal)
      if (
        activeRequest.current?.id === request.id &&
        !request.controller.signal.aborted
      ) {
        setState({ kind: 'loaded', page: response })
      }
    } catch (error) {
      if (
        request.controller.signal.aborted ||
        activeRequest.current?.id !== request.id
      ) {
        return
      }

      if (error instanceof ApiError && error.status === 401) {
        onAuthenticationRequired(error.detail)
        return
      }
      if (error instanceof ApiError && error.status === 403) {
        setState({ kind: 'forbidden', detail: error.detail })
        return
      }
      setState({ kind: 'error', page })
    } finally {
      if (activeRequest.current?.id === request.id) {
        activeRequest.current = null
      }
    }
  }, [onAuthenticationRequired])

  useEffect(() => {
    void load(requestedPage.current)
    return () => {
      activeRequest.current?.controller.abort()
    }
  }, [load])

  if (state.kind === 'loading') {
    return (
      <div className="platform-content" aria-live="polite" aria-busy="true">
        <p className="business-list-state">Зареждане на бизнесите…</p>
      </div>
    )
  }

  if (state.kind === 'forbidden') {
    return (
      <div className="platform-content">
        <p className="status-message status-error" role="alert">
          {state.detail}
        </p>
      </div>
    )
  }

  if (state.kind === 'error') {
    return (
      <div className="platform-content">
        <div className="status-message status-error" role="alert">
          <p>{GENERIC_ERROR}</p>
          <button
            type="button"
            className="secondary-button"
            onClick={() => void load(state.page)}
          >
            Опитай отново
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="platform-content">
      <div className="business-list-content">
        <div className="business-page-actions business-page-actions-end">
          <button type="button" onClick={onCreate}>
            Нов бизнес
          </button>
        </div>
        {state.page.businesses.length === 0 ? (
          <p className="business-list-state" aria-live="polite">
            {state.page.totalElements === 0
              ? 'Все още няма създадени бизнеси.'
              : 'Няма бизнеси на тази страница.'}
          </p>
        ) : (
          <div className="business-table-container">
            <table className="business-table">
              <caption className="visually-hidden">Списък с бизнеси</caption>
              <thead>
                <tr>
                  <th scope="col">Име</th>
                  <th scope="col">Идентификатор в уеб адреса</th>
                  <th scope="col">Дейност</th>
                  <th scope="col">Статус</th>
                </tr>
              </thead>
              <tbody>
                {state.page.businesses.map((business) => {
                  const status = BUSINESS_STATUS_PRESENTATION[business.status]
                  return (
                    <tr key={business.id}>
                      <td data-label="Име">
                        <div className="business-name-cell">
                          <a
                            aria-label={`Отвори ${business.displayName}`}
                            href={routeHref({
                              kind: 'platform-business-detail',
                              businessId: business.id,
                            })}
                            onClick={(event) => {
                              event.preventDefault()
                              onOpen(business.id)
                            }}
                          >
                            {business.displayName}
                          </a>
                        </div>
                      </td>
                      <td data-label="Идентификатор в уеб адреса">
                        {business.slug}
                      </td>
                      <td data-label="Дейност">
                        {businessTypeLabel(business.businessType)}
                      </td>
                      <td data-label="Статус">
                        <span className={`status-badge status-badge-${status.tone}`}>
                          {status.label}
                        </span>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
        <BusinessPagination page={state.page} onPageRequested={load} />
      </div>
    </div>
  )
}

type BusinessPaginationProps = {
  page: BusinessPage
  onPageRequested: (page: number) => void
}

function BusinessPagination({ page, onPageRequested }: BusinessPaginationProps) {
  const previousDisabled = page.page === 0
  const nextDisabled = (page.page + 1) * page.size >= page.totalElements

  return (
    <nav className="business-pagination" aria-label="Странициране на бизнесите">
      <div className="business-pagination-summary">
        <span>Страница {page.page + 1}</span>
        <span>Общо бизнеси: {page.totalElements}</span>
      </div>
      <div className="business-pagination-actions">
        <button
          type="button"
          className="secondary-button"
          disabled={previousDisabled}
          onClick={() => onPageRequested(page.page - 1)}
        >
          Предишна
        </button>
        <button
          type="button"
          className="secondary-button"
          disabled={nextDisabled}
          onClick={() => onPageRequested(page.page + 1)}
        >
          Следваща
        </button>
      </div>
    </nav>
  )
}
