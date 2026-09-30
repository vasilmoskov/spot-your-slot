import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Button } from '../../../ui/Button'
import { FieldError } from '../../../ui/formValidation'
import { nextClientId } from './formModel'
import {
  MAX_EXCEPTION_PERIODS,
  TOO_MANY_PERIODS_MESSAGE,
  formatPeriodRange,
  periodConflictMessage,
  periodErrorMessage,
  sortPeriods,
  validatePeriods,
  type DraftPeriod,
} from './presentation'

type PeriodDialogErrors = { start?: string; end?: string; range?: string }

type PeriodDialogState = {
  clientId: string | null
  start: string
  end: string
  initialStart: string
  initialEnd: string
  errors: PeriodDialogErrors
}

type PeriodListEditorProps = {
  id: string
  legend: string
  periods: DraftPeriod[]
  error: string | undefined
  disabled: boolean
  onChange: (periods: DraftPeriod[]) => void
  onDialogDirtyChange: (dirty: boolean) => void
  addButtonRef: (element: HTMLElement | null) => void
}

// Time periods of one date: always shown earliest first, edited through a
// focused dialog with its own validation, like the weekly schedule.
export function PeriodListEditor({
  id,
  legend,
  periods,
  error,
  disabled,
  onChange,
  onDialogDirtyChange,
  addButtonRef,
}: PeriodListEditorProps) {
  const [dialog, setDialog] = useState<PeriodDialogState | null>(null)
  const invoker = useRef<HTMLElement | null>(null)
  const startRef = useRef<HTMLInputElement>(null)
  const endRef = useRef<HTMLInputElement>(null)

  const dialogDirty =
    !!dialog && (dialog.start !== dialog.initialStart || dialog.end !== dialog.initialEnd)

  useEffect(() => {
    onDialogDirtyChange(dialogDirty)
  }, [dialogDirty, onDialogDirtyChange])
  useEffect(() => () => onDialogDirtyChange(false), [onDialogDirtyChange])

  // Keyed on presence only, so typing never steals focus back to the start field.
  useLayoutEffect(() => {
    if (dialog) startRef.current?.focus()
  }, [Boolean(dialog)])

  const capture = () => {
    invoker.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
  }

  const openAdd = () => {
    capture()
    setDialog({ clientId: null, start: '', end: '', initialStart: '', initialEnd: '', errors: {} })
  }

  const openEdit = (period: DraftPeriod) => {
    capture()
    setDialog({
      clientId: period.clientId,
      start: period.startTime,
      end: period.endTime,
      initialStart: period.startTime,
      initialEnd: period.endTime,
      errors: {},
    })
  }

  const close = () => {
    setDialog(null)
    invoker.current?.focus()
  }

  const submit = () => {
    if (!dialog) return
    const errors: PeriodDialogErrors = {}
    if (!dialog.start) errors.start = 'Въведете начален час.'
    if (!dialog.end) errors.end = 'Въведете краен час.'
    if (!errors.start && !errors.end) {
      const clientId = dialog.clientId ?? nextClientId()
      const candidate: DraftPeriod = { clientId, startTime: dialog.start, endTime: dialog.end }
      const next = dialog.clientId
        ? periods.map((period) => (period.clientId === clientId ? candidate : period))
        : [...periods, candidate]
      const result = validatePeriods(next)
      const own = result.errors.get(clientId)
      if (own === 'OVERLAP') {
        // Name the exact hours from the form, never a guess.
        errors.range =
          periodConflictMessage(
            { startTime: dialog.start, endTime: dialog.end },
            periods.filter((period) => period.clientId !== clientId),
          ) ?? periodErrorMessage(own)
      } else if (own) {
        errors.range = periodErrorMessage(own)
      } else if (result.tooMany) {
        errors.range = TOO_MANY_PERIODS_MESSAGE
      } else {
        onChange(sortPeriods(next))
        close()
        return
      }
    }
    setDialog({ ...dialog, errors })
    const target = errors.start || errors.range ? startRef : endRef
    target.current?.focus()
  }

  const remove = (clientId: string) => {
    onChange(periods.filter((period) => period.clientId !== clientId))
  }

  const sorted = sortPeriods(periods)
  const errorId = `${id}-error`

  return (
    <div className="form-field">
      <span className="form-field-label" id={`${id}-legend`}>
        {legend}
      </span>
      <div
        className="exception-periods"
        role="group"
        aria-labelledby={`${id}-legend`}
        aria-describedby={error ? errorId : undefined}
      >
        {sorted.map((period) => (
          <span key={period.clientId} className="schedule-period-chip schedule-period-chip--editable">
            <button
              type="button"
              className="schedule-period-chip-label"
              disabled={disabled}
              onClick={() => openEdit(period)}
            >
              {formatPeriodRange(period)}
              <span aria-hidden="true">✎</span>
              <span className="visually-hidden">
                Редактирай периода {formatPeriodRange(period)}
              </span>
            </button>
            <button
              type="button"
              className="schedule-period-chip-remove"
              disabled={disabled}
              aria-label={`Премахни периода ${formatPeriodRange(period)}`}
              onClick={() => remove(period.clientId)}
            >
              <span aria-hidden="true">×</span>
            </button>
          </span>
        ))}
        <button
          type="button"
          id={`${id}-add`}
          className="schedule-add-period"
          disabled={disabled}
          ref={addButtonRef}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          onClick={openAdd}
        >
          + Добави
        </button>
      </div>
      {periods.length >= MAX_EXCEPTION_PERIODS && (
        <p className="exception-hint">{TOO_MANY_PERIODS_MESSAGE}</p>
      )}
      <FieldError id={id} error={error} />

      {dialog && (
        <div
          className="schedule-dialog-overlay"
          onKeyDown={(event) => {
            if (event.key === 'Escape' && !dialogDirty) close()
          }}
        >
          <div
            className="confirmation-panel schedule-dialog-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby={`${id}-dialog-heading`}
          >
            <h4 id={`${id}-dialog-heading`}>
              {dialog.clientId ? 'Редактиране на период' : 'Добавяне на период'}
            </h4>
            <div className="schedule-dialog-fields">
              <div className="form-field">
                <label htmlFor={`${id}-start`}>Начален час</label>
                <input
                  id={`${id}-start`}
                  ref={startRef}
                  type="time"
                  value={dialog.start}
                  aria-invalid={dialog.errors.start || dialog.errors.range ? true : undefined}
                  aria-describedby={
                    dialog.errors.start
                      ? `${id}-start-error`
                      : dialog.errors.range
                        ? `${id}-range-error`
                        : undefined
                  }
                  onChange={(event) =>
                    setDialog((current) =>
                      current
                        ? {
                            ...current,
                            start: event.target.value,
                            errors: current.errors.end ? { end: current.errors.end } : {},
                          }
                        : current,
                    )
                  }
                />
                <FieldError id={`${id}-start`} error={dialog.errors.start} />
              </div>
              <div className="form-field">
                <label htmlFor={`${id}-end`}>Краен час</label>
                <input
                  id={`${id}-end`}
                  ref={endRef}
                  type="time"
                  value={dialog.end}
                  aria-invalid={dialog.errors.end || dialog.errors.range ? true : undefined}
                  aria-describedby={
                    dialog.errors.end
                      ? `${id}-end-error`
                      : dialog.errors.range
                        ? `${id}-range-error`
                        : undefined
                  }
                  onChange={(event) =>
                    setDialog((current) =>
                      current
                        ? {
                            ...current,
                            end: event.target.value,
                            errors: current.errors.start ? { start: current.errors.start } : {},
                          }
                        : current,
                    )
                  }
                />
                <FieldError id={`${id}-end`} error={dialog.errors.end} />
              </div>
            </div>
            <FieldError id={`${id}-range`} error={dialog.errors.range} />
            <div className="action-group">
              <Button type="button" onClick={submit}>
                {dialog.clientId ? 'Запази' : 'Добави'}
              </Button>
              <Button type="button" variant="secondary" onClick={close}>
                Отказ
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
