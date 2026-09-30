import {
  BUSINESS_SCHEDULE_EXCEPTIONS_ROUTE,
  BUSINESS_SCHEDULE_ROUTE,
  routeHref,
  type AuthenticatedRoute,
} from '../../navigation'

type ScheduleTabsProps = {
  current: 'weekly' | 'exceptions'
  onNavigate: (route: AuthenticatedRoute) => void
}

// The two destinations of "Работно време". Links navigate; the parent routes
// them through the shared unsaved-changes guard.
export function ScheduleTabs({ current, onNavigate }: ScheduleTabsProps) {
  const destinations = [
    { key: 'weekly', label: 'Седмични графици', route: BUSINESS_SCHEDULE_ROUTE },
    { key: 'exceptions', label: 'Промени в графика', route: BUSINESS_SCHEDULE_EXCEPTIONS_ROUTE },
  ] as const
  return (
    <nav className="schedule-tabs" aria-label="Работно време">
      {destinations.map((destination) => (
        <a
          key={destination.key}
          className="schedule-tab"
          href={routeHref(destination.route)}
          aria-current={current === destination.key ? 'page' : undefined}
          onClick={(event) => {
            event.preventDefault()
            if (current !== destination.key) onNavigate(destination.route)
          }}
        >
          {destination.label}
        </a>
      ))}
    </nav>
  )
}
