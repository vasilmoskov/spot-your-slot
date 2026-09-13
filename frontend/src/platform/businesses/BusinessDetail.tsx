import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type InvalidEvent,
  type RefObject,
} from 'react'
import { useFeedback, errorCategory, type Feedback } from '../../ui/useFeedback'
import { Button } from '../../ui/Button'
import {
  changeBusinessStatus,
  getBusiness,
  inviteBusinessOwner,
  updateBusiness,
  type BusinessDetails,
  type LifecycleAction,
  type UpdateBusinessInput,
} from './api'
import { BusinessForm } from './BusinessForm'
import {
  isAuthenticationRequired,
  isConcurrentUpdate,
  safeBusinessError,
} from './errors'
import {
  BUSINESS_STATUS_PRESENTATION,
  businessTypeLabel,
} from './presentation'

type BusinessDetailProps = {
  businessId: string
  onAuthenticationRequired: (detail: string) => void
  onBack: () => void
}

type LifecyclePresentation = {
  action: LifecycleAction
  actionLabel: string
  confirmationHeading: string
  confirmationText: string
  confirmationLabel: string
  successText: string
  danger?: boolean
}

const LIFECYCLE_PRESENTATION: Record<
  BusinessDetails['status'],
  LifecyclePresentation
> = {
  DRAFT: {
    action: 'activate',
    actionLabel: 'Активирай',
    confirmationHeading: 'Потвърдете активирането',
    confirmationText:
      'Бизнесът може да бъде активиран, след като поканеният собственик приеме поканата.',
    confirmationLabel: 'Потвърди активирането',
    successText: 'Бизнесът е активиран.',
  },
  ACTIVE: {
    action: 'suspend',
    actionLabel: 'Спри временно',
    confirmationHeading: 'Потвърдете временното спиране',
    confirmationText:
      'Публичното записване ще бъде недостъпно, докато бизнесът не бъде активиран отново.',
    confirmationLabel: 'Потвърди спирането',
    successText: 'Бизнесът е временно спрян.',
    danger: true,
  },
  SUSPENDED: {
    action: 'reactivate',
    actionLabel: 'Активирай отново',
    confirmationHeading: 'Потвърдете повторното активиране',
    confirmationText: 'Публичното записване ще бъде достъпно отново.',
    confirmationLabel: 'Потвърди активирането',
    successText: 'Бизнесът е активиран отново.',
  },
}

