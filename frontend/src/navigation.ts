export type IdentityPage = 'login' | 'forgot' | 'reset' | 'invitation'

export type ListSortDirection = 'asc' | 'desc'

/**
 * How a list-state change should affect browser history: 'push' (the default)
 * for an explicit user interaction the user should be able to undo with Back,
 * and 'replace' for automatic canonicalization/recovery (for example an
 * out-of-range page correcting itself) that must not leave a Back-navigation
 * trap pointing at the obsolete state.
 */
export type ListNavigationMode = 'push' | 'replace'

export type ListPageSize = 10 | 25 | 50

export type ListQueryState = {
  page: number
  size: ListPageSize
  sort: string
  direction: ListSortDirection
}

export type PlatformRoute =
  | { kind: 'platform-businesses'; list: ListQueryState }
  | { kind: 'platform-business-new' }
  | { kind: 'platform-business-detail'; businessId: string }

/** An inclusive Business-local date window, both bounds canonical `yyyy-MM-dd`. */
export const EXCEPTION_SORT_FIELDS = ['kind', 'dates', 'staff', 'status'] as const
export type ExceptionSortField = (typeof EXCEPTION_SORT_FIELDS)[number]

export type DateWindow = { from: string; to: string }

/**
 * The complete route-owned state of the schedule-change list. It travels with
 * every related route so create, detail, edit and delete return to exactly the
 * page, size and ordering the user left.
 */
export type ExceptionListState = DateWindow & {
  page: number
  size: ListPageSize
  sort: ExceptionSortField
  direction: ListSortDirection
}

export const EXCEPTION_LIST_DEFAULTS = {
  page: 0,
  size: 10,
  sort: 'dates',
  direction: 'asc',
} as const satisfies Omit<ExceptionListState, 'from' | 'to'>

export function withListDefaults(window: DateWindow): ExceptionListState {
  return { from: window.from, to: window.to, ...EXCEPTION_LIST_DEFAULTS }
}

/** Backend technical bounds and list-window size for schedule changes. */
export const SCHEDULE_MIN_DATE = '2000-01-01'
export const SCHEDULE_MAX_DATE = '2100-12-31'
export const SCHEDULE_MAX_WINDOW_DATES = 93

export type BusinessOwnerRoute =
  | { kind: 'business-services'; list: ListQueryState }
  | { kind: 'business-service-new' }
  | { kind: 'business-service-detail'; serviceId: string }
  | { kind: 'business-staff'; list: ListQueryState }
  | { kind: 'business-staff-new' }
  | { kind: 'business-staff-detail'; staffMemberId: string }
  | { kind: 'business-schedule' }
  | { kind: 'business-schedule-exceptions'; window: ExceptionListState | null }
  // `returnWindow` is the list window to come back to; null means the default.
  | { kind: 'business-schedule-exception-new'; returnWindow: ExceptionListState | null }
  | {
      kind: 'business-schedule-exception-detail'
      exceptionId: string
      returnWindow: ExceptionListState | null
    }

export type AuthenticatedRoute = { kind: 'profile' } | PlatformRoute | BusinessOwnerRoute

export const LIST_PAGE_SIZES: readonly ListPageSize[] = [10, 25, 50]

export const SERVICES_SORT_FIELDS = ['name', 'duration', 'price', 'status'] as const
export const STAFF_SORT_FIELDS = ['name', 'status', 'phone', 'email'] as const
export const BUSINESSES_SORT_FIELDS = [
  'displayName',
  'slug',
  'businessType',
  'status',
] as const

export const SERVICES_DEFAULT_LIST: ListQueryState = {
  page: 0,
  size: 10,
  sort: 'name',
  direction: 'asc',
}
export const STAFF_DEFAULT_LIST: ListQueryState = {
  page: 0,
  size: 10,
  sort: 'name',
  direction: 'asc',
}
export const BUSINESSES_DEFAULT_LIST: ListQueryState = {
  page: 0,
  size: 10,
  sort: 'displayName',
  direction: 'asc',
}

export const PROFILE_ROUTE: AuthenticatedRoute = { kind: 'profile' }
export const PLATFORM_BUSINESSES_ROUTE: AuthenticatedRoute = {
  kind: 'platform-businesses',
  list: BUSINESSES_DEFAULT_LIST,
}
export const PLATFORM_BUSINESS_NEW_ROUTE: AuthenticatedRoute = {
  kind: 'platform-business-new',
}
export const BUSINESS_SERVICES_ROUTE: AuthenticatedRoute = {
  kind: 'business-services',
  list: SERVICES_DEFAULT_LIST,
}
export const BUSINESS_SERVICE_NEW_ROUTE: AuthenticatedRoute = {
  kind: 'business-service-new',
}
export const BUSINESS_STAFF_ROUTE: AuthenticatedRoute = {
  kind: 'business-staff',
  list: STAFF_DEFAULT_LIST,
}
export const BUSINESS_STAFF_NEW_ROUTE: AuthenticatedRoute = {
  kind: 'business-staff-new',
}
export const BUSINESS_SCHEDULE_ROUTE: AuthenticatedRoute = {
  kind: 'business-schedule',
}

