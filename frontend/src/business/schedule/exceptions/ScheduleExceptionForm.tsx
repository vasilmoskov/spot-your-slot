import { useCallback, useState, type FormEvent } from 'react'
import { Button } from '../../../ui/Button'
import {
  FieldError,
  fieldControlProps,
  useFieldValidation,
  type SubmitOutcome,
} from '../../../ui/formValidation'
import { useGuardedFormState } from '../../../ui/UnsavedChangesGuard'
import type { StaffMemberSummary } from '../../staff/api'
import type { ExceptionKind } from './api'
import {
  EXCEPTION_FIELD_ORDER,
  dirtyKey,
  usesDateRange,
  usesPeriods,
  validateException,
  type ExceptionField,
  type ExceptionFormValues,
  type ExceptionMode,
} from './formModel'
import { DatePeriodFields } from './DatePeriodFields'
import { PeriodListEditor } from './PeriodListEditor'
import {
  KIND_GROUPS,
  KIND_EXPLANATIONS,
  KIND_LABELS,
  isBlockKind,
  isStaffScoped,
} from './presentation'

type ScheduleExceptionFormProps = {
  // Create chooses kind and StaffMember; edit shows both as fixed context.
  mode: 'create' | 'edit'
  initial: ExceptionFormValues
  staffMembers: readonly StaffMemberSummary[]
  busy: boolean
  submitLabel: string
  onChange?: () => void
  onCancel: () => void
  onSubmit: (values: ExceptionFormValues) => Promise<SubmitOutcome<ExceptionField>> | void
}

const MODE_CHOICES: Record<'block' | 'override', { legend: string; whole: string; part: string }> = {
  block: { legend: 'Обхват', whole: 'Цели дни', part: 'Част от деня' },
  override: { legend: 'Работен ден', whole: 'Неработен ден', part: 'Работни часове' },
}

function isEmptyField(field: ExceptionField, values: ExceptionFormValues): boolean {
  switch (field) {
    case 'kind':
      return values.kind === ''
    case 'staffMemberId':
      return values.staffMemberId === ''
    case 'firstDate':
      return values.firstDate === ''
    case 'lastDate':
      return values.lastDate === ''
    case 'periods':
      return values.periods.length === 0
  }
}

