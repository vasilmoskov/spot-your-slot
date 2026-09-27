import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Button } from '../ui/Button'
import {
  BUSINESS_SCHEDULE_ROUTE,
  BUSINESS_SERVICES_ROUTE,
  BUSINESS_STAFF_ROUTE,
  PROFILE_ROUTE,
  routeHref,
  type AuthenticatedRoute,
} from '../navigation'

export type ActiveBusinessIdentity = {
  displayName: string
  status: string
}

const BUSINESS_STATUS_LABEL: Record<string, { label: string; tone: 'neutral' | 'success' | 'warning' }> = {
  DRAFT: { label: 'Бизнесът е в подготовка', tone: 'neutral' },
  ACTIVE: { label: 'Активен', tone: 'success' },
  SUSPENDED: { label: 'Временно спрян', tone: 'warning' },
}

type BusinessOwnerShellProps = {
  route: AuthenticatedRoute
  activeBusiness: ActiveBusinessIdentity | undefined
  busy: boolean
  children: ReactNode
  onNavigate: (route: AuthenticatedRoute) => void
  onLogout: () => void
}

function sectionTitle(route: AuthenticatedRoute): string {
  if (route.kind === 'business-service-new') return 'Нова услуга'
  if (route.kind === 'business-service-detail') return 'Услуга'
  if (route.kind === 'business-staff') return 'Екип'
  if (route.kind === 'business-schedule') return 'Работно време'
  return 'Услуги'
}

export function BusinessOwnerShell({
  route,
  activeBusiness,
  busy,
  children,
  onNavigate,
  onLogout,
}: BusinessOwnerShellProps) {
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false)
  const navigationId = useId()
  const triggerRef = useRef<HTMLButtonElement>(null)
  const firstNavigationItemRef = useRef<HTMLAnchorElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    if (mobileNavigationOpen) {
      firstNavigationItemRef.current?.focus()
    }
  }, [mobileNavigationOpen])

  useEffect(() => {
    headingRef.current?.focus()
  }, [route.kind])

  const closeMobileNavigation = (restoreFocus: boolean) => {
    setMobileNavigationOpen(false)
    if (restoreFocus) {
      triggerRef.current?.focus()
    }
  }

  const navigate = (
    event: React.MouseEvent<HTMLAnchorElement>,
    nextRoute: AuthenticatedRoute,
  ) => {
    event.preventDefault()
    setMobileNavigationOpen(false)
    onNavigate(nextRoute)
  }

  const status = activeBusiness ? BUSINESS_STATUS_LABEL[activeBusiness.status] : undefined
  const suspended = activeBusiness?.status === 'SUSPENDED'

  return (
    <div className="platform-shell">
      <header className="mobile-header">
        <span className="wordmark">SpotYourSlot</span>
        <Button
          ref={triggerRef}
          type="button"
          variant="secondary"
          className="navigation-toggle"
          aria-expanded={mobileNavigationOpen}
          aria-controls={navigationId}
          aria-label={mobileNavigationOpen ? 'Затвори навигацията' : 'Отвори навигацията'}
          onClick={() => {
            if (mobileNavigationOpen) {
              closeMobileNavigation(true)
            } else {
              setMobileNavigationOpen(true)
            }
          }}
        >
          Меню
        </Button>
      </header>

      <aside className="platform-sidebar">
        <div className="sidebar-brand wordmark">SpotYourSlot</div>
        <nav
          id={navigationId}
          className={mobileNavigationOpen ? 'primary-navigation is-open' : 'primary-navigation'}
          aria-label="Навигация на бизнеса"
          onKeyDown={(event) => {
            if (event.key === 'Escape' && mobileNavigationOpen) {
              closeMobileNavigation(true)
            }
          }}
        >
          <a
            className="navigation-link"
            ref={firstNavigationItemRef}
            href={routeHref(BUSINESS_SERVICES_ROUTE)}
            aria-current={
              route.kind === 'business-services' ||
              route.kind === 'business-service-new' ||
              route.kind === 'business-service-detail'
                ? 'page'
                : undefined
            }
            onClick={(event) => navigate(event, BUSINESS_SERVICES_ROUTE)}
          >
            Услуги
          </a>
          <a
            className="navigation-link"
            href={routeHref(BUSINESS_STAFF_ROUTE)}
            aria-current={route.kind === 'business-staff' ? 'page' : undefined}
            onClick={(event) => navigate(event, BUSINESS_STAFF_ROUTE)}
          >
            Екип
          </a>
          <a
            className="navigation-link"
            href={routeHref(BUSINESS_SCHEDULE_ROUTE)}
            aria-current={route.kind === 'business-schedule' ? 'page' : undefined}
            onClick={(event) => navigate(event, BUSINESS_SCHEDULE_ROUTE)}
          >
            Работно време
          </a>
          <a
            className="navigation-link"
            href={routeHref(PROFILE_ROUTE)}
            onClick={(event) => navigate(event, PROFILE_ROUTE)}
          >
            Профил
          </a>
          <div className="mobile-account">
            {activeBusiness && <span>{activeBusiness.displayName}</span>}
            <Button type="button" variant="secondary" disabled={busy} onClick={onLogout}>
              Изход
            </Button>
          </div>
        </nav>
        <div className="sidebar-account">
          {activeBusiness && <span>{activeBusiness.displayName}</span>}
          <Button type="button" variant="secondary" disabled={busy} onClick={onLogout}>
            Изход
          </Button>
        </div>
      </aside>

      <main className="platform-main">
        <div className="platform-page-header">
          <div className="business-identity-row">
            <p className="eyebrow">УПРАВЛЕНИЕ НА БИЗНЕСА</p>
            {status && (
              <span className={`status-badge status-badge-${status.tone}`}>{status.label}</span>
            )}
          </div>
          <h1 ref={headingRef} tabIndex={-1}>
            {sectionTitle(route)}
          </h1>
        </div>
        {suspended && (
          <div className="platform-content">
            <p role="status">
              Бизнесът е временно спрян. Данните са видими, но конфигурацията не може да бъде
              променяна.
            </p>
          </div>
        )}
        {children}
      </main>
    </div>
  )
}
