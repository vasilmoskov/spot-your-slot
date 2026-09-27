import { useCallback, useEffect, useRef, useState } from 'react'
import { Button } from '../../ui/Button'
import { ApiError } from '../../identity/api'
import { PageSizeSelect, ResponsiveSortSelect, SortableColumnHeader } from '../../ui/ListSortControls'
import {
  SERVICES_SORT_FIELDS,
  BUSINESS_SERVICE_NEW_ROUTE,
  pushRoute,
  routeHref,
  type ListNavigationMode,
  type ListPageSize,
  type ListQueryState,
  type ListSortDirection,
} from '../../navigation'
import { listServices, type ServicePage } from './api'
import { formatServiceDuration, formatServicePrice, serviceStatusPresentation } from './presentation'

type ServiceSortField = (typeof SERVICES_SORT_FIELDS)[number]

const SORT_OPTIONS: ReadonlyArray<{ field: ServiceSortField; ascLabel: string; descLabel: string }> = [
  { field: 'name', ascLabel: 'Име (А-Я)', descLabel: 'Име (Я-А)' },
  {
    field: 'duration',
    ascLabel: 'Продължителност (най-кратки)',
    descLabel: 'Продължителност (най-дълги)',
  },
  { field: 'price', ascLabel: 'Цена (най-ниска)', descLabel: 'Цена (най-висока)' },
  { field: 'status', ascLabel: 'Статус (активни)', descLabel: 'Статус (неактивни)' },
]

const COLUMN_LABELS: Record<ServiceSortField, string> = {
  name: 'Име',
  duration: 'Продължителност',
  price: 'Цена',
  status: 'Статус',
}

type ServiceListProps = {
  readOnly: boolean
  list: ListQueryState
  onListChange: (next: ListQueryState, mode?: ListNavigationMode) => void
  onAuthenticationRequired: (detail: string) => void
  onCreate?: () => void
  onOpen?: (serviceId: string) => void
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; page: ServicePage }
  | { kind: 'forbidden'; detail: string }
  | { kind: 'error' }

const GENERIC_ERROR = 'Списъкът с услуги не може да бъде зареден.'

export function ServiceList({
  readOnly,
  list,
  onListChange,
  onAuthenticationRequired,
  onCreate = () => pushRoute(BUSINESS_SERVICE_NEW_ROUTE),
  onOpen = (serviceId) => pushRoute({ kind: 'business-service-detail', serviceId }),
}: ServiceListProps) {
  const [state, setState] = useState<ListState>({ kind: 'loading' })
  const requestSequence = useRef(0)
  const activeRequest = useRef<{ id: number; controller: AbortController } | null>(null)

  const load = useCallback(
    async (query: ListQueryState) => {
      activeRequest.current?.controller.abort()

      const requestState = { id: ++requestSequence.current, controller: new AbortController() }
      activeRequest.current = requestState
      setState({ kind: 'loading' })

      try {
        const response = await listServices(
          query.page,
          query.size,
          query.sort,
          query.direction,
          requestState.controller.signal,
        )
        if (
          activeRequest.current?.id !== requestState.id ||
          requestState.controller.signal.aborted
        ) {
          return
        }
        if (query.page > 0 && response.services.length === 0 && response.totalElements > 0) {
          const lastValidPage = Math.max(
            0,
            Math.ceil(response.totalElements / query.size) - 1,
          )
          if (lastValidPage !== query.page) {
            onListChange({ ...query, page: lastValidPage }, 'replace')
            return
          }
        }
        setState({ kind: 'loaded', page: response })
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
        setState({ kind: 'error' })
      } finally {
        if (activeRequest.current?.id === requestState.id) {
          activeRequest.current = null
        }
      }
    },
    [onAuthenticationRequired, onListChange],
  )

  useEffect(() => {
    void load(list)
    return () => {
      activeRequest.current?.controller.abort()
    }
  }, [load, list.page, list.size, list.sort, list.direction])

  const sortColumn = (field: ServiceSortField) => {
    if (list.sort === field) {
      onListChange({ ...list, page: 0, direction: list.direction === 'asc' ? 'desc' : 'asc' })
      return
    }
    onListChange({ ...list, page: 0, sort: field, direction: 'asc' })
  }

  const changePageSize = (size: ListPageSize) => {
    onListChange({ ...list, page: 0, size })
  }

  const changeResponsiveSort = (next: { sort: string; direction: ListSortDirection }) => {
    onListChange({ ...list, page: 0, sort: next.sort, direction: next.direction })
  }

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
            <Button type="button" variant="secondary" onClick={() => void load(list)}>
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
        <div className="list-toolbar services-list-toolbar">
          <ResponsiveSortSelect
            id="services-responsive-sort"
            label="Подреди по"
            options={SORT_OPTIONS}
            sort={list.sort}
            direction={list.direction}
            onChange={changeResponsiveSort}
          />
        </div>
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
                  {SERVICES_SORT_FIELDS.map((field) => (
                    <SortableColumnHeader
                      key={field}
                      label={COLUMN_LABELS[field]}
                      active={list.sort === field}
                      direction={list.direction}
                      onSort={() => sortColumn(field)}
                    />
                  ))}
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
        <ServicePagination
          page={state.page}
          onPageRequested={(page) => onListChange({ ...list, page })}
          onSizeChange={changePageSize}
        />
      </div>
    </div>
  )
}

type ServicePaginationProps = {
  page: ServicePage
  onPageRequested: (page: number) => void
  onSizeChange: (size: ListPageSize) => void
}

function ServicePagination({ page, onPageRequested, onSizeChange }: ServicePaginationProps) {
  const previousDisabled = page.page === 0
  const nextDisabled = (page.page + 1) * page.size >= page.totalElements
  const totalPages = Math.max(1, Math.ceil(page.totalElements / page.size))
  const from = page.totalElements === 0 ? 0 : page.page * page.size + 1
  const to = Math.min((page.page + 1) * page.size, page.totalElements)

  return (
    <nav className="business-pagination" aria-label="Странициране на услугите">
      <div className="business-pagination-summary">
        <span>
          {from}–{to} от {page.totalElements} услуги
        </span>
        <span>
          Страница {page.page + 1} от {totalPages}
        </span>
      </div>
      <div className="business-pagination-controls">
        <PageSizeSelect id="services-page-size" value={page.size as ListPageSize} onChange={onSizeChange} />
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
      </div>
    </nav>
  )
}
