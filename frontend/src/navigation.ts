export type IdentityPage = 'login' | 'forgot' | 'reset' | 'invitation'

export type PlatformRoute =
  | { kind: 'platform-businesses' }
  | { kind: 'platform-business-new' }
  | { kind: 'platform-business-detail'; businessId: string }

export type BusinessOwnerRoute =
  | { kind: 'business-services' }
  | { kind: 'business-service-new' }
  | { kind: 'business-service-detail'; serviceId: string }
  | { kind: 'business-staff' }
  | { kind: 'business-schedule' }

export type AuthenticatedRoute = { kind: 'profile' } | PlatformRoute | BusinessOwnerRoute

export const PROFILE_ROUTE: AuthenticatedRoute = { kind: 'profile' }
export const PLATFORM_BUSINESSES_ROUTE: AuthenticatedRoute = {
  kind: 'platform-businesses',
}
export const PLATFORM_BUSINESS_NEW_ROUTE: AuthenticatedRoute = {
  kind: 'platform-business-new',
}
export const BUSINESS_SERVICES_ROUTE: AuthenticatedRoute = {
  kind: 'business-services',
}
export const BUSINESS_SERVICE_NEW_ROUTE: AuthenticatedRoute = {
  kind: 'business-service-new',
}
export const BUSINESS_STAFF_ROUTE: AuthenticatedRoute = { kind: 'business-staff' }
export const BUSINESS_SCHEDULE_ROUTE: AuthenticatedRoute = {
  kind: 'business-schedule',
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
  if (hash === '#/business/services') return BUSINESS_SERVICES_ROUTE
  if (hash === '#/business/services/new') return BUSINESS_SERVICE_NEW_ROUTE
  if (hash === '#/business/staff') return BUSINESS_STAFF_ROUTE
  if (hash === '#/business/schedule') return BUSINESS_SCHEDULE_ROUTE

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

  const serviceDetail = hash.match(/^#\/business\/services\/([^/?#]+)$/)
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

  return PROFILE_ROUTE
}

export function routeHref(route: AuthenticatedRoute): string {
  if (route.kind === 'platform-businesses') return '/#/platform/businesses'
  if (route.kind === 'platform-business-new') return '/#/platform/businesses/new'
  if (route.kind === 'platform-business-detail') {
    return `/#/platform/businesses/${encodeURIComponent(route.businessId)}`
  }
  if (route.kind === 'business-services') return '/#/business/services'
  if (route.kind === 'business-service-new') return '/#/business/services/new'
  if (route.kind === 'business-service-detail') {
    return `/#/business/services/${encodeURIComponent(route.serviceId)}`
  }
  if (route.kind === 'business-staff') return '/#/business/staff'
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
