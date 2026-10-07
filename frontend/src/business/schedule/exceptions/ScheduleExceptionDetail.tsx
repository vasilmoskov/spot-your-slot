import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Button } from '../../../ui/Button'
import { type SubmitOutcome } from '../../../ui/formValidation'
import { useUnsavedChangesGuard } from '../../../ui/UnsavedChangesGuard'
import { errorCategory, useFeedback } from '../../../ui/useFeedback'
import type { StaffMemberSummary } from '../../staff/api'
import { loadEveryStaffMember } from '../../staff/fullCatalog'
import {
  deleteScheduleException,
  getScheduleException,
  replaceScheduleException,
  type ScheduleExceptionDetails as ExceptionDetails,
} from './api'
import {
  exceptionFieldErrors,
  isAuthenticationRequired,
  isConcurrentUpdate,
  isOverlapConflict,
  safeExceptionError,
} from './errors'
import { FeedbackLines } from './FeedbackLines'
import {
  toPayload,
  valuesFromItem,
  type ExceptionField,
  type ExceptionFormValues,
} from './formModel'
import { ScheduleExceptionForm } from './ScheduleExceptionForm'
import {
  KIND_LABELS,
  businessToday,
  formatDate,
  formatDateRange,
  hoursSummary,
  isStaffScoped,
  scheduleChangeStatus,
  scheduleChangeStatusPresentation,
} from './presentation'

type ScheduleExceptionDetailProps = {
  exceptionId: string
  readOnly: boolean
  onAuthenticationRequired: (detail: string) => void
  onBack: () => void
  onDeleted: () => void
  // Injectable for tests; the real clock otherwise.
  now?: () => Date
}

