import type { ReactNode, RefObject } from 'react'
import { Button } from '../../ui/Button'
import { formatDateOnlyLong } from '../../public/booking/dates'
import { formatCents, priceToCents } from '../../public/booking/money'
import type { AppointmentDetails } from '../calendar/api'
import { businessDateOf, formatTimeRange, formatTotalDuration, statusLabel } from '../calendar/presentation'
import { LoadError } from './steps'
import type { RescheduleOptions } from './rescheduleApi'
import { dataFor, type KeyedLoad } from './useKeyedLoad'

// The presentational pieces of the reschedule flow. The state, the loads, the validation, and the single
// POST belong to Reschedule.

type Focusable = RefObject<HTMLElement | null>

/** «price — duration»: the money first, exact to the cent, then the stored duration. */
function totalLine(appointment: AppointmentDetails): string {
  return `${formatCents(priceToCents(appointment.price))} — ${formatTotalDuration(appointment.durationMinutes)}`
}

// ---- the read-only context ----------------------------------------------------------------------------------

/** What the Appointment is today. The Customer and the Services cannot be changed from here. */
export function AppointmentContext({ appointment }: { appointment: AppointmentDetails }) {
  const timezone = appointment.timezone
  return (
    <section className="reschedule-context" aria-labelledby="reschedule-context-heading">
      <h3 id="reschedule-context-heading">Текущ час</h3>
      <dl className="manual-review">
        <div className="manual-review-row">
          <dt>Клиент</dt>
          <dd>{appointment.customer.displayName}</dd>
        </div>
        <div className="manual-review-row">
          <dt>Услуги</dt>
          <dd>
            <ol className="manual-review-services">
              {[...appointment.services]
                .sort((first, second) => first.position - second.position)
                .map((service) => (
                  <li key={service.position}>
                    <span className="manual-choice-title">{service.name}</span>{' '}
                    {formatCents(priceToCents(service.price))} — {formatTotalDuration(service.durationMinutes)}
                  </li>
                ))}
            </ol>
            <p className="manual-selection-total">Общо: {totalLine(appointment)}</p>
          </dd>
        </div>
        <div className="manual-review-row">
          <dt>Член на екипа</dt>
          <dd>{appointment.staffDisplayName}</dd>
        </div>
        <div className="manual-review-row">
          <dt>Дата и час</dt>
          <dd>
            {formatDateOnlyLong(businessDateOf(appointment.start, timezone))},{' '}
            {formatTimeRange(appointment.start, appointment.end, timezone)}
          </dd>
        </div>
        <div className="manual-review-row">
          <dt>Състояние</dt>
          <dd>{statusLabel(appointment.status)}</dd>
        </div>
      </dl>
      <p className="manual-hint">
        Клиентът и услугите на часа не могат да се променят. При грешка отменете часа и създайте нов.
      </p>
    </section>
  )
}

// ---- 1. StaffMember --------------------------------------------------------------------------------------------

type StaffStepProps = {
  options: KeyedLoad<RescheduleOptions>
  optionsKey: string | null
  staffMemberId: string | null
  error: string | undefined
  focusRef: Focusable
  onChoose: (staffMemberId: string) => void
  onRetry: () => void
}

/**
 * The current StaffMember apart from the eligible replacements, both from the server only. Keeping the
 * current one is offered only while it is available; nobody is ever assigned automatically.
 */
