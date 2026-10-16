import { describe, expect, it } from 'vitest'
import {
  BUSINESS_CALENDAR_ROUTE,
  readAuthenticatedRoute,
  routeHref,
  type AuthenticatedRoute,
} from './navigation'

const STAFF = '11111111-1111-4111-8111-111111111111'
const APPOINTMENT = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const CUSTOMER = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

function roundTrip(route: AuthenticatedRoute): AuthenticatedRoute {
  return readAuthenticatedRoute(routeHref(route).slice(1))
}

describe('calendar routes', () => {
  it('defaults to the day view of the Business today', () => {
    expect(readAuthenticatedRoute('#/business/calendar')).toEqual(BUSINESS_CALENDAR_ROUTE)
    expect(routeHref(BUSINESS_CALENDAR_ROUTE)).toBe('/#/business/calendar?view=day')
  })

  it('round-trips every view with only safe navigation values', () => {
    const week: AuthenticatedRoute = {
      kind: 'business-calendar',
      calendar: { view: 'week', date: '2026-10-14', staffMemberId: STAFF, page: 0, size: 10 },
    }
    expect(routeHref(week)).toBe(`/#/business/calendar?view=week&date=2026-10-14&staff=${STAFF}`)
    expect(roundTrip(week)).toEqual(week)
    const agenda: AuthenticatedRoute = {
      kind: 'business-calendar',
      calendar: { view: 'agenda', date: '2026-10-14', staffMemberId: null, page: 2, size: 25 },
    }
    expect(routeHref(agenda)).toBe('/#/business/calendar?view=agenda&date=2026-10-14&page=2&size=25')
    expect(roundTrip(agenda)).toEqual(agenda)
  })

  it('normalizes invalid values to the defaults instead of failing', () => {
    expect(
      readAuthenticatedRoute('#/business/calendar?view=month&date=2026-02-30&staff=not-an-id&page=x&size=7'),
    ).toEqual(BUSINESS_CALENDAR_ROUTE)
    const route = readAuthenticatedRoute('#/business/calendar?view=agenda&date=1999-12-31&page=-1&size=99')
    expect(route).toEqual({
      kind: 'business-calendar',
      calendar: { view: 'agenda', date: null, staffMemberId: null, page: 0, size: 10 },
    })
    // Page and size belong to the agenda only.
    expect(
      readAuthenticatedRoute('#/business/calendar?view=week&page=4&size=50'),
    ).toEqual({
      kind: 'business-calendar',
      calendar: { view: 'week', date: null, staffMemberId: null, page: 0, size: 10 },
    })
  })

  it('never carries free text: a name, search or note in the query is dropped', () => {
    const route = readAuthenticatedRoute('#/business/calendar?view=day&customer=Мария&search=Мария&note=x')
    expect(routeHref(route)).toBe('/#/business/calendar?view=day')
  })

  it('opens an Appointment detail from the calendar or from a Customer, with a stable return', () => {
    const fromCalendar: AuthenticatedRoute = {
      kind: 'business-appointment-detail',
      appointmentId: APPOINTMENT,
      origin: {
        kind: 'calendar',
        calendar: { view: 'week', date: '2026-10-14', staffMemberId: null, page: 0, size: 10 },
      },
    }
    expect(routeHref(fromCalendar)).toBe(
      `/#/business/appointments/${APPOINTMENT}?view=week&date=2026-10-14`,
    )
    expect(roundTrip(fromCalendar)).toEqual(fromCalendar)
    const fromCustomer: AuthenticatedRoute = {
      kind: 'business-appointment-detail',
      appointmentId: APPOINTMENT,
      origin: { kind: 'customer', customerId: CUSTOMER },
    }
    expect(routeHref(fromCustomer)).toBe(`/#/business/appointments/${APPOINTMENT}?customer=${CUSTOMER}`)
    expect(roundTrip(fromCustomer)).toEqual(fromCustomer)
  })

  it('treats a malformed detail path as the calendar and a plain detail as the default return', () => {
    expect(readAuthenticatedRoute('#/business/appointments/%E0%A4%A')).toEqual(BUSINESS_CALENDAR_ROUTE)
    expect(readAuthenticatedRoute(`#/business/appointments/${APPOINTMENT}`)).toEqual({
      kind: 'business-appointment-detail',
      appointmentId: APPOINTMENT,
      origin: { kind: 'calendar', calendar: BUSINESS_CALENDAR_ROUTE.kind === 'business-calendar' ? BUSINESS_CALENDAR_ROUTE.calendar : undefined },
    })
  })
})
