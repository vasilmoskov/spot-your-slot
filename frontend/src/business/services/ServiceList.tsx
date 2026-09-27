import { useCallback, useEffect, useRef, useState } from 'react'
import { Button } from '../../ui/Button'
import { ApiError } from '../../identity/api'
import { BUSINESS_SERVICE_NEW_ROUTE, pushRoute, routeHref } from '../../navigation'
import { listServices, type ServicePage } from './api'
import { formatServiceDuration, formatServicePrice, serviceStatusPresentation } from './presentation'

type ServiceListProps = {
  readOnly: boolean
  onAuthenticationRequired: (detail: string) => void
  onCreate?: () => void
  onOpen?: (serviceId: string) => void
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; page: ServicePage }
  | { kind: 'forbidden'; detail: string }
  | { kind: 'error'; page: number }

const GENERIC_ERROR = 'Списъкът с услуги не може да бъде зареден.'
const PAGE_SIZE = 50

export function ServiceList({
  readOnly,
  onAuthenticationRequired,
  onCreate = () => pushRoute(BUSINESS_SERVICE_NEW_ROUTE),
  onOpen = (serviceId) => pushRoute({ kind: 'business-service-detail', serviceId }),
}: ServiceListProps) {
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

    const requestState = {
      id: ++requestSequence.current,
      page,
      controller: new AbortController(),
    }
    requestedPage.current = page
    activeRequest.current = requestState
    setState({ kind: 'loading' })

    try {
      const response = await listServices(page, PAGE_SIZE, requestState.controller.signal)
      if (
        activeRequest.current?.id === requestState.id &&
        !requestState.controller.signal.aborted
      ) {
        setState({ kind: 'loaded', page: response })
      }
    } catch (error) {
      if (
        requestState.controller.signal.aborted ||
        activeRequest.current?.id !== requestState.id
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
      if (activeRequest.current?.id === requestState.id) {
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
        <p className="business-list-state">Зареждане на услугите…</p>
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
        <div className="feedback-action-layout">
          <div className="status-message status-error" role="alert">
            <p>{GENERIC_ERROR}</p>
          </div>
          <div className="feedback-action-controls">
            <Button type="button" variant="secondary" onClick={() => void load(state.page)}>
              Опитай отново
            </Button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="platform-content">
      <div className="business-list-content">
        <div className="business-page-actions">
          {!readOnly && (
            <Button type="button" onClick={onCreate}>
              Нова услуга
            </Button>
          )}
        </div>
        {readOnly && (
          <p className="section-introduction">
            Бизнесът е временно спрян — услугите могат само да бъдат преглеждани.
          </p>
        )}
        {state.page.services.length === 0 ? (
          <p className="business-list-state" aria-live="polite">
            {state.page.totalElements === 0
              ? 'Все още няма създадени услуги.'
              : 'Няма услуги на тази страница.'}
          </p>
        ) : (
          <div className="business-table-container services-table-container">
            <table className="business-table services-table">
              <caption className="visually-hidden">Списък с услуги</caption>
              <thead>
                <tr>
                  <th scope="col">Име</th>
                  <th scope="col">Продължителност</th>
                  <th scope="col">Цена</th>
                  <th scope="col">Статус</th>
                </tr>
              </thead>
              <tbody>
                {state.page.services.map((service) => {
                  const status = serviceStatusPresentation(service.active)
                  return (
                    <tr key={service.id}>
                      <td data-label="Име">
                        <div className="business-name-cell">
                          <a
                            aria-label={`Отвори ${service.name}`}
                            href={routeHref({ kind: 'business-service-detail', serviceId: service.id })}
                            onClick={(event) => {
                              event.preventDefault()
                              onOpen(service.id)
                            }}
                          >
                            {service.name}
                          </a>
                        </div>
                      </td>
                      <td data-label="Продължителност">
                        {formatServiceDuration(service.durationMinutes)}
                      </td>
                      <td data-label="Цена">{formatServicePrice(service.price)}</td>
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
        <ServicePagination page={state.page} onPageRequested={load} />
      </div>
    </div>
  )
}

type ServicePaginationProps = {
  page: ServicePage
  onPageRequested: (page: number) => void
}

function ServicePagination({ page, onPageRequested }: ServicePaginationProps) {
  const previousDisabled = page.page === 0
  const nextDisabled = (page.page + 1) * page.size >= page.totalElements

  return (
    <nav className="business-pagination" aria-label="Странициране на услугите">
      <div className="business-pagination-summary">
        <span>Страница {page.page + 1}</span>
        <span>Общо услуги: {page.totalElements}</span>
      </div>
      <div className="business-pagination-actions">
        <Button
          type="button"
          variant="secondary"
          disabled={previousDisabled}
          onClick={() => onPageRequested(page.page - 1)}
        >
          Предишна
        </Button>
        <Button
          type="button"
          variant="secondary"
          disabled={nextDisabled}
          onClick={() => onPageRequested(page.page + 1)}
        >
          Следваща
        </Button>
      </div>
    </nav>
  )
}
