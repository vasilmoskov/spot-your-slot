import { FormEvent, useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import { useFeedback, errorCategory, type Feedback, type FeedbackAttempt } from './ui/useFeedback'
import { Button } from './ui/Button'
import {
  UnsavedChangesGuardProvider,
  useGuardedFormState,
  useUnsavedChangesGuard,
} from './ui/UnsavedChangesGuard'
import { ApiError, request, type Session } from './identity/api'
import {
  PROFILE_ROUTE,
  PLATFORM_BUSINESSES_ROUTE,
  PLATFORM_BUSINESS_NEW_ROUTE,
  BUSINESS_SERVICES_ROUTE,
  isBusinessOwnerRoute,
  isPlatformRoute,
  pushRoute,
  readAuthenticatedRoute,
  readIdentityPage,
  replaceRoute,
  routeHref,
  subscribeToNavigation,
  type AuthenticatedRoute,
  type IdentityPage,
} from './navigation'
import { PlatformAdminShell } from './platform/PlatformAdminShell'
import { BusinessList } from './platform/businesses/BusinessList'
import { BusinessCreate } from './platform/businesses/BusinessCreate'
import { BusinessDetail } from './platform/businesses/BusinessDetail'
import { BusinessOwnerShell } from './business/BusinessOwnerShell'
import { ServiceList } from './business/services/ServiceList'
import { ServiceCreate } from './business/services/ServiceCreate'
import { ServiceDetail } from './business/services/ServiceDetail'

const safeErrorDetail = (error: unknown): string =>
  error instanceof ApiError ? error.detail : 'Възникна грешка. Опитайте отново.'

const invitationErrorDetail = (error: unknown): string =>
  error instanceof ApiError && error.code === 'INVITATION_INVALID'
    ? 'Поканата е невалидна, изтекла или вече е използвана. Поискайте нова покана.'
    : error instanceof ApiError && error.code === 'INVITATION_CREDENTIAL_MISMATCH'
      ? 'Паролата не съвпада със съществуващия профил за този имейл.'
      : safeErrorDetail(error)

export function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [page, setPage] = useState<IdentityPage>(readIdentityPage())
  const { feedback, setFeedback, beginFeedback } = useFeedback(page)
  const [busy, setBusy] = useState(false)
  const [invitationAccepted, setInvitationAccepted] = useState(false)

  useEffect(() => {
    request<Session>('/api/auth/session').then(setSession).catch(() => undefined)
  }, [])

  useEffect(
    () =>
      subscribeToNavigation(() => {
        if (!window.location.hash) {
          setPage(readIdentityPage())
          setFeedback(null)
          setInvitationAccepted(false)
        }
      }),
    [setFeedback],
  )

  const navigate = (next: IdentityPage) => {
    setPage(next)
    setFeedback(null)
    setInvitationAccepted(false)
    history.pushState(
      {},
      '',
      next === 'login' ? '/' : `/${next === 'forgot' ? 'forgot-password' : next}`,
    )
  }

  const submit = async (
    event: FormEvent<HTMLFormElement>,
    path: string,
    success: string,
    bodyFactory: ((data: FormData) => Record<string, FormDataEntryValue>) | null = null,
  ) => {
    event.preventDefault()
    const formData = new FormData(event.currentTarget)
    const confirmation = event.currentTarget.elements.namedItem(
      'passwordConfirmation',
    ) as HTMLInputElement | null
    if (confirmation) {
      const password = String(formData.get('password') ?? '')
      if (confirmation.value !== password) {
        confirmation.setCustomValidity('Паролите не съвпадат.')
        confirmation.reportValidity()
        return
      }
    }
    setBusy(true)
    const publish = beginFeedback()
    const data = bodyFactory
      ? bodyFactory(formData)
      : Object.fromEntries(formData)
    try {
      const result = await request<Session>(path, {
        method: 'POST',
        body: JSON.stringify(data),
      })
      if (!publish(null)) return
      if (path.endsWith('login')) setSession(result)
      if (path.endsWith('invitations/accept')) setInvitationAccepted(true)
      if (success) {
        setFeedback({
          kind: path.endsWith('invitations/accept') ? 'success' : 'info',
          text: success,
        })
      }
    } catch (error) {
      publish({
        kind: 'error',
        category: errorCategory(error),
        text: path.endsWith('invitations/accept')
          ? invitationErrorDetail(error)
          : safeErrorDetail(error),
      })
    } finally {
      setBusy(false)
    }
  }

  if (session) {
    return (
      <UnsavedChangesGuardProvider>
        <AuthenticatedApplication
          session={session}
          setSession={setSession}
          busy={busy}
          setBusy={setBusy}
          feedback={feedback}
          setFeedback={setFeedback}
          beginFeedback={beginFeedback}
        />
      </UnsavedChangesGuardProvider>
    )
  }

  return (
    <main className="identity-main">
      <section className="identity-card" aria-labelledby="app-title">
        <div className="compact-content">
          <p className="eyebrow">SpotYourSlot</p>
          <h1 id="app-title">
            {page === 'forgot'
              ? 'Забравена парола?'
              : page === 'invitation'
                ? 'Приемане на покана'
                : 'Вход'}
          </h1>
          {page === 'login' && (
            <form onChange={() => setFeedback(null)} onSubmit={(event) => submit(event, '/api/auth/login', '')}>
              <Field name="email" label="Имейл" type="email" />
              <Field name="password" label="Парола" type="password" />
              <Button disabled={busy}>
                Вход
              </Button>
              <a
                className="text-link"
                href="/forgot-password"
                onClick={(event) => {
                  event.preventDefault()
                  navigate('forgot')
                }}
              >
                Забравена парола
              </a>
            </form>
          )}
          {page === 'forgot' && (
            <>
              <p>Въведи имейла си, за да получиш инструкции.</p>
              <form
                onChange={() => setFeedback(null)}
                onSubmit={(event) =>
                  submit(
                    event,
                    '/api/auth/password/forgot',
                    'Ако съществува профил, ще получите инструкции.',
                  )
                }
              >
                <Field name="email" label="Имейл" type="email" />
                <Button disabled={busy}>
                  Изпрати
                </Button>
                <a
                className="text-link"
                href="/"
                onClick={(event) => {
                  event.preventDefault()
                  navigate('login')
                }}
              >
                Обратно към вход
              </a>
              </form>
            </>
          )}
          {page === 'reset' && (
            <form
                onChange={() => setFeedback(null)}
              onSubmit={(event) =>
                submit(event, '/api/auth/password/reset', 'Паролата е променена.')
              }
            >
              <input
                type="hidden"
                name="token"
                value={new URLSearchParams(location.search).get('token') ?? ''}
              />
              <Field name="password" label="Нова парола" type="password" minLength={8} />
              <Button disabled={busy}>
                Промени паролата
              </Button>
            </form>
          )}
          {page === 'invitation' && !invitationAccepted && (
            <form
                onChange={() => setFeedback(null)}
              onSubmit={(event) =>
                submit(
                  event,
                  '/api/auth/invitations/accept',
                  'Поканата е приета успешно. Бизнесът очаква активиране от администратор.',
                  (data) => ({
                    token: data.get('token') ?? '',
                    displayName: `${String(data.get('firstName') ?? '').trim()} ${String(data.get('lastName') ?? '').trim()}`,
                    password: data.get('password') ?? '',
                  }),
                )
              }
            >
              <input
                type="hidden"
                name="token"
                value={new URLSearchParams(location.search).get('token') ?? ''}
              />
              <Field name="firstName" label="Име" requireTrimmedValue />
              <Field name="lastName" label="Фамилия" requireTrimmedValue />
              <Field name="password" label="Парола" type="password" minLength={8} />
              <Field
                name="passwordConfirmation"
                label="Потвърди паролата"
                type="password"
                minLength={8}
              />
              <Button disabled={busy}>
                Приеми поканата
              </Button>
            </form>
          )}
          {page === 'invitation' && invitationAccepted ? (
            <div className="invitation-accepted">
              <FeedbackMessage feedback={feedback} />
              <a
                className="text-link"
                href="/"
                onClick={(event) => {
                  event.preventDefault()
                  navigate('login')
                }}
              >
                Към вход
              </a>
            </div>
          ) : (
            <FeedbackMessage feedback={feedback} />
          )}
        </div>
      </section>
    </main>
  )
}

