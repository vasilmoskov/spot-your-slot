import { useLayoutEffect, useRef, type KeyboardEvent, type RefObject } from 'react'
import { Button } from '../../ui/Button'
import { FieldError, fieldControlProps } from '../../ui/formValidation'
import type { AppointmentDetails } from '../calendar/api'
import { businessDateOf, formatDateHeading, formatTimeRange } from '../calendar/presentation'
import { noteError } from './model'
import type { AppointmentOperations, Notice } from './useAppointmentOperations'
import type { Operation } from './lifecycleErrors'

// The presentation of the two owner operations of the Appointment detail. All state and every request
// live in `useAppointmentOperations`; these pieces only render it, keep the two operations visually
// separate, and never decide anything about a request.

type NoticeViewProps = {
  operation: Operation
  notice: Notice | null
  innerRef: RefObject<HTMLDivElement | null>
  busy: boolean
  onReread: () => void
}

// The kinds after which the owner is offered the explicit way to read the Appointment again.
const REREAD_KINDS = new Set(['validation', 'uncertain', 'unexpected', 'reload'])

/** The one alert of an operation; a result of the other operation is never shown here. */
export function OperationNotice({ operation, notice, innerRef, busy, onReread }: NoticeViewProps) {
  if (notice === null || notice.operation !== operation) return null
  return (
    <div className="feedback-action-layout">
      <div
        ref={innerRef}
        className={notice.kind === 'reload' ? 'status-message status-info' : 'status-message status-error'}
        role="alert"
        tabIndex={-1}
      >
        <p>{notice.text}</p>
      </div>
      {REREAD_KINDS.has(notice.kind) && (
        <div className="feedback-action-controls action-group">
          <Button type="button" variant="secondary" disabled={busy} onClick={onReread}>
            Прочети часа отново
          </Button>
        </div>
      )}
    </div>
  )
}

// ---- cancellation ---------------------------------------------------------------------------------------------------

type CancelSectionProps = {
  operations: AppointmentOperations
  // The owner of an ACTIVE Business views a CONFIRMED Appointment that has not started.
  eligible: boolean
  openRef: RefObject<HTMLButtonElement | null>
  noticeRef: RefObject<HTMLDivElement | null>
  onReread: () => void
}

export function CancelSection({ operations, eligible, openRef, noticeRef, onReread }: CancelSectionProps) {
  const hasNotice = operations.notice?.operation === 'cancel'
  if (!eligible && !hasNotice) return null
  // An action that cannot work is absent, not disabled.
  const canOpen =
    eligible &&
    operations.unresolved === null &&
    !operations.blocked &&
    !operations.reloadNeeded &&
    !operations.cancelRefused
  return (
    <section className="business-section appointment-cancel" aria-labelledby="appointment-cancel">
      <h3 id="appointment-cancel">Отмяна на часа</h3>
      {canOpen && (
        <p>Отмяната освобождава времето в графика и не може да бъде върната. Нов час се създава отделно.</p>
      )}
      <OperationNotice
        operation="cancel"
        notice={operations.notice}
        innerRef={noticeRef}
        busy={operations.pending !== null}
        onReread={onReread}
      />
      {canOpen && (
        <div className="action-group">
          <Button
            ref={openRef}
            type="button"
            variant="destructive"
            aria-haspopup="dialog"
            disabled={operations.pending !== null}
            onClick={operations.openCancel}
          >
            Отмени часа
          </Button>
        </div>
      )}
    </section>
  )
}

