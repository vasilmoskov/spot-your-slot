export type IdentityPage = 'login' | 'forgot' | 'reset' | 'invitation'

export type AuthenticatedRoute =
  | { kind: 'profile' }
  | { kind: 'platform-businesses' }
  | { kind: 'platform-business-new' }
  | { kind: 'platform-business-detail'; businessId: string }

export const PROFILE_ROUTE: AuthenticatedRoute = { kind: 'profile' }
export const PLATFORM_BUSINESSES_ROUTE: AuthenticatedRoute = {
  kind: 'platform-businesses',
}
export const PLATFORM_BUSINESS_NEW_ROUTE: AuthenticatedRoute = {
  kind: 'platform-business-new',
}

export function readIdentityPage(pathname = window.location.pathname): IdentityPage {
  if (pathname.includes('password-reset')) return 'reset'
  if (pathname.includes('invitation')) return 'invitation'
  if (pathname.includes('forgot-password')) return 'forgot'
  return 'login'
}

export function readAuthenticatedRoute(hash = window.location.hash): AuthenticatedRoute {
  if (hash === '#/platform/businesses') return PLATFORM_BUSINESSES_ROUTE
  if (hash === '#/platform/businesses/new') return PLATFORM_BUSINESS_NEW_ROUTE

  const detail = hash.match(/^#\/platform\/businesses\/([^/?#]+)$/)
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

  return PROFILE_ROUTE
}

export function routeHref(route: AuthenticatedRoute): string {
  if (route.kind === 'platform-businesses') return '/#/platform/businesses'
  if (route.kind === 'platform-business-new') return '/#/platform/businesses/new'
  if (route.kind === 'platform-business-detail') {
    return `/#/platform/businesses/${encodeURIComponent(route.businessId)}`
  }
  return '/#/profile'
}

export function isPlatformRoute(route: AuthenticatedRoute): boolean {
  return route.kind !== 'profile'
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