function FeedbackMessage({
  feedback,
  errorRef,
}: {
  feedback: Feedback | null
  errorRef?: RefObject<HTMLParagraphElement | null>
}) {
  if (!feedback) return null

  if (feedback.kind === 'error') {
    return (
      <p
        ref={errorRef}
        className="status-message status-error"
        role="alert"
        tabIndex={-1}
      >
        {feedback.text}
      </p>
    )
  }

  return (
    <p
      className={`status-message status-${feedback.kind}`}
      role="status"
      aria-live="polite"
    >
      {feedback.text}
    </p>
  )
}

function Field(props: {
  name: string
  label: string
  type?: string
  minLength?: number
  maxLength?: number
  defaultValue?: string
  value?: string
  onValueChange?: (value: string) => void
  requireTrimmedValue?: boolean
}) {
  const {
    name,
    label,
    type,
    minLength,
    maxLength,
    defaultValue,
    value,
    onValueChange,
    requireTrimmedValue,
  } = props

  const minimumPasswordLengthMessage = (value: string): string => {
    if (
      type === 'password' &&
      minLength === 8 &&
      value.length > 0 &&
      Array.from(value).length < 8
    ) {
      return 'Паролата трябва да бъде поне 8 знака.'
    }
    return ''
  }

  const validationMessage = (input: HTMLInputElement): string => {
    if (input.validity.valueMissing) {
      if (type === 'email') return 'Моля, въведете имейл адрес.'
      if (type === 'password') return 'Моля, въведете парола.'
      return 'Моля, попълнете това поле.'
    }
    if (input.validity.typeMismatch && type === 'email') {
      return 'Моля, въведете валиден имейл адрес.'
    }
    if (requireTrimmedValue && input.value.trim() === '') {
      return 'Моля, попълнете това поле.'
    }
    if (
      type === 'password' &&
      minLength === 8 &&
      (input.validity.tooShort || Array.from(input.value).length < 8)
    ) {
      return 'Паролата трябва да бъде поне 8 знака.'
    }
    return ''
  }

  return (
    <label>
      {label}
      <input
        name={name}
        type={type}
        minLength={minLength}
        maxLength={maxLength}
        {...(value !== undefined ? { value } : { defaultValue })}
        required
        onChange={onValueChange ? (event) => onValueChange(event.target.value) : undefined}
        onInvalid={(event) => {
          if (event.currentTarget.validity.customError) return
          event.currentTarget.setCustomValidity('')
          event.currentTarget.setCustomValidity(validationMessage(event.currentTarget))
        }}
        onInput={(event) => {
          event.currentTarget.setCustomValidity('')
          if (
            requireTrimmedValue &&
            event.currentTarget.value !== '' &&
            event.currentTarget.value.trim() === ''
          ) {
            event.currentTarget.setCustomValidity('Моля, попълнете това поле.')
            return
          }
          event.currentTarget.setCustomValidity(
            minimumPasswordLengthMessage(event.currentTarget.value),
          )
        }}
      />
    </label>
  )
}

