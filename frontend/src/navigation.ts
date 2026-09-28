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

export type BusinessOwnerRoute =
  | { kind: 'business-services'; list: ListQueryState }
  | { kind: 'business-service-new' }
  | { kind: 'business-service-detail'; serviceId: string }
  | { kind: 'business-staff'; list: ListQueryState }
  | { kind: 'business-staff-new' }
  | { kind: 'business-staff-detail'; staffMemberId: string }
  | { kind: 'business-schedule' }

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
