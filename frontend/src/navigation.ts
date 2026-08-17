export type IdentityPage = 'login' | 'forgot' | 'reset' | 'invitation'

export type AuthenticatedRoute =
  | { kind: 'profile' }
  | { kind: 'platform-businesses' }

export const PROFILE_ROUTE: AuthenticatedRoute = { kind: 'profile' }
export const PLATFORM_BUSINESSES_ROUTE: AuthenticatedRoute = {
  kind: 'platform-businesses',
}

export function readIdentityPage(pathname = window.location.pathname): IdentityPage {
  if (pathname.includes('password-reset')) return 'reset'
  if (pathname.includes('invitation')) return 'invitation'
  if (pathname.includes('forgot-password')) return 'forgot'
  return 'login'
}

export function readAuthenticatedRoute(hash = window.location.hash): AuthenticatedRoute {
  return hash === '#/platform/businesses' ? PLATFORM_BUSINESSES_ROUTE : PROFILE_ROUTE
}

export function routeHref(route: AuthenticatedRoute): string {
  return route.kind === 'platform-businesses'
    ? '/#/platform/businesses'
    : '/#/profile'
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
