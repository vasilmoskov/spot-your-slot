import {
  FormEvent,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type RefObject,
} from 'react'
import { useFeedback, errorCategory, type Feedback, type FeedbackAttempt } from './ui/useFeedback'
import { Button } from './ui/Button'
import {
  UnsavedChangesGuardProvider,
  useGuardedFormState,
  useUnsavedChangesGuard,
} from './ui/UnsavedChangesGuard'
import { ApiError, request, type Session } from './identity/api'
import { onBusinessContextLost } from './identity/businessRequest'
import {
  PROFILE_ROUTE,
  BUSINESSES_ROUTE,
  PLATFORM_BUSINESSES_ROUTE,
  PLATFORM_BUSINESS_NEW_ROUTE,
  BUSINESS_SERVICES_ROUTE,
  BUSINESS_STAFF_ROUTE,
  BUSINESS_CUSTOMERS_ROUTE,
  CUSTOMERS_DEFAULT_LIST,
  isBusinessOwnerRoute,
  isCustomerRoute,
  isPlatformRoute,
  pushRoute,
  readAuthenticatedRoute,
  readIdentityPage,
  replaceRoute,
  routeHref,
  subscribeToNavigation,
  type AuthenticatedRoute,
  type IdentityPage,
  type ExceptionListState,
  type ListNavigationMode,
  type ListQueryState,
} from './navigation'
import { PlatformAdminShell } from './platform/PlatformAdminShell'
import { BusinessList } from './platform/businesses/BusinessList'
import { BusinessCreate } from './platform/businesses/BusinessCreate'
import { BusinessDetail } from './platform/businesses/BusinessDetail'
import { BusinessOwnerShell } from './business/BusinessOwnerShell'
import { BusinessSelection } from './business/BusinessSelection'
import { ServiceList } from './business/services/ServiceList'
import { ServiceCreate } from './business/services/ServiceCreate'
import { ServiceDetail } from './business/services/ServiceDetail'
import { StaffList } from './business/staff/StaffList'
import { StaffCreate } from './business/staff/StaffCreate'
import { StaffDetail } from './business/staff/StaffDetail'
import { CustomerList } from './business/customers/CustomerList'
import { CustomerCreate } from './business/customers/CustomerCreate'
import { CustomerDetail } from './business/customers/CustomerDetail'
import { StaffWorkingSchedule } from './business/schedule/StaffWorkingSchedule'
import { ScheduleTabs } from './business/schedule/ScheduleTabs'
import { ScheduleExceptionList } from './business/schedule/exceptions/ScheduleExceptionList'
import { ScheduleExceptionCreate } from './business/schedule/exceptions/ScheduleExceptionCreate'
import { ScheduleExceptionDetail } from './business/schedule/exceptions/ScheduleExceptionDetail'

// How long reports of a lost Business context are ignored after a refresh confirmed the context.
const CONTEXT_RECHECK_COOLDOWN_MS = 5_000

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

// The Businesses the user can manage (an owner Membership), whether or not one is selected.
function ownedBusinessesOf(session: Session) {
  return session.businesses.filter((business) => business.role === 'BUSINESS_OWNER')
}

/**
 * The route a user may actually open: platform routes need the platform role, Business routes
 * a selected Business they own (otherwise the Business selection, or the Profile when they
 * manage none), and the own-Businesses page a Business to manage unless they only administer
 * the platform.
 */
