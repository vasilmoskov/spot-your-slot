import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { Button } from '../../ui/Button'
import { ApiError } from '../../identity/api'
import { FieldError, fieldControlProps } from '../../ui/formValidation'
import {
  ListPagination,
  ResponsiveSortSelect,
  SortableColumnHeader,
} from '../../ui/ListSortControls'
import {
  CUSTOMERS_SORT_FIELDS,
  routeHref,
  type ListNavigationMode,
  type ListPageSize,
  type ListQueryState,
  type ListSortDirection,
} from '../../navigation'
import { listCustomers, type CustomerPage } from './api'
import { customerErrorMessage } from './errors'
import { formatCustomerPhone } from './presentation'
import { normalizeSearchTerm, searchTermError } from './validation'

type CustomerSortField = (typeof CUSTOMERS_SORT_FIELDS)[number]

const SORT_OPTIONS: ReadonlyArray<{ field: CustomerSortField; ascLabel: string; descLabel: string }> = [
  { field: 'name', ascLabel: 'Име (А-Я)', descLabel: 'Име (Я-А)' },
  { field: 'phone', ascLabel: 'Телефон (възходящо)', descLabel: 'Телефон (низходящо)' },
  { field: 'email', ascLabel: 'Имейл (възходящо)', descLabel: 'Имейл (низходящо)' },
]

const COLUMN_LABELS: Record<CustomerSortField, string> = {
  name: 'Име',
  phone: 'Телефон',
  email: 'Имейл',
}

type CustomerListProps = {
  readOnly: boolean
  list: ListQueryState
  // The active search term lives in the application's memory only; it is never a route value.
  searchTerm: string
  onSearchTermChange: (term: string) => void
  onListChange: (next: ListQueryState, mode?: ListNavigationMode) => void
  onAuthenticationRequired: (detail: string) => void
  onCreate: () => void
  onOpen: (customerId: string) => void
}

type ListState =
  | { kind: 'loading' }
  // `refreshing`: a newer request is in flight and the previous result stays visible, so typing
  // or sorting never blanks the table (the result is replaced only by the latest response).
  | { kind: 'loaded'; page: CustomerPage; term: string; refreshing: boolean }
  | { kind: 'forbidden'; detail: string }
  | { kind: 'error' }

const GENERIC_ERROR = 'Списъкът с клиенти не може да бъде зареден.'

// How long typing must pause before the search is applied.
export const SEARCH_DEBOUNCE_MS = 300

