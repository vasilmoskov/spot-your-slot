import { FormEvent, useCallback, useEffect, useState } from 'react'
import { request, type Session } from './identity/api'
import {
  PROFILE_ROUTE,
  pushRoute,
  readAuthenticatedRoute,
  readIdentityPage,
  replaceRoute,
  subscribeToNavigation,
  type AuthenticatedRoute,
  type IdentityPage,
} from './navigation'
import { PlatformAdminShell } from './platform/PlatformAdminShell'
import { BusinessList } from './platform/businesses/BusinessList'

const safeErrorDetail = (error: unknown): string =>
  error instanceof Error ? error.message : 'Възникна грешка.'

type Feedback = {
  kind: 'error' | 'info' | 'success'
  text: string
}

export function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [page, setPage] = useState<IdentityPage>(readIdentityPage())
  const [feedback, setFeedback] = useState<Feedback | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    request<Session>('/api/auth/session').then(setSession).catch(() => undefined)
  }, [])

  useEffect(
    () =>
      subscribeToNavigation(() => {
        if (!window.location.hash) {
          setPage(readIdentityPage())
          setFeedback(null)
        }
      }),
    [],
  )

  const navigate = (next: IdentityPage) => {
    setPage(next)
    setFeedback(null)
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
  ) => {
    event.preventDefault()
    setBusy(true)
    setFeedback(null)
    const data = Object.fromEntries(new FormData(event.currentTarget))
    try {
      const result = await request<Session>(path, {
        method: 'POST',
        body: JSON.stringify(data),
      })
      if (path.endsWith('login')) setSession(result)
      if (success) setFeedback({ kind: 'info', text: success })
    } catch (error) {
      setFeedback({ kind: 'error', text: safeErrorDetail(error) })
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
      />
    )
  }

  return (
    <main className="identity-main">
      <section className="identity-card" aria-labelledby="app-title">
        <div className="compact-content">
          <p className="eyebrow">SpotYourSlot</p>
          <h1 id="app-title">
            {page === 'forgot' ? 'Забравена парола?' : 'Вход'}
          </h1>
          {page === 'login' && (
            <form onSubmit={(event) => submit(event, '/api/auth/login', '')}>
              <Field name="email" label="Имейл" type="email" />
              <Field name="password" label="Парола" type="password" />
              <button className="form-primary-action" disabled={busy}>
                Вход
              </button>
              <button
                className="form-secondary-action"
                type="button"
                onClick={() => navigate('forgot')}
              >
                Забравена парола
              </button>
            </form>
          )}
          {page === 'forgot' && (
            <>
              <p>Въведи имейла си, за да получиш инструкции.</p>
              <form
                onSubmit={(event) =>
                  submit(
                    event,
                    '/api/auth/password/forgot',
                    'Ако съществува профил, ще получите инструкции.',
                  )
                }
              >
                <Field name="email" label="Имейл" type="email" />
                <button className="form-primary-action" disabled={busy}>
                  Изпрати
                </button>
                <button
                  className="form-secondary-action"
                  type="button"
                  onClick={() => navigate('login')}
                >
                  Обратно към вход
                </button>
              </form>
            </>
          )}
          {page === 'reset' && (
            <form
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
              <button className="form-primary-action" disabled={busy}>
                Промени паролата
              </button>
            </form>
          )}
          {page === 'invitation' && (
            <form
              onSubmit={(event) =>
                submit(
                  event,
                  '/api/auth/invitations/accept',
                  'Профилът е създаден. Вече можете да влезете.',
                )
              }
            >
              <input
                type="hidden"
                name="token"
                value={new URLSearchParams(location.search).get('token') ?? ''}
              />
              <Field name="displayName" label="Име" />
              <Field name="password" label="Парола" type="password" minLength={8} />
              <button className="form-primary-action" disabled={busy}>
                Приеми поканата
              </button>
            </form>
          )}
          <FeedbackMessage feedback={feedback} />
        </div>
      </section>
    </main>
  )
}

