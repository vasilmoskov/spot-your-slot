import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  BUSINESS_SCHEDULE_ROUTE,
  BUSINESS_SERVICE_NEW_ROUTE,
  BUSINESS_SERVICES_ROUTE,
  BUSINESS_STAFF_ROUTE,
  BUSINESSES_DEFAULT_LIST,
  PROFILE_ROUTE,
  PLATFORM_BUSINESS_NEW_ROUTE,
  PLATFORM_BUSINESSES_ROUTE,
  SERVICES_DEFAULT_LIST,
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
    expect(routeHref(PLATFORM_BUSINESSES_ROUTE)).toBe(
      '/#/platform/businesses?page=0&size=10&sort=displayName&direction=asc',
    )
    expect(routeHref(PLATFORM_BUSINESS_NEW_ROUTE)).toBe('/#/platform/businesses/new')
    expect(
      routeHref({ kind: 'platform-business-detail', businessId: 'business/a' }),
    ).toBe('/#/platform/businesses/business%2Fa')
    expect(routeHref(BUSINESS_SERVICES_ROUTE)).toBe(
      '/#/business/services?page=0&size=10&sort=name&direction=asc',
    )
    expect(routeHref(BUSINESS_SERVICE_NEW_ROUTE)).toBe('/#/business/services/new')
    expect(
      routeHref({ kind: 'business-service-detail', serviceId: 'service/a' }),
    ).toBe('/#/business/services/service%2Fa')
    expect(routeHref(BUSINESS_STAFF_ROUTE)).toBe('/#/business/staff')
    expect(routeHref(BUSINESS_SCHEDULE_ROUTE)).toBe('/#/business/schedule')

    pushRoute(PLATFORM_BUSINESSES_ROUTE)
    expect(window.location.hash).toBe(
      '#/platform/businesses?page=0&size=10&sort=displayName&direction=asc',
    )

    replaceRoute(PROFILE_ROUTE)
    expect(window.location.hash).toBe('#/profile')
  })

  it('canonicalizes explicit list query state in the URL', () => {
    const servicesRoute = {
      kind: 'business-services' as const,
      list: { page: 2, size: 25 as const, sort: 'price', direction: 'desc' as const },
    }
    expect(routeHref(servicesRoute)).toBe(
      '/#/business/services?page=2&size=25&sort=price&direction=desc',
    )
    expect(readAuthenticatedRoute('#/business/services?page=2&size=25&sort=price&direction=desc'))
      .toEqual(servicesRoute)

    const businessesRoute = {
      kind: 'platform-businesses' as const,
      list: { page: 1, size: 50 as const, sort: 'status', direction: 'desc' as const },
    }
    expect(routeHref(businessesRoute)).toBe(
      '/#/platform/businesses?page=1&size=50&sort=status&direction=desc',
    )
    expect(
      readAuthenticatedRoute('#/platform/businesses?page=1&size=50&sort=status&direction=desc'),
    ).toEqual(businessesRoute)
  })

  it('normalizes invalid or unsupported list query values to canonical defaults', () => {
    expect(readAuthenticatedRoute('#/business/services?page=-1&size=999&sort=bogus&direction=up'))
      .toEqual({ kind: 'business-services', list: SERVICES_DEFAULT_LIST })
    expect(readAuthenticatedRoute('#/business/services?page=abc&size=10'))
      .toEqual({ kind: 'business-services', list: SERVICES_DEFAULT_LIST })
    expect(
      readAuthenticatedRoute('#/platform/businesses?sort=displayName&direction=DESC'),
    ).toEqual({ kind: 'platform-businesses', list: BUSINESSES_DEFAULT_LIST })
    expect(readAuthenticatedRoute('#/business/services?sort=businessType')).toEqual({
      kind: 'business-services',
      list: SERVICES_DEFAULT_LIST,
    })
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
