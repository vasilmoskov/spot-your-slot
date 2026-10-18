import { useCallback, useEffect, useRef, useState } from 'react'
import { routeHref, type AppointmentOrigin } from '../../navigation'
import { Button } from '../../ui/Button'
import { formatDateOnlyLong, formatTimeDistinct, parseInstant } from '../../public/booking/dates'
import { formatCustomerPhone } from '../customers/presentation'
import { getAppointment, type AppointmentDetails } from './api'
import {
  APPOINTMENT_NOT_FOUND_MESSAGE,
  calendarFailureMessage,
  isAuthenticationRequired,
  isMissingAppointment,
} from './errors'
import {
  businessDateOf,
  formatPrice,
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

  const load = useCallback(async () => {
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
  }, [appointmentId])

  useEffect(() => {
    void load()
    return () => {
      const controller = activeLoad.current
      activeLoad.current = null
      controller?.abort()
    }
  }, [load])

  useEffect(() => {
    if (failure) errorBox.current?.focus()
  }, [failure])

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
  const cancelledAt = appointment.cancellation?.cancelledAt ?? null
  const cancelledMoment =
    cancelledAt !== null && parseInstant(cancelledAt) !== null
      ? `${formatDateOnlyLong(businessDateOf(cancelledAt, timezone))}, ${formatTimeDistinct(cancelledAt, timezone)}`
      : null

  return (
    <div className="platform-content business-detail appointment-detail">
      <div className="business-page-actions">
        <Button type="button" variant="secondary" onClick={onBack}>
          {backLabel}
        </Button>
        {changeable && (
          <Button type="button" onClick={onReschedule}>
            Промени часа
          </Button>
        )}
      </div>

      <header className="business-detail-header">
        <h2>
          {formatDateOnlyLong(date)}, {formatTimeDistinct(appointment.start, timezone)}–
          {formatTimeDistinct(appointment.end, timezone)}
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
        <p className="status-message status-info" role="status">
          Този час е отменен и вече не заема време в графика.
          {cancelledMoment ? ` Отменен на ${cancelledMoment}.` : ''}
        </p>
      )}

      <section className="business-section" aria-labelledby="appointment-facts">
        <h3 id="appointment-facts">Час</h3>
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
          <div>
            <dt>Часова зона</dt>
            <dd>{timezone}</dd>
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
                  {formatTotalDuration(service.durationMinutes)} · {formatPrice(service.price)}
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

      <section className="business-section" aria-labelledby="appointment-notes">
        <h3 id="appointment-notes">Бележки</h3>
        <dl className="business-details-list">
          <div>
            <dt>Бележка от клиента</dt>
            <dd className="appointment-note">{appointment.customerNote ?? 'Няма бележка.'}</dd>
          </div>
          <div>
            <dt>Вътрешна бележка</dt>
            <dd className="appointment-note">{appointment.internalNote ?? 'Няма бележка.'}</dd>
          </div>
        </dl>
      </section>
    </div>
  )
}
