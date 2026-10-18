import type { AuthenticatedRoute } from '../navigation'

// What the shared SUSPENDED banner says. The wording follows the domain operation of the screen
// instead of one generic sentence, so a screen never describes its records as something else.
const GENERIC_SUSPENDED_NOTICE =
  'Бизнесът е временно спрян. Данните са видими, но конфигурацията не може да бъде променяна.'

const CUSTOMERS_SUSPENDED_NOTICE =
  'Бизнесът е временно спрян. Можете да преглеждате и редактирате клиентите, но не можете да добавяте нови.'

const CALENDAR_SUSPENDED_NOTICE =
  'Бизнесът е временно спрян. Можете да преглеждате графика и часовете, но не можете да ги променяте.'

const CALENDAR_DRAFT_NOTICE =
  'Бизнесът още не е активен. Можете да преглеждате графика и часовете, но не можете да ги променяте.'

export function isCalendarRoute(route: AuthenticatedRoute): boolean {
  return (
    route.kind === 'business-calendar' ||
    route.kind === 'business-appointment-detail' ||
    route.kind === 'business-appointment-new' ||
    route.kind === 'business-appointment-reschedule'
  )
}

/** The one lifecycle statement of a calendar screen, or null when the Business state needs none. */
export function calendarLifecycleNotice(route: AuthenticatedRoute, status: string | undefined): string | null {
  if (!isCalendarRoute(route)) return null
  if (status === 'SUSPENDED') return CALENDAR_SUSPENDED_NOTICE
  if (status === 'DRAFT') return CALENDAR_DRAFT_NOTICE
  return null
}

export function suspendedNotice(route: AuthenticatedRoute): string {
  if (isCalendarRoute(route)) return CALENDAR_SUSPENDED_NOTICE
  if (route.kind.startsWith('business-customer')) return CUSTOMERS_SUSPENDED_NOTICE
  return GENERIC_SUSPENDED_NOTICE
}
