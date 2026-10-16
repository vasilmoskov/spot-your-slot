import { useEffect, useRef, useState } from 'react'
import { routeHref, type ListPageSize } from '../../navigation'
import { Button } from '../../ui/Button'
import { ListPagination } from '../../ui/ListSortControls'
import { getCustomerAppointments, type CustomerAppointmentPage } from './api'
import { calendarFailureMessage, isAuthenticationRequired } from './errors'
import { StatusBadge } from './CalendarViews'
import { customerHistoryServiceNames, formatDateAndRange } from './presentation'

type CustomerAppointmentHistoryProps = {
  customerId: string
  now?: () => number
  onAuthenticationRequired: (detail: string) => void
  onOpenAppointment: (appointmentId: string) => void
}

type HistoryState =
  | { kind: 'loading' }
  | { kind: 'loaded'; page: CustomerAppointmentPage; refreshing: boolean }
  | { kind: 'error'; message: string }

const defaultNow = () => Date.now()

/**
 * The Customer's Appointments of the selected Business, newest first, exactly as the server pages
 * them. An entry is "предстоящ", "минал" or "отменен" from its status and its own instant; the
 * server's order and totals are never reshuffled, and `version` is not history.
 */
export function CustomerAppointmentHistory({
  customerId,
  now = defaultNow,
  onAuthenticationRequired,
  onOpenAppointment,
}: CustomerAppointmentHistoryProps) {
  const [page, setPage] = useState(0)
  const [size, setSize] = useState<ListPageSize>(10)
  const [state, setState] = useState<HistoryState>({ kind: 'loading' })
  const [reloadToken, setReloadToken] = useState(0)
  const handlers = useRef({ onAuthenticationRequired })
  handlers.current = { onAuthenticationRequired }

  useEffect(() => {
    const controller = new AbortController()
    setState((current) =>
      current.kind === 'loaded' ? { ...current, refreshing: true } : { kind: 'loading' },
    )
    getCustomerAppointments(customerId, page, size, controller.signal)
      .then((response) => {
        if (controller.signal.aborted) return
        if (page > 0 && response.items.length === 0) {
          const lastValid = Math.max(0, Math.ceil(response.total / size) - 1)
          if (lastValid !== page) {
            setPage(lastValid)
            return
          }
        }
        setState({ kind: 'loaded', page: response, refreshing: false })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        if (isAuthenticationRequired(error)) {
          handlers.current.onAuthenticationRequired(error.detail)
          return
        }
        setState({
          kind: 'error',
          message: calendarFailureMessage(error, 'Историята на часовете не може да бъде заредена.'),
        })
      })
    return () => controller.abort()
  }, [customerId, page, size, reloadToken])

  return (
    <section className="business-section customer-history" aria-labelledby="customer-history-heading">
      <h3 id="customer-history-heading">Часове</h3>
      {state.kind === 'loading' && (
        <p className="business-list-state" aria-live="polite" aria-busy="true">
          Зареждане на часовете…
        </p>
      )}
      {state.kind === 'error' && (
        <div className="feedback-action-layout">
          <div className="status-message status-error" role="alert">
            <p>{state.message}</p>
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
        <div className={state.refreshing ? 'calendar-results is-refreshing' : 'calendar-results'} aria-busy={state.refreshing}>
          {state.page.total === 0 ? (
            <p className="business-list-state" aria-live="polite">
              Клиентът няма часове.
            </p>
          ) : (
            <>
              <ul className="customer-history-list">
                {state.page.items.map((entry) => {
                  const cancelled = entry.status === 'CANCELLED'
                  const startedAt = Date.parse(entry.start)
                  const upcoming = !cancelled && startedAt > now()
                  return (
                    <li
                      key={entry.id}
                      className={cancelled ? 'customer-history-entry is-cancelled' : 'customer-history-entry'}
                    >
                      <p className="calendar-entry-time">
                        <a
                          href={routeHref({
                            kind: 'business-appointment-detail',
                            appointmentId: entry.id,
                            origin: { kind: 'customer', customerId },
                          })}
                          onClick={(event) => {
                            event.preventDefault()
                            onOpenAppointment(entry.id)
                          }}
                        >
                          {formatDateAndRange(entry.start, entry.end, state.page.timezone)}
                        </a>
                        <StatusBadge status={entry.status} />
                        {!cancelled && (
                          <span className="status-badge status-badge-info calendar-timing">
                            {upcoming ? 'Предстоящ' : 'Минал'}
                          </span>
                        )}
                      </p>
                      <p className="calendar-entry-services">{customerHistoryServiceNames(entry)}</p>
                      <p className="calendar-entry-meta">{entry.staffDisplayName}</p>
                    </li>
                  )
                })}
              </ul>
              <ListPagination
                label="Странициране на часовете на клиента"
                idPrefix="customer-history"
                page={state.page.page}
                size={state.page.size as ListPageSize}
                totalElements={state.page.total}
                onPageChange={setPage}
                onSizeChange={(next) => {
                  setPage(0)
                  setSize(next)
                }}
              />
            </>
          )}
        </div>
      )}
    </section>
  )
}
