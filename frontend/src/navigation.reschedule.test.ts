import { describe, expect, it } from 'vitest'
import {
  BUSINESS_CALENDAR_ROUTE,
  CALENDAR_DEFAULT_STATE,
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

describe('the reschedule route', () => {
  it('carries the Appointment in the path and only the safe calendar origin in the query', () => {
    const route: AuthenticatedRoute = {
      kind: 'business-appointment-reschedule',
      appointmentId: APPOINTMENT,
      origin: {
        kind: 'calendar',
        calendar: { view: 'agenda', date: '2026-11-10', staffMemberId: STAFF, page: 2, size: 25 },
      },
    }
    expect(routeHref(route)).toBe(
      `/#/business/appointments/${APPOINTMENT}/reschedule?view=agenda&date=2026-11-10&staff=${STAFF}&page=2&size=25`,
    )
    expect(roundTrip(route)).toEqual(route)
  })

  it('round-trips a Customer origin', () => {
    const route: AuthenticatedRoute = {
      kind: 'business-appointment-reschedule',
      appointmentId: APPOINTMENT,
      origin: { kind: 'customer', customerId: CUSTOMER },
    }
    expect(routeHref(route)).toBe(`/#/business/appointments/${APPOINTMENT}/reschedule?customer=${CUSTOMER}`)
    expect(roundTrip(route)).toEqual(route)
  })

  it('is distinct from the detail and from the create route', () => {
    expect(readAuthenticatedRoute(`#/business/appointments/${APPOINTMENT}`).kind).toBe('business-appointment-detail')
    expect(readAuthenticatedRoute('#/business/appointments/new').kind).toBe('business-appointment-new')
    expect(readAuthenticatedRoute(`#/business/appointments/${APPOINTMENT}/reschedule`)).toEqual({
      kind: 'business-appointment-reschedule',
      appointmentId: APPOINTMENT,
      origin: { kind: 'calendar', calendar: CALENDAR_DEFAULT_STATE },
    })
  })

  it('drops everything that is not a safe navigation value, including a smuggled private value or version', () => {
    const route = readAuthenticatedRoute(
      `#/business/appointments/${APPOINTMENT}/reschedule?view=week&name=Мария&version=7&start=2026-11-10T09:00:00Z&staff=${STAFF}`,
    )
    expect(routeHref(route)).toBe(`/#/business/appointments/${APPOINTMENT}/reschedule?view=week&staff=${STAFF}`)
  })

  it('falls back to the calendar for a malformed path and ignores deeper paths', () => {
    expect(readAuthenticatedRoute('#/business/appointments/%E0%A4%A/reschedule')).toEqual(BUSINESS_CALENDAR_ROUTE)
    expect(readAuthenticatedRoute(`#/business/appointments/${APPOINTMENT}/reschedule/extra`).kind).not.toBe(
      'business-appointment-reschedule',
    )
  })
})