export const BUSINESS_SCHEDULE_EXCEPTIONS_ROUTE: AuthenticatedRoute = {
  kind: 'business-schedule-exceptions',
  window: null,
}
export const BUSINESS_SCHEDULE_EXCEPTION_NEW_ROUTE: AuthenticatedRoute = {
  kind: 'business-schedule-exception-new',
  returnWindow: null,
}

const CANONICAL_DATE = /^(\d{4})-(\d{2})-(\d{2})$/

/** True for a real calendar date in the exact zero-padded `yyyy-MM-dd` form. */
export function isCanonicalDate(value: string): boolean {
  const match = CANONICAL_DATE.exec(value)
  if (!match) return false
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const date = new Date(Date.UTC(year, month - 1, day))
  return (
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
  )
}

/** Inclusive number of dates from `from` through `to` (both canonical). */
export function inclusiveDateCount(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1
}

/** A window is usable only when it is exactly what the backend list accepts. */
export function isValidDateWindow(from: string, to: string): boolean {
  return (
    isCanonicalDate(from) &&
    isCanonicalDate(to) &&
    from >= SCHEDULE_MIN_DATE &&
    to <= SCHEDULE_MAX_DATE &&
    from <= to &&
    inclusiveDateCount(from, to) <= SCHEDULE_MAX_WINDOW_DATES
  )
}

function parseDateWindow(query: string): ExceptionListState | null {
  const params = new URLSearchParams(query)
  const from = params.get('from')
  const to = params.get('to')
  if (from === null || to === null || !isValidDateWindow(from, to)) return null
  // Every other value falls back to its documented default, never an error.
  return {
    from,
    to,
    page: parsePage(params.get('page')) ?? EXCEPTION_LIST_DEFAULTS.page,
    size: parseSize(params.get('size')) ?? EXCEPTION_LIST_DEFAULTS.size,
    sort:
      (parseSort(params.get('sort'), EXCEPTION_SORT_FIELDS) as ExceptionSortField | null) ??
      EXCEPTION_LIST_DEFAULTS.sort,
    direction: parseDirection(params.get('direction')) ?? EXCEPTION_LIST_DEFAULTS.direction,
  }
}

function windowQuery(window: ExceptionListState | null): string {
  if (!window) return ''
  return `?${new URLSearchParams({
    from: window.from,
    to: window.to,
    page: String(window.page),
    size: String(window.size),
    sort: window.sort,
    direction: window.direction,
  }).toString()}`
}

function parsePage(value: string | null): number | null {
  if (value === null) return null
  if (!/^\d+$/.test(value)) return null
  const page = Number(value)
  return Number.isSafeInteger(page) ? page : null
}

function parseSize(value: string | null): ListPageSize | null {
  if (value === null) return null
  const size = Number(value)
  return (LIST_PAGE_SIZES as readonly number[]).includes(size) ? (size as ListPageSize) : null
}

function parseSort(value: string | null, allowed: readonly string[]): string | null {
  return value !== null && allowed.includes(value) ? value : null
}

function parseDirection(value: string | null): ListSortDirection | null {
  return value === 'asc' || value === 'desc' ? value : null
}

function parseListQuery(
  query: string,
  defaults: ListQueryState,
  allowedSorts: readonly string[],
): ListQueryState {
  const params = new URLSearchParams(query)
  return {
    page: parsePage(params.get('page')) ?? defaults.page,
    size: parseSize(params.get('size')) ?? defaults.size,
    sort: parseSort(params.get('sort'), allowedSorts) ?? defaults.sort,
    direction: parseDirection(params.get('direction')) ?? defaults.direction,
  }
}

function serializeListQuery(list: ListQueryState): string {
  const params = new URLSearchParams({
    page: String(list.page),
    size: String(list.size),
    sort: list.sort,
    direction: list.direction,
  })
  return `?${params.toString()}`
}

function splitHash(hash: string): { path: string; query: string } {
  const separator = hash.indexOf('?')
  return separator === -1
    ? { path: hash, query: '' }
    : { path: hash.slice(0, separator), query: hash.slice(separator + 1) }
}

export function readIdentityPage(pathname = window.location.pathname): IdentityPage {
  if (pathname.includes('password-reset')) return 'reset'
  if (pathname.includes('invitation')) return 'invitation'
  if (pathname.includes('forgot-password')) return 'forgot'
  return 'login'
}

