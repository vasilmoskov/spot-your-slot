import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { useFeedback, errorCategory, type Feedback } from '../../ui/useFeedback'
import { Button } from '../../ui/Button'
import { useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import {
  deactivateStaffMember,
  getStaffMember,
  reactivateStaffMember,
  updateStaffMember,
  type StaffMemberDetails as StaffMemberDetailsResponse,
  type UpdateStaffMemberInput,
} from './api'
import { StaffForm } from './StaffForm'
import { StaffServiceAssignments } from './StaffServiceAssignments'
import { isAuthenticationRequired, isConcurrentUpdate, safeStaffError } from './errors'
import { backendFieldErrors, type SubmitOutcome } from '../../ui/formValidation'
import { STAFF_BACKEND_FIELDS, STAFF_REJECTED_MESSAGE, type StaffField } from './validation'
import { formatStaffPhone, staffStatusPresentation } from './presentation'

type StaffDetailProps = {
  staffMemberId: string
  readOnly: boolean
  onAuthenticationRequired: (detail: string) => void
  onBack: () => void
}

export function StaffDetail({
  staffMemberId,
  readOnly,
  onAuthenticationRequired,
  onBack,
}: StaffDetailProps) {
  const [staffMember, setStaffMember] = useState<StaffMemberDetailsResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const {
    feedback: profileFeedback,
    setFeedback: setProfileFeedback,
    beginFeedback: beginProfileFeedback,
  } = useFeedback(staffMemberId)
  const {
    feedback: lifecycleFeedback,
    setFeedback: setLifecycleFeedback,
    beginFeedback: beginLifecycleFeedback,
  } = useFeedback(staffMemberId)
  // The Staff profile editor and the Service-assignment editor are mutually
  // exclusive: at most one of them may be open (and therefore dirty) at a
  // time. This keeps the shared UnsavedChangesGuard's single dirty
  // registration coherent without changing its contract — switching editors
  // always goes through `guard.guard`, so a dirty editor is confirmed or
  // discarded before the other one opens.
  const [editingSection, setEditingSection] = useState<'none' | 'profile' | 'assignments'>('none')
  const guard = useUnsavedChangesGuard()
  const [updating, setUpdating] = useState(false)
  const [deactivateConfirmation, setDeactivateConfirmation] = useState(false)
  const [lifecycleBusy, setLifecycleBusy] = useState(false)
  const activeLoad = useRef<AbortController | null>(null)
  const updateInProgress = useRef(false)
  const lifecycleInProgress = useRef(false)
  const cancelDeactivationButton = useRef<HTMLButtonElement>(null)
  const profileError = useRef<HTMLDivElement>(null)
  const lifecycleError = useRef<HTMLDivElement>(null)

  const load = useCallback(async () => {
    activeLoad.current?.abort()
    const controller = new AbortController()
    activeLoad.current = controller
    setLoading(true)
    setLoadError(null)
    setProfileFeedback(null)
    setLifecycleFeedback(null)
    try {
      const loaded = await getStaffMember(staffMemberId, controller.signal)
      if (!controller.signal.aborted && activeLoad.current === controller) {
        setStaffMember(loaded)
        setEditingSection('none')
      }
    } catch (caught) {
      if (controller.signal.aborted || activeLoad.current !== controller) return
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setLoadError(
        safeStaffError(caught, 'Данните за члена на екипа не могат да бъдат заредени.'),
      )
    } finally {
      if (activeLoad.current === controller) {
        activeLoad.current = null
        setLoading(false)
      }
    }
  }, [staffMemberId, onAuthenticationRequired, setProfileFeedback, setLifecycleFeedback])

  useEffect(() => {
    void load()
    return () => {
      const controller = activeLoad.current
      activeLoad.current = null
      controller?.abort()
    }
  }, [load])

  useLayoutEffect(() => {
    if (deactivateConfirmation) {
      cancelDeactivationButton.current?.focus()
    }
  }, [deactivateConfirmation])

  useLayoutEffect(() => {
    if (profileFeedback?.kind === 'error') {
      profileError.current?.focus()
    }
  }, [profileFeedback])

  useLayoutEffect(() => {
    if (lifecycleFeedback?.kind === 'error') {
      lifecycleError.current?.focus()
    }
  }, [lifecycleFeedback])

  const handleAssignmentVersionChange = useCallback((version: number) => {
    setStaffMember((current) => (current ? { ...current, version } : current))
  }, [])

  const update = async (input: UpdateStaffMemberInput): Promise<SubmitOutcome<StaffField>> => {
    if (updateInProgress.current) return
    updateInProgress.current = true
    setUpdating(true)
    const publish = beginProfileFeedback()
    try {
      const updated = await updateStaffMember(staffMemberId, input)
      if (!publish(null)) return
      setStaffMember(updated)
      setEditingSection('none')
      setProfileFeedback({ kind: 'success', text: 'Промените са запазени.' })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      const fieldErrors = backendFieldErrors(caught, STAFF_BACKEND_FIELDS)
      if (fieldErrors) return { fieldErrors }
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeStaffError(
          caught,
          'Промените не могат да бъдат запазени.',
          STAFF_REJECTED_MESSAGE,
        ),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      updateInProgress.current = false
      setUpdating(false)
    }
  }

  const deactivate = async () => {
    if (!staffMember || lifecycleInProgress.current) return
    lifecycleInProgress.current = true
    setLifecycleBusy(true)
    const publish = beginLifecycleFeedback()
    try {
      const updated = await deactivateStaffMember(staffMember.id, staffMember.version)
      if (!publish(null)) return
      setStaffMember(updated)
      setDeactivateConfirmation(false)
      setLifecycleFeedback({ kind: 'success', text: 'Членът на екипа е деактивиран.' })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setDeactivateConfirmation(false)
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeStaffError(caught, 'Членът на екипа не може да бъде деактивиран.'),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      lifecycleInProgress.current = false
      setLifecycleBusy(false)
    }
  }

  const reactivate = async () => {
    if (!staffMember || lifecycleInProgress.current) return
    lifecycleInProgress.current = true
    setLifecycleBusy(true)
    const publish = beginLifecycleFeedback()
    try {
      const updated = await reactivateStaffMember(staffMember.id, staffMember.version)
      if (!publish(null)) return
      setStaffMember(updated)
      setLifecycleFeedback({ kind: 'success', text: 'Членът на екипа е активиран отново.' })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeStaffError(caught, 'Членът на екипа не може да бъде активиран отново.'),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      lifecycleInProgress.current = false
      setLifecycleBusy(false)
    }
  }

  // Reloading discards an open, possibly dirty editor, so it goes through the
  // shared unsaved-changes guard like every other discarding transition.
  const reloadGuarded = () => guard.guard(() => void load())

  if (loading) {
    return (
      <div className="platform-content" aria-live="polite" aria-busy="true">
        <p className="business-list-state">Зареждане на члена на екипа…</p>
      </div>
    )
  }

  if (loadError || !staffMember) {
    return (
      <div className="platform-content">
        <div className="feedback-action-layout">
          <div
            ref={profileError}
            className="status-message status-error"
            role="alert"
            tabIndex={-1}
          >
            <p>{loadError ?? 'Членът на екипа не е намерен.'}</p>
          </div>
          <div className="action-group">
            <Button type="button" variant="secondary" onClick={() => void load()}>
              Зареди отново
            </Button>
            <Button type="button" variant="secondary" onClick={onBack}>
              Обратно към екипа
            </Button>
          </div>
        </div>
      </div>
    )
  }

  const status = staffStatusPresentation(staffMember.active)

  return (
    <div className="platform-content business-detail">
      <div className="business-page-actions">
        <Button type="button" variant="secondary" onClick={onBack}>
          Обратно към екипа
        </Button>
      </div>

      <header className="business-detail-header">
        <h2>{staffMember.displayName}</h2>
        <span className={`status-badge status-badge-${status.tone}`}>{status.label}</span>
      </header>

      <div className="business-section">
        <div className="feedback-action-layout">
          {editingSection === 'profile' ? (
            <StaffForm
              key={`${staffMember.id}-${staffMember.version}`}
              staffMember={staffMember}
              busy={updating}
              submitLabel="Запази промените"
              onChange={() => {
                if (!profileFeedback?.reload) setProfileFeedback(null)
              }}
              onCancel={() => {
                setEditingSection('none')
                setProfileFeedback(null)
              }}
              onSubmit={(input) => update(input as UpdateStaffMemberInput)}
            />
          ) : (
            <>
              <dl className="business-details-list">
                <div>
                  <dt>Имейл за връзка</dt>
                  <dd>{staffMember.contactEmail ?? 'Няма посочен имейл'}</dd>
                </div>
                <div>
                  <dt>Телефон за връзка</dt>
                  <dd>
                    {staffMember.contactPhone
                      ? formatStaffPhone(staffMember.contactPhone)
                      : 'Няма посочен телефон'}
                  </dd>
                </div>
              </dl>
              {!readOnly && (
                <Button
                  type="button"
                  onClick={() => {
                    guard.guard(() => {
                      setProfileFeedback(null)
                      setEditingSection('profile')
                    })
                  }}
                >
                  Редактирай
                </Button>
              )}
            </>
          )}
          <LocalFeedback feedback={profileFeedback} errorRef={profileError} />
          <FeedbackReloadControl feedback={profileFeedback} onReload={reloadGuarded} />
        </div>
      </div>

      {!readOnly && (
        <div className="business-section">
          <div className="feedback-action-layout">
            <LocalFeedback feedback={lifecycleFeedback} errorRef={lifecycleError} />
            <div className="feedback-action-controls">
              <FeedbackReloadControl feedback={lifecycleFeedback} onReload={reloadGuarded} />
              {staffMember.active && !deactivateConfirmation && (
                <Button
                  type="button"
                  variant="destructive"
                  onClick={() => {
                    setLifecycleFeedback(null)
                    setDeactivateConfirmation(true)
                  }}
                >
                  Деактивирай
                </Button>
              )}
              {!staffMember.active && (
                <Button
                  type="button"
                  disabled={lifecycleBusy}
                  onClick={() => void reactivate()}
                >
                  {lifecycleBusy ? 'Запазване…' : 'Активирай отново'}
                </Button>
              )}
              {deactivateConfirmation && (
                <div
                  className="confirmation-panel"
                  role="alertdialog"
                  aria-labelledby="deactivate-staff-confirmation-heading"
                >
                  <h4 id="deactivate-staff-confirmation-heading">Потвърдете деактивирането</h4>
                  <p>
                    Членът на екипа „{staffMember.displayName}“ няма да бъде предлаган за нови
                    резервации.
                  </p>
                  <div className="action-group">
                    <Button
                      type="button"
                      variant="destructive"
                      disabled={lifecycleBusy}
                      onClick={() => void deactivate()}
                    >
                      {lifecycleBusy ? 'Запазване…' : 'Потвърди деактивирането'}
                    </Button>
                    <Button
                      ref={cancelDeactivationButton}
                      type="button"
                      variant="secondary"
                      disabled={lifecycleBusy}
                      onClick={() => {
                        setDeactivateConfirmation(false)
                        setLifecycleFeedback(null)
                      }}
                    >
                      Отказ
                    </Button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      <div className="business-section">
        <h3>Услуги</h3>
        <StaffServiceAssignments
          staffMemberId={staffMember.id}
          staffMemberVersion={staffMember.version}
          readOnly={readOnly}
          editing={editingSection === 'assignments'}
          onRequestEdit={() => guard.guard(() => setEditingSection('assignments'))}
          onEditingDone={() => setEditingSection('none')}
          onAuthenticationRequired={onAuthenticationRequired}
          onVersionChange={handleAssignmentVersionChange}
        />
      </div>
    </div>
  )
}

function LocalFeedback({
  feedback,
  errorRef,
}: {
  feedback: Feedback | null
  errorRef: RefObject<HTMLDivElement | null>
}) {
  if (!feedback) return null

  return (
    <div
      ref={feedback.kind === 'error' ? errorRef : undefined}
      className={`status-message status-${feedback.kind}`}
      role={feedback.kind === 'error' ? 'alert' : 'status'}
      aria-live={feedback.kind === 'success' ? 'polite' : undefined}
      tabIndex={feedback.kind === 'error' ? -1 : undefined}
    >
      <p>{feedback.text}</p>
    </div>
  )
}

function FeedbackReloadControl({
  feedback,
  onReload,
}: {
  feedback: Feedback | null
  onReload: () => void
}) {
  if (!feedback?.reload) return null

  return (
    <Button type="button" variant="secondary" onClick={onReload}>
      Зареди актуалните данни
    </Button>
  )
}
