import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { useFeedback, errorCategory, type Feedback } from '../../ui/useFeedback'
import { Button } from '../../ui/Button'
import { useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import {
  deactivateService,
  getService,
  reactivateService,
  updateService,
  type ServiceDetails as ServiceDetailsResponse,
  type UpdateServiceInput,
} from './api'
import { ServiceForm } from './ServiceForm'
import { isAuthenticationRequired, isConcurrentUpdate, safeServiceError } from './errors'
import { backendFieldErrors, type SubmitOutcome } from '../../ui/formValidation'
import {
  SERVICE_BACKEND_FIELDS,
  SERVICE_REJECTED_MESSAGE,
  type ServiceField,
} from './validation'
import { formatServiceDuration, formatServicePrice, serviceStatusPresentation } from './presentation'

type ServiceDetailProps = {
  serviceId: string
  readOnly: boolean
  onAuthenticationRequired: (detail: string) => void
  onBack: () => void
}

export function ServiceDetail({
  serviceId,
  readOnly,
  onAuthenticationRequired,
  onBack,
}: ServiceDetailProps) {
  const [service, setService] = useState<ServiceDetailsResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const {
    feedback: profileFeedback,
    setFeedback: setProfileFeedback,
    beginFeedback: beginProfileFeedback,
  } = useFeedback(serviceId)
  const {
    feedback: lifecycleFeedback,
    setFeedback: setLifecycleFeedback,
    beginFeedback: beginLifecycleFeedback,
  } = useFeedback(serviceId)
  const [editing, setEditing] = useState(false)
  const [updating, setUpdating] = useState(false)
  const [deactivateConfirmation, setDeactivateConfirmation] = useState(false)
  const [lifecycleBusy, setLifecycleBusy] = useState(false)
  const guard = useUnsavedChangesGuard()
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
      const loaded = await getService(serviceId, controller.signal)
      if (!controller.signal.aborted && activeLoad.current === controller) {
        setService(loaded)
        setEditing(false)
      }
    } catch (caught) {
      if (controller.signal.aborted || activeLoad.current !== controller) return
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setLoadError(safeServiceError(caught, 'Данните за услугата не могат да бъдат заредени.'))
    } finally {
      if (activeLoad.current === controller) {
        activeLoad.current = null
        setLoading(false)
      }
    }
  }, [serviceId, onAuthenticationRequired, setProfileFeedback, setLifecycleFeedback])

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

  const update = async (input: UpdateServiceInput): Promise<SubmitOutcome<ServiceField>> => {
    if (updateInProgress.current) return
    updateInProgress.current = true
    setUpdating(true)
    const publish = beginProfileFeedback()
    try {
      const updated = await updateService(serviceId, input)
      if (!publish(null)) return
      setService(updated)
      setEditing(false)
      setProfileFeedback({ kind: 'success', text: 'Промените са запазени.' })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      const fieldErrors = backendFieldErrors(caught, SERVICE_BACKEND_FIELDS)
      if (fieldErrors) return { fieldErrors }
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeServiceError(
          caught,
          'Промените не могат да бъдат запазени.',
          SERVICE_REJECTED_MESSAGE,
        ),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      updateInProgress.current = false
      setUpdating(false)
    }
  }

  const deactivate = async () => {
    if (!service || lifecycleInProgress.current) return
    lifecycleInProgress.current = true
    setLifecycleBusy(true)
    const publish = beginLifecycleFeedback()
    try {
      const updated = await deactivateService(service.id, service.version)
      if (!publish(null)) return
      setService(updated)
      setDeactivateConfirmation(false)
      setLifecycleFeedback({ kind: 'success', text: 'Услугата е деактивирана.' })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setDeactivateConfirmation(false)
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeServiceError(caught, 'Услугата не може да бъде деактивирана.'),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      lifecycleInProgress.current = false
      setLifecycleBusy(false)
    }
  }

  const reactivate = async () => {
    if (!service || lifecycleInProgress.current) return
    lifecycleInProgress.current = true
    setLifecycleBusy(true)
    const publish = beginLifecycleFeedback()
    try {
      const updated = await reactivateService(service.id, service.version)
      if (!publish(null)) return
      setService(updated)
      setLifecycleFeedback({ kind: 'success', text: 'Услугата е активирана отново.' })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeServiceError(caught, 'Услугата не може да бъде активирана отново.'),
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
        <p className="business-list-state">Зареждане на услугата…</p>
      </div>
    )
  }

  if (loadError || !service) {
    return (
      <div className="platform-content">
        <div className="feedback-action-layout">
          <div
            ref={profileError}
            className="status-message status-error"
            role="alert"
            tabIndex={-1}
          >
            <p>{loadError ?? 'Услугата не е намерена.'}</p>
          </div>
          <div className="action-group">
            <Button type="button" variant="secondary" onClick={() => void load()}>
              Зареди отново
            </Button>
            <Button type="button" variant="secondary" onClick={onBack}>
              Обратно към услугите
            </Button>
          </div>
        </div>
      </div>
    )
  }

  const status = serviceStatusPresentation(service.active)

  return (
    <div className="platform-content business-detail">
      <div className="business-page-actions">
        <Button type="button" variant="secondary" onClick={onBack}>
          Обратно към услугите
        </Button>
      </div>

      <header className="business-detail-header">
        <h2>{service.name}</h2>
        <span className={`status-badge status-badge-${status.tone}`}>{status.label}</span>
      </header>

      <div className="business-section">
        <div className="feedback-action-layout">
          {editing ? (
            <ServiceForm
              key={`${service.id}-${service.version}`}
              service={service}
              busy={updating}
              submitLabel="Запази промените"
              onChange={() => {
                if (!profileFeedback?.reload) setProfileFeedback(null)
              }}
              onCancel={() => {
                setEditing(false)
                setProfileFeedback(null)
              }}
              onSubmit={(input) => update(input as UpdateServiceInput)}
            />
          ) : (
            <>
              <dl className="business-details-list">
                <div>
                  <dt>Описание</dt>
                  <dd>{service.description ?? 'Няма описание'}</dd>
                </div>
                <div>
                  <dt>Продължителност</dt>
                  <dd>{formatServiceDuration(service.durationMinutes)}</dd>
                </div>
                <div>
                  <dt>Цена</dt>
                  <dd>{formatServicePrice(service.price)}</dd>
                </div>
              </dl>
              {!readOnly && (
                <Button
                  type="button"
                  onClick={() => {
                    setProfileFeedback(null)
                    setEditing(true)
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
              {service.active && !deactivateConfirmation && (
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
              {!service.active && (
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
                  aria-labelledby="deactivate-confirmation-heading"
                >
                  <h4 id="deactivate-confirmation-heading">Потвърдете деактивирането</h4>
                  <p>
                    Услугата „{service.name}“ няма да бъде предлагана за нови резервации.
                  </p>
                  <div className="action-group">
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
                    <Button
                      type="button"
                      variant="destructive"
                      disabled={lifecycleBusy}
                      onClick={() => void deactivate()}
                    >
                      {lifecycleBusy ? 'Запазване…' : 'Потвърди деактивирането'}
                    </Button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
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
