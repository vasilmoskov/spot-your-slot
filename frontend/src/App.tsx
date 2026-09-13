import { FormEvent, useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import { useFeedback, errorCategory, type Feedback, type FeedbackAttempt } from './ui/useFeedback'
import { Button } from './ui/Button'
import { ApiError, request, type Session } from './identity/api'
import {
  PROFILE_ROUTE,
  PLATFORM_BUSINESSES_ROUTE,
  PLATFORM_BUSINESS_NEW_ROUTE,
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
      <AuthenticatedApplication
        session={session}
        setSession={setSession}
        busy={busy}
        setBusy={setBusy}
        feedback={feedback}
        setFeedback={setFeedback}
        beginFeedback={beginFeedback}
      />
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
  requireTrimmedValue?: boolean
}) {
  const {
    name,
    label,
    type,
    minLength,
    maxLength,
    defaultValue,
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
        defaultValue={defaultValue}
        required
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

type AuthenticatedApplicationProps = {
  session: Session
  setSession: (session: Session | null) => void
  busy: boolean
  setBusy: (busy: boolean) => void
  feedback: Feedback | null
  setFeedback: (feedback: Feedback | null) => void
  beginFeedback: () => FeedbackAttempt
}

function AuthenticatedApplication({
  session,
  setSession,
  busy,
  setBusy,
  feedback,
  setFeedback,
  beginFeedback,
}: AuthenticatedApplicationProps) {
  const initialRoute = readAuthenticatedRoute()
  const [route, setRoute] = useState<AuthenticatedRoute>(
    isPlatformRoute(initialRoute) && !session.platformAdmin
      ? PROFILE_ROUTE
      : initialRoute,
  )
  const authenticationRequired = useCallback(
    (detail: string) => {
      setFeedback({ kind: 'error', category: 'blocking', text: detail })
      setSession(null)
    },
    [setFeedback, setSession],
  )

  useEffect(() => {
    const synchronizeRoute = () => {
      setFeedback(null)
      const nextRoute = readAuthenticatedRoute()
      if (isPlatformRoute(nextRoute) && !session.platformAdmin) {
        replaceRoute(PROFILE_ROUTE)
        setRoute(PROFILE_ROUTE)
        return
      }
      setRoute(nextRoute)
    }

    const approvedHash = routeHrefMatchesCurrentLocation(initialRoute)
    if (!approvedHash || (isPlatformRoute(initialRoute) && !session.platformAdmin)) {
      replaceRoute(PROFILE_ROUTE)
    }

    return subscribeToNavigation(synchronizeRoute)
  }, [initialRoute.kind, session.platformAdmin, setFeedback])

  useEffect(() => {
    setFeedback(null)
  }, [session.activeBusinessId, setFeedback])

  const navigate = (nextRoute: AuthenticatedRoute) => {
    if (isPlatformRoute(nextRoute) && !session.platformAdmin) return
    pushRoute(nextRoute)
    setRoute(nextRoute)
    setFeedback(null)
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

  return (
    <PlatformAdminShell
      route={route}
      platformAdmin={session.platformAdmin}
      displayName={session.displayName}
      busy={busy}
      onNavigate={navigate}
      onLogout={logout}
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

function routeHrefMatchesCurrentLocation(route: AuthenticatedRoute): boolean {
  return window.location.hash === routeHref(route).slice(1)
}

type ProfileProps = {
  session: Session
  busy: boolean
  feedback: Feedback | null
  setFeedback: (feedback: Feedback | null) => void
  action: (path: string, body?: object) => Promise<boolean>
}

function Profile({ session, busy, feedback, setFeedback, action }: ProfileProps) {
  const [section, setSection] = useState<'personal' | 'password'>('personal')
  const [editingPersonal, setEditingPersonal] = useState(false)
  const profileFeedback = useRef<HTMLParagraphElement>(null)
  const personalSelected = section === 'personal'

  useEffect(() => {
    if (feedback?.kind === 'error') {
      profileFeedback.current?.focus()
    }
  }, [editingPersonal, feedback, personalSelected])

  const selectSection = (next: 'personal' | 'password') => {
    setSection(next)
    setEditingPersonal(false)
    setFeedback(null)
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
                      const displayName = String(
                        new FormData(event.currentTarget).get('displayName') ?? '',
                      )
                      if (await action('/api/auth/profile', { displayName })) {
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
                      defaultValue={session.displayName}
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
                        onClick={() => {
                          setEditingPersonal(false)
                          setFeedback(null)
                        }}
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
                      onClick={() => {
                        setEditingPersonal(true)
                        setFeedback(null)
                      }}
                    >
                      Редактирай
                    </Button>
                  </>
                )}
                {session.businesses.length > 1 && (
                  <label>
                    Избери бизнес
                    <select
                      value={session.activeBusinessId ?? ''}
                      onChange={(event) =>
                        action('/api/auth/business', { businessId: event.target.value })
                      }
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
                    const data = Object.fromEntries(new FormData(event.currentTarget))
                    const currentPassword = String(data.currentPassword ?? '')
                    const newPassword = String(data.newPassword ?? '')
                    const passwordConfirmation = String(data.passwordConfirmation ?? '')
                    if (newPassword !== passwordConfirmation) {
                      setSection('password')
                      setFeedback({
                        kind: 'error',
                        category: 'validation',
                        text: 'Паролите не съвпадат.',
                      })
                      return
                    }
                    const form = event.currentTarget
                    if (
                      await action('/api/auth/password/change', {
                        currentPassword,
                        newPassword,
                      })
                    ) {
                      form.reset()
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
                  />
                  <Field
                    name="newPassword"
                    label="Нова парола"
                    type="password"
                    minLength={8}
                  />
                  <Field
                    name="passwordConfirmation"
                    label="Потвърди новата парола"
                    type="password"
                    minLength={8}
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
