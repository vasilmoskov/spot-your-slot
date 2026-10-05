import { useId, useRef, useState, useLayoutEffect, type ReactNode } from 'react'
import { Button } from '../ui/Button'
import { ShellNavigation, type SelectedBusiness } from '../ui/ShellNavigation'
import type { AuthenticatedRoute } from '../navigation'

type PlatformAdminShellProps = {
  route: AuthenticatedRoute
  platformAdmin: boolean
  hasOwnedBusinesses: boolean
  displayName: string
  // Present only while a Business the user manages is selected.
  selectedBusiness: SelectedBusiness | undefined
  busy: boolean
  children: ReactNode
  onNavigate: (route: AuthenticatedRoute) => void
  onLogout: () => void
}

export function PlatformAdminShell({
  route,
  platformAdmin,
  hasOwnedBusinesses,
  displayName,
  selectedBusiness,
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
          data-focus-fallback
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
          <ShellNavigation
            route={route}
            platformAdmin={platformAdmin}
            hasOwnedBusinesses={hasOwnedBusinesses}
            selectedBusiness={selectedBusiness}
            firstItemRef={firstNavigationItemRef}
            onNavigate={navigate}
          />
          <div className="mobile-account">
            {displayName && <span>{displayName}</span>}
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
          {displayName && <span>{displayName}</span>}
          <Button type="button" variant="secondary" disabled={busy} onClick={onLogout}>
            Изход
          </Button>
        </div>
      </aside>

      <main className="platform-main">
        <div className="platform-page-header">
          <p className="eyebrow">
            {route.kind === 'businesses' ? 'Управление на бизнеса' : 'Администрация'}
          </p>
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
