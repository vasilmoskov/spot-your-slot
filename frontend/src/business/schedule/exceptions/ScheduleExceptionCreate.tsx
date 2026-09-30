import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Button } from '../../../ui/Button'
import { type SubmitOutcome } from '../../../ui/formValidation'
import { useUnsavedChangesGuard } from '../../../ui/UnsavedChangesGuard'
import { errorCategory, useFeedback } from '../../../ui/useFeedback'
import type { StaffMemberSummary } from '../../staff/api'
import { loadEveryStaffMember } from '../../staff/fullCatalog'
import { createScheduleException, type CreateScheduleExceptionInput } from './api'
import {
  exceptionFieldErrors,
  isAuthenticationRequired,
  isOverlapConflict,
  safeExceptionError,
} from './errors'
import { FeedbackLines } from './FeedbackLines'
import {
  emptyValues,
  toPayload,
  type ExceptionField,
  type ExceptionFormValues,
} from './formModel'
import { ScheduleExceptionForm } from './ScheduleExceptionForm'
import { isStaffScoped } from './presentation'

type ScheduleExceptionCreateProps = {
  readOnly: boolean
  onAuthenticationRequired: (detail: string) => void
  onCreated: (exceptionId: string) => void
  onCancel: () => void
}

export function toCreateInput(values: ExceptionFormValues): CreateScheduleExceptionInput {
  const kind = values.kind
  if (kind === '') throw new Error('A kind is required')
  return {
    kind,
    ...(isStaffScoped(kind) ? { staffMemberId: values.staffMemberId } : {}),
    ...toPayload(values),
  }
}

export function ScheduleExceptionCreate({
  readOnly,
  onAuthenticationRequired,
  onCreated,
  onCancel,
}: ScheduleExceptionCreateProps) {
  const [staffMembers, setStaffMembers] = useState<StaffMemberSummary[] | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [busy, setBusy] = useState(false)
  const { feedback: error, setFeedback, beginFeedback } = useFeedback('schedule-exception-create')
  const guard = useUnsavedChangesGuard()
  const submitting = useRef(false)
  const activeLoad = useRef<AbortController | null>(null)
  const errorMessage = useRef<HTMLDivElement>(null)

  const load = useCallback(async () => {
    activeLoad.current?.abort()
    const controller = new AbortController()
    activeLoad.current = controller
    setLoadError(false)
    try {
      const everyone = await loadEveryStaffMember(controller.signal)
      if (controller.signal.aborted || activeLoad.current !== controller) return
      setStaffMembers(everyone)
    } catch (caught) {
      if (controller.signal.aborted || activeLoad.current !== controller) return
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setLoadError(true)
    } finally {
      if (activeLoad.current === controller) activeLoad.current = null
    }
  }, [onAuthenticationRequired])

  useEffect(() => {
    if (readOnly) return
    void load()
    return () => {
      const controller = activeLoad.current
      activeLoad.current = null
      controller?.abort()
    }
  }, [load, readOnly])

  useLayoutEffect(() => {
    if (error) errorMessage.current?.focus()
  }, [error])

  const submit = async (values: ExceptionFormValues): Promise<SubmitOutcome<ExceptionField>> => {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    const publish = beginFeedback()
    try {
      const created = await createScheduleException(toCreateInput(values))
      if (publish(null)) {
        // The form is still registered dirty until it unmounts; clear it so the
        // navigation to the created record shows no false unsaved-changes prompt.
        guard.unregisterDirty()
        onCreated(created.id)
      }
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      const fieldErrors = exceptionFieldErrors(caught)
      if (fieldErrors) return { fieldErrors }
      publish({
        kind: 'error',
        category: isOverlapConflict(caught) ? 'validation' : errorCategory(caught),
        text: safeExceptionError(
          caught,
          'Промяната не може да бъде добавена.',
          values.kind === '' ? undefined : values.kind,
        ),
      })
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  if (readOnly) {
    return (
      <div className="platform-content">
        <div className="business-page-actions">
          <Button type="button" variant="secondary" onClick={onCancel}>
            Обратно към графика
          </Button>
        </div>
        <p className="section-introduction">Нови промени не могат да бъдат добавяни.</p>
      </div>
    )
  }

  if (loadError) {
    return (
      <div className="platform-content">
        <div className="feedback-action-layout">
          <div className="status-message status-error" role="alert">
            <p>Екипът не може да бъде зареден.</p>
          </div>
          <div className="action-group">
            <Button type="button" variant="secondary" onClick={() => void load()}>
              Опитай отново
            </Button>
            <Button type="button" variant="secondary" onClick={onCancel}>
              Обратно към графика
            </Button>
          </div>
        </div>
      </div>
    )
  }

  if (!staffMembers) {
    return (
      <div className="platform-content" aria-live="polite" aria-busy="true">
        <p className="business-list-state">Зареждане…</p>
      </div>
    )
  }

  return (
    <div className="platform-content">
      <div className="business-page-actions">
        <Button
          type="button"
          variant="secondary"
          disabled={busy}
          onClick={() => guard.guard(onCancel)}
        >
          Обратно към графика
        </Button>
      </div>
      <div className="business-section">
        <div className="feedback-action-layout">
          <ScheduleExceptionForm
            mode="create"
            initial={emptyValues()}
            staffMembers={staffMembers}
            busy={busy}
            submitLabel="Добави"
            onChange={() => setFeedback(null)}
            onCancel={onCancel}
            onSubmit={submit}
          />
          {error && (
            <div
              ref={errorMessage}
              className={`status-message status-${error.kind}`}
              role="alert"
              tabIndex={-1}
            >
              <FeedbackLines text={error.text} />
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