export function ScheduleExceptionDetail({
  exceptionId,
  readOnly,
  onAuthenticationRequired,
  onBack,
  onDeleted,
  now = () => new Date(),
}: ScheduleExceptionDetailProps) {
  const [exception, setException] = useState<ExceptionDetails | null>(null)
  const [staffMembers, setStaffMembers] = useState<StaffMemberSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)
  const [updating, setUpdating] = useState(false)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  // Bumped after every (re)load so the edit form always restarts from the
  // freshly loaded record.
  const [generation, setGeneration] = useState(0)
  const { feedback, setFeedback, beginFeedback } = useFeedback(exceptionId)
  const guard = useUnsavedChangesGuard()
  const activeLoad = useRef<AbortController | null>(null)
  const updateInProgress = useRef(false)
  const deleteInProgress = useRef(false)
  const errorMessage = useRef<HTMLDivElement>(null)
  const deleteButton = useRef<HTMLButtonElement>(null)
  const cancelDeleteButton = useRef<HTMLButtonElement>(null)

  const load = useCallback(async () => {
    activeLoad.current?.abort()
    const controller = new AbortController()
    activeLoad.current = controller
    setLoading(true)
    setLoadError(null)
    setFeedback(null)
    try {
      const [loaded, everyone] = await Promise.all([
        getScheduleException(exceptionId, controller.signal),
        loadEveryStaffMember(controller.signal),
      ])
      if (controller.signal.aborted || activeLoad.current !== controller) return
      setException(loaded)
      setStaffMembers(everyone)
      setEditing(false)
      setGeneration((current) => current + 1)
    } catch (caught) {
      if (controller.signal.aborted || activeLoad.current !== controller) return
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setLoadError(safeExceptionError(caught, 'Данните за промяната не могат да бъдат заредени.'))
    } finally {
      if (activeLoad.current === controller) {
        activeLoad.current = null
        setLoading(false)
      }
    }
  }, [exceptionId, onAuthenticationRequired, setFeedback])

  useEffect(() => {
    void load()
    return () => {
      const controller = activeLoad.current
      activeLoad.current = null
      controller?.abort()
    }
  }, [load])

  useLayoutEffect(() => {
    if (feedback?.kind === 'error') errorMessage.current?.focus()
  }, [feedback])

  useLayoutEffect(() => {
    if (confirmingDelete) cancelDeleteButton.current?.focus()
  }, [confirmingDelete])

  const closeDeleteDialog = () => {
    setConfirmingDelete(false)
    // The Delete control is still rendered, so focus returns to it.
    deleteButton.current?.focus()
  }

  const update = async (values: ExceptionFormValues): Promise<SubmitOutcome<ExceptionField>> => {
    if (!exception || updateInProgress.current) return
    updateInProgress.current = true
    setUpdating(true)
    const publish = beginFeedback()
    try {
      const updated = await replaceScheduleException(exception.id, {
        expectedVersion: exception.version,
        ...toPayload(values),
      })
      if (!publish(null)) return
      guard.unregisterDirty()
      setException(updated)
      setEditing(false)
      setGeneration((current) => current + 1)
      setFeedback({ kind: 'success', text: 'Промените са запазени.' })
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
        text: safeExceptionError(caught, 'Промените не могат да бъдат запазени.', exception.kind),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      updateInProgress.current = false
      setUpdating(false)
    }
  }

  const remove = async () => {
    if (!exception || deleteInProgress.current) return
    deleteInProgress.current = true
    setDeleting(true)
    const publish = beginFeedback()
    try {
      await deleteScheduleException(exception.id, exception.version)
      if (!publish(null)) return
      onDeleted()
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setConfirmingDelete(false)
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeExceptionError(caught, 'Промяната не може да бъде изтрита.'),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      deleteInProgress.current = false
      setDeleting(false)
    }
  }

  const reloadGuarded = () => guard.guard(() => void load())

  if (loading) {
    return (
      <div className="platform-content" aria-live="polite" aria-busy="true">
        <p className="business-list-state">Зареждане на промяната…</p>
      </div>
    )
  }

  if (loadError || !exception) {
    return (
      <div className="platform-content">
        <div className="feedback-action-layout">
          <div className="status-message status-error" role="alert">
            <FeedbackLines text={loadError ?? 'Промяната не е намерена.'} />
          </div>
          <div className="action-group">
            <Button type="button" variant="secondary" onClick={() => void load()}>
              Зареди отново
            </Button>
            <Button type="button" variant="secondary" onClick={onBack}>
              Обратно към графика
            </Button>
          </div>
        </div>
      </div>
    )
  }

  const staffMember = exception.staffMemberId
    ? staffMembers.find((candidate) => candidate.id === exception.staffMemberId)
    : undefined
  const staffName = staffMember?.displayName ?? 'Член на екипа'
  const staffInactive = staffMember !== undefined && !staffMember.active
  const canMutate = !readOnly && !staffInactive
  const detailStatus = scheduleChangeStatusPresentation(
    scheduleChangeStatus(exception, businessToday(now(), exception.timezone)),
  )
  const summary = `${KIND_LABELS[exception.kind]}${
    isStaffScoped(exception.kind) ? `, ${staffName}` : ''
  }`

  return (
    <div className="platform-content business-detail">
      <div className="business-page-actions">
        <Button type="button" variant="secondary" disabled={deleting} onClick={() => guard.guard(onBack)}>
          Обратно към графика
        </Button>
      </div>

      <div className="business-section">
        <div className="feedback-action-layout">
          {editing ? (
            <ScheduleExceptionForm
              key={`${exception.id}-${generation}`}
              mode="edit"
              initial={valuesFromItem(exception)}
              staffMembers={staffMembers}
              busy={updating}
              submitLabel="Запази промените"
              onChange={() => {
                if (!feedback?.reload) setFeedback(null)
              }}
              onCancel={() => {
                setEditing(false)
                setFeedback(null)
              }}
              onSubmit={update}
            />
          ) : (
            <>
              <dl className="business-details-list">
                <div>
                  <dt>Вид</dt>
                  <dd>{KIND_LABELS[exception.kind]}</dd>
                </div>
                {isStaffScoped(exception.kind) && (
                  <div>
                    <dt>Член на екипа</dt>
                    <dd>{staffName}</dd>
                  </div>
                )}
                <div>
                  <dt>{exception.firstDate === exception.lastDate ? 'Дата' : 'Дати'}</dt>
                  <dd>{formatDateRange(exception.firstDate, exception.lastDate)}</dd>
                </div>
                <div>
                  <dt>Часове</dt>
                  <dd>{hoursSummary(exception)}</dd>
                </div>
                <div>
                  <dt>Статус</dt>
                  <dd>
                    <span className={`status-badge status-badge-${detailStatus.tone}`}>
                      {detailStatus.label}
                    </span>
                  </dd>
                </div>
              </dl>
              {!readOnly && staffInactive && (
                <p className="section-introduction">
                  Промените на неактивен член на екипа могат само да бъдат преглеждани.
                </p>
              )}
              {canMutate && (
                <div className="action-group">
                  <Button
                    type="button"
                    disabled={deleting}
                    onClick={() => {
                      setFeedback(null)
                      setEditing(true)
                    }}
                  >
                    Редактирай
                  </Button>
                  <Button
                    ref={deleteButton}
                    type="button"
                    variant="destructive"
                    disabled={deleting}
                    onClick={() => {
                      setFeedback(null)
                      setConfirmingDelete(true)
                    }}
                  >
                    Изтрий
                  </Button>
                </div>
              )}
            </>
          )}
          {feedback && (
            <div
              ref={feedback.kind === 'error' ? errorMessage : undefined}
              className={`status-message status-${feedback.kind}`}
              role={feedback.kind === 'error' ? 'alert' : 'status'}
              aria-live={feedback.kind === 'success' ? 'polite' : undefined}
              tabIndex={feedback.kind === 'error' ? -1 : undefined}
            >
              <FeedbackLines text={feedback.text} />
            </div>
          )}
          {feedback?.reload && (
            <Button type="button" variant="secondary" onClick={reloadGuarded}>
              Зареди актуалните данни
            </Button>
          )}
        </div>
      </div>

      {confirmingDelete && (
        <div
          className="schedule-dialog-overlay"
          onKeyDown={(event) => {
            if (event.key === 'Escape' && !deleting) closeDeleteDialog()
          }}
        >
          <div
            className="confirmation-panel schedule-dialog-panel"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="delete-exception-heading"
            aria-describedby="delete-exception-description-1 delete-exception-description-2"
          >
            <h4 id="delete-exception-heading">Изтриване на промяна</h4>
            <p id="delete-exception-description-1">
              Промяната „{summary}“ за{' '}
              {exception.firstDate === exception.lastDate
                ? formatDate(exception.firstDate)
                : formatDateRange(exception.firstDate, exception.lastDate)}{' '}
              ще бъде изтрита завинаги.
            </p>
            <p id="delete-exception-description-2">Сигурни ли сте, че искате да продължите?</p>
            <div className="action-group">
              <Button
                ref={cancelDeleteButton}
                type="button"
                variant="secondary"
                disabled={deleting}
                onClick={closeDeleteDialog}
              >
                Отказ
              </Button>
              <Button
                type="button"
                variant="destructive"
                disabled={deleting}
                onClick={() => void remove()}
              >
                {deleting ? 'Изтриване…' : 'Изтрий промяната'}
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
