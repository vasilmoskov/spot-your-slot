import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { routeHref, type AppointmentOrigin } from '../../navigation'
import { Button } from '../../ui/Button'
import { formatDateOnlyLong, formatTimeDistinct, parseInstant } from '../../public/booking/dates'
import { formatCustomerPhone } from '../customers/presentation'
import { CancelDialog, CancelSection, InternalNote, OperationNotice } from '../appointments/OperationViews'
import { useAppointmentOperations, type RefreshResult } from '../appointments/useAppointmentOperations'
import { getAppointment, type AppointmentDetails } from './api'
import {
  APPOINTMENT_NOT_FOUND_MESSAGE,
  calendarFailureMessage,
  isAuthenticationRequired,
  isMissingAppointment,
} from './errors'
import {
  businessDateOf,
  formatDateHeading,
  formatPrice,
  formatTimeRange,
  formatTotalDuration,
  sourceLabel,
  statusLabel,
} from './presentation'

type AppointmentDetailProps = {
  appointmentId: string
  origin: AppointmentOrigin
  // True for the owner of an ACTIVE Business; the server decides again on every request.
  canChange?: boolean
  // Injected clock, only used to tell whether the visit has already started.
  now?: () => Date
  onReschedule?: () => void
  onAuthenticationRequired: (detail: string) => void
  onBack: () => void
  onOpenCustomer: (customerId: string) => void
}

type LoadFailure = { message: string; retryable: boolean }

