import { useId, type MouseEvent, type Ref } from 'react'
import {
  BUSINESSES_ROUTE,
  BUSINESS_CUSTOMERS_ROUTE,
  BUSINESS_SCHEDULE_ROUTE,
  BUSINESS_SERVICES_ROUTE,
  BUSINESS_STAFF_ROUTE,
  PROFILE_ROUTE,
  PLATFORM_BUSINESSES_ROUTE,
  isPlatformRoute,
  routeHref,
  type AuthenticatedRoute,
} from '../navigation'

export type SelectedBusiness = {
  displayName: string
}

type ShellNavigationProps = {
  route: AuthenticatedRoute
  platformAdmin: boolean
  // The user manages at least one Business (an active owner Membership).
  hasOwnedBusinesses: boolean
  // Present only while a Business the user manages is selected.
  selectedBusiness: SelectedBusiness | undefined
  firstItemRef: Ref<HTMLAnchorElement>
  onNavigate: (event: MouseEvent<HTMLAnchorElement>, route: AuthenticatedRoute) => void
}

const SCOPED_LINKS: ReadonlyArray<{
  label: string
  route: AuthenticatedRoute
  kinds: readonly string[]
}> = [
  {
    label: 'Услуги',
    route: BUSINESS_SERVICES_ROUTE,
    kinds: ['business-services', 'business-service-new', 'business-service-detail'],
  },
  {
    label: 'Екип',
    route: BUSINESS_STAFF_ROUTE,
    kinds: ['business-staff', 'business-staff-new', 'business-staff-detail'],
  },
  {
    label: 'Работно време',
    route: BUSINESS_SCHEDULE_ROUTE,
    kinds: [
      'business-schedule',
      'business-schedule-exceptions',
      'business-schedule-exception-new',
      'business-schedule-exception-detail',
    ],
  },
  {
    label: 'Клиенти',
    route: BUSINESS_CUSTOMERS_ROUTE,
    kinds: ['business-customers', 'business-customer-new', 'business-customer-detail'],
  },
]

/**
 * The shared sidebar links. Two groups stay visibly separate: the global account destinations
 * (always present) and, only while a Business is selected, that Business's destinations under
 * its name. A Business-scoped link never exists without its Business context.
 */
export function ShellNavigation({
  route,
  platformAdmin,
  hasOwnedBusinesses,
  selectedBusiness,
  firstItemRef,
  onNavigate,
}: ShellNavigationProps) {
  const contextHeadingId = useId()
  // A Platform Administrator's "Бизнеси" is the platform list; an administrator who also manages
  // Businesses reaches their own Businesses through a second, differently named link.
  const showOwnBusinesses = !platformAdmin || hasOwnedBusinesses
  const ownBusinessesLabel = platformAdmin ? 'Моите бизнеси' : 'Бизнеси'

  return (
    <>
      <div className="navigation-group">
        {platformAdmin && (
          <a
            className="navigation-link"
            ref={firstItemRef}
            href={routeHref(PLATFORM_BUSINESSES_ROUTE)}
            aria-current={isPlatformRoute(route) ? 'page' : undefined}
            onClick={(event) => onNavigate(event, PLATFORM_BUSINESSES_ROUTE)}
          >
            Бизнеси
          </a>
        )}
        {showOwnBusinesses && (
          <a
            className="navigation-link"
            ref={platformAdmin ? undefined : firstItemRef}
            href={routeHref(BUSINESSES_ROUTE)}
            aria-current={route.kind === 'businesses' ? 'page' : undefined}
            onClick={(event) => onNavigate(event, BUSINESSES_ROUTE)}
          >
            {ownBusinessesLabel}
          </a>
        )}
        <a
          className="navigation-link"
          href={routeHref(PROFILE_ROUTE)}
          aria-current={route.kind === 'profile' ? 'page' : undefined}
          onClick={(event) => onNavigate(event, PROFILE_ROUTE)}
        >
          Профил
        </a>
      </div>
      {selectedBusiness && (
        <div
          className="navigation-group navigation-context"
          role="group"
          aria-labelledby={contextHeadingId}
        >
          <p id={contextHeadingId} className="navigation-context-heading">
            {selectedBusiness.displayName}
          </p>
          {SCOPED_LINKS.map((link) => (
            <a
              key={link.label}
              className="navigation-link"
              href={routeHref(link.route)}
              aria-current={link.kinds.includes(route.kind) ? 'page' : undefined}
              onClick={(event) => onNavigate(event, link.route)}
            >
              {link.label}
            </a>
          ))}
        </div>
      )}
    </>
  )
}