export function readAuthenticatedRoute(hash = window.location.hash): AuthenticatedRoute {
  const { path, query } = splitHash(hash)

  if (path === '#/platform/businesses') {
    return {
      kind: 'platform-businesses',
      list: parseListQuery(query, BUSINESSES_DEFAULT_LIST, BUSINESSES_SORT_FIELDS),
    }
  }
  if (path === '#/platform/businesses/new') return PLATFORM_BUSINESS_NEW_ROUTE
  if (path === '#/business/services') {
    return {
      kind: 'business-services',
      list: parseListQuery(query, SERVICES_DEFAULT_LIST, SERVICES_SORT_FIELDS),
    }
  }
  if (path === '#/business/services/new') return BUSINESS_SERVICE_NEW_ROUTE
  if (path === '#/business/staff') {
    return {
      kind: 'business-staff',
      list: parseListQuery(query, STAFF_DEFAULT_LIST, STAFF_SORT_FIELDS),
    }
  }
  if (path === '#/business/staff/new') return BUSINESS_STAFF_NEW_ROUTE
  if (path === '#/business/schedule') return BUSINESS_SCHEDULE_ROUTE
  if (path === '#/business/schedule/exceptions') {
    return { kind: 'business-schedule-exceptions', window: parseDateWindow(query) }
  }
  if (path === '#/business/schedule/exceptions/new') {
    return { kind: 'business-schedule-exception-new', returnWindow: parseDateWindow(query) }
  }

  const detail = path.match(/^#\/platform\/businesses\/([^/?#]+)$/)
  if (detail?.[1]) {
    try {
      return {
        kind: 'platform-business-detail',
        businessId: decodeURIComponent(detail[1]),
      }
    } catch {
      return PLATFORM_BUSINESSES_ROUTE
    }
  }

  const serviceDetail = path.match(/^#\/business\/services\/([^/?#]+)$/)
  if (serviceDetail?.[1]) {
    try {
      return {
        kind: 'business-service-detail',
        serviceId: decodeURIComponent(serviceDetail[1]),
      }
    } catch {
      return BUSINESS_SERVICES_ROUTE
    }
  }

  const exceptionDetail = path.match(/^#\/business\/schedule\/exceptions\/([^/?#]+)$/)
  if (exceptionDetail?.[1]) {
    try {
      return {
        kind: 'business-schedule-exception-detail',
        exceptionId: decodeURIComponent(exceptionDetail[1]),
        returnWindow: parseDateWindow(query),
      }
    } catch {
      return BUSINESS_SCHEDULE_EXCEPTIONS_ROUTE
    }
  }

  const staffDetail = path.match(/^#\/business\/staff\/([^/?#]+)$/)
  if (staffDetail?.[1]) {
    try {
      return {
        kind: 'business-staff-detail',
        staffMemberId: decodeURIComponent(staffDetail[1]),
      }
    } catch {
      return BUSINESS_STAFF_ROUTE
    }
  }

  return PROFILE_ROUTE
}

export function routeHref(route: AuthenticatedRoute): string {
  if (route.kind === 'platform-businesses') {
    return `/#/platform/businesses${serializeListQuery(route.list)}`
  }
  if (route.kind === 'platform-business-new') return '/#/platform/businesses/new'
  if (route.kind === 'platform-business-detail') {
    return `/#/platform/businesses/${encodeURIComponent(route.businessId)}`
  }
  if (route.kind === 'business-services') {
    return `/#/business/services${serializeListQuery(route.list)}`
  }
  if (route.kind === 'business-service-new') return '/#/business/services/new'
  if (route.kind === 'business-service-detail') {
    return `/#/business/services/${encodeURIComponent(route.serviceId)}`
  }
  if (route.kind === 'business-staff') {
    return `/#/business/staff${serializeListQuery(route.list)}`
  }
  if (route.kind === 'business-staff-new') return '/#/business/staff/new'
  if (route.kind === 'business-staff-detail') {
    return `/#/business/staff/${encodeURIComponent(route.staffMemberId)}`
  }
  if (route.kind === 'business-schedule') return '/#/business/schedule'
  if (route.kind === 'business-schedule-exceptions') {
    return `/#/business/schedule/exceptions${windowQuery(route.window)}`
  }
  if (route.kind === 'business-schedule-exception-new') {
    return `/#/business/schedule/exceptions/new${windowQuery(route.returnWindow)}`
  }
  if (route.kind === 'business-schedule-exception-detail') {
    return `/#/business/schedule/exceptions/${encodeURIComponent(route.exceptionId)}${windowQuery(
      route.returnWindow,
    )}`
  }
  return '/#/profile'
}

export function isPlatformRoute(route: AuthenticatedRoute): route is PlatformRoute {
  return route.kind.startsWith('platform-')
}

export function isBusinessOwnerRoute(route: AuthenticatedRoute): route is BusinessOwnerRoute {
  return route.kind.startsWith('business-')
}

export function pushRoute(route: AuthenticatedRoute): void {
  window.history.pushState({}, '', routeHref(route))
}

export function replaceRoute(route: AuthenticatedRoute): void {
  window.history.replaceState({}, '', routeHref(route))
}

export function subscribeToNavigation(listener: () => void): () => void {
  window.addEventListener('popstate', listener)
  window.addEventListener('hashchange', listener)
  return () => {
    window.removeEventListener('popstate', listener)
    window.removeEventListener('hashchange', listener)
  }
}