export function AppointmentDetail({
  appointmentId,
  origin,
  canChange = false,
  now = () => new Date(),
  onReschedule,
  onAuthenticationRequired,
  onBack,
  onOpenCustomer,
}: AppointmentDetailProps) {
  const [appointment, setAppointment] = useState<AppointmentDetails | null>(null)
  const [loading, setLoading] = useState(true)
  const [failure, setFailure] = useState<LoadFailure | null>(null)
  const activeLoad = useRef<AbortController | null>(null)
  const errorBox = useRef<HTMLDivElement>(null)
  const handlers = useRef({ onAuthenticationRequired })
  handlers.current = { onAuthenticationRequired }

  const activeRefresh = useRef<AbortController | null>(null)
  const cancelOpenRef = useRef<HTMLButtonElement>(null)
  const cancelNoticeRef = useRef<HTMLDivElement>(null)
  const cancelledRef = useRef<HTMLParagraphElement>(null)
  const noteInputRef = useRef<HTMLTextAreaElement>(null)
  const noteEditRef = useRef<HTMLButtonElement>(null)
  const noteNoticeRef = useRef<HTMLDivElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  // An explicit re-read replaces the page: focus returns to its heading once the data is shown again.
  const focusHeading = useRef(false)

  // A silent re-read: the page stays mounted, so an open edit or a frozen operation is not lost.
  const refresh = useCallback(async (): Promise<RefreshResult> => {
    activeRefresh.current?.abort()
    const controller = new AbortController()
    activeRefresh.current = controller
    try {
      const loaded = await getAppointment(appointmentId, controller.signal)
      if (controller.signal.aborted || activeRefresh.current !== controller) return 'stale'
      setAppointment(loaded)
      return 'ok'
    } catch (caught) {
      if (controller.signal.aborted || activeRefresh.current !== controller) return 'stale'
      if (isAuthenticationRequired(caught)) {
        handlers.current.onAuthenticationRequired(caught.detail)
        return 'stale'
      }
      return isMissingAppointment(caught) ? 'missing' : 'failed'
    } finally {
      if (activeRefresh.current === controller) activeRefresh.current = null
    }
  }, [appointmentId])

  const operations = useAppointmentOperations({
    appointment,
    refresh,
    onAuthenticationRequired: (detail) => handlers.current.onAuthenticationRequired(detail),
    onMissing: () => {
      setAppointment(null)
      setFailure({ message: APPOINTMENT_NOT_FOUND_MESSAGE, retryable: false })
    },
  })
  const resetOperations = operations.reset

  const load = useCallback(async () => {
    // A full re-read forgets every result and invalidates every request still in flight.
    resetOperations()
    activeRefresh.current?.abort()
    activeLoad.current?.abort()
    const controller = new AbortController()
    activeLoad.current = controller
    setLoading(true)
    setFailure(null)
    try {
      const loaded = await getAppointment(appointmentId, controller.signal)
      if (controller.signal.aborted || activeLoad.current !== controller) return
      setAppointment(loaded)
    } catch (caught) {
      if (controller.signal.aborted || activeLoad.current !== controller) return
      if (isAuthenticationRequired(caught)) {
        handlers.current.onAuthenticationRequired(caught.detail)
        return
      }
      setAppointment(null)
      setFailure(
        isMissingAppointment(caught)
          ? { message: APPOINTMENT_NOT_FOUND_MESSAGE, retryable: false }
          : {
              message: calendarFailureMessage(caught, 'Часът не може да бъде зареден.'),
              retryable: true,
            },
      )
    } finally {
      if (activeLoad.current === controller) {
        activeLoad.current = null
        setLoading(false)
      }
    }
  }, [appointmentId, resetOperations])

  useEffect(() => {
    void load()
    return () => {
      const controller = activeLoad.current
      activeLoad.current = null
      controller?.abort()
      activeRefresh.current?.abort()
    }
  }, [load])

  useEffect(() => {
    if (failure) errorBox.current?.focus()
  }, [failure])

  const focusRequest = operations.focus
  useLayoutEffect(() => {
    if (focusRequest === null) return
    const targets = {
      'cancel-open': cancelOpenRef,
      'cancel-notice': cancelNoticeRef,
      cancelled: cancelledRef,
      'note-input': noteInputRef,
      'note-edit': noteEditRef,
      'note-notice': noteNoticeRef,
    }
    targets[focusRequest.target].current?.focus()
    // Focus is requested by the tick only.
  }, [focusRequest?.tick])

  const shown = !loading && appointment !== null
  useLayoutEffect(() => {
    if (shown && focusHeading.current) {
      focusHeading.current = false
      headingRef.current?.focus()
    }
  }, [shown])

  const backLabel = origin.kind === 'customer' ? 'Обратно към клиента' : 'Обратно към графика'

  if (loading) {
    return (
      <div className="platform-content" aria-live="polite" aria-busy="true">
        <p className="business-list-state">Зареждане на часа…</p>
      </div>
    )
  }

  if (failure || !appointment) {
    return (
      <div className="platform-content">
        <div className="feedback-action-layout">
          <div ref={errorBox} className="status-message status-error" role="alert" tabIndex={-1}>
            <p>{failure?.message ?? APPOINTMENT_NOT_FOUND_MESSAGE}</p>
          </div>
          <div className="action-group">
            {failure?.retryable !== false && (
              <Button type="button" variant="secondary" onClick={() => void load()}>
                Зареди отново
              </Button>
            )}
            <Button type="button" variant="secondary" onClick={onBack}>
              {backLabel}
            </Button>
          </div>
        </div>
      </div>
    )
  }

  // The operation is known to have been applied (or the data is known to have changed) but the fresh facts
  // could not be read: no Appointment fact is shown, because anything on the page would be stale.
  if (operations.reloadNeeded && operations.notice) {
    const operation = operations.notice.operation
    return (
      <div className="platform-content">
        <div className="business-page-actions">
          <Button type="button" variant="secondary" onClick={onBack}>
            {backLabel}
          </Button>
        </div>
        <OperationNotice
          operation={operation}
          notice={operations.notice}
          innerRef={operation === 'cancel' ? cancelNoticeRef : noteNoticeRef}
          busy={false}
          onReread={() => {
            focusHeading.current = true
            void load()
          }}
        />
      </div>
    )
  }

  const timezone = appointment.timezone
  const cancelled = appointment.status === 'CANCELLED'
  const date = businessDateOf(appointment.start, timezone)
  // Only an eligible, future, confirmed Appointment of an ACTIVE Business can be changed; an action
  // that cannot work is absent, not disabled.
  const startMoment = parseInstant(appointment.start)
  const changeable =
    canChange &&
    onReschedule !== undefined &&
    appointment.businessStatus === 'ACTIVE' &&
    appointment.status === 'CONFIRMED' &&
    startMoment !== null &&
    startMoment > now().getTime()
  const notStarted = startMoment !== null && startMoment > now().getTime()
  const ownerActive = canChange && appointment.businessStatus === 'ACTIVE'
  // Only a confirmed, future Appointment of an ACTIVE Business can be cancelled; the note is editable in
  // every status. An action that cannot work is absent, not disabled; the server decides again.
  const cancellable = ownerActive && appointment.status === 'CONFIRMED' && notStarted
  const unresolved = operations.unresolved !== null
  const cancelledAt = appointment.cancellation?.cancelledAt ?? null
  const cancelledMoment =
    cancelledAt !== null && parseInstant(cancelledAt) !== null
      ? `${formatDateOnlyLong(businessDateOf(cancelledAt, timezone))}, ${formatTimeDistinct(cancelledAt, timezone)}`
      : null

  // Leaving to the calendar after an unresolved outcome is the recovery itself: no ordinary prompt.
  const leave = (target: () => void) => {
    if (unresolved) operations.guard.unregisterDirty()
    target()
  }
  const reread = () => {
    focusHeading.current = true
    void load()
  }

  return (
    <>
      <div className="platform-content business-detail appointment-detail" inert={operations.confirming}>
        <div className="business-page-actions">
          <Button type="button" variant="secondary" onClick={() => leave(onBack)}>
            {backLabel}
          </Button>
          {changeable && !operations.locked && (
            <Button type="button" onClick={onReschedule}>
              Промени часа
            </Button>
          )}
        </div>

        <header className="business-detail-header">
          <h2 ref={headingRef} tabIndex={-1}>
            {formatDateHeading(date)}, {formatTimeRange(appointment.start, appointment.end, timezone)}
          </h2>
          <p>
            <span
              className={
                cancelled
                  ? 'status-badge status-badge-neutral calendar-status-cancelled'
                  : 'status-badge status-badge-success calendar-status-confirmed'
              }
            >
              {statusLabel(appointment.status)}
            </span>
          </p>
        </header>

        {cancelled && (
          <p ref={cancelledRef} className="status-message status-info" role="status" tabIndex={-1}>
            Този час е отменен и вече не заема време в графика.
            {cancelledMoment ? ` Отменен на ${cancelledMoment}.` : ''}
          </p>
        )}

        <section className="business-section" aria-labelledby="appointment-facts">
          <h3 id="appointment-facts">Детайли</h3>
          <dl className="business-details-list">
            <div>
              <dt>Член на екипа</dt>
              <dd>{appointment.staffDisplayName}</dd>
            </div>
            <div>
              <dt>Обща продължителност</dt>
              <dd>{formatTotalDuration(appointment.durationMinutes)}</dd>
            </div>
            <div>
              <dt>Обща цена</dt>
              <dd>{formatPrice(appointment.price)}</dd>
            </div>
            <div>
              <dt>Източник</dt>
              <dd>{sourceLabel(appointment.source)}</dd>
            </div>
            <div>
              <dt>Номер</dt>
              <dd>{appointment.reference}</dd>
            </div>
          </dl>
        </section>

        <section className="business-section" aria-labelledby="appointment-services">
          <h3 id="appointment-services">Услуги</h3>
          <ol className="appointment-services">
            {[...appointment.services]
              .sort((a, b) => a.position - b.position)
              .map((service) => (
                <li key={service.position}>
                  <span className="appointment-service-name">{service.name}</span>
                  <span>
                    {formatPrice(service.price)} — {formatTotalDuration(service.durationMinutes)}
                  </span>
                </li>
              ))}
          </ol>
        </section>

        <section className="business-section" aria-labelledby="appointment-customer">
          <h3 id="appointment-customer">Клиент</h3>
          <dl className="business-details-list">
            <div>
              <dt>Име</dt>
              <dd>
                <a
                  href={routeHref({
                    kind: 'business-customer-detail',
                    customerId: appointment.customer.id,
                    returnList: null,
                  })}
                  onClick={(event) => {
                    event.preventDefault()
                    onOpenCustomer(appointment.customer.id)
                  }}
                >
                  {appointment.customer.displayName}
                </a>
              </dd>
            </div>
            {appointment.customer.phone && (
              <div>
                <dt>Телефон</dt>
                <dd>{formatCustomerPhone(appointment.customer.phone)}</dd>
              </div>
            )}
            {appointment.customer.email && (
              <div>
                <dt>Имейл</dt>
                <dd>{appointment.customer.email}</dd>
              </div>
            )}
          </dl>
        </section>

        <section className="business-section appointment-notes" aria-labelledby="appointment-notes">
          <h3 id="appointment-notes">Бележки</h3>
          <dl className="business-details-list">
            <div>
              <dt>Бележка от клиента</dt>
              <dd className="appointment-note">{appointment.customerNote ?? 'Няма бележка.'}</dd>
            </div>
          </dl>
          <InternalNote
            appointment={appointment}
            operations={operations}
            editable={ownerActive}
            inputRef={noteInputRef}
            editRef={noteEditRef}
            noticeRef={noteNoticeRef}
            onReread={reread}
          />
        </section>

        <CancelSection
          operations={operations}
          eligible={cancellable}
          openRef={cancelOpenRef}
          noticeRef={cancelNoticeRef}
          onReread={reread}
        />
      </div>
      {operations.confirming && <CancelDialog appointment={appointment} operations={operations} />}
    </>
  )
}
