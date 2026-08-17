import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import {
  PROFILE_ROUTE,
  PLATFORM_BUSINESSES_ROUTE,
  routeHref,
  type AuthenticatedRoute,
} from '../navigation'

type PlatformAdminShellProps = {
  route: AuthenticatedRoute
  platformAdmin: boolean
  displayName: string
  busy: boolean
  children: ReactNode
  onNavigate: (route: AuthenticatedRoute) => void
  onLogout: () => void
}

export function PlatformAdminShell({
  route,
  platformAdmin,
  displayName,
  busy,
  children,
  onNavigate,
  onLogout,
}: PlatformAdminShellProps) {
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

  return (
    <div className="platform-shell">
      <header className="mobile-header">
        <span className="wordmark">SpotYourSlot</span>
        <button
          ref={triggerRef}
          type="button"
          className="secondary-button navigation-toggle"
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
        </button>
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
              ref={firstNavigationItemRef}
              href={routeHref(PLATFORM_BUSINESSES_ROUTE)}
              aria-current={route.kind === 'platform-businesses' ? 'page' : undefined}
              onClick={(event) => navigate(event, PLATFORM_BUSINESSES_ROUTE)}
            >
              Бизнеси
            </a>
          )}
          <a
            ref={platformAdmin ? undefined : firstNavigationItemRef}
            href={routeHref(PROFILE_ROUTE)}
            aria-current={route.kind === 'profile' ? 'page' : undefined}
            onClick={(event) => navigate(event, PROFILE_ROUTE)}
          >
            Профил
          </a>
          <div className="mobile-account">
            <span>{displayName}</span>
            <button
              type="button"
              className="secondary-button"
              disabled={busy}
              onClick={onLogout}
            >
              Изход
            </button>
          </div>
        </nav>
        <div className="sidebar-account">
          <span>{displayName}</span>
          <button type="button" className="secondary-button" disabled={busy} onClick={onLogout}>
            Изход
          </button>
        </div>
      </aside>

      <main className="platform-main">
        <div className="platform-page-header">
          <p className="eyebrow">Администрация</p>
          <h1 ref={headingRef} tabIndex={-1}>
            {route.kind === 'platform-businesses' ? 'Бизнеси' : 'Профил'}
          </h1>
        </div>
        {children}
      </main>
    </div>
  )
}
