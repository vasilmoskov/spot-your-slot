import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type InvalidEvent,
  type RefObject,
} from 'react'
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

type Feedback = {
  kind: 'error' | 'success'
  text: string
  reload?: boolean
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
  const [profileFeedback, setProfileFeedback] = useState<Feedback | null>(null)
  const [invitationFeedback, setInvitationFeedback] = useState<Feedback | null>(null)
  const [lifecycleFeedback, setLifecycleFeedback] = useState<Feedback | null>(null)
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
  }, [businessId, onAuthenticationRequired])

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
    setProfileFeedback(null)
    try {
      const updated = await updateBusiness(businessId, input)
      setBusiness(updated)
      setEditing(false)
      setProfileFeedback({ kind: 'success', text: 'Промените са запазени.' })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setProfileFeedback({
        kind: 'error',
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
    setLifecycleFeedback(null)
    const lifecycle = LIFECYCLE_PRESENTATION[business.status]
    try {
      const updated = await changeBusinessStatus(
        business.id,
        lifecycle.action,
        business.version,
      )
      setBusiness(updated)
      setLifecycleConfirmation(false)
      setLifecycleFeedback({ kind: 'success', text: lifecycle.successText })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setLifecycleConfirmation(false)
      setLifecycleFeedback({
        kind: 'error',
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
    setInvitationFeedback(null)
    try {
      await inviteBusinessOwner(businessId, email)
      setLastInvitedEmail(email.toLowerCase())
      setResendEmail(null)
      setInvitationFeedback({
        kind: 'success',
        text: 'Заявката за покана е приета.',
      })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setResendEmail(null)
      setInvitationFeedback({
        kind: 'error',
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
        <div
          ref={profileError}
          className="status-message status-error"
          role="alert"
          tabIndex={-1}
        >
          <p>{loadError ?? 'Бизнесът не е намерен.'}</p>
          <div className="action-group">
            <button
              type="button"
              className="secondary-button"
              onClick={() => void load()}
            >
              Зареди отново
            </button>
            <button type="button" className="secondary-button" onClick={onBack}>
              Обратно към бизнесите
            </button>
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
        <button type="button" className="secondary-button" onClick={onBack}>
          Обратно към бизнесите
        </button>
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
        {editing ? (
          <BusinessForm
            key={`${business.id}-${business.version}`}
            business={business}
            busy={updating}
            submitLabel="Запази промените"
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
            <button
              type="button"
              onClick={() => {
                setProfileOpen(true)
                setEditing(true)
              }}
            >
              Редактирай
            </button>
          </>
        )}
        <LocalFeedback
          feedback={profileFeedback}
          errorRef={profileError}
          onReload={load}
        />
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
          <form className="invitation-form" onSubmit={submitInvitation}>
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
            <button disabled={invitationBusy}>
              {invitationBusy ? 'Изпращане…' : 'Изпрати покана'}
            </button>
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
                <button
                  ref={confirmationButton}
                  type="button"
                  disabled={invitationBusy}
                  onClick={() => void sendInvitation(resendEmail)}
                >
                  Потвърди изпращането
                </button>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={invitationBusy}
                  onClick={() => setResendEmail(null)}
                >
                  Отказ
                </button>
              </div>
            </div>
          )}
          <LocalFeedback feedback={invitationFeedback} errorRef={invitationError} />
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
        <LocalFeedback
          feedback={lifecycleFeedback}
          errorRef={lifecycleError}
          onReload={load}
        />
        {!lifecycleConfirmation && (
          <button
            type="button"
            className={lifecycle.danger ? 'danger-button' : undefined}
            onClick={() => {
              setLifecycleOpen(true)
              setLifecycleConfirmation(true)
            }}
          >
            {lifecycle.actionLabel}
          </button>
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
              <button
                ref={confirmationButton}
                type="button"
                className={lifecycle.danger ? 'danger-button' : undefined}
                disabled={lifecycleBusy}
                onClick={() => void changeStatus()}
              >
                {lifecycleBusy ? 'Запазване…' : lifecycle.confirmationLabel}
              </button>
              <button
                type="button"
                className="secondary-button"
                disabled={lifecycleBusy}
                onClick={() => setLifecycleConfirmation(false)}
              >
                Отказ
              </button>
            </div>
          </div>
        )}
      </details>
    </div>
  )
}

function LocalFeedback({
  feedback,
  errorRef,
  onReload,
}: {
  feedback: Feedback | null
  errorRef: RefObject<HTMLDivElement | null>
  onReload?: () => Promise<void>
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
      {feedback.reload && onReload && (
        <div className="action-group">
          <button
            type="button"
            className="secondary-button"
            onClick={() => void onReload()}
          >
            Зареди актуалните данни
          </button>
        </div>
      )}
    </div>
  )
}
