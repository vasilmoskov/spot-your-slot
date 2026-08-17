import { FormEvent, useEffect, useState } from 'react'
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

const safeErrorDetail = (error: unknown): string =>
  error instanceof Error ? error.message : 'Възникна грешка.'

export function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [page, setPage] = useState<IdentityPage>(readIdentityPage())
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    request<Session>('/api/auth/session').then(setSession).catch(() => undefined)
  }, [])

  const navigate = (next: IdentityPage) => {
    setPage(next)
    setMessage('')
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
    setMessage('')
    const data = Object.fromEntries(new FormData(event.currentTarget))
    try {
      const result = await request<Session>(path, {
        method: 'POST',
        body: JSON.stringify(data),
      })
      if (path.endsWith('login')) setSession(result)
      setMessage(success)
    } catch (error) {
      setMessage(safeErrorDetail(error))
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
        message={message}
        setMessage={setMessage}
      />
    )
  }

  return (
    <main className="identity-main">
      <section className="identity-card" aria-labelledby="app-title">
        <p className="eyebrow">SpotYourSlot</p>
        <h1 id="app-title">Вход за бизнеса</h1>
        {page === 'login' && (
          <form onSubmit={(event) => submit(event, '/api/auth/login', '')}>
            <Field name="email" label="Имейл" type="email" />
            <Field name="password" label="Парола" type="password" />
            <button disabled={busy}>Вход</button>
            <button className="link-button" type="button" onClick={() => navigate('forgot')}>
              Забравена парола
            </button>
          </form>
        )}
        {page === 'forgot' && (
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
            <button disabled={busy}>Изпрати инструкции</button>
          </form>
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
            <Field name="password" label="Нова парола" type="password" minLength={12} />
            <button disabled={busy}>Промени паролата</button>
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
            <Field name="password" label="Парола" type="password" minLength={12} />
            <button disabled={busy}>Приеми поканата</button>
          </form>
        )}
        {message && (
          <p className="status-message" role="status">
            {message}
          </p>
        )}
      </section>
    </main>
  )
}

function Field(props: { name: string; label: string; type?: string; minLength?: number }) {
  return (
    <label>
      {props.label}
      <input required {...props} />
    </label>
  )
}

type AuthenticatedApplicationProps = {
  session: Session
  setSession: (session: Session | null) => void
  busy: boolean
  setBusy: (busy: boolean) => void
  message: string
  setMessage: (message: string) => void
}

function AuthenticatedApplication({
  session,
  setSession,
  busy,
  setBusy,
  message,
  setMessage,
}: AuthenticatedApplicationProps) {
  const initialRoute = readAuthenticatedRoute()
  const [route, setRoute] = useState<AuthenticatedRoute>(
    initialRoute.kind === 'platform-businesses' && !session.platformAdmin
      ? PROFILE_ROUTE
      : initialRoute,
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
    setMessage('')
  }

  const action = async (path: string, body?: object) => {
    setBusy(true)
    setMessage('')
    try {
      const options: RequestInit = { method: 'POST' }
      if (body) options.body = JSON.stringify(body)
      const value = await request<Session>(path, options)
      if (value) setSession(value)
    } catch (error) {
      setMessage(safeErrorDetail(error))
    } finally {
      setBusy(false)
    }
  }

  const logout = async () => {
    setBusy(true)
    setMessage('')
    try {
      await request('/api/auth/logout', { method: 'POST' })
      history.replaceState({}, '', '/')
      setSession(null)
    } catch (error) {
      setMessage(safeErrorDetail(error))
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
        <Profile session={session} busy={busy} message={message} action={action} />
      ) : null}
    </PlatformAdminShell>
  )
}

type ProfileProps = {
  session: Session
  busy: boolean
  message: string
  action: (path: string, body?: object) => Promise<void>
}

function Profile({ session, busy, message, action }: ProfileProps) {
  return (
    <section className="content-card" aria-label="Настройки на профила">
      <h2>Настройки</h2>
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
        onSubmit={(event) => {
          event.preventDefault()
          const data = Object.fromEntries(new FormData(event.currentTarget))
          void action('/api/auth/password/change', data)
        }}
      >
        <Field name="currentPassword" label="Текуща парола" type="password" />
        <Field name="newPassword" label="Нова парола" type="password" minLength={12} />
        <button disabled={busy}>Промени паролата</button>
      </form>
      {message && (
        <p role="status" className="status-message">
          {message}
        </p>
      )}
    </section>
  )
}