function permittedRoute(route: AuthenticatedRoute, session: Session): AuthenticatedRoute {
  const ownsBusinesses = ownedBusinessesOf(session).length > 0
  if (isPlatformRoute(route)) return session.platformAdmin ? route : PROFILE_ROUTE
  if (isBusinessOwnerRoute(route)) {
    if (isBusinessOwnerSession(session)) return route
    return ownsBusinesses ? BUSINESSES_ROUTE : PROFILE_ROUTE
  }
  if (route.kind === 'businesses') {
    return !session.platformAdmin || ownsBusinesses ? route : PROFILE_ROUTE
  }
  return route
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
  const ownedBusinesses = ownedBusinessesOf(session)
  const [route, setRoute] = useState<AuthenticatedRoute>(() => {
    const permitted = permittedRoute(initialRoute, session)
    if (permitted !== initialRoute) return permitted
    if (smartLandingApplicable && owner) return BUSINESS_SERVICES_ROUTE
    // Several Businesses and none selected yet: the user chooses where to work.
    if (smartLandingApplicable && !session.platformAdmin && ownedBusinesses.length > 1) {
      return BUSINESSES_ROUTE
    }
    return initialRoute
  })
  const landingResolved = useRef(!smartLandingApplicable || owner)
  // Kept current during render (not via an effect) so it can never lag behind
  // the layout effects below that read it to keep the URL hash synchronized
  // with the rendered route before the browser paints.
  const routeRef = useRef(route)
  routeRef.current = route
  const guardRef = useRef(guard)
  guardRef.current = guard
  const lastBusinessKey = useRef<string | undefined>(session.activeBusinessId)
  if (session.activeBusinessId) lastBusinessKey.current = session.activeBusinessId

  // The Customer search term is personal data. It lives only here, in memory, for the current
  // Business and only while a Customer screen is open: never in the URL, history or storage.
  // It is tied to the Business it was entered for, so another Business never sees it.
  const [customerSearchState, setCustomerSearchState] = useState({
    businessId: session.activeBusinessId,
    term: '',
  })
  const customerSearch =
    customerSearchState.businessId === session.activeBusinessId ? customerSearchState.term : ''
  const setCustomerSearch = useCallback(
    (term: string) => setCustomerSearchState({ businessId: session.activeBusinessId, term }),
    [session.activeBusinessId],
  )
  // A success message for a just-created Customer, shown by the detail after it has loaded.
  const [createdCustomer, setCreatedCustomer] = useState<string | null>(null)

  useEffect(() => {
    setCreatedCustomer((current) =>
      current !== null && route.kind === 'business-customer-detail' && route.customerId === current
        ? current
        : null,
    )
    if (isCustomerRoute(route)) return
    setCustomerSearchState((current) =>
      current.term === '' ? current : { businessId: current.businessId, term: '' },
    )
  }, [route])

  const authenticationRequired = useCallback(
    (detail: string) => {
      setFeedback({ kind: 'error', category: 'blocking', text: detail })
      setSession(null)
    },
    [setFeedback, setSession],
  )

  // Recovery from a lost Business context (and from a mutation that revealed a suspension): one
  // session refresh at a time, however many requests report the loss. The refreshed session
  // either still carries the selected Business (nothing changes, each screen keeps its safe
  // error) or does not, and the route effect below then leaves the Business screens. An expired
  // login found here follows the ordinary authentication flow; any other failure changes nothing.
  const sessionRefresh = useRef<Promise<void> | null>(null)
  const latestSession = useRef(session)
  latestSession.current = session
  // After a refresh that confirmed the selected Business is still valid, further loss reports
  // are ignored for a short while: they cannot be answered differently, and a screen that
  // refetches on every render must not turn them into a request loop.
  const recheckAfter = useRef(0)
  const refreshSession = useCallback(
    (options: { force?: boolean } = {}) => {
    if (sessionRefresh.current) return
    if (!options.force) {
      // A report that arrives when the context is already known to be gone needs no refresh.
      if (!latestSession.current.activeBusinessId) return
      if (Date.now() < recheckAfter.current) return
    }
    sessionRefresh.current = request<Session>('/api/auth/session')
      .then(
        (value) => {
          if (!value) return
          if (value.activeBusinessId && value.activeBusinessId === latestSession.current.activeBusinessId) {
            recheckAfter.current = Date.now() + CONTEXT_RECHECK_COOLDOWN_MS
          }
          setSession(value)
        },
        (error: unknown) => {
          if (error instanceof ApiError && error.status === 401) authenticationRequired(error.detail)
        },
      )
      .finally(() => {
        sessionRefresh.current = null
      })
    },
    [setSession, authenticationRequired],
  )

  useEffect(() => onBusinessContextLost(() => refreshSession()), [refreshSession])

  // A layout effect (not a passive effect) so the corrected hash is applied
  // to the URL before the browser paints the rendered route: with a plain
  // useEffect, the route/heading commit and the history.replaceState() call
  // that corrects the hash are not guaranteed to happen in the same tick,
  // leaving a real (if brief) window where the rendered view and the URL
  // disagree.
  useLayoutEffect(() => {
    const synchronizeRoute = () => {
      const nextRoute = readAuthenticatedRoute()
      const target = permittedRoute(nextRoute, session)
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
    if (!approvedHash || permittedRoute(initialRoute, session) !== initialRoute) {
      replaceRoute(routeRef.current)
    }

    return subscribeToNavigation(synchronizeRoute)
  }, [initialRoute.kind, session, setFeedback, guard])

  useEffect(() => {
    setFeedback(null)
  }, [session.activeBusinessId, setFeedback])

  // A selected Business that is no longer available (the session no longer carries it) never
  // leaves its screens behind: the user returns to the Business selection.
  useLayoutEffect(() => {
    const current = routeRef.current
    const permitted = permittedRoute(current, session)
    if (permitted === current) return
    // The route is replaced, never pushed, so Back cannot return to a screen without context. A
    // dirty form is not discarded silently: the shared dialog asks first.
    guardRef.current.guard(() => {
      replaceRoute(permitted)
      setRoute(permitted)
    })
    // Only a changed session may trigger this; the guard object itself changes on every render.
  }, [session])

  const previousActiveBusinessId = useRef(session.activeBusinessId)
  // A layout effect so a stale page number from the previous Business is
  // corrected before the Services list's own data-fetch effect (a passive
  // effect) ever runs — otherwise it would briefly fetch the new Business's
  // data using the old page number before self-correcting.
  useLayoutEffect(() => {
    if (previousActiveBusinessId.current === session.activeBusinessId) return
    previousActiveBusinessId.current = session.activeBusinessId
    // A selection that disappeared is not a switch to another Business: the route effect above
    // leaves the Business screens, and nothing here may override that.
    if (!session.activeBusinessId) return
    const current = routeRef.current
    // A date window chosen for one Business is never carried to another: the
    // list resolves its own canonical window from the new Business timezone.
    if (current.kind === 'business-schedule-exceptions' && current.window) {
      const reset: AuthenticatedRoute = { kind: 'business-schedule-exceptions', window: null }
      replaceRoute(reset)
      setRoute(reset)
      return
    }
    if (
      (current.kind === 'business-schedule-exception-new' ||
        current.kind === 'business-schedule-exception-detail') &&
      current.returnWindow
    ) {
      const reset: AuthenticatedRoute = { ...current, returnWindow: null }
      replaceRoute(reset)
      setRoute(reset)
      return
    }
    // Customer list state is never carried to another Business: every part of it returns
    // to the defaults (the search term is dropped with the Business it belongs to).
    if (current.kind === 'business-customers') {
      const list = current.list
      const isDefault =
        list.page === CUSTOMERS_DEFAULT_LIST.page &&
        list.size === CUSTOMERS_DEFAULT_LIST.size &&
        list.sort === CUSTOMERS_DEFAULT_LIST.sort &&
        list.direction === CUSTOMERS_DEFAULT_LIST.direction
      if (isDefault) return
      const reset = BUSINESS_CUSTOMERS_ROUTE
      replaceRoute(reset)
      setRoute(reset)
      return
    }
    if (
      (current.kind === 'business-customer-new' || current.kind === 'business-customer-detail') &&
      current.returnList
    ) {
      const reset: AuthenticatedRoute = { ...current, returnList: null }
      replaceRoute(reset)
      setRoute(reset)
      return
    }
    if (
      (current.kind !== 'business-services' && current.kind !== 'business-staff') ||
      current.list.page === 0
    ) {
      return
    }
    const next: AuthenticatedRoute = { ...current, list: { ...current.list, page: 0 } }
    replaceRoute(next)
    setRoute(next)
  }, [session.activeBusinessId])

  const updateBusinessServicesList = (next: ListQueryState, mode: ListNavigationMode = 'push') => {
    if (route.kind !== 'business-services') return
    const nextRoute: AuthenticatedRoute = { ...route, list: next }
    if (mode === 'replace') {
      replaceRoute(nextRoute)
    } else {
      pushRoute(nextRoute)
    }
    setRoute(nextRoute)
  }

  const updateBusinessStaffList = (next: ListQueryState, mode: ListNavigationMode = 'push') => {
    if (route.kind !== 'business-staff') return
    const nextRoute: AuthenticatedRoute = { ...route, list: next }
    if (mode === 'replace') {
      replaceRoute(nextRoute)
    } else {
      pushRoute(nextRoute)
    }
    setRoute(nextRoute)
  }

  const updateBusinessCustomersList = (next: ListQueryState, mode: ListNavigationMode = 'push') => {
    if (route.kind !== 'business-customers') return
    const nextRoute: AuthenticatedRoute = { ...route, list: next }
    if (mode === 'replace') {
      replaceRoute(nextRoute)
    } else {
      pushRoute(nextRoute)
    }
    setRoute(nextRoute)
  }

  const updateScheduleExceptionWindow = (
    next: ExceptionListState,
    mode: ListNavigationMode = 'push',
  ) => {
    if (route.kind !== 'business-schedule-exceptions') return
    const nextRoute: AuthenticatedRoute = { ...route, window: next }
    if (mode === 'replace') {
      replaceRoute(nextRoute)
    } else {
      pushRoute(nextRoute)
    }
    setRoute(nextRoute)
  }

  const updatePlatformBusinessesList = (
    next: ListQueryState,
    mode: ListNavigationMode = 'push',
  ) => {
    if (route.kind !== 'platform-businesses') return
    const nextRoute: AuthenticatedRoute = { ...route, list: next }
    if (mode === 'replace') {
      replaceRoute(nextRoute)
    } else {
      pushRoute(nextRoute)
    }
    setRoute(nextRoute)
  }

  // Also a layout effect, for the same reason: the automatic Services
  // landing must correct the hash before paint so the rendered route and the
  // URL never observably disagree.
  useLayoutEffect(() => {
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
    if (permittedRoute(nextRoute, session) !== nextRoute) return
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

  // Selecting a Business to manage: establish the context, then open its first destination.
  const manageBusiness = async (businessId: string) => {
    if (businessId !== session.activeBusinessId) {
      if (!(await action('/api/auth/business', { businessId }))) return
    }
    pushRoute(BUSINESS_SERVICES_ROUTE)
    setRoute(BUSINESS_SERVICES_ROUTE)
    setFeedback(null)
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
    // While the selection is gone (a lost context awaiting recovery or the user's answer to the
    // guard dialog) the screens keep their key: they are not remounted, so an open form is never
    // discarded behind the user's back.
    const businessKey = session.activeBusinessId ?? lastBusinessKey.current ?? 'none'
    // During the render that follows a Business switch the layout effect above has not yet reset
    // the route, so the new Business's list must not start from the previous Business's state.
    const businessJustChanged = previousActiveBusinessId.current !== session.activeBusinessId
    return (
      <BusinessOwnerShell
        route={route}
        activeBusiness={activeBusiness}
        displayName={session.displayName}
        platformAdmin={session.platformAdmin}
        hasOwnedBusinesses={ownedBusinesses.length > 0}
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
            list={route.list}
            onListChange={updateBusinessServicesList}
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
        ) : route.kind === 'business-staff' ? (
          <StaffList
            key={businessKey}
            readOnly={readOnly}
            list={route.list}
            onListChange={updateBusinessStaffList}
            onAuthenticationRequired={authenticationRequired}
            onCreate={() => navigate({ kind: 'business-staff-new' })}
            onOpen={(staffMemberId) => navigate({ kind: 'business-staff-detail', staffMemberId })}
          />
        ) : route.kind === 'business-staff-new' ? (
          <StaffCreate
            key={businessKey}
            readOnly={readOnly}
            onAuthenticationRequired={authenticationRequired}
            onCancel={() => navigate(BUSINESS_STAFF_ROUTE)}
            onCreated={(staffMemberId) => navigate({ kind: 'business-staff-detail', staffMemberId })}
          />
        ) : route.kind === 'business-staff-detail' ? (
          <StaffDetail
            key={`${businessKey}-${route.staffMemberId}`}
            staffMemberId={route.staffMemberId}
            readOnly={readOnly}
            onAuthenticationRequired={authenticationRequired}
            onBack={() => navigate(BUSINESS_STAFF_ROUTE)}
          />
        ) : route.kind === 'business-customers' ? (
          <CustomerList
            key={businessKey}
            readOnly={readOnly}
            list={businessJustChanged ? CUSTOMERS_DEFAULT_LIST : route.list}
            searchTerm={customerSearch}
            onSearchTermChange={setCustomerSearch}
            onListChange={updateBusinessCustomersList}
            onAuthenticationRequired={authenticationRequired}
            onCreate={() => navigate({ kind: 'business-customer-new', returnList: route.list })}
            onOpen={(customerId) =>
              navigate({ kind: 'business-customer-detail', customerId, returnList: route.list })
            }
          />
        ) : route.kind === 'business-customer-new' ? (
          <CustomerCreate
            key={businessKey}
            readOnly={readOnly}
            onAuthenticationRequired={authenticationRequired}
            onBusinessSuspended={() => refreshSession({ force: true })}
            onCancel={() => navigate(customerListRoute(route.returnList))}
            onCreated={(customerId) => {
              setCreatedCustomer(customerId)
              navigate({
                kind: 'business-customer-detail',
                customerId,
                returnList: route.returnList,
              })
            }}
          />
        ) : route.kind === 'business-customer-detail' ? (
          <CustomerDetail
            key={`${businessKey}-${route.customerId}`}
            customerId={route.customerId}
            initialSuccess={createdCustomer === route.customerId ? 'Клиентът е добавен.' : undefined}
            onInitialSuccessShown={() => setCreatedCustomer(null)}
            onAuthenticationRequired={authenticationRequired}
            onBack={() => navigate(customerListRoute(route.returnList))}
          />
        ) : route.kind === 'business-schedule' ? (
          <>
            <div className="platform-content">
              <ScheduleTabs current="weekly" onNavigate={navigate} />
            </div>
            <StaffWorkingSchedule
              key={businessKey}
              readOnly={readOnly}
              onAuthenticationRequired={authenticationRequired}
            />
          </>
        ) : route.kind === 'business-schedule-exceptions' ? (
          <>
            <div className="platform-content">
              <ScheduleTabs current="exceptions" onNavigate={navigate} />
            </div>
            <ScheduleExceptionList
              key={businessKey}
              readOnly={readOnly}
              window={route.window}
              onWindowChange={updateScheduleExceptionWindow}
              onAuthenticationRequired={authenticationRequired}
              onCreate={() =>
                navigate({ kind: 'business-schedule-exception-new', returnWindow: route.window })
              }
              onOpen={(exceptionId) =>
                navigate({
                  kind: 'business-schedule-exception-detail',
                  exceptionId,
                  returnWindow: route.window,
                })
              }
            />
          </>
        ) : route.kind === 'business-schedule-exception-new' ? (
          <ScheduleExceptionCreate
            key={businessKey}
            readOnly={readOnly}
            onAuthenticationRequired={authenticationRequired}
            onCancel={() => navigate(exceptionListRoute(route.returnWindow))}
            onCreated={(exceptionId) => {
              navigate({
                kind: 'business-schedule-exception-detail',
                exceptionId,
                returnWindow: route.returnWindow,
              })
              setFeedback({ kind: 'success', text: 'Промяната е добавена.' })
            }}
          />
        ) : route.kind === 'business-schedule-exception-detail' ? (
          <ScheduleExceptionDetail
            key={`${businessKey}-${route.exceptionId}`}
            exceptionId={route.exceptionId}
            readOnly={readOnly}
            onAuthenticationRequired={authenticationRequired}
            onBack={() => navigate(exceptionListRoute(route.returnWindow))}
            onDeleted={() => {
              navigate(exceptionListRoute(route.returnWindow))
              setFeedback({ kind: 'success', text: 'Промяната е изтрита.' })
            }}
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
      hasOwnedBusinesses={ownedBusinesses.length > 0}
      displayName={session.displayName}
      selectedBusiness={owner ? activeBusinessOf(session) : undefined}
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
      ) : route.kind === 'businesses' ? (
        <BusinessSelection
          businesses={ownedBusinesses}
          activeBusinessId={owner ? session.activeBusinessId : undefined}
          busy={busy}
          onManage={(businessId) => void manageBusiness(businessId)}
        />
      ) : route.kind === 'platform-businesses' ? (
        <BusinessList
          list={route.list}
          onListChange={updatePlatformBusinessesList}
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

// The list the create and detail routes return to: the window the user was
// looking at, or the canonical default when none was carried.
function exceptionListRoute(window: ExceptionListState | null): AuthenticatedRoute {
  return { kind: 'business-schedule-exceptions', window }
}

// The list the Customer create and detail routes return to: the state the user was looking at,
// or the canonical default when none was carried.
function customerListRoute(returnList: ListQueryState | null): AuthenticatedRoute {
  return returnList
    ? { kind: 'business-customers', list: returnList }
    : BUSINESS_CUSTOMERS_ROUTE
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

  useLayoutEffect(() => {
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
                      requireTrimmedValue
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