export function CustomerList({
  readOnly,
  list,
  searchTerm,
  onSearchTermChange,
  onListChange,
  onAuthenticationRequired,
  onCreate,
  onOpen,
}: CustomerListProps) {
  const [state, setState] = useState<ListState>({ kind: 'loading' })
  const [reloadToken, setReloadToken] = useState(0)
  const [draft, setDraft] = useState(searchTerm)
  const searchInput = useRef<HTMLInputElement>(null)

  // Callbacks are read through a ref so the data effect depends only on the query itself and a
  // parent re-render can never trigger an extra request.
  const callbacks = useRef({
    onListChange,
    onAuthenticationRequired,
    onSearchTermChange,
    list,
    searchTerm,
  })
  callbacks.current = {
    onListChange,
    onAuthenticationRequired,
    onSearchTermChange,
    list,
    searchTerm,
  }

  useEffect(() => {
    // Each run owns one request; the cleanup aborts it, so an older response can never replace a
    // newer one, and a response that does arrive late is ignored by its aborted signal.
    const controller = new AbortController()
    setState((current) =>
      current.kind === 'loaded' ? { ...current, refreshing: true } : { kind: 'loading' },
    )
    const query: ListQueryState = {
      page: list.page,
      size: list.size,
      sort: list.sort,
      direction: list.direction,
    }
    listCustomers(query, searchTerm, controller.signal)
      .then((response) => {
        if (controller.signal.aborted) return
        // A page beyond the data (including a result that became empty) is never rendered: the
        // route is corrected by replacing history, to the last valid page or to page 0 when
        // nothing matches. The previous state stays until the corrected page arrives.
        if (query.page > 0 && response.items.length === 0) {
          const lastValidPage = Math.max(0, Math.ceil(response.total / query.size) - 1)
          if (lastValidPage !== query.page) {
            callbacks.current.onListChange({ ...query, page: lastValidPage }, 'replace')
            return
          }
        }
        setState({ kind: 'loaded', page: response, term: searchTerm, refreshing: false })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        if (error instanceof ApiError && error.status === 401) {
          callbacks.current.onAuthenticationRequired(error.detail)
          return
        }
        if (error instanceof ApiError && error.status === 403) {
          setState({ kind: 'forbidden', detail: customerErrorMessage(error, GENERIC_ERROR) })
          return
        }
        setState({ kind: 'error' })
      })
    return () => controller.abort()
  }, [list.page, list.size, list.sort, list.direction, searchTerm, reloadToken])

  // The effective search follows the typed text after a short pause. An invalid (too long) value
  // is reported at once and never applied, so the last valid result stays on screen.
  const normalizedDraft = normalizeSearchTerm(draft)
  const searchError = searchTermError(normalizedDraft)

  const applySearch = (term: string) => {
    const { list: current, searchTerm: applied, onSearchTermChange: change, onListChange: listChange } =
      callbacks.current
    if (term === applied) return
    change(term)
    if (current.page !== 0) listChange({ ...current, page: 0 })
  }

  useEffect(() => {
    if (searchError) return
    const timer = setTimeout(() => applySearch(normalizedDraft), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
    // applySearch only reads the latest props through the ref.
  }, [normalizedDraft, searchError])

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    // Enter applies the typed value at once instead of waiting for the pause.
    event.preventDefault()
    if (!searchError) applySearch(normalizedDraft)
  }

  const clearOnEscape = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== 'Escape' || draft === '') return
    event.preventDefault()
    setDraft('')
    applySearch('')
  }

  const sortColumn = (field: CustomerSortField) => {
    if (list.sort === field) {
      onListChange({ ...list, page: 0, direction: list.direction === 'asc' ? 'desc' : 'asc' })
      return
    }
    onListChange({ ...list, page: 0, sort: field, direction: 'asc' })
  }

  const changePageSize = (size: ListPageSize) => {
    onListChange({ ...list, page: 0, size })
  }

  const changePage = (page: number) => {
    onListChange({ ...list, page })
  }

  const changeResponsiveSort = (next: { sort: string; direction: ListSortDirection }) => {
    onListChange({ ...list, page: 0, sort: next.sort, direction: next.direction })
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

  return (
    <div className="platform-content">
      <div className="business-list-content customer-list-content">
        {!readOnly && (
          <div className="business-page-actions">
            <Button type="button" onClick={onCreate}>
              Добави клиент
            </Button>
          </div>
        )}
        <form
          role="search"
          aria-label="Търсене на клиенти"
          className="customer-search"
          noValidate
          onSubmit={submitSearch}
        >
          <div className="form-field customer-search-field">
            <label htmlFor="customer-search">Търсене</label>
            <input
              {...fieldControlProps('customer-search', searchError)}
              ref={searchInput}
              name="search"
              type="text"
              autoComplete="off"
              autoCapitalize="off"
              spellCheck={false}
              enterKeyHint="search"
              placeholder="Име, телефон или имейл"
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={clearOnEscape}
            />
            <FieldError id="customer-search" error={searchError} />
          </div>
        </form>
        {state.kind === 'loading' && (
          <p className="business-list-state" aria-live="polite" aria-busy="true">
            Зареждане на клиентите…
          </p>
        )}
        {state.kind === 'error' && (
          <div className="feedback-action-layout">
            <div className="status-message status-error" role="alert">
              <p>{GENERIC_ERROR}</p>
            </div>
            <div className="feedback-action-controls">
              <Button
                type="button"
                variant="secondary"
                onClick={() => setReloadToken((token) => token + 1)}
              >
                Опитай отново
              </Button>
            </div>
          </div>
        )}
        {state.kind === 'loaded' && (
          <div
            className={
              state.refreshing ? 'customer-results is-refreshing' : 'customer-results'
            }
            aria-busy={state.refreshing}
          >
            {state.refreshing && (
              <p className="visually-hidden" role="status">
                Зареждане на клиентите…
              </p>
            )}
            {state.page.items.length === 0 ? (
              <p className="business-list-state" aria-live="polite">
                {state.term !== ''
                  ? 'Не са намерени клиенти по това търсене.'
                  : 'Все още няма добавени клиенти.'}
              </p>
            ) : (
              <>
                <div className="list-toolbar customer-list-toolbar">
                  <ResponsiveSortSelect
                    id="customer-responsive-sort"
                    label="Подреди по"
                    options={SORT_OPTIONS}
                    sort={list.sort}
                    direction={list.direction}
                    onChange={changeResponsiveSort}
                  />
                </div>
                <div className="business-table-container customer-table-container">
                  <table className="business-table customer-table">
                    <caption className="visually-hidden">Списък с клиенти</caption>
                    <thead>
                      <tr>
                        {CUSTOMERS_SORT_FIELDS.map((field) => (
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
                      {state.page.items.map((customer) => (
                        <tr key={customer.id}>
                          <td data-label="Име">
                            <div className="business-name-cell">
                              <a
                                aria-label={`Отвори ${customer.displayName}`}
                                href={routeHref({
                                  kind: 'business-customer-detail',
                                  customerId: customer.id,
                                  returnList: list,
                                })}
                                onClick={(event) => {
                                  event.preventDefault()
                                  onOpen(customer.id)
                                }}
                              >
                                {customer.displayName}
                              </a>
                            </div>
                          </td>
                          <td data-label="Телефон">
                            <ContactValue
                              value={customer.phone === null ? null : formatCustomerPhone(customer.phone)}
                            />
                          </td>
                          <td data-label="Имейл">
                            <ContactValue value={customer.email} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <ListPagination
                  label="Странициране на клиентите"
                  idPrefix="customer"
                  page={state.page.page}
                  size={state.page.size as ListPageSize}
                  totalElements={state.page.total}
                  onPageChange={changePage}
                  onSizeChange={changePageSize}
                />
              </>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

function ContactValue({ value }: { value: string | null }) {
  if (value === null || value === '') {
    return (
      <>
        <span aria-hidden="true">—</span>
        <span className="visually-hidden">не е посочен</span>
      </>
    )
  }
  return <>{value}</>
}