export function BusinessDetail({
  businessId,
  onAuthenticationRequired,
  onBack,
}: BusinessDetailProps) {
  const [business, setBusiness] = useState<BusinessDetails | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const {
    feedback: profileFeedback,
    setFeedback: setProfileFeedback,
    beginFeedback: beginProfileFeedback,
  } = useFeedback(businessId)
  const {
    feedback: invitationFeedback,
    setFeedback: setInvitationFeedback,
    beginFeedback: beginInvitationFeedback,
  } = useFeedback(businessId)
  const {
    feedback: lifecycleFeedback,
    setFeedback: setLifecycleFeedback,
    beginFeedback: beginLifecycleFeedback,
  } = useFeedback(businessId)
  const [editing, setEditing] = useState(false)
  const [updating, setUpdating] = useState(false)
  const [lifecycleConfirmation, setLifecycleConfirmation] = useState(false)
  const [lifecycleBusy, setLifecycleBusy] = useState(false)
  const [invitationBusy, setInvitationBusy] = useState(false)
  const [lastInvitedEmail, setLastInvitedEmail] = useState<string | null>(null)
  const [resendEmail, setResendEmail] = useState<string | null>(null)
  const [profileOpen, setProfileOpen] = useState(false)
  const [invitationOpen, setInvitationOpen] = useState(false)
  const [lifecycleOpen, setLifecycleOpen] = useState(false)
  const activeLoad = useRef<AbortController | null>(null)
  const updateInProgress = useRef(false)
  const lifecycleInProgress = useRef(false)
  const invitationInProgress = useRef(false)
  const confirmationButton = useRef<HTMLButtonElement>(null)
  const profileSection = useRef<HTMLDetailsElement>(null)
  const invitationSection = useRef<HTMLDetailsElement>(null)
  const lifecycleSection = useRef<HTMLDetailsElement>(null)
  const profileError = useRef<HTMLDivElement>(null)
  const invitationError = useRef<HTMLDivElement>(null)
  const lifecycleError = useRef<HTMLDivElement>(null)

  const load = useCallback(async () => {
    activeLoad.current?.abort()
    const controller = new AbortController()
    activeLoad.current = controller
    setLoading(true)
    setLoadError(null)
    setProfileFeedback(null)
    setInvitationFeedback(null)
    setLifecycleFeedback(null)
    try {
      const loaded = await getBusiness(businessId, controller.signal)
      if (!controller.signal.aborted && activeLoad.current === controller) {
        setBusiness(loaded)
        setEditing(false)
        setProfileOpen(false)
        setInvitationOpen(false)
        setLifecycleOpen(false)
      }
    } catch (caught) {
      if (controller.signal.aborted || activeLoad.current !== controller) return
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setLoadError(
        safeBusinessError(caught, 'Данните за бизнеса не могат да бъдат заредени.'),
      )
    } finally {
      if (activeLoad.current === controller) {
        activeLoad.current = null
        setLoading(false)
      }
    }
  }, [businessId, onAuthenticationRequired, setProfileFeedback, setInvitationFeedback, setLifecycleFeedback])

  useEffect(() => {
    void load()
    return () => {
      const controller = activeLoad.current
      activeLoad.current = null
      controller?.abort()
    }
  }, [load])

  useEffect(() => {
    if (lifecycleConfirmation || resendEmail) {
      confirmationButton.current?.focus()
    }
  }, [lifecycleConfirmation, resendEmail])

  useEffect(() => {
    if (profileFeedback?.kind === 'error') {
      if (profileSection.current) profileSection.current.open = true
      setProfileOpen(true)
      profileError.current?.focus()
    }
  }, [profileFeedback])

  useEffect(() => {
    if (invitationFeedback?.kind === 'error') {
      if (invitationSection.current) invitationSection.current.open = true
      setInvitationOpen(true)
      invitationError.current?.focus()
    }
  }, [invitationFeedback])

  useEffect(() => {
    if (lifecycleFeedback?.kind === 'error') {
      if (lifecycleSection.current) lifecycleSection.current.open = true
      setLifecycleOpen(true)
      lifecycleError.current?.focus()
    }
  }, [lifecycleFeedback])

  const update = async (input: UpdateBusinessInput) => {
    if (updateInProgress.current) return
    updateInProgress.current = true
    setUpdating(true)
    const publish = beginProfileFeedback()
    try {
      const updated = await updateBusiness(businessId, input)
      if (!publish(null)) return
      setBusiness(updated)
      setEditing(false)
      setProfileFeedback({ kind: 'success', text: 'Промените са запазени.' })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeBusinessError(caught, 'Промените не могат да бъдат запазени.'),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      updateInProgress.current = false
      setUpdating(false)
    }
  }

  const changeStatus = async () => {
    if (!business || lifecycleInProgress.current) return
    lifecycleInProgress.current = true
    setLifecycleBusy(true)
    const publish = beginLifecycleFeedback()
    const lifecycle = LIFECYCLE_PRESENTATION[business.status]
    try {
      const updated = await changeBusinessStatus(
        business.id,
        lifecycle.action,
        business.version,
      )
      if (!publish(null)) return
      setBusiness(updated)
      setLifecycleConfirmation(false)
      setLifecycleFeedback({ kind: 'success', text: lifecycle.successText })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setLifecycleConfirmation(false)
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeBusinessError(caught, 'Статусът не може да бъде променен.'),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      lifecycleInProgress.current = false
      setLifecycleBusy(false)
    }
  }

  const sendInvitation = async (email: string) => {
    if (invitationInProgress.current) return
    invitationInProgress.current = true
    setInvitationBusy(true)
    const publish = beginInvitationFeedback()
    try {
      await inviteBusinessOwner(businessId, email)
      if (!publish(null)) return
      setLastInvitedEmail(email.toLowerCase())
      setResendEmail(null)
      setInvitationFeedback({
        kind: 'success',
        text: 'Заявката за покана е изпратена.',
      })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setResendEmail(null)
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeBusinessError(caught, 'Заявката за покана не може да бъде изпратена.'),
      })
    } finally {
      invitationInProgress.current = false
      setInvitationBusy(false)
    }
  }

  const submitInvitation = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (invitationInProgress.current) return
    setInvitationFeedback(null)
    const data = new FormData(event.currentTarget)
    const email = String(data.get('ownerEmail') ?? '').trim()
    if (lastInvitedEmail === email.toLowerCase()) {
      setInvitationOpen(true)
      setResendEmail(email)
      return
    }
    void sendInvitation(email)
  }

  if (loading) {
    return (
      <div className="platform-content" aria-live="polite" aria-busy="true">
        <p className="business-list-state">Зареждане на бизнеса…</p>
      </div>
    )
  }

  if (loadError || !business) {
    return (
      <div className="platform-content">
        <div className="feedback-action-layout">
          <div
            ref={profileError}
            className="status-message status-error"
            role="alert"
            tabIndex={-1}
          >
            <p>{loadError ?? 'Бизнесът не е намерен.'}</p>
          </div>
          <div className="action-group">
            <Button
              type="button"
              variant="secondary"
              onClick={() => void load()}
            >
              Зареди отново
            </Button>
            <Button type="button" variant="secondary" onClick={onBack}>
              Обратно към бизнесите
            </Button>
          </div>
        </div>
      </div>
    )
  }

  const status = BUSINESS_STATUS_PRESENTATION[business.status]
  const lifecycle = LIFECYCLE_PRESENTATION[business.status]

  return (
    <div className="platform-content business-detail">
      <div className="business-page-actions">
        <Button type="button" variant="secondary" onClick={onBack}>
          Обратно към бизнесите
        </Button>
      </div>

      <header className="business-detail-header">
        <div>
          <p className="eyebrow">{business.slug}</p>
          <h2>{business.displayName}</h2>
          <p className="business-detail-summary">
            {businessTypeLabel(business.businessType)}
          </p>
        </div>
        <span className={`status-badge status-badge-${status.tone}`}>
          {status.label}
        </span>
      </header>

      <details
        ref={profileSection}
        className="business-section"
        open={profileOpen}
        onToggle={(event) => setProfileOpen(event.currentTarget.open)}
      >
        <summary>
          <span>Данни за бизнеса</span>
          <span className="section-summary">{business.displayName}</span>
        </summary>
        <div className="feedback-action-layout">
          {editing ? (
            <BusinessForm
              key={`${business.id}-${business.version}`}
              business={business}
              busy={updating}
              submitLabel="Запази промените"
              onChange={() => {
                if (!profileFeedback?.reload) setProfileFeedback(null)
              }}
              onCancel={() => {
                setEditing(false)
                setProfileFeedback(null)
              }}
              onSubmit={(input) => void update(input as UpdateBusinessInput)}
            />
          ) : (
            <>
              <div className="business-information-columns business-details-columns">
                <dl className="business-details-list">
                  <div>
                    <dt>Име на бизнеса</dt>
                    <dd>{business.displayName}</dd>
                  </div>
                  <div>
                    <dt>Идентификатор в уеб адреса</dt>
                    <dd>{business.slug}</dd>
                  </div>
                  <div>
                    <dt>Дейност</dt>
                    <dd>{businessTypeLabel(business.businessType)}</dd>
                  </div>
                  <div>
                    <dt>Телефон (по избор)</dt>
                    <dd>{business.phone ?? 'Не е посочен'}</dd>
                  </div>
                  <div>
                    <dt>Имейл за контакт (по избор)</dt>
                    <dd>{business.contactEmail ?? 'Не е посочен'}</dd>
                  </div>
                </dl>
                <dl className="business-details-list">
                  <div>
                    <dt>Улица (по избор)</dt>
                    <dd>{business.street ?? 'Не е посочена'}</dd>
                  </div>
                  <div>
                    <dt>Номер (по избор)</dt>
                    <dd>{business.streetNumber ?? 'Не е посочен'}</dd>
                  </div>
                  <div>
                    <dt>Пощенски код (по избор)</dt>
                    <dd>{business.postalCode ?? 'Не е посочен'}</dd>
                  </div>
                  <div>
                    <dt>Град (по избор)</dt>
                    <dd>{business.city ?? 'Не е посочен'}</dd>
                  </div>
                  <div>
                    <dt>Допълнителни указания (по избор)</dt>
                    <dd>{business.addressDetails ?? 'Не са посочени'}</dd>
                  </div>
                </dl>
              </div>
              <dl className="business-details-list business-description-details">
                <div>
                  <dt>Описание (по избор)</dt>
                  <dd>{business.description ?? 'Няма описание'}</dd>
                </div>
              </dl>
              <Button
                type="button"
                onClick={() => {
                  setProfileFeedback(null)
                  setProfileOpen(true)
                  setEditing(true)
                }}
              >
                Редактирай
              </Button>
            </>
          )}
          <LocalFeedback feedback={profileFeedback} errorRef={profileError} />
          <FeedbackReloadControl feedback={profileFeedback} onReload={load} />
        </div>
      </details>

      {business.status === 'DRAFT' && (
        <details
          ref={invitationSection}
          className="business-section"
          open={invitationOpen}
          onToggle={(event) => setInvitationOpen(event.currentTarget.open)}
        >
          <summary>
            <span>Покана</span>
            <span className="section-summary">Поканете собственик</span>
          </summary>
          <div className="feedback-action-layout">
            <form
              className="invitation-form"
              onChange={() => setInvitationFeedback(null)}
              onSubmit={submitInvitation}
            >
              <label>
                Имейл на собственика
                <input
                  name="ownerEmail"
                  type="email"
                  required
                  maxLength={320}
                  onInvalid={(event: InvalidEvent<HTMLInputElement>) => {
                    event.currentTarget.setCustomValidity('')
                    event.currentTarget.setCustomValidity(
                      event.currentTarget.validity.valueMissing
                        ? 'Моля, въведете имейл адрес.'
                        : 'Моля, въведете валиден имейл адрес.',
                    )
                  }}
                  onInput={(event) => event.currentTarget.setCustomValidity('')}
                />
              </label>
              <Button disabled={invitationBusy}>
                {invitationBusy ? 'Изпращане…' : 'Изпрати покана'}
              </Button>
            </form>
            {resendEmail && (
              <div
                className="confirmation-panel"
                role="alertdialog"
                aria-labelledby="resend-confirmation-heading"
              >
                <h4 id="resend-confirmation-heading">Изпращане на нова покана</h4>
                <p>
                  Предишната активна покана за този имейл ще бъде заменена.
                </p>
                <div className="action-group">
                  <Button
                    ref={confirmationButton}
                    type="button"
                    disabled={invitationBusy}
                    onClick={() => void sendInvitation(resendEmail)}
                  >
                    Потвърди изпращането
                  </Button>
                  <Button
                    type="button"
                    variant="secondary"
                    disabled={invitationBusy}
                    onClick={() => {
                      setResendEmail(null)
                      setInvitationFeedback(null)
                    }}
                  >
                    Отказ
                  </Button>
                </div>
              </div>
            )}
            <LocalFeedback feedback={invitationFeedback} errorRef={invitationError} />
          </div>
        </details>
      )}

      <details
        ref={lifecycleSection}
        className="business-section"
        open={lifecycleOpen}
        onToggle={(event) => setLifecycleOpen(event.currentTarget.open)}
      >
        <summary>
          <span>Активиране</span>
          <span className="section-summary">{status.label}</span>
        </summary>
        <p className="section-introduction">
          Текущ статус: <strong>{status.label}</strong>
        </p>
        {business.status === 'DRAFT' && (
          <>
            <p>
              Бизнесът може да бъде активиран, след като поканеният собственик
              приеме поканата.
            </p>
            <ol className="activation-steps">
              <li>Изпратете покана до собственика.</li>
              <li>Собственикът приема поканата.</li>
              <li>Активирайте бизнеса.</li>
            </ol>
          </>
        )}
        <div className="feedback-action-layout">
          <LocalFeedback
            feedback={lifecycleFeedback}
            errorRef={lifecycleError}
            className="lifecycle-feedback"
          />
          <div className="feedback-action-controls">
            <FeedbackReloadControl feedback={lifecycleFeedback} onReload={load} />
            {!lifecycleConfirmation && (
              <Button
                type="button"
                variant={lifecycle.danger ? 'destructive' : 'primary'}
                onClick={() => {
                  setLifecycleFeedback(null)
                  setLifecycleOpen(true)
                  setLifecycleConfirmation(true)
                }}
              >
                {lifecycle.actionLabel}
              </Button>
            )}
            {lifecycleConfirmation && (
              <div
                className="confirmation-panel"
                role="alertdialog"
                aria-labelledby="lifecycle-confirmation-heading"
              >
                <h4 id="lifecycle-confirmation-heading">
                  {lifecycle.confirmationHeading}
                </h4>
                <p>{lifecycle.confirmationText}</p>
                <div className="action-group">
                  <Button
                    ref={confirmationButton}
                    type="button"
                    variant={lifecycle.danger ? 'destructive' : 'primary'}
                    disabled={lifecycleBusy}
                    onClick={() => void changeStatus()}
                  >
                    {lifecycleBusy ? 'Запазване…' : lifecycle.confirmationLabel}
                  </Button>
                  <Button
                    type="button"
                    variant="secondary"
                    disabled={lifecycleBusy}
                    onClick={() => {
                      setLifecycleConfirmation(false)
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
      </details>
    </div>
  )
}

function LocalFeedback({
  feedback,
  errorRef,
  className,
}: {
  feedback: Feedback | null
  errorRef: RefObject<HTMLDivElement | null>
  className?: string
}) {
  if (!feedback) return null

  return (
    <div
      ref={feedback.kind === 'error' ? errorRef : undefined}
      className={
        `status-message status-${feedback.kind}${className ? ` ${className}` : ''}`
      }
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
  onReload: () => Promise<void>
}) {
  if (!feedback?.reload) return null

  return (
    <Button
      type="button"
      variant="secondary"
      onClick={() => void onReload()}
    >
      Зареди актуалните данни
    </Button>
  )
}
