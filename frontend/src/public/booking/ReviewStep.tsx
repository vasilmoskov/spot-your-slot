import { useEffect, useRef, type ReactNode } from 'react'
import { formatServiceDuration, formatServicePrice } from '../../business/services/presentation'
import { trimApproved } from '../../business/text'
import { Button } from '../../ui/Button'
import type { PublicService } from '../api'
import { dialableNumber } from '../presentation'
import type { BookingDetails, Submission } from './attempt'
import { retryDelayMs } from './attempt'
import type { Slot, SubmissionRejection } from './api'
import { formatDateOnlyLong, formatTimeDistinct } from './dates'
import { useWaiting } from './hooks'
import {
  ATTEMPT_MISMATCH_MESSAGE,
  KNOWN_ROLLBACK_MESSAGE,
  NO_PREFERENCE_LABEL,
  RATE_LIMITED_MESSAGE,
  UNCERTAIN_MESSAGE,
  rejectionMessage,
} from './messages'
import { StepActions } from './steps'

// Reasons after which sending the same choices again cannot help: the guest first changes something.
const BLOCKING_REASONS: readonly SubmissionRejection[] = [
  'slot-unavailable',
  'service-unavailable',
  'staff-unavailable',
  'identity-conflict',
  'validation',
]

export type ReviewRecovery = {
  chooseSlot: () => void
  chooseService: () => void
  chooseStaff: () => void
  editDetails: () => void
  restart: () => void
}

function BusinessPhone({ phone }: { phone: string | null }) {
  const text = phone?.trim() ?? ''
  if (text === '') return null
  const dialable = dialableNumber(text)
  return (
    <p>
      Телефон на бизнеса:{' '}
      {dialable ? (
        <a className="text-link public-phone" href={`tel:${dialable}`}>
          {text}
        </a>
      ) : (
        text
      )}
    </p>
  )
}

function Row({
  term,
  onEdit,
  editLabel,
  disabled,
  children,
}: {
  term: string
  onEdit: () => void
  editLabel: string
  disabled: boolean
  children: ReactNode
}) {
  return (
    <div className="booking-summary-row">
      <dt>{term}</dt>
      <dd>{children}</dd>
      <Button
        type="button"
        variant="secondary"
        className="booking-summary-edit"
        disabled={disabled}
        aria-label={editLabel}
        onClick={onEdit}
      >
        Промени
      </Button>
    </div>
  )
}

/**
 * The last step before anything is sent. It shows the advertised duration and price (the confirmation
 * shows the final ones), makes clear that the time is not reserved yet, and carries every submission
 * state: sending, the known rollback, the rate limit, the frozen uncertain outcome and each rejection.
 */
