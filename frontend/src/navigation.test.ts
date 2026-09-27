import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  BUSINESS_SCHEDULE_ROUTE,
  BUSINESS_SERVICE_NEW_ROUTE,
  BUSINESS_SERVICES_ROUTE,
  BUSINESS_STAFF_ROUTE,
  PROFILE_ROUTE,
  PLATFORM_BUSINESS_NEW_ROUTE,
  PLATFORM_BUSINESSES_ROUTE,
  isBusinessOwnerRoute,
  isPlatformRoute,
  pushRoute,
  readAuthenticatedRoute,
  readIdentityPage,
  replaceRoute,
  routeHref,
  subscribeToNavigation,
} from './navigation'

afterEach(() => {
  window.history.replaceState({}, '', '/')
})

describe('application navigation', () => {
  it.each([
    ['/', 'login'],
    ['/forgot-password', 'forgot'],
    ['/password-reset', 'reset'],
    ['/invitation', 'invitation'],
  ] as const)('maps pathname %s to %s', (pathname, page) => {
    expect(readIdentityPage(pathname)).toBe(page)
  })

  it('maps approved hashes and safely defaults unknown hashes to Profile', () => {
    expect(readAuthenticatedRoute('#/platform/businesses')).toEqual(
      PLATFORM_BUSINESSES_ROUTE,
    )
    expect(readAuthenticatedRoute('#/profile')).toEqual(PROFILE_ROUTE)
    expect(readAuthenticatedRoute('#/platform/businesses/new')).toEqual(
      PLATFORM_BUSINESS_NEW_ROUTE,
    )
    expect(readAuthenticatedRoute('#/platform/businesses/business-a')).toEqual({
      kind: 'platform-business-detail',
      businessId: 'business-a',
    })
    expect(readAuthenticatedRoute('#/business/services')).toEqual(BUSINESS_SERVICES_ROUTE)
    expect(readAuthenticatedRoute('#/business/services/new')).toEqual(
      BUSINESS_SERVICE_NEW_ROUTE,
    )
    expect(readAuthenticatedRoute('#/business/services/service-a')).toEqual({
      kind: 'business-service-detail',
      serviceId: 'service-a',
    })
    expect(readAuthenticatedRoute('#/business/staff')).toEqual(BUSINESS_STAFF_ROUTE)
    expect(readAuthenticatedRoute('#/business/schedule')).toEqual(BUSINESS_SCHEDULE_ROUTE)
    expect(readAuthenticatedRoute('#/unknown')).toEqual(PROFILE_ROUTE)
  })

  it('builds and applies application-owned hash routes', () => {
    expect(routeHref(PROFILE_ROUTE)).toBe('/#/profile')
    expect(routeHref(PLATFORM_BUSINESSES_ROUTE)).toBe('/#/platform/businesses')
    expect(routeHref(PLATFORM_BUSINESS_NEW_ROUTE)).toBe('/#/platform/businesses/new')
    expect(
      routeHref({ kind: 'platform-business-detail', businessId: 'business/a' }),
    ).toBe('/#/platform/businesses/business%2Fa')
    expect(routeHref(BUSINESS_SERVICES_ROUTE)).toBe('/#/business/services')
    expect(routeHref(BUSINESS_SERVICE_NEW_ROUTE)).toBe('/#/business/services/new')
    expect(
      routeHref({ kind: 'business-service-detail', serviceId: 'service/a' }),
    ).toBe('/#/business/services/service%2Fa')
    expect(routeHref(BUSINESS_STAFF_ROUTE)).toBe('/#/business/staff')
    expect(routeHref(BUSINESS_SCHEDULE_ROUTE)).toBe('/#/business/schedule')

    pushRoute(PLATFORM_BUSINESSES_ROUTE)
    expect(window.location.hash).toBe('#/platform/businesses')

    replaceRoute(PROFILE_ROUTE)
    expect(window.location.hash).toBe('#/profile')
  })

  it('classifies platform and business-owner routes distinctly from Profile', () => {
    expect(isPlatformRoute(PROFILE_ROUTE)).toBe(false)
    expect(isPlatformRoute(PLATFORM_BUSINESSES_ROUTE)).toBe(true)
    expect(isPlatformRoute(BUSINESS_SERVICES_ROUTE)).toBe(false)
    expect(isBusinessOwnerRoute(PROFILE_ROUTE)).toBe(false)
    expect(isBusinessOwnerRoute(PLATFORM_BUSINESSES_ROUTE)).toBe(false)
    expect(isBusinessOwnerRoute(BUSINESS_SERVICES_ROUTE)).toBe(true)
    expect(isBusinessOwnerRoute(BUSINESS_STAFF_ROUTE)).toBe(true)
    expect(isBusinessOwnerRoute(BUSINESS_SCHEDULE_ROUTE)).toBe(true)
  })

  it('subscribes to browser Back/Forward and hash navigation', () => {
    const listener = vi.fn()
    const unsubscribe = subscribeToNavigation(listener)

    window.dispatchEvent(new PopStateEvent('popstate'))
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    expect(listener).toHaveBeenCalledTimes(2)

    unsubscribe()
    window.dispatchEvent(new PopStateEvent('popstate'))
    expect(listener).toHaveBeenCalledTimes(2)
  })
})