export function CancelDialog({
  appointment,
  operations,
}: {
  appointment: AppointmentDetails
  operations: AppointmentOperations
}) {
  const safeRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const sending = operations.pending === 'cancel'
  const date = businessDateOf(appointment.start, appointment.timezone)

  // The safe action receives the initial focus; the destructive one never does.
  useLayoutEffect(() => {
    safeRef.current?.focus()
  }, [])
  // While the request is in flight the buttons are disabled; focus stays inside the dialog.
  useLayoutEffect(() => {
    if (sending) panelRef.current?.focus()
  }, [sending])

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.stopPropagation()
      operations.closeCancel()
      return
    }
    if (event.key !== 'Tab') return
    const buttons = Array.from(panelRef.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])
    if (buttons.length === 0) {
      event.preventDefault()
      return
    }
    const first = buttons[0]!
    const last = buttons[buttons.length - 1]!
    if (event.shiftKey && (document.activeElement === first || document.activeElement === panelRef.current)) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <div className="unsaved-changes-overlay" onKeyDown={onKeyDown}>
      <div
        ref={panelRef}
        className="confirmation-panel unsaved-changes-panel cancel-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="cancel-dialog-title"
        aria-describedby="cancel-dialog-summary cancel-dialog-consequence"
        tabIndex={-1}
      >
        <h4 id="cancel-dialog-title">Отмяна на час</h4>
        <dl id="cancel-dialog-summary" className="cancel-dialog-context">
          <div>
            <dt>Клиент:</dt>
            <dd>{appointment.customer.displayName}</dd>
          </div>
          <div>
            <dt>Дата:</dt>
            <dd>{formatDateHeading(date)}</dd>
          </div>
          <div>
            <dt>Час:</dt>
            <dd>{formatTimeRange(appointment.start, appointment.end, appointment.timezone)}</dd>
          </div>
          <div>
            <dt>Член на екипа:</dt>
            <dd>{appointment.staffDisplayName}</dd>
          </div>
        </dl>
        <p id="cancel-dialog-consequence" className="cancel-dialog-warning">
          <span>Часът ще бъде отменен.</span>
          <span>Това действие не може да бъде върнато.</span>
        </p>
        <div className="action-group unsaved-changes-actions">
          <Button type="button" variant="destructive" disabled={sending} onClick={operations.confirmCancel}>
            Отмени часа
          </Button>
          <Button ref={safeRef} type="button" variant="neutral" disabled={sending} onClick={operations.closeCancel}>
            Отказ
          </Button>
        </div>
        {sending && (
          <p role="status" className="manual-hint">
            Отмяна на часа…
          </p>
        )}
      </div>
    </div>
  )
}

// ---- internal note --------------------------------------------------------------------------------------------------

type NoteEditorProps = {
  appointment: AppointmentDetails
  operations: AppointmentOperations
  // The owner of an ACTIVE Business: allowed for every status, as the backend contract defines.
  editable: boolean
  inputRef: RefObject<HTMLTextAreaElement | null>
  editRef: RefObject<HTMLButtonElement | null>
  noticeRef: RefObject<HTMLDivElement | null>
  onReread: () => void
}

/** The frozen submitted value, the editor, or the plain note with its edit control, never two of them. */
export function InternalNote({
  appointment,
  operations,
  editable,
  inputRef,
  editRef,
  noticeRef,
  onReread,
}: NoteEditorProps) {
  const frozen = operations.unresolved?.operation === 'note' ? operations.unresolved : null
  const sending = operations.pending === 'note'
  const problem = operations.editing ? noteError(operations.draft) : undefined
  const canEdit =
    editable &&
    operations.unresolved === null &&
    !operations.blocked &&
    !operations.reloadNeeded &&
    !operations.editing

  return (
    <>
      {frozen ? (
        <dl className="business-details-list">
          <div>
            <dt>Изпратена вътрешна бележка (непотвърдена)</dt>
            <dd className="appointment-note">{frozen.note ?? 'Празна бележка (изчистване на бележката)'}</dd>
          </div>
        </dl>
      ) : operations.editing ? (
        <form
          className="appointment-note-form"
          noValidate
          onSubmit={(event) => {
            event.preventDefault()
            operations.saveNote()
          }}
        >
          <div className="form-field">
            <label htmlFor="internal-note">Вътрешна бележка</label>
            <textarea
              {...fieldControlProps('internal-note', problem)}
              ref={inputRef}
              rows={5}
              value={operations.draft}
              readOnly={sending}
              onChange={(event) => operations.changeDraft(event.target.value)}
            />
            <FieldError id="internal-note" error={problem} />
          </div>
          <div className="action-group">
            <Button type="submit" disabled={sending}>
              Запази бележката
            </Button>
            <Button type="button" variant="destructive" disabled={sending} onClick={operations.cancelEdit}>
              Отказ
            </Button>
          </div>
          {sending && (
            <p className="visually-hidden" role="status">
              Запазване на бележката…
            </p>
          )}
        </form>
      ) : (
        <dl className="business-details-list">
          <div>
            <dt>Вътрешна бележка</dt>
            <dd className="appointment-note">{appointment.internalNote ?? 'Няма бележка.'}</dd>
          </div>
        </dl>
      )}
      {operations.noteStatus && (
        <p className="status-message status-info" role="status">
          {operations.noteStatus}
        </p>
      )}
      <OperationNotice
        operation="note"
        notice={operations.notice}
        innerRef={noticeRef}
        busy={operations.pending !== null}
        onReread={onReread}
      />
      {canEdit && (
        <div className="action-group">
          <Button ref={editRef} type="button" variant="secondary" onClick={operations.startEdit}>
            {appointment.internalNote === null ? 'Добави бележка' : 'Редактирай'}
          </Button>
        </div>
      )}
    </>
  )
}
