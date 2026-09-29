import { useId, useRef, useState, useLayoutEffect, type ReactNode } from 'react'
import { Button } from '../ui/Button'
import {
  BUSINESS_SCHEDULE_ROUTE,
  BUSINESS_SERVICES_ROUTE,
  BUSINESS_STAFF_ROUTE,
  isBusinessOwnerRoute,
  isPlatformRoute,
  PROFILE_ROUTE,
  PLATFORM_BUSINESSES_ROUTE,
  routeHref,
  type AuthenticatedRoute,
} from '../navigation'

type PlatformAdminShellProps = {
  route: AuthenticatedRoute
  platformAdmin: boolean
  businessOwner: boolean
  displayName: string
  activeBusinessName: string | undefined
  busy: boolean
  children: ReactNode
  onNavigate: (route: AuthenticatedRoute) => void
  onLogout: () => void
}

export function PlatformAdminShell({
  route,
  platformAdmin,
  businessOwner,
  displayName,
  activeBusinessName,
  busy,
  children,
  onNavigate,
  onLogout,
}: PlatformAdminShellProps) {
  const accountIdentity = businessOwner ? activeBusinessName : displayName
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false)
  const navigationId = useId()
  const triggerRef = useRef<HTMLButtonElement>(null)
  const firstNavigationItemRef = useRef<HTMLAnchorElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)

  useLayoutEffect(() => {
    if (mobileNavigationOpen) {
      firstNavigationItemRef.current?.focus()
    }
  }, [mobileNavigationOpen])

  useLayoutEffect(() => {
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
          aria-label="Основна навигация"
          onKeyDown={(event) => {
            if (event.key === 'Escape' && mobileNavigationOpen) {
              closeMobileNavigation(true)
            }
          }}
        >
          {platformAdmin && (
            <a
              className="navigation-link"
              ref={firstNavigationItemRef}
              href={routeHref(PLATFORM_BUSINESSES_ROUTE)}
              aria-current={isPlatformRoute(route) ? 'page' : undefined}
              onClick={(event) => navigate(event, PLATFORM_BUSINESSES_ROUTE)}
            >
              Бизнеси
            </a>
          )}
          {businessOwner && (
            <>
              <a
                className="navigation-link"
                ref={platformAdmin ? undefined : firstNavigationItemRef}
                href={routeHref(BUSINESS_SERVICES_ROUTE)}
                aria-current={isBusinessOwnerRoute(route) ? 'page' : undefined}
                onClick={(event) => navigate(event, BUSINESS_SERVICES_ROUTE)}
              >
                Услуги
              </a>
              <a
                className="navigation-link"
                href={routeHref(BUSINESS_STAFF_ROUTE)}
                onClick={(event) => navigate(event, BUSINESS_STAFF_ROUTE)}
              >
                Екип
              </a>
              <a
                className="navigation-link"
                href={routeHref(BUSINESS_SCHEDULE_ROUTE)}
                onClick={(event) => navigate(event, BUSINESS_SCHEDULE_ROUTE)}
              >
                Работно време
              </a>
            </>
          )}
          <a
              className="navigation-link"
            ref={!platformAdmin && !businessOwner ? firstNavigationItemRef : undefined}
            href={routeHref(PROFILE_ROUTE)}
            aria-current={route.kind === 'profile' ? 'page' : undefined}
            onClick={(event) => navigate(event, PROFILE_ROUTE)}
          >
            Профил
          </a>
          <div className="mobile-account">
            {accountIdentity && <span>{accountIdentity}</span>}
            <Button
              type="button"
              variant="secondary"
              disabled={busy}
              onClick={onLogout}
            >
              Изход
            </Button>
          </div>
        </nav>
        <div className="sidebar-account">
          {accountIdentity && <span>{accountIdentity}</span>}
          <Button type="button" variant="secondary" disabled={busy} onClick={onLogout}>
            Изход
          </Button>
        </div>
      </aside>

      <main className="platform-main">
        <div className="platform-page-header">
          <p className="eyebrow">Администрация</p>
          <h1 ref={headingRef} tabIndex={-1}>
            {route.kind === 'profile'
              ? 'Профил'
              : route.kind === 'platform-business-new'
                ? 'Нов бизнес'
                : route.kind === 'platform-business-detail'
                  ? 'Бизнес'
                  : 'Бизнеси'}
          </h1>
        </div>
        {children}
      </main>
    </div>
  )
}