export function ReviewStep({
  service,
  staffLabel,
  date,
  slot,
  timezone,
  details,
  submission,
  businessPhone,
  now,
  recovery,
  onSubmit,
  onBack,
  onEdit,
}: {
  service: PublicService
  staffLabel: string
  date: string
  slot: Slot
  timezone: string
  details: BookingDetails
  submission: Submission
  businessPhone: string | null
  now: () => number
  recovery: ReviewRecovery
  onSubmit: () => void
  onBack: () => void
  onEdit: (step: number) => void
}) {
  const frozen =
    submission.phase === 'sending' || submission.phase === 'uncertain' || submission.phase === 'confirmed'
  const delayMs = retryDelayMs(submission, now())
  const retryToken = submission.phase === 'retryable' || submission.phase === 'uncertain' ? submission : null
  const waiting = useWaiting(delayMs, retryToken)

  const notice = submission.phase === 'idle' ? submission.notice : null
  // The same attempt would only meet the same mismatch, so only the warned restart is offered.
  const mismatched = submission.phase === 'uncertain' && submission.cause === 'mismatch'
  const blocked = notice !== null && BLOCKING_REASONS.includes(notice.reason)

  // A failed or refused submission moves focus to its message, so it is announced and reachable.
  const noticeRef = useRef<HTMLDivElement>(null)
  const noticeKey =
    notice?.reason ?? (submission.phase === 'retryable' || submission.phase === 'uncertain' ? submission.phase : null)
  useEffect(() => {
    if (noticeKey !== null) noticeRef.current?.focus()
  }, [noticeKey, submission])

  const note = trimApproved(details.note)
  const phone = trimApproved(details.phone)
  const email = trimApproved(details.email)
  const startText = formatTimeDistinct(slot.start, timezone)
  const endText = formatTimeDistinct(slot.end, timezone)

  let message: string | null = null
  if (notice) message = rejectionMessage(notice.reason)
  else if (submission.phase === 'retryable') {
    message = submission.cause === 'rate-limited' ? RATE_LIMITED_MESSAGE : KNOWN_ROLLBACK_MESSAGE
  } else if (submission.phase === 'uncertain') {
    message =
      submission.cause === 'rate-limited'
        ? RATE_LIMITED_MESSAGE
        : submission.cause === 'mismatch'
          ? ATTEMPT_MISMATCH_MESSAGE
          : UNCERTAIN_MESSAGE
  }

  const canSend = submission.phase === 'idle' || submission.phase === 'retryable' || submission.phase === 'uncertain'
  const sendLabel =
    submission.phase === 'sending'
      ? 'Изпращане…'
      : submission.phase === 'retryable' || submission.phase === 'uncertain'
        ? 'Опитайте отново'
        : 'Потвърди резервацията'

  return (
    <div className="booking-review">
      <dl className="booking-summary">
        <Row term="Услуга" editLabel="Промени услугата" disabled={frozen} onEdit={() => onEdit(1)}>
          <span className="booking-summary-strong">{service.name}</span>
          <span>
            {formatServiceDuration(service.durationMinutes)} · {formatServicePrice(service.price)}
          </span>
        </Row>
        <Row term="Служител" editLabel="Промени служителя" disabled={frozen} onEdit={() => onEdit(2)}>
          <span className="booking-summary-strong">{staffLabel}</span>
          {staffLabel === NO_PREFERENCE_LABEL && (
            <span>Служителят се определя при потвърждаване.</span>
          )}
        </Row>
        <Row term="Дата и час" editLabel="Промени датата и часа" disabled={frozen} onEdit={() => onEdit(3)}>
          <span className="booking-summary-strong">{formatDateOnlyLong(date)}</span>
          <span>
            {startText} – {endText}
          </span>
        </Row>
        <Row term="Вашите данни" editLabel="Промени данните" disabled={frozen} onEdit={() => onEdit(4)}>
          <span className="booking-summary-strong">{trimApproved(details.displayName)}</span>
          {phone !== '' && <span>{phone}</span>}
          {email !== '' && <span>{email}</span>}
          {note !== '' && <span className="booking-summary-note">{note}</span>}
        </Row>
      </dl>

      <p className="field-note">
        Часът не е запазен, докато резервацията не бъде потвърдена. Наличността се проверява при
        изпращането.
      </p>

      {submission.phase === 'sending' && (
        <p role="status" className="public-status">
          Изпращане на резервацията…
        </p>
      )}

      {message !== null && (
        <div
          ref={noticeRef}
          tabIndex={-1}
          className="status-message status-error booking-outcome"
          role="alert"
        >
          <p>{message}</p>
          {(submission.phase === 'uncertain' || notice?.reason === 'identity-conflict') && (
            <>
              {submission.phase === 'uncertain' && (
                <p>Резервацията може вече да е направена. Не започвайте нова.</p>
              )}
              {submission.phase === 'uncertain' && submission.cause === 'business-unavailable' && (
                <p>Страницата на бизнеса в момента не е налична.</p>
              )}
              <BusinessPhone phone={businessPhone} />
            </>
          )}
        </div>
      )}

      <StepActions>
        {!frozen && (
          <Button type="button" variant="secondary" onClick={onBack}>
            Назад
          </Button>
        )}
        {notice?.reason === 'slot-unavailable' && (
          <Button type="button" onClick={recovery.chooseSlot}>
            Избор на друг час
          </Button>
        )}
        {notice?.reason === 'service-unavailable' && (
          <Button type="button" onClick={recovery.chooseService}>
            Избор на услуга
          </Button>
        )}
        {notice?.reason === 'staff-unavailable' && (
          <Button type="button" onClick={recovery.chooseStaff}>
            Избор на служител
          </Button>
        )}
        {(notice?.reason === 'validation' || notice?.reason === 'identity-conflict') && (
          <Button type="button" onClick={recovery.editDetails}>
            Промяна на данните
          </Button>
        )}
        {submission.phase === 'uncertain' && (
          <Button type="button" variant="secondary" onClick={recovery.restart}>
            Започни отначало
          </Button>
        )}
        {!blocked && !mismatched && (canSend || submission.phase === 'sending') && (
          <Button
            type="button"
            disabled={waiting || submission.phase === 'sending'}
            onClick={onSubmit}
          >
            {sendLabel}
          </Button>
        )}
      </StepActions>
    </div>
  )
}