export function ScheduleExceptionForm({
  mode,
  initial,
  staffMembers,
  busy,
  submitLabel,
  onChange,
  onCancel,
  onSubmit,
}: ScheduleExceptionFormProps) {
  const [values, setValues] = useState<ExceptionFormValues>(initial)
  const [dialogDirty, setDialogDirty] = useState(false)
  // Bumped when the guard discards the form, remounting the period editor so an
  // open dialog with typed input closes together with the reset values.
  const [resetCount, setResetCount] = useState(0)
  const creating = mode === 'create'

  const { errors, controlRef, touch, edited, validateAll, applyServerErrors } =
    useFieldValidation<ExceptionField, ExceptionFormValues>({
      order: EXCEPTION_FIELD_ORDER,
      values,
      validate: (current) => validateException(current, creating),
      isEmpty: isEmptyField,
    })

  const isDirty = dirtyKey(values) !== dirtyKey(initial) || dialogDirty
  // Discarding really resets the form: when the confirmed navigation stays on
  // this same form (for example a same-route Back/Forward), the still-mounted
  // form must not keep dirty values the guard already believes are gone.
  const guard = useGuardedFormState(isDirty, () => {
    setValues(initial)
    setDialogDirty(false)
    setResetCount((current) => current + 1)
  })
  const handleDialogDirty = useCallback((dirty: boolean) => setDialogDirty(dirty), [])

  const update = (field: ExceptionField, patch: Partial<ExceptionFormValues>) => {
    edited(field)
    setValues((current) => ({ ...current, ...patch }))
  }

  const changeKind = (kind: ExceptionKind) => {
    edited('kind')
    setValues((current) => {
      let nextMode: ExceptionMode = current.mode
      if (kind === 'ADDITIONAL_WORKING_PERIODS') nextMode = 'PART'
      else if (current.kind === 'ADDITIONAL_WORKING_PERIODS') nextMode = 'WHOLE'
      return { ...current, kind, mode: nextMode }
    })
  }

  const requestCancel = () => guard.guard(onCancel)

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (busy) return
    if (!validateAll()) return
    const outcome = await onSubmit(values)
    if (outcome?.fieldErrors) applyServerErrors(outcome.fieldErrors)
  }

  const kind = values.kind
  const showModeChoice = kind !== '' && kind !== 'ADDITIONAL_WORKING_PERIODS'
  const choices = kind !== '' && isBlockKind(kind) ? MODE_CHOICES.block : MODE_CHOICES.override
  const range = usesDateRange(values)
  const activeStaff = staffMembers.filter((staffMember) => staffMember.active)

  return (
    <form onChange={onChange} className="business-form exception-form" onSubmit={submit} noValidate>
      {creating && (
        <div className="form-field">
          <fieldset
            className="choice-group"
            aria-describedby={errors.kind ? 'exception-kind-error' : undefined}
          >
            <legend>Вид промяна</legend>
            {KIND_GROUPS.map((group, groupIndex) => {
              const headingId = `exception-kind-group-${groupIndex}`
              return (
                <div key={group.label} role="group" aria-labelledby={headingId} className="kind-group">
                  <p id={headingId} className="kind-group-heading">
                    {group.label}
                  </p>
                  {group.kinds.map((option) => (
                    <label key={option} className="choice">
                      <input
                        type="radio"
                        name="exception-kind"
                        value={option}
                        checked={kind === option}
                        ref={groupIndex === 0 ? controlRef('kind') : undefined}
                        aria-invalid={errors.kind ? true : undefined}
                        onChange={() => changeKind(option)}
                      />
                      <span>{KIND_LABELS[option]}</span>
                    </label>
                  ))}
                </div>
              )
            })}
          </fieldset>
          <FieldError id="exception-kind" error={errors.kind} />
        </div>
      )}

      {kind !== '' && !creating && (
        <dl className="business-details-list">
          <div>
            <dt>Вид</dt>
            <dd>{KIND_LABELS[kind]}</dd>
          </div>
          {isStaffScoped(kind) && (
            <div>
              <dt>Член на екипа</dt>
              <dd>
                {staffMembers.find((staffMember) => staffMember.id === values.staffMemberId)
                  ?.displayName ?? 'Член на екипа'}
              </dd>
            </div>
          )}
        </dl>
      )}

      {kind !== '' && (
        <div className="exception-hints">
          {KIND_EXPLANATIONS[kind].map((line) => (
            <p key={line} className="exception-hint">
              {line}
            </p>
          ))}
        </div>
      )}

      {creating && kind !== '' && isStaffScoped(kind) && (
        <div className="form-field">
          <label htmlFor="exception-staff">Член на екипа</label>
          <select
            {...fieldControlProps('exception-staff', errors.staffMemberId)}
            ref={controlRef('staffMemberId')}
            value={values.staffMemberId}
            onBlur={() => touch('staffMemberId')}
            onChange={(event) => update('staffMemberId', { staffMemberId: event.target.value })}
          >
            <option value="">Изберете член на екипа</option>
            {activeStaff.map((staffMember) => (
              <option key={staffMember.id} value={staffMember.id}>
                {staffMember.displayName}
              </option>
            ))}
          </select>
          <FieldError id="exception-staff" error={errors.staffMemberId} />
        </div>
      )}

      {showModeChoice && (
        <div className="form-field">
          <fieldset className="choice-group">
            <legend>{choices.legend}</legend>
            {(['WHOLE', 'PART'] as const).map((option) => (
              <label key={option} className="choice">
                <input
                  type="radio"
                  name="exception-mode"
                  value={option}
                  checked={values.mode === option}
                  onChange={() => setValues((current) => ({ ...current, mode: option }))}
                />
                <span>{option === 'WHOLE' ? choices.whole : choices.part}</span>
              </label>
            ))}
          </fieldset>
        </div>
      )}

      {kind !== '' && (
        <DatePeriodFields
          first={{
            id: 'exception-first-date',
            label: range ? 'От' : 'Дата',
            value: values.firstDate,
            error: errors.firstDate,
            controlRef: controlRef('firstDate'),
            onBlur: () => touch('firstDate'),
            onChange: (value) => update('firstDate', { firstDate: value }),
          }}
          {...(range
            ? {
                last: {
                  id: 'exception-last-date',
                  label: 'До',
                  value: values.lastDate,
                  error: errors.lastDate,
                  controlRef: controlRef('lastDate'),
                  onBlur: () => touch('lastDate'),
                  onChange: (value: string) => update('lastDate', { lastDate: value }),
                },
              }
            : {})}
        />
      )}

      {kind !== '' && usesPeriods(values) && (
        <PeriodListEditor
          key={resetCount}
          id="exception-periods"
          legend="Часове"
          periods={values.periods}
          error={errors.periods}
          disabled={busy}
          onChange={(periods) => update('periods', { periods })}
          onDialogDirtyChange={handleDialogDirty}
          addButtonRef={controlRef('periods')}
        />
      )}

      <div className="action-group">
        <Button type="button" variant="secondary" disabled={busy} onClick={requestCancel}>
          Отказ
        </Button>
        <Button disabled={busy}>{busy ? 'Запазване…' : submitLabel}</Button>
      </div>
    </form>
  )
}