export type AuthenticatedApplicationProps = {
  session: Session
  setSession: (session: Session | null) => void
  busy: boolean
  setBusy: (busy: boolean) => void
  feedback: Feedback | null
  setFeedback: (feedback: Feedback | null) => void
  beginFeedback: () => FeedbackAttempt
}

function activeBusinessOf(session: Session) {
  return session.businesses.find((business) => business.id === session.activeBusinessId)
}

function isBusinessOwnerSession(session: Session): boolean {
  return activeBusinessOf(session)?.role === 'BUSINESS_OWNER'
}

export function AuthenticatedApplication({
  session,
  setSession,
  busy,
  setBusy,
  feedback,
  setFeedback,
  beginFeedback,
}: AuthenticatedApplicationProps) {
  const guard = useUnsavedChangesGuard()
  const initialRoute = readAuthenticatedRoute()
  const owner = isBusinessOwnerSession(session)
  const explicitProfileRequested = window.location.hash === '#/profile'
  const smartLandingApplicable = initialRoute.kind === 'profile' && !explicitProfileRequested
  const [route, setRoute] = useState<AuthenticatedRoute>(() => {
    if (isPlatformRoute(initialRoute) && !session.platformAdmin) return PROFILE_ROUTE
    if (isBusinessOwnerRoute(initialRoute) && !owner) return PROFILE_ROUTE
    if (smartLandingApplicable && owner) return BUSINESS_SERVICES_ROUTE
    return initialRoute
  })
  const landingResolved = useRef(!smartLandingApplicable || owner)
  const routeRef = useRef(route)
  useEffect(() => {
    routeRef.current = route
  }, [route])

  const authenticationRequired = useCallback(
    (detail: string) => {
      setFeedback({ kind: 'error', category: 'blocking', text: detail })
      setSession(null)
    },
    [setFeedback, setSession],
  )

  useEffect(() => {
    const synchronizeRoute = () => {
      const nextRoute = readAuthenticatedRoute()
      const target: AuthenticatedRoute =
        isPlatformRoute(nextRoute) && !session.platformAdmin
          ? PROFILE_ROUTE
          : isBusinessOwnerRoute(nextRoute) && !isBusinessOwnerSession(session)
            ? PROFILE_ROUTE
            : nextRoute
      const targetNeedsReplace = target !== nextRoute

      guard.guard(
        () => {
          setFeedback(null)
          if (targetNeedsReplace) replaceRoute(target)
          setRoute(target)
        },
        () => {
          replaceRoute(routeRef.current)
        },
      )
    }

    const approvedHash = routeHrefMatchesCurrentLocation(initialRoute)
    if (
      !approvedHash ||
      (isPlatformRoute(initialRoute) && !session.platformAdmin) ||
      (isBusinessOwnerRoute(initialRoute) && !isBusinessOwnerSession(session))
    ) {
      replaceRoute(routeRef.current)
    }

    return subscribeToNavigation(synchronizeRoute)
  }, [initialRoute.kind, session, setFeedback, guard])

  useEffect(() => {
    setFeedback(null)
  }, [session.activeBusinessId, setFeedback])

  useEffect(() => {
    if (route.kind !== 'profile') {
      landingResolved.current = true
      return
    }
    if (landingResolved.current) return
    if (owner) {
      landingResolved.current = true
      replaceRoute(BUSINESS_SERVICES_ROUTE)
      setRoute(BUSINESS_SERVICES_ROUTE)
    }
  }, [owner, route.kind])

  useEffect(() => {
    if (session.activeBusinessId || session.businesses.length !== 1) {
      return
    }
    const businessId = session.businesses[0]!.id
    const controller = new AbortController()
    request<Session>('/api/auth/business', {
      method: 'POST',
      body: JSON.stringify({ businessId }),
      signal: controller.signal,
    })
      .then((value) => {
        if (controller.signal.aborted) return
        if (value) setSession(value)
      })
      .catch((error) => {
        if (controller.signal.aborted) return
        if (error instanceof ApiError && error.status === 401) {
          authenticationRequired(error.detail)
          return
        }
        setFeedback({ kind: 'error', category: errorCategory(error), text: safeErrorDetail(error) })
      })
    return () => controller.abort()
  }, [session, setSession, setFeedback, authenticationRequired])

  const navigate = (nextRoute: AuthenticatedRoute) => {
    if (isPlatformRoute(nextRoute) && !session.platformAdmin) return
    if (isBusinessOwnerRoute(nextRoute) && !owner) return
    guard.guard(() => {
      pushRoute(nextRoute)
      setRoute(nextRoute)
      setFeedback(null)
    })
  }

  const action = async (path: string, body?: object) => {
    setBusy(true)
    const publish = beginFeedback()
    try {
      const options: RequestInit = { method: 'POST' }
      if (body) options.body = JSON.stringify(body)
      const value = await request<Session>(path, options)
      if (value) setSession(value)
      if (!publish(null)) return false
      return true
    } catch (error) {
      publish({ kind: 'error', category: errorCategory(error), text: safeErrorDetail(error) })
      return false
    } finally {
      setBusy(false)
    }
  }

  const logout = async () => {
    setBusy(true)
    const publish = beginFeedback()
    try {
      await request('/api/auth/logout', { method: 'POST' })
      if (!publish(null)) return
      history.replaceState({}, '', '/')
      setSession(null)
    } catch (error) {
      publish({ kind: 'error', category: errorCategory(error), text: safeErrorDetail(error) })
    } finally {
      setBusy(false)
    }
  }

  const guardedLogout = () => guard.guard(() => void logout())

  if (isBusinessOwnerRoute(route)) {
    const activeBusiness = activeBusinessOf(session)
    const readOnly = activeBusiness?.status === 'SUSPENDED'
    const businessKey = session.activeBusinessId ?? 'none'
    return (
      <BusinessOwnerShell
        route={route}
        activeBusiness={activeBusiness}
        busy={busy}
        onNavigate={navigate}
        onLogout={guardedLogout}
      >
        {feedback && (
          <div className="platform-content">
            <FeedbackMessage feedback={feedback} />
          </div>
        )}
        {route.kind === 'business-services' ? (
          <ServiceList
            key={businessKey}
            readOnly={readOnly}
            onAuthenticationRequired={authenticationRequired}
            onCreate={() => navigate({ kind: 'business-service-new' })}
            onOpen={(serviceId) => navigate({ kind: 'business-service-detail', serviceId })}
          />
        ) : route.kind === 'business-service-new' ? (
          <ServiceCreate
            key={businessKey}
            readOnly={readOnly}
            onAuthenticationRequired={authenticationRequired}
            onCancel={() => navigate(BUSINESS_SERVICES_ROUTE)}
            onCreated={(serviceId) => navigate({ kind: 'business-service-detail', serviceId })}
          />
        ) : route.kind === 'business-service-detail' ? (
          <ServiceDetail
            key={`${businessKey}-${route.serviceId}`}
            serviceId={route.serviceId}
            readOnly={readOnly}
            onAuthenticationRequired={authenticationRequired}
            onBack={() => navigate(BUSINESS_SERVICES_ROUTE)}
          />
        ) : (
          <ComingSoon />
        )}
      </BusinessOwnerShell>
    )
  }

  return (
    <PlatformAdminShell
      route={route}
      platformAdmin={session.platformAdmin}
      businessOwner={owner}
      displayName={session.displayName}
      activeBusinessName={activeBusinessOf(session)?.displayName}
      busy={busy}
      onNavigate={navigate}
      onLogout={guardedLogout}
    >
      {route.kind !== 'profile' && feedback && (
        <div className="platform-content">
          <FeedbackMessage feedback={feedback} />
        </div>
      )}
      {route.kind === 'profile' ? (
        <Profile
          session={session}
          busy={busy}
          feedback={feedback}
          setFeedback={setFeedback}
          action={action}
          guard={guard}
        />
      ) : route.kind === 'platform-businesses' ? (
        <BusinessList
          onAuthenticationRequired={authenticationRequired}
          onCreate={() => navigate(PLATFORM_BUSINESS_NEW_ROUTE)}
          onOpen={(businessId) =>
            navigate({ kind: 'platform-business-detail', businessId })
          }
        />
      ) : route.kind === 'platform-business-new' ? (
        <BusinessCreate
          onAuthenticationRequired={authenticationRequired}
          onCancel={() => navigate(PLATFORM_BUSINESSES_ROUTE)}
          onCreated={(businessId) =>
            navigate({ kind: 'platform-business-detail', businessId })
          }
        />
      ) : (
        <BusinessDetail
          key={route.businessId}
          businessId={route.businessId}
          onAuthenticationRequired={authenticationRequired}
          onBack={() => navigate(PLATFORM_BUSINESSES_ROUTE)}
        />
      )}
    </PlatformAdminShell>
  )
}

