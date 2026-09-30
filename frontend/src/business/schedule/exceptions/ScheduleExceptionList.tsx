import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../../../identity/api'
import { Button } from '../../../ui/Button'
import { useFieldValidation } from '../../../ui/formValidation'
import { ListPagination, ResponsiveSortSelect, SortableColumnHeader } from '../../../ui/ListSortControls'
import {
    pushRoute,
  routeHref,
  withListDefaults,
  type DateWindow,
  type ExceptionListState,
  type ExceptionSortField,
  type ListNavigationMode,
  type ListPageSize,
  type ListSortDirection,
} from '../../../navigation'
import { loadEveryStaffMember } from '../../staff/fullCatalog'
import { DatePeriodFields } from './DatePeriodFields'
import { listScheduleExceptions, type ScheduleExceptionWindow } from './api'
import {
  KIND_LABELS,
  WINDOW_FIELD_ORDER,
  businessToday as businessTodayOf,
  scheduleChangeStatus,
  scheduleChangeStatusPresentation,
  sortScheduleExceptions,
  defaultWindowFrom,
  formatDateRange,
  hoursSummary,
  localDateIn,
  sameWindow,
  staffNameOf,
  validateWindow,
  type WindowField,
} from './presentation'

type ScheduleExceptionListProps = {
  readOnly: boolean
  // `null` means the URL carries no window yet (the default is to be resolved).
  window: ExceptionListState | null
  onWindowChange: (next: ExceptionListState, mode: ListNavigationMode) => void
  onAuthenticationRequired: (detail: string) => void
  onCreate?: () => void
  onOpen?: (exceptionId: string) => void
  // Injectable for tests; the real clock otherwise.
  now?: () => Date
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; response: ScheduleExceptionWindow; names: ReadonlyMap<string, string> }
  | { kind: 'forbidden'; detail: string }
  | { kind: 'error' }

const GENERIC_ERROR = 'Промените не могат да бъдат заредени.'

const COLUMN_LABELS: Record<ExceptionSortField, string> = {
  kind: 'Вид',
  dates: 'Дати',
  staff: 'Член на екипа',
  status: 'Статус',
}

const SORT_OPTIONS = [
  { field: 'kind', ascLabel: 'Вид (А–Я)', descLabel: 'Вид (Я–А)' },
  { field: 'dates', ascLabel: 'Дати (най-ранни първо)', descLabel: 'Дати (най-късни първо)' },
  { field: 'staff', ascLabel: 'Член на екипа (А–Я)', descLabel: 'Член на екипа (Я–А)' },
  { field: 'status', ascLabel: 'Статус (В сила първо)', descLabel: 'Статус (Минали първо)' },
] as const


function windowKey(window: DateWindow): string {
  return `${window.from}|${window.to}`
}

function browserToday(now: Date): string {
  return localDateIn(now) ?? now.toISOString().slice(0, 10)
}

