import { FormEvent, useEffect, useState } from 'react'
import { request, type Session } from './identity/api'

type Page = 'login' | 'forgot' | 'reset' | 'invitation'

const currentPage = (): Page => {
  if (location.pathname.includes('password-reset')) return 'reset'
  if (location.pathname.includes('invitation')) return 'invitation'
  if (location.pathname.includes('forgot-password')) return 'forgot'
  return 'login'
}

export function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [page, setPage] = useState<Page>(currentPage())
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    request<Session>('/api/auth/session').then(setSession).catch(() => undefined)
  }, [])

  const navigate = (next: Page) => {
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
      setMessage(error instanceof Error ? error.message : 'Възникна грешка.')
    } finally {
      setBusy(false)
    }
  }

  if (session) {
    return (
      <Authenticated
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
    <main>
      <section aria-labelledby="app-title">
        <p className="eyebrow">SpotYourSlot</p>
        <h1 id="app-title">Вход за бизнеса</h1>
        {page === 'login' && (
          <form onSubmit={(event) => submit(event, '/api/auth/login', '')}>
            <Field name="email" label="Имейл" type="email" />
            <Field name="password" label="Парола" type="password" />
            <button disabled={busy}>Вход</button>
            <button className="link" type="button" onClick={() => navigate('forgot')}>
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
          <p className="status" role="status">
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

type AuthenticatedProps = {
  session: Session
  setSession: (session: Session | null) => void
  busy: boolean
  setBusy: (busy: boolean) => void
  message: string
  setMessage: (message: string) => void
}

function Authenticated({
  session,
  setSession,
  busy,
  setBusy,
  message,
  setMessage,
}: AuthenticatedProps) {
  const action = async (path: string, body?: object) => {
    setBusy(true)
    setMessage('')
    try {
      const options: RequestInit = { method: 'POST' }
      if (body) options.body = JSON.stringify(body)
      const value = await request<Session>(path, options)
      if (value) setSession(value)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Възникна грешка.')
    } finally {
      setBusy(false)
    }
  }

  const logout = async () => {
    setBusy(true)
    setMessage('')
    try {
      await request('/api/auth/logout', { method: 'POST' })
      setSession(null)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Възникна грешка.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main>
      <section>
        <p className="eyebrow">SpotYourSlot</p>
        <h1>Здравей, {session.displayName}</h1>
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
            action('/api/auth/password/change', data)
          }}
        >
          <Field name="currentPassword" label="Текуща парола" type="password" />
          <Field name="newPassword" label="Нова парола" type="password" minLength={12} />
          <button disabled={busy}>Промени паролата</button>
        </form>
        <button disabled={busy} onClick={logout}>
          Изход
        </button>
        {message && (
          <p role="status" className="status">
            {message}
          </p>
        )}
      </section>
    </main>
  )
}
