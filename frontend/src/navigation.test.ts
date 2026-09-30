import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  BUSINESS_SCHEDULE_ROUTE,
  BUSINESS_SERVICE_NEW_ROUTE,
  BUSINESS_SERVICES_ROUTE,
  BUSINESS_STAFF_NEW_ROUTE,
  BUSINESS_STAFF_ROUTE,
  BUSINESSES_DEFAULT_LIST,
  PROFILE_ROUTE,
  PLATFORM_BUSINESS_NEW_ROUTE,
  PLATFORM_BUSINESSES_ROUTE,
  SERVICES_DEFAULT_LIST,
  STAFF_DEFAULT_LIST,
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
    expect(readAuthenticatedRoute('#/business/staff/new')).toEqual(BUSINESS_STAFF_NEW_ROUTE)
    expect(readAuthenticatedRoute('#/business/staff/staff-a')).toEqual({
      kind: 'business-staff-detail',
      staffMemberId: 'staff-a',
    })
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
    expect(routeHref(BUSINESS_STAFF_ROUTE)).toBe(
      '/#/business/staff?page=0&size=10&sort=name&direction=asc',
    )
    expect(routeHref(BUSINESS_STAFF_NEW_ROUTE)).toBe('/#/business/staff/new')
    expect(
      routeHref({ kind: 'business-staff-detail', staffMemberId: 'staff/a' }),
    ).toBe('/#/business/staff/staff%2Fa')
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

    const staffRoute = {
      kind: 'business-staff' as const,
      list: { page: 1, size: 25 as const, sort: 'status', direction: 'desc' as const },
    }
    expect(routeHref(staffRoute)).toBe(
      '/#/business/staff?page=1&size=25&sort=status&direction=desc',
    )
    expect(readAuthenticatedRoute('#/business/staff?page=1&size=25&sort=status&direction=desc'))
      .toEqual(staffRoute)

    const staffPhoneRoute = {
      kind: 'business-staff' as const,
      list: { page: 0, size: 10 as const, sort: 'phone', direction: 'asc' as const },
    }
    expect(readAuthenticatedRoute('#/business/staff?page=0&size=10&sort=phone&direction=asc'))
      .toEqual(staffPhoneRoute)
    const staffEmailRoute = {
      kind: 'business-staff' as const,
      list: { page: 0, size: 10 as const, sort: 'email', direction: 'desc' as const },
    }
    expect(readAuthenticatedRoute('#/business/staff?page=0&size=10&sort=email&direction=desc'))
      .toEqual(staffEmailRoute)
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
    expect(readAuthenticatedRoute('#/business/staff?page=-1&size=999&sort=bogus&direction=up'))
      .toEqual({ kind: 'business-staff', list: STAFF_DEFAULT_LIST })
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