export function ScheduleExceptionList({
  readOnly,
  window: routeWindow,
  onWindowChange,
  onAuthenticationRequired,
  onCreate,
  onOpen,
  now = () => new Date(),
}: ScheduleExceptionListProps) {
  const [state, setState] = useState<ListState>({ kind: 'loading' })
  const [filter, setFilter] = useState<{ from: string; to: string }>(
    () => routeWindow ?? defaultWindowFrom(browserToday(now())),
  )
  const create =
    onCreate ??
    (() => pushRoute({ kind: 'business-schedule-exception-new', returnWindow: routeWindow }))
  const open =
    onOpen ??
    ((exceptionId: string) =>
      pushRoute({
        kind: 'business-schedule-exception-detail',
        exceptionId,
        returnWindow: routeWindow,
      }))
  const listState = routeWindow ?? withListDefaults(filter)
  const activeSort = listState.sort
  const activeDirection = listState.direction
  const requestSequence = useRef(0)
  const activeRequest = useRef<{ id: number; controller: AbortController } | null>(null)
  const staffNames = useRef<ReadonlyMap<string, string> | null>(null)
  // A response already fetched for the window the route is about to adopt, so
  // canonicalizing a provisional window that turns out equal costs no refetch.
  const resolved = useRef<{ key: string; response: ScheduleExceptionWindow } | null>(null)
  const callbacks = useRef({ onWindowChange, onAuthenticationRequired, now })
  callbacks.current = { onWindowChange, onAuthenticationRequired, now }

  const load = useCallback(async (target: DateWindow | null) => {
    activeRequest.current?.controller.abort()
    const requestState = { id: ++requestSequence.current, controller: new AbortController() }
    activeRequest.current = requestState
    const current = () =>
      activeRequest.current?.id === requestState.id && !requestState.controller.signal.aborted
    setState({ kind: 'loading' })

    // Without a window in the URL the first request is provisional: it uses the
    // browser-local date until the response reveals the Business timezone.
    const provisional = target ?? defaultWindowFrom(browserToday(callbacks.current.now()))
    try {
      const [response, names] = await Promise.all([
        listScheduleExceptions(provisional.from, provisional.to, requestState.controller.signal),
        staffNames.current
          ? Promise.resolve(staffNames.current)
          : loadEveryStaffMember(requestState.controller.signal).then(
              (everyone) =>
                new Map(everyone.map((staffMember) => [staffMember.id, staffMember.displayName])),
            ),
      ])
      if (!current()) return
      staffNames.current = names

      if (target === null) {
        const businessToday = localDateIn(callbacks.current.now(), response.timezone)
        const canonical = businessToday ? defaultWindowFrom(businessToday) : provisional
        if (sameWindow(canonical, provisional)) {
          resolved.current = { key: windowKey(canonical), response }
        }
        // Automatic canonicalization replaces history; a different canonical
        // window is refetched exactly once by the effect that follows.
        callbacks.current.onWindowChange(withListDefaults(canonical), 'replace')
        return
      }
      setState({ kind: 'loaded', response, names })
    } catch (error) {
      if (!current()) return
      if (error instanceof ApiError && error.status === 401) {
        callbacks.current.onAuthenticationRequired(error.detail)
        return
      }
      if (error instanceof ApiError && error.status === 403) {
        setState({ kind: 'forbidden', detail: error.detail })
        return
      }
      setState({ kind: 'error' })
    } finally {
      if (activeRequest.current?.id === requestState.id) activeRequest.current = null
    }
  }, [])

  const routeFrom = routeWindow?.from
  const routeTo = routeWindow?.to
  useEffect(() => {
    const target = routeFrom && routeTo ? { from: routeFrom, to: routeTo } : null
    if (target) {
      setFilter(target)
      const ready = resolved.current
      if (ready && ready.key === windowKey(target) && staffNames.current) {
        resolved.current = null
        setState({ kind: 'loaded', response: ready.response, names: staffNames.current })
        return
      }
    }
    void load(target)
    return () => {
      activeRequest.current?.controller.abort()
    }
  }, [load, routeFrom, routeTo])

  const { errors, controlRef, touch, edited, validateAll } = useFieldValidation<
    WindowField,
    { from: string; to: string }
  >({
    order: WINDOW_FIELD_ORDER,
    values: filter,
    validate: validateWindow,
    isEmpty: (field, values) => values[field] === '',
  })

  const applyWindow = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!validateAll()) return
    if (!routeWindow) return
    const next: DateWindow = { from: filter.from, to: filter.to }
    if (sameWindow(routeWindow, next)) return
    // An explicit filter change is ordinary navigation: it is pushed, returns
    // to the first page and keeps the size and ordering.
    onWindowChange({ ...routeWindow, ...next, page: 0 }, 'push')
  }

  // Sorting and pagination are client-side: the endpoint returns the complete
  // selected window (at most 93 dates, no pagination), so the whole result is
  // sorted first and only then cut into pages.
  const changeSort = (sort: ExceptionSortField, direction: ListSortDirection) => {
    if (!routeWindow) return
    onWindowChange({ ...routeWindow, sort, direction, page: 0 }, 'push')
  }
  const changePage = (page: number) => {
    if (routeWindow) onWindowChange({ ...routeWindow, page }, 'push')
  }
  const changeSize = (size: ListPageSize) => {
    if (routeWindow) onWindowChange({ ...routeWindow, size, page: 0 }, 'push')
  }
  const sortColumn = (field: ExceptionSortField) =>
    changeSort(field, activeSort === field && activeDirection === 'asc' ? 'desc' : 'asc')

  const today =
    state.kind === 'loaded' ? businessTodayOf(now(), state.response.timezone) : ''
  const sortedItems = useMemo(() => {
    if (state.kind !== 'loaded') return []
    return sortScheduleExceptions(
      state.response.exceptions,
      state.names,
      today,
      listState.sort,
      listState.direction,
    )
  }, [state, today, listState.sort, listState.direction])

  const total = sortedItems.length
  const lastPage = Math.max(0, Math.ceil(total / listState.size) - 1)
  // An out-of-range page is never rendered: the clamped page is shown at once
  // and the route is corrected by replacing (not pushing) the history entry.
  const page = Math.min(listState.page, lastPage)
  const pageItems = sortedItems.slice(page * listState.size, (page + 1) * listState.size)
  const outOfRange = state.kind === 'loaded' && routeWindow !== null && listState.page !== page
  useEffect(() => {
    if (outOfRange && routeWindow) {
      callbacks.current.onWindowChange({ ...routeWindow, page }, 'replace')
    }
  }, [outOfRange, routeWindow, page])

  return (
    <div className="platform-content">
      <div className="business-list-content">
        {!readOnly && (
          <div className="business-page-actions">
            <Button type="button" onClick={create}>
              Добави промяна
            </Button>
          </div>
        )}

        <form className="exception-window-form" onSubmit={applyWindow} noValidate>
          <DatePeriodFields
            first={{
              id: 'exception-window-from',
              label: 'От',
              value: filter.from,
              error: errors.from,
              controlRef: controlRef('from'),
              onBlur: () => touch('from'),
              onChange: (value) => {
                edited('from')
                setFilter((current) => ({ ...current, from: value }))
              },
            }}
            last={{
              id: 'exception-window-to',
              label: 'До',
              value: filter.to,
              error: errors.to,
              controlRef: controlRef('to'),
              onBlur: () => touch('to'),
              onChange: (value) => {
                edited('to')
                setFilter((current) => ({ ...current, to: value }))
              },
            }}
          >
            <Button type="submit" variant="secondary">
              Покажи
            </Button>
          </DatePeriodFields>
        </form>

        {state.kind === 'loading' && (
          <div aria-live="polite" aria-busy="true">
            <p className="business-list-state">Зареждане на промените…</p>
          </div>
        )}

        {state.kind === 'forbidden' && (
          <p className="status-message status-error" role="alert">
            {state.detail}
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
                onClick={() => void load(routeWindow)}
              >
                Опитай отново
              </Button>
            </div>
          </div>
        )}

        {state.kind === 'loaded' &&
          (state.response.exceptions.length === 0 ? (
            <p className="business-list-state" aria-live="polite">
              Няма промени за избрания период.
            </p>
          ) : (
            <>
            <div className="list-toolbar exception-list-toolbar">
              <ResponsiveSortSelect
                id="exception-responsive-sort"
                label="Подреди по"
                options={SORT_OPTIONS}
                sort={activeSort}
                direction={activeDirection}
                onChange={(next) => changeSort(next.sort as ExceptionSortField, next.direction)}
              />
            </div>
            <div className="business-table-container exception-table-container">
              <table className="business-table exception-table">
                <caption className="visually-hidden">Промени в графика</caption>
                <thead>
                  <tr>
                    {(['kind', 'dates', 'staff'] as const).map((field) => (
                      <SortableColumnHeader
                        key={field}
                        label={COLUMN_LABELS[field]}
                        active={activeSort === field}
                        direction={activeDirection}
                        onSort={() => sortColumn(field)}
                      />
                    ))}
                    <th scope="col">Часове</th>
                    <SortableColumnHeader
                      label={COLUMN_LABELS.status}
                      active={activeSort === 'status'}
                      direction={activeDirection}
                      onSort={() => sortColumn('status')}
                    />
                  </tr>
                </thead>
                <tbody>
                  {pageItems.map((item) => {
                    const dates = formatDateRange(item.firstDate, item.lastDate)
                    const status = scheduleChangeStatusPresentation(
                      scheduleChangeStatus(item, today),
                    )
                    return (
                      <tr key={item.id}>
                        <td data-label="Вид">
                          <div className="business-name-cell">
                            <a
                              aria-label={`Отвори: ${KIND_LABELS[item.kind]}, ${dates}`}
                              href={routeHref({
                                kind: 'business-schedule-exception-detail',
                                exceptionId: item.id,
                                returnWindow: routeWindow,
                              })}
                              onClick={(event) => {
                                event.preventDefault()
                                open(item.id)
                              }}
                            >
                              {KIND_LABELS[item.kind]}
                            </a>
                          </div>
                        </td>
                        <td data-label="Дати">{dates}</td>
                        <td data-label="Член на екипа">{staffNameOf(item, state.names)}</td>
                        <td data-label="Часове">{hoursSummary(item)}</td>
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
            <ListPagination
              label="Странициране на промените в графика"
              idPrefix="exception"
              page={page}
              size={listState.size}
              totalElements={total}
              onPageChange={changePage}
              onSizeChange={changeSize}
            />
            </>
          ))}
      </div>
    </div>
  )
}
