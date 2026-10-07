import type { ReactNode } from 'react'
import { formatServiceDuration, formatServicePrice } from '../../business/services/presentation'
import { Button } from '../../ui/Button'
import type { PublicService } from '../api'
import {
  BookingReadError,
  type Availability,
  type BookingOptions,
  type ReadUnavailable,
  type Slot,
} from './api'
import { BookingCalendar } from './BookingCalendar'
import { formatDateOnlyLong, formatTimeDistinct, isRepeatedLocalTime } from './dates'
import { useWaiting, type ReadView } from './hooks'
import {
  NO_PREFERENCE_LABEL,
  RATE_LIMITED_MESSAGE,
  READ_NETWORK_MESSAGE,
  SERVICE_UNAVAILABLE_MESSAGE,
  STAFF_UNAVAILABLE_MESSAGE,
  UNEXPECTED_MESSAGE,
} from './messages'

export function StepActions({ children }: { children: ReactNode }) {
  return <div className="action-group booking-actions">{children}</div>
}

function Notice({ kind, children }: { kind: 'error' | 'info'; children: ReactNode }) {
  return (
    <div
      className={`status-message status-${kind}`}
      role={kind === 'error' ? 'alert' : 'status'}
    >
      {children}
    </div>
  )
}

/** A read that did not produce data: the network, the limiter or an unexpected answer. */
export function ReadProblem({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const failure = error instanceof BookingReadError ? error : null
  const delayMs = failure?.retryAfterSeconds ? failure.retryAfterSeconds * 1000 : 0
  // The wait starts when this notice first appears, and nothing is retried by itself.
  const waiting = useWaiting(delayMs, error)
  const message =
    failure?.reason === 'network'
      ? READ_NETWORK_MESSAGE
      : failure?.reason === 'rate-limited'
        ? RATE_LIMITED_MESSAGE
        : UNEXPECTED_MESSAGE
  return (
    <div className="booking-problem">
      <Notice kind="error">
        <p>{message}</p>
      </Notice>
      <Button type="button" variant="secondary" disabled={waiting} onClick={onRetry}>
        Опитайте отново
      </Button>
    </div>
  )
}

export function Loading({ children }: { children: string }) {
  return (
    <p role="status" className="public-status">
      {children}
    </p>
  )
}

export function UnavailableProblem({
  kind,
  onChooseService,
  onChooseStaff,
}: {
  kind: ReadUnavailable['kind']
  onChooseService: () => void
  onChooseStaff: () => void
}) {
  if (kind === 'staff') {
    return (
      <div className="booking-problem">
        <Notice kind="error">
          <p>{STAFF_UNAVAILABLE_MESSAGE}</p>
        </Notice>
        <Button type="button" variant="secondary" onClick={onChooseStaff}>
          Избор на служител
        </Button>
      </div>
    )
  }
  return (
    <div className="booking-problem">
      <Notice kind="error">
        <p>{SERVICE_UNAVAILABLE_MESSAGE}</p>
      </Notice>
      <Button type="button" variant="secondary" onClick={onChooseService}>
        Избор на услуга
      </Button>
    </div>
  )
}

/** The chosen Service on every later step, as advertised by the profile. */
export function ServiceContext({ service }: { service: PublicService | undefined }) {
  if (!service) return null
  return (
    <p className="booking-context">
      <span className="booking-context-name">{service.name}</span>
      <span>
        {formatServiceDuration(service.durationMinutes)} · {formatServicePrice(service.price)}
      </span>
    </p>
  )
}

// ---- step 1: the Service ----

export function ServiceStep({
  services,
  serviceId,
  frozen,
  nextLabel,
  onSelect,
  onNext,
}: {
  services: PublicService[]
  serviceId: string | null
  frozen: boolean
  nextLabel: string
  onSelect: (id: string) => void
  onNext: () => void
}) {
  return (
    <form
      className="booking-step-form"
      onSubmit={(event) => {
        event.preventDefault()
        if (serviceId !== null) onNext()
      }}
      noValidate
    >
      <fieldset className="booking-choices" disabled={frozen}>
        <legend className="visually-hidden">Услуга</legend>
        {services.map((service) => (
          <label key={service.id} className="booking-choice">
            <input
              type="radio"
              name="booking-service"
              value={service.id}
              checked={service.id === serviceId}
              onChange={() => onSelect(service.id)}
            />
            <span className="booking-choice-body">
              <span className="booking-choice-title">{service.name}</span>
              {service.description && (
                <span className="booking-choice-description">{service.description}</span>
              )}
              <span className="booking-choice-facts">
                {formatServiceDuration(service.durationMinutes)} ·{' '}
                {formatServicePrice(service.price)}
              </span>
            </span>
          </label>
        ))}
      </fieldset>
      <StepActions>
        <Button type="submit" disabled={serviceId === null || frozen}>
          {nextLabel}
        </Button>
      </StepActions>
    </form>
  )
}

// ---- step 2: the StaffMember preference ----

export function StaffStep({
  view,
  staffId,
  frozen,
  nextLabel,
  onSelect,
  onReload,
  onChooseService,
  onNext,
  onBack,
}: {
  view: ReadView<{ kind: 'options'; options: BookingOptions } | ReadUnavailable>
  staffId: string | null
  frozen: boolean
  nextLabel: string
  onSelect: (id: string | null) => void
  onReload: () => void
  onChooseService: () => void
  onNext: () => void
  onBack: () => void
}) {
  const back = (
    <Button type="button" variant="secondary" onClick={onBack} disabled={frozen}>
      Назад
    </Button>
  )
  if (view.status === 'idle' || view.status === 'loading') {
    return (
      <>
        <Loading>Зареждане на служителите…</Loading>
        <StepActions>{back}</StepActions>
      </>
    )
  }
  if (view.status === 'failed') {
    return (
      <>
        <ReadProblem error={view.error} onRetry={onReload} />
        <StepActions>{back}</StepActions>
      </>
    )
  }
  if (view.data.kind !== 'options') {
    return (
      <>
        <UnavailableProblem
          kind={view.data.kind}
          onChooseService={onChooseService}
          onChooseStaff={onReload}
        />
        <StepActions>{back}</StepActions>
      </>
    )
  }
  const { staff } = view.data.options
  if (staff.length === 0) {
    return (
      <>
        <Notice kind="info">
          <p>В момента няма служител, при когото можете да запазите тази услуга онлайн.</p>
        </Notice>
        <StepActions>
          <Button type="button" variant="secondary" onClick={onChooseService}>
            Избор на друга услуга
          </Button>
        </StepActions>
      </>
    )
  }
  return (
    <form
      className="booking-step-form"
      onSubmit={(event) => {
        event.preventDefault()
        onNext()
      }}
      noValidate
    >
      <fieldset className="booking-choices" disabled={frozen}>
        <legend className="visually-hidden">Служител</legend>
        <label className="booking-choice">
          <input
            type="radio"
            name="booking-staff"
            checked={staffId === null}
            onChange={() => onSelect(null)}
          />
          <span className="booking-choice-body">
            <span className="booking-choice-title">{NO_PREFERENCE_LABEL}</span>
            <span className="booking-choice-description">
              Служителят се определя при потвърждаване на резервацията.
            </span>
          </span>
        </label>
        {staff.map((member) => (
          <label key={member.id} className="booking-choice">
            <input
              type="radio"
              name="booking-staff"
              value={member.id}
              checked={staffId === member.id}
              onChange={() => onSelect(member.id)}
            />
            <span className="booking-choice-body">
              <span className="booking-choice-title">{member.displayName}</span>
            </span>
          </label>
        ))}
      </fieldset>
      <StepActions>
        {back}
        <Button type="submit" disabled={frozen}>
          {nextLabel}
        </Button>
      </StepActions>
    </form>
  )
}

// ---- step 3: date and time ----

export function DateTimeStep({
  view,
  availableDates,
  firstDate,
  lastDate,
  timezone,
  today,
  date,
  slot,
  frozen,
  nextLabel,
  onSelectDate,
  onSelectSlot,
  onReload,
  onChooseService,
  onChooseStaff,
  onNext,
  onBack,
}: {
  view: ReadView<{ kind: 'availability'; availability: Availability } | ReadUnavailable>
  // The dates of the last answer for this Service and preference; they stay while a date's slots load.
  availableDates: string[] | null
  // The booking horizon from the booking options.
  firstDate: string
  lastDate: string
  timezone: string
  today: string | null
  date: string | null
  slot: Slot | null
  frozen: boolean
  nextLabel: string
  onSelectDate: (date: string) => void
  onSelectSlot: (slot: Slot) => void
  onReload: () => void
  onChooseService: () => void
  onChooseStaff: () => void
  onNext: () => void
  onBack: () => void
}) {
  const back = (
    <Button type="button" variant="secondary" onClick={onBack} disabled={frozen}>
      Назад
    </Button>
  )
  if (view.status === 'failed') {
    return (
      <>
        <ReadProblem error={view.error} onRetry={onReload} />
        <StepActions>{back}</StepActions>
      </>
    )
  }
  if (view.status === 'ready' && view.data.kind !== 'availability') {
    return (
      <>
        <UnavailableProblem
          kind={view.data.kind}
          onChooseService={onChooseService}
          onChooseStaff={onChooseStaff}
        />
        <StepActions>{back}</StepActions>
      </>
    )
  }
  if (availableDates === null) {
    return (
      <>
        <Loading>Зареждане на свободните дати…</Loading>
        <StepActions>{back}</StepActions>
      </>
    )
  }
  if (availableDates.length === 0) {
    return (
      <>
        <Notice kind="info">
          <p>Няма свободни дати за избраните услуга и служител. Опитайте с друг служител.</p>
        </Notice>
        <StepActions>{back}</StepActions>
      </>
    )
  }

  const slots = view.status === 'ready' && view.data.kind === 'availability' ? view.data.availability.slots : null
  // A chosen time counts only while the offer for its date still contains it, so the very render that
  // shows a refreshed list is already consistent, before the state catches up.
  const offered = slot !== null && slots !== null && slots.some((item) => item.start === slot.start)
  return (
    <form
      className="booking-step-form"
      onSubmit={(event) => {
        event.preventDefault()
        if (offered) onNext()
      }}
      noValidate
    >
      <div className="booking-picker">
        <BookingCalendar
          firstDate={firstDate}
          lastDate={lastDate}
          availableDates={availableDates}
          selected={date}
          today={today}
          disabled={frozen}
          onSelect={onSelectDate}
        />
        <div className="booking-picker-slots">
          {date === null ? null : (
            <fieldset className="booking-slots" disabled={frozen}>
              <legend className="booking-legend">Свободни часове</legend>
              <p className="booking-slots-date">{formatDateOnlyLong(date)}</p>
              {slots === null ? (
                <Loading>Зареждане на свободните часове…</Loading>
              ) : slots.length === 0 ? (
                <p className="public-empty">Няма свободни часове за тази дата.</p>
              ) : (
                <div className="booking-slot-list">
                  {slots.map((candidate) => {
                    const repeated = isRepeatedLocalTime(candidate.start, timezone)
                    return (
                      <label key={candidate.start} className="booking-slot">
                        <input
                          type="radio"
                          name="booking-slot"
                          value={candidate.start}
                          checked={slot?.start === candidate.start}
                          onChange={() => onSelectSlot(candidate)}
                        />
                        <span
                          className={
                            repeated ? 'booking-slot-face booking-slot-face--wide' : 'booking-slot-face'
                          }
                        >
                          {formatTimeDistinct(candidate.start, timezone)}
                        </span>
                      </label>
                    )
                  })}
                </div>
              )}
            </fieldset>
          )}
        </div>
        <p className="booking-picker-summary" role="status">
          {offered && date !== null && slot !== null
            ? `Избрано: ${formatDateOnlyLong(date)}, ${formatTimeDistinct(slot.start, timezone)} – ${formatTimeDistinct(slot.end, timezone)}`
            : ''}
        </p>
      </div>
      <StepActions>
        {back}
        <Button type="submit" disabled={!offered || frozen}>
          {nextLabel}
        </Button>
      </StepActions>
    </form>
  )
}