function FeedbackMessage({ feedback }: { feedback: Feedback | null }) {
  if (!feedback) return null

  if (feedback.kind === 'error') {
    return (
      <p className="status-message status-error" role="alert">
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

function Field(props: { name: string; label: string; type?: string; minLength?: number }) {
  const minimumPasswordLengthMessage = (value: string): string => {
    if (
      props.type === 'password' &&
      props.minLength === 8 &&
      value.length > 0 &&
      Array.from(value).length < 8
    ) {
      return 'Паролата трябва да бъде поне 8 знака.'
    }
    return ''
  }

  const validationMessage = (input: HTMLInputElement): string => {
    if (input.validity.valueMissing) {
      if (props.type === 'email') return 'Моля, въведете имейл адрес.'
      if (props.type === 'password') return 'Моля, въведете парола.'
      return 'Моля, попълнете това поле.'
    }
    if (input.validity.typeMismatch && props.type === 'email') {
      return 'Моля, въведете валиден имейл адрес.'
    }
    if (
      props.type === 'password' &&
      props.minLength === 8 &&
      (input.validity.tooShort || Array.from(input.value).length < 8)
    ) {
      return 'Паролата трябва да бъде поне 8 знака.'
    }
    return ''
  }

  return (
    <label>
      {props.label}
      <input
        required
        {...props}
        onInvalid={(event) => {
          event.currentTarget.setCustomValidity('')
          event.currentTarget.setCustomValidity(validationMessage(event.currentTarget))
        }}
        onInput={(event) => {
          event.currentTarget.setCustomValidity('')
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
}

function AuthenticatedApplication({
  session,
  setSession,
  busy,
  setBusy,
  feedback,
  setFeedback,
}: AuthenticatedApplicationProps) {
  const initialRoute = readAuthenticatedRoute()
  const [route, setRoute] = useState<AuthenticatedRoute>(
    initialRoute.kind === 'platform-businesses' && !session.platformAdmin
      ? PROFILE_ROUTE
      : initialRoute,
  )
  const authenticationRequired = useCallback(
    (detail: string) => {
      setFeedback({ kind: 'error', text: detail })
      setSession(null)
    },
    [setFeedback, setSession],
  )

  useEffect(() => {
    const synchronizeRoute = () => {
      const nextRoute = readAuthenticatedRoute()
      if (nextRoute.kind === 'platform-businesses' && !session.platformAdmin) {
        replaceRoute(PROFILE_ROUTE)
        setRoute(PROFILE_ROUTE)
        return
      }
      setRoute(nextRoute)
    }

    const approvedHash =
      window.location.hash === '#/profile' ||
      window.location.hash === '#/platform/businesses'
    if (!approvedHash || (initialRoute.kind === 'platform-businesses' && !session.platformAdmin)) {
      replaceRoute(PROFILE_ROUTE)
    }

    return subscribeToNavigation(synchronizeRoute)
  }, [initialRoute.kind, session.platformAdmin])

  const navigate = (nextRoute: AuthenticatedRoute) => {
    if (nextRoute.kind === 'platform-businesses' && !session.platformAdmin) return
    pushRoute(nextRoute)
    setRoute(nextRoute)
    setFeedback(null)
  }

  const action = async (path: string, body?: object) => {
    setBusy(true)
    setFeedback(null)
    try {
      const options: RequestInit = { method: 'POST' }
      if (body) options.body = JSON.stringify(body)
      const value = await request<Session>(path, options)
      if (value) setSession(value)
      return true
    } catch (error) {
      setFeedback({ kind: 'error', text: safeErrorDetail(error) })
      return false
    } finally {
      setBusy(false)
    }
  }

  const logout = async () => {
    setBusy(true)
    setFeedback(null)
    try {
      await request('/api/auth/logout', { method: 'POST' })
      history.replaceState({}, '', '/')
      setSession(null)
    } catch (error) {
      setFeedback({ kind: 'error', text: safeErrorDetail(error) })
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
      {route.kind === 'profile' ? (
        <Profile
          session={session}
          busy={busy}
          feedback={feedback}
          setFeedback={setFeedback}
          action={action}
        />
      ) : (
        <BusinessList onAuthenticationRequired={authenticationRequired} />
      )}
    </PlatformAdminShell>
  )
}

type ProfileProps = {
  session: Session
  busy: boolean
  feedback: Feedback | null
  setFeedback: (feedback: Feedback | null) => void
  action: (path: string, body?: object) => Promise<boolean>
}

function Profile({ session, busy, feedback, setFeedback, action }: ProfileProps) {
  return (
    <div className="platform-content">
      <section className="content-card" aria-label="Настройки на профила">
        <div className="compact-content">
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
                setFeedback({
                  kind: 'error',
                  text: 'Паролите не съвпадат.',
                })
                return
              }
              const form = event.currentTarget
              if (await action('/api/auth/password/change', { currentPassword, newPassword })) {
                form.reset()
                setFeedback({
                  kind: 'success',
                  text: 'Паролата е променена успешно.',
                })
              }
            }}
          >
            <h2>Смяна на парола</h2>
            <Field name="currentPassword" label="Текуща парола" type="password" />
            <Field name="newPassword" label="Нова парола" type="password" minLength={8} />
            <Field
              name="passwordConfirmation"
              label="Потвърди новата парола"
              type="password"
              minLength={8}
            />
            <button className="form-primary-action" disabled={busy}>
              Запази
            </button>
          </form>
          <FeedbackMessage feedback={feedback} />
        </div>
      </section>
    </div>
  )
}