function ComingSoon() {
  return (
    <div className="platform-content">
      <section className="content-card" aria-label="Предстояща секция">
        <p>Тази секция ще бъде налична скоро.</p>
      </section>
    </div>
  )
}

function routeHrefMatchesCurrentLocation(route: AuthenticatedRoute): boolean {
  return window.location.hash === routeHref(route).slice(1)
}

type ProfileProps = {
  session: Session
  busy: boolean
  feedback: Feedback | null
  setFeedback: (feedback: Feedback | null) => void
  action: (path: string, body?: object) => Promise<boolean>
  guard: ReturnType<typeof useUnsavedChangesGuard>
}

function Profile({ session, busy, feedback, setFeedback, action, guard }: ProfileProps) {
  const [section, setSection] = useState<'personal' | 'password'>('personal')
  const [editingPersonal, setEditingPersonal] = useState(false)
  const [displayNameDraft, setDisplayNameDraft] = useState(session.displayName)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [passwordConfirmation, setPasswordConfirmation] = useState('')
  const profileFeedback = useRef<HTMLParagraphElement>(null)
  const personalSelected = section === 'personal'
  const personalEditDirty = editingPersonal && displayNameDraft !== session.displayName
  const passwordEditDirty =
    currentPassword !== '' || newPassword !== '' || passwordConfirmation !== ''

  const resetPasswordFields = () => {
    setCurrentPassword('')
    setNewPassword('')
    setPasswordConfirmation('')
  }

  useGuardedFormState(personalEditDirty || passwordEditDirty, () => {
    setDisplayNameDraft(session.displayName)
    resetPasswordFields()
  })

  useEffect(() => {
    if (feedback?.kind === 'error') {
      profileFeedback.current?.focus()
    }
  }, [editingPersonal, feedback, personalSelected])

  const exitPersonalEditing = () => {
    setEditingPersonal(false)
    setDisplayNameDraft(session.displayName)
    setFeedback(null)
  }

  const startEditingPersonal = () => {
    setDisplayNameDraft(session.displayName)
    setEditingPersonal(true)
    setFeedback(null)
  }

  const selectSection = (next: 'personal' | 'password') => {
    guard.guard(() => {
      setSection(next)
      exitPersonalEditing()
    })
  }

  return (
    <div className="platform-content">
      <section className="content-card profile-card" aria-label="Настройки на профила">
        <div className="profile-layout">
          <nav className="profile-navigation" aria-label="Настройки на профила">
            <Button
              type="button"
              variant="navigation"
              aria-pressed={personalSelected}
              onClick={() => selectSection('personal')}
            >
              Лични данни
            </Button>
            <Button
              type="button"
              variant="navigation"
              aria-pressed={!personalSelected}
              onClick={() => selectSection('password')}
            >
              Смяна на парола
            </Button>
          </nav>
          <section className="profile-panel" aria-labelledby="profile-panel-heading">
            {personalSelected ? (
              <>
                <h2 id="profile-panel-heading">Лични данни</h2>
                {editingPersonal ? (
                  <form
                    onChange={() => setFeedback(null)}
                    onSubmit={async (event) => {
                      event.preventDefault()
                      setFeedback(null)
                      if (await action('/api/auth/profile', { displayName: displayNameDraft })) {
                        setEditingPersonal(false)
                        setFeedback({
                          kind: 'success',
                          text: 'Личните данни са запазени.',
                        })
                      }
                    }}
                  >
                    <Field
                      name="displayName"
                      label="Име"
                      value={displayNameDraft}
                      onValueChange={setDisplayNameDraft}
                      maxLength={200}
                    />
                    <dl className="profile-personal-details">
                      <div>
                        <dt>Имейл</dt>
                        <dd>{session.email}</dd>
                      </div>
                    </dl>
                    <div className="action-group">
                      <Button disabled={busy}>
                        Запази промените
                      </Button>
                      <Button
                        type="button"
                        variant="secondary"
                        disabled={busy}
                        onClick={() => guard.guard(exitPersonalEditing)}
                      >
                        Отказ
                      </Button>
                    </div>
                  </form>
                ) : (
                  <>
                    <dl className="profile-personal-details">
                      <div>
                        <dt>Име</dt>
                        <dd>{session.displayName}</dd>
                      </div>
                      <div>
                        <dt>Имейл</dt>
                        <dd>{session.email}</dd>
                      </div>
                    </dl>
                    <Button
                      type="button"
                      variant="secondary"
                      onClick={startEditingPersonal}
                    >
                      Редактирай
                    </Button>
                  </>
                )}
                {(session.businesses.length > 1 ||
                  (session.businesses.length === 1 && !session.activeBusinessId)) && (
                  <label>
                    Избери бизнес
                    <select
                      value={session.activeBusinessId ?? ''}
                      onChange={(event) => {
                        const businessId = event.target.value
                        guard.guard(() => {
                          void action('/api/auth/business', { businessId })
                        })
                      }}
                    >
                      <option value="" disabled>
                        Изберете
                      </option>
                      {session.businesses.map((business) => (
                        <option key={business.id} value={business.id}>
                          {business.displayName} — {business.role}
                        </option>
                      ))}
                    </select>
                  </label>
                )}
                <FeedbackMessage feedback={feedback} errorRef={profileFeedback} />
              </>
            ) : (
              <>
                <h2 id="profile-panel-heading">Смяна на парола</h2>
                <form
                  onChange={() => setFeedback(null)}
                  onSubmit={async (event) => {
                    event.preventDefault()
                    setFeedback(null)
                    if (newPassword !== passwordConfirmation) {
                      setSection('password')
                      setFeedback({
                        kind: 'error',
                        category: 'validation',
                        text: 'Паролите не съвпадат.',
                      })
                      return
                    }
                    if (
                      await action('/api/auth/password/change', {
                        currentPassword,
                        newPassword,
                      })
                    ) {
                      resetPasswordFields()
                      setFeedback({
                        kind: 'success',
                        text: 'Паролата е променена успешно.',
                      })
                    }
                  }}
                >
                  <Field
                    name="currentPassword"
                    label="Текуща парола"
                    type="password"
                    value={currentPassword}
                    onValueChange={setCurrentPassword}
                  />
                  <Field
                    name="newPassword"
                    label="Нова парола"
                    type="password"
                    minLength={8}
                    value={newPassword}
                    onValueChange={setNewPassword}
                  />
                  <Field
                    name="passwordConfirmation"
                    label="Потвърди новата парола"
                    type="password"
                    minLength={8}
                    value={passwordConfirmation}
                    onValueChange={setPasswordConfirmation}
                  />
                  <Button disabled={busy}>
                    Запази
                  </Button>
                </form>
                <FeedbackMessage feedback={feedback} errorRef={profileFeedback} />
              </>
            )}
          </section>
        </div>
      </section>
    </div>
  )
}