describe('schedule change routes', () => {
  it('maps the four schedule destinations and their canonical hrefs', () => {
    expect(readAuthenticatedRoute('#/business/schedule')).toEqual(BUSINESS_SCHEDULE_ROUTE)
    expect(readAuthenticatedRoute('#/business/schedule/exceptions')).toEqual({
      kind: 'business-schedule-exceptions',
      window: null,
    })
    expect(readAuthenticatedRoute('#/business/schedule/exceptions/new')).toEqual({
      kind: 'business-schedule-exception-new',
      returnWindow: null,
    })
    expect(readAuthenticatedRoute('#/business/schedule/exceptions/abc-1')).toEqual({
      kind: 'business-schedule-exception-detail',
      exceptionId: 'abc-1',
      returnWindow: null,
    })
    expect(routeHref(readAuthenticatedRoute('#/business/schedule'))).toBe('/#/business/schedule')
    expect(routeHref({ kind: 'business-schedule-exceptions', window: null })).toBe(
      '/#/business/schedule/exceptions',
    )
    expect(routeHref({ kind: 'business-schedule-exception-new', returnWindow: null })).toBe(
      '/#/business/schedule/exceptions/new',
    )
    expect(
      routeHref({
        kind: 'business-schedule-exception-detail',
        exceptionId: 'a b',
        returnWindow: null,
      }),
    ).toBe('/#/business/schedule/exceptions/a%20b')
  })

  const DEFAULTS = '&page=0&size=10&sort=dates&direction=asc'
  const W = 'from=2026-10-01&to=2026-10-30'
  const list = (query: string) =>
    readAuthenticatedRoute(`#/business/schedule/exceptions?${W}${query}`)
  const stateOf = (route: ReturnType<typeof readAuthenticatedRoute>) =>
    'window' in route ? route.window : null

  it('round-trips the complete list state on list, create and detail routes', () => {
    const window = {
      from: '2026-10-01',
      to: '2026-10-30',
      page: 2,
      size: 25,
      sort: 'status',
      direction: 'desc',
    }
    const query = '?from=2026-10-01&to=2026-10-30&page=2&size=25&sort=status&direction=desc'

    const parsed = readAuthenticatedRoute(`#/business/schedule/exceptions${query}`)
    expect(parsed).toEqual({ kind: 'business-schedule-exceptions', window })
    expect(routeHref(parsed)).toBe(`/#/business/schedule/exceptions${query}`)

    const created = readAuthenticatedRoute(`#/business/schedule/exceptions/new${query}`)
    expect(created).toEqual({ kind: 'business-schedule-exception-new', returnWindow: window })
    expect(routeHref(created)).toBe(`/#/business/schedule/exceptions/new${query}`)

    const detail = readAuthenticatedRoute(`#/business/schedule/exceptions/abc-1${query}`)
    expect(detail).toEqual({
      kind: 'business-schedule-exception-detail',
      exceptionId: 'abc-1',
      returnWindow: window,
    })
    expect(routeHref(detail)).toBe(`/#/business/schedule/exceptions/abc-1${query}`)
  })

  it('defaults missing pagination values to page 0, size 10, dates ascending and writes them all', () => {
    const route = list('')
    expect(stateOf(route)).toEqual({
      from: '2026-10-01',
      to: '2026-10-30',
      page: 0,
      size: 10,
      sort: 'dates',
      direction: 'asc',
    })
    expect(routeHref(route)).toBe(`/#/business/schedule/exceptions?${W}${DEFAULTS}`)
  })

  it.each([10, 25, 50])('accepts the page size %i', (size) => {
    expect(stateOf(list(`&size=${size}`))).toMatchObject({ size })
  })

  it.each(['0', '1', '5', '7', '20', '37', '100', '-10', 'abc', '', '25.5'])(
    'normalizes the unsupported size "%s" to 10',
    (size) => {
      expect(stateOf(list(`&size=${size}`))).toMatchObject({ size: 10 })
    },
  )

  it.each(['-1', '-0', 'abc', '', '1.5', '99999999999999999999', '1e2'])(
    'normalizes the invalid page "%s" to 0',
    (page) => {
      expect(stateOf(list(`&page=${page}`))).toMatchObject({ page: 0 })
    },
  )

  it('keeps a valid page, however large; recovery happens after the data is known', () => {
    expect(stateOf(list('&page=37'))).toMatchObject({ page: 37 })
  })

  it.each([
    ['&sort=hours', { sort: 'dates', direction: 'asc' }],
    ['&sort=constructor', { sort: 'dates', direction: 'asc' }],
    ['&sort=&direction=', { sort: 'dates', direction: 'asc' }],
    ['&direction=sideways', { sort: 'dates', direction: 'asc' }],
    ['&sort=kind&direction=x', { sort: 'kind', direction: 'asc' }],
    ['&sort=x&direction=desc', { sort: 'dates', direction: 'desc' }],
  ])('normalizes the invalid sorting %s field by field', (extra, expected) => {
    expect(stateOf(list(extra))).toMatchObject(expected)
  })

  it('does not let malformed values break route matching', () => {
    expect(list('&page=%E0%A4%A&size=%&sort=%zz')).toMatchObject({
      kind: 'business-schedule-exceptions',
    })
    expect(readAuthenticatedRoute(`#/business/schedule/exceptions/abc-1?${W}&page=-1&size=3`)).toMatchObject({
      kind: 'business-schedule-exception-detail',
      returnWindow: { page: 0, size: 10 },
    })
  })

  it('carries the return window on the create and detail routes and round-trips it', () => {
    const window = {
      from: '2026-10-01',
      to: '2026-10-30',
      page: 0,
      size: 10,
      sort: 'dates',
      direction: 'asc',
    }
    const query = '?from=2026-10-01&to=2026-10-30&page=0&size=10&sort=dates&direction=asc'

    const created = readAuthenticatedRoute(`#/business/schedule/exceptions/new${query}`)
    expect(created).toEqual({ kind: 'business-schedule-exception-new', returnWindow: window })
    expect(routeHref(created)).toBe(`/#/business/schedule/exceptions/new${query}`)

    const detail = readAuthenticatedRoute(`#/business/schedule/exceptions/abc-1${query}`)
    expect(detail).toEqual({
      kind: 'business-schedule-exception-detail',
      exceptionId: 'abc-1',
      returnWindow: window,
    })
    expect(routeHref(detail)).toBe(`/#/business/schedule/exceptions/abc-1${query}`)
  })

  it.each([
    ['?from=2026-10-01'],
    ['?to=2026-10-30'],
    ['?from=2026-10-30&to=2026-10-01'],
    ['?from=2026-02-30&to=2026-03-01'],
    ['?from=2026-10-01&to=2027-01-02'],
    ['?from=x&to=y'],
    ['?from=&to='],
  ])('discards the unusable return window %s on create and detail routes', (query) => {
    expect(readAuthenticatedRoute(`#/business/schedule/exceptions/new${query}`)).toEqual({
      kind: 'business-schedule-exception-new',
      returnWindow: null,
    })
    expect(readAuthenticatedRoute(`#/business/schedule/exceptions/abc-1${query}`)).toEqual({
      kind: 'business-schedule-exception-detail',
      exceptionId: 'abc-1',
      returnWindow: null,
    })
  })

  it('round-trips a canonical window through the hash query', () => {
    const route = readAuthenticatedRoute('#/business/schedule/exceptions?from=2026-10-01&to=2026-10-30')
    expect(route).toEqual({
      kind: 'business-schedule-exceptions',
      window: {
        from: '2026-10-01',
        to: '2026-10-30',
        page: 0,
        size: 10,
        sort: 'dates',
        direction: 'asc',
      },
    })
    expect(routeHref(route)).toBe(
      '/#/business/schedule/exceptions?from=2026-10-01&to=2026-10-30&page=0&size=10&sort=dates&direction=asc',
    )
  })

  it.each([
    ['?from=2026-10-01'],
    ['?to=2026-10-30'],
    ['?from=2026-10-30&to=2026-10-01'],
    ['?from=2026-02-30&to=2026-03-01'],
    ['?from=2026-1-1&to=2026-10-30'],
    ['?from=2026-10-01&to=2027-01-02'],
    ['?from=1999-12-31&to=2000-01-02'],
    ['?from=2100-12-30&to=2101-01-01'],
    ['?from=x&to=y'],
  ])('normalizes the unusable window %s to the default', (query) => {
    expect(readAuthenticatedRoute(`#/business/schedule/exceptions${query}`)).toEqual({
      kind: 'business-schedule-exceptions',
      window: null,
    })
  })

  it('accepts the maximum 93-date window', () => {
    expect(
      readAuthenticatedRoute('#/business/schedule/exceptions?from=2026-10-01&to=2026-12-31'),
    ).toMatchObject({
      kind: 'business-schedule-exceptions',
      window: { from: '2026-10-01', to: '2026-12-31', page: 0, size: 10 },
    })
  })

  it('treats every schedule route as a Business-owner route and recovers from a bad identifier', () => {
    expect(isBusinessOwnerRoute({ kind: 'business-schedule-exceptions', window: null })).toBe(true)
    expect(
      isBusinessOwnerRoute({ kind: 'business-schedule-exception-new', returnWindow: null }),
    ).toBe(true)
    expect(readAuthenticatedRoute('#/business/schedule/exceptions/%E0%A4%A')).toEqual({
      kind: 'business-schedule-exceptions',
      window: null,
    })
  })
})