export function StaffStep({
  options,
  optionsKey,
  staffMemberId,
  error,
  focusRef,
  onChoose,
  onRetry,
}: StaffStepProps) {
  if (options.kind === 'error') {
    return <LoadError error={options.error} fallback="Екипът не може да бъде зареден." onRetry={onRetry} />
  }
  const loaded = dataFor(options, optionsKey)
  if (!loaded) {
    return (
      <p className="business-list-state" aria-live="polite" aria-busy="true">
        Зареждане на екипа…
      </p>
    )
  }
  const current = loaded.currentStaffMember
  const replacements = loaded.staffMembers.filter((member) => member.id !== current.id)
  const first = current.available ? 'current' : 'replacement'
  const radio = (id: string, name: string, facts: string | null, index: number, kind: 'current' | 'replacement') => (
    <label key={id} className="manual-choice">
      <input
        ref={kind === first && index === 0 ? (focusRef as RefObject<HTMLInputElement>) : undefined}
        type="radio"
        name="reschedule-staff"
        checked={staffMemberId === id}
        onChange={() => onChoose(id)}
      />
      <span className="manual-choice-body">
        <span className="manual-choice-title">{name}</span>
        {facts && <span className="manual-choice-facts">{facts}</span>}
      </span>
    </label>
  )
  const nobody = !current.available && replacements.length === 0
  return (
    <div className="manual-step-body">
      {!current.available && !nobody && (
        <p className="status-message status-info" role="status">
          Текущият член на екипа ({current.displayName}) вече не е наличен за този час. Изберете друг член
          на екипа.
        </p>
      )}
      {nobody ? (
        <p className="status-message status-error" role="status">
          Няма наличен член на екипа за този час. Текущият член на екипа ({current.displayName}) вече не е
          наличен и няма друг, който извършва всички услуги на часа.
        </p>
      ) : (
        <fieldset className="manual-choices" aria-describedby={error ? 'reschedule-staff-error' : undefined}>
          <legend className="visually-hidden">Избор на служител</legend>
          {current.available && radio(current.id, current.displayName, 'Текущ член на екипа', 0, 'current')}
          {replacements.length > 0 && (
            <p className="manual-subheading">{current.available ? 'Друг член на екипа' : 'Налични членове на екипа'}</p>
          )}
          {replacements.map((member, index) => radio(member.id, member.displayName, null, index, 'replacement'))}
          {current.available && replacements.length === 0 && (
            <p className="manual-hint" role="status">
              Няма друг член на екипа, който извършва всички услуги на този час.
            </p>
          )}
        </fieldset>
      )}
      {error && (
        <p id="reschedule-staff-error" className="field-error" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

// ---- 3. Review -------------------------------------------------------------------------------------------------

export type ReviewData = {
  appointment: AppointmentDetails
  currentStaffName: string
  newStaffName: string
  staffChanged: boolean
  currentWhen: string
  newDate: string
  newTime: string
}

type ReviewStepProps = {
  data: ReviewData
  effective: boolean
  // Absent when the review is read-only (an unresolved outcome): then no edit control is rendered.
  onEdit?: (section: 'staff' | 'datetime') => void
}

/** The unchanged Customer and Services, and what changes: the original beside the proposed. */
export function ReviewStep({ data, effective, onEdit }: ReviewStepProps) {
  const { appointment } = data
  const row = (
    section: 'staff' | 'datetime',
    label: string,
    editLabel: string,
    body: ReactNode,
  ) => (
    <div className="manual-review-row">
      <dt>{label}</dt>
      <dd>{body}</dd>
      {onEdit && (
        <div className="manual-review-edit">
          <Button type="button" variant="secondary" aria-label={editLabel} onClick={() => onEdit(section)}>
            Промени
          </Button>
        </div>
      )}
    </div>
  )
  return (
    <div className="manual-step-body">
      <dl className="manual-review">
        <div className="manual-review-row">
          <dt>Клиент (без промяна)</dt>
          <dd>{appointment.customer.displayName}</dd>
        </div>
        <div className="manual-review-row">
          <dt>Услуги (без промяна)</dt>
          <dd>
            <ol className="manual-review-services">
              {[...appointment.services]
                .sort((first, second) => first.position - second.position)
                .map((service) => (
                  <li key={service.position}>
                    <span className="manual-choice-title">{service.name}</span>
                  </li>
                ))}
            </ol>
            <p className="manual-selection-total">Общо: {totalLine(appointment)}</p>
          </dd>
        </div>
        {row(
          'staff',
          'Член на екипа',
          'Промени члена на екипа',
          <>
            <span className="reschedule-from">Сега: {data.currentStaffName}</span>
            <span className="reschedule-to">
              След промяната: {data.newStaffName}
              {data.staffChanged ? '' : ' (без промяна)'}
            </span>
          </>,
        )}
        {row(
          'datetime',
          'Дата и час',
          'Промени датата и часа',
          <>
            <span className="reschedule-from">Сега: {data.currentWhen}</span>
            <span className="reschedule-to">
              След промяната: {formatDateOnlyLong(data.newDate)}, {data.newTime}
            </span>
          </>,
        )}
      </dl>
      {!effective && onEdit && (
        <p className="status-message status-info" role="status">
          Няма промяна спрямо сегашния час. Изберете друг член на екипа или друг час.
        </p>
      )}
    </div>
  )
}
