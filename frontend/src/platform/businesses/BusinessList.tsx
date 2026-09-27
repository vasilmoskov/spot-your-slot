import { useCallback, useEffect, useRef, useState } from 'react'
import { Button } from '../../ui/Button'
import { ApiError } from '../../identity/api'
import { PageSizeSelect, ResponsiveSortSelect, SortableColumnHeader } from '../../ui/ListSortControls'
import {
  BUSINESSES_SORT_FIELDS,
  PLATFORM_BUSINESS_NEW_ROUTE,
  pushRoute,
  routeHref,
  type ListNavigationMode,
  type ListPageSize,
  type ListQueryState,
  type ListSortDirection,
} from '../../navigation'
import { listBusinesses, type BusinessPage } from './api'
import {
  BUSINESS_STATUS_PRESENTATION,
  businessTypeLabel,
} from './presentation'

type BusinessSortField = (typeof BUSINESSES_SORT_FIELDS)[number]

const SORT_OPTIONS: ReadonlyArray<{ field: BusinessSortField; ascLabel: string; descLabel: string }> = [
  { field: 'displayName', ascLabel: 'Име (А-Я)', descLabel: 'Име (Я-А)' },
  { field: 'slug', ascLabel: 'Идентификатор (А-Я)', descLabel: 'Идентификатор (Я-А)' },
  { field: 'businessType', ascLabel: 'Дейност (А-Я)', descLabel: 'Дейност (Я-А)' },
  { field: 'status', ascLabel: 'Статус (по жизнен цикъл)', descLabel: 'Статус (обратен ред)' },
]

const COLUMN_LABELS: Record<BusinessSortField, string> = {
  displayName: 'Име',
  slug: 'Идентификатор в уеб адреса',
  businessType: 'Дейност',
  status: 'Статус',
}

type BusinessListProps = {
  list: ListQueryState
  onListChange: (next: ListQueryState, mode?: ListNavigationMode) => void
  onAuthenticationRequired: (detail: string) => void
  onCreate?: () => void
  onOpen?: (businessId: string) => void
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; page: BusinessPage }
  | { kind: 'forbidden'; detail: string }
  | { kind: 'error' }

const GENERIC_ERROR = 'Списъкът с бизнеси не може да бъде зареден.'

export function BusinessList({
  list,
  onListChange,
  onAuthenticationRequired,
  onCreate = () => pushRoute(PLATFORM_BUSINESS_NEW_ROUTE),
  onOpen = (businessId) =>
    pushRoute({ kind: 'platform-business-detail', businessId }),
}: BusinessListProps) {
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
        const response = await listBusinesses(
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
        if (query.page > 0 && response.businesses.length === 0 && response.totalElements > 0) {
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

  const sortColumn = (field: BusinessSortField) => {
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
        <div className="feedback-action-layout">
          <div className="status-message status-error" role="alert">
            <p>{GENERIC_ERROR}</p>
          </div>
          <div className="feedback-action-controls">
            <Button
              type="button"
              variant="secondary"
              onClick={() => void load(list)}
            >
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
          <Button type="button" onClick={onCreate}>
            Нов бизнес
          </Button>
        </div>
        <div className="list-toolbar">
          <ResponsiveSortSelect
            id="businesses-responsive-sort"
            label="Подреди по"
            options={SORT_OPTIONS}
            sort={list.sort}
            direction={list.direction}
            onChange={changeResponsiveSort}
          />
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
                  {BUSINESSES_SORT_FIELDS.map((field) => (
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
        <BusinessPagination
          page={state.page}
          onPageRequested={(page) => onListChange({ ...list, page })}
          onSizeChange={changePageSize}
        />
      </div>
    </div>
  )
}

type BusinessPaginationProps = {
  page: BusinessPage
  onPageRequested: (page: number) => void
  onSizeChange: (size: ListPageSize) => void
}

function BusinessPagination({ page, onPageRequested, onSizeChange }: BusinessPaginationProps) {
  const previousDisabled = page.page === 0
  const nextDisabled = (page.page + 1) * page.size >= page.totalElements
  const totalPages = Math.max(1, Math.ceil(page.totalElements / page.size))
  const from = page.totalElements === 0 ? 0 : page.page * page.size + 1
  const to = Math.min((page.page + 1) * page.size, page.totalElements)

  return (
    <nav className="business-pagination" aria-label="Странициране на бизнесите">
      <div className="business-pagination-summary">
        <span>
          {from}–{to} от {page.totalElements} бизнеса
        </span>
        <span>
          Страница {page.page + 1} от {totalPages}
        </span>
      </div>
      <div className="business-pagination-controls">
        <PageSizeSelect id="businesses-page-size" value={page.size as ListPageSize} onChange={onSizeChange} />
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
