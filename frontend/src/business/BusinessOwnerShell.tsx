import { useId, useRef, useState, useLayoutEffect, type ReactNode } from 'react'
import { Button } from '../ui/Button'
import { ShellNavigation } from '../ui/ShellNavigation'
import type { AuthenticatedRoute } from '../navigation'
import { suspendedNotice } from './lifecycleNotice'

export type ActiveBusinessIdentity = {
  displayName: string
  status: string
}

type BusinessOwnerShellProps = {
  route: AuthenticatedRoute
  activeBusiness: ActiveBusinessIdentity | undefined
  displayName: string
  platformAdmin: boolean
  hasOwnedBusinesses: boolean
  busy: boolean
  children: ReactNode
  onNavigate: (route: AuthenticatedRoute) => void
  onLogout: () => void
}

function sectionTitle(route: AuthenticatedRoute): string {
  if (route.kind === 'business-service-new') return 'Нова услуга'
  if (route.kind === 'business-service-detail') return 'Услуга'
  if (route.kind === 'business-staff') return 'Екип'
  if (route.kind === 'business-staff-new') return 'Нов член на екипа'
  if (route.kind === 'business-staff-detail') return 'Член на екипа'
  // Both schedule tabs share one heading; the active tab names the subsection.
  if (route.kind === 'business-schedule' || route.kind === 'business-schedule-exceptions') {
    return 'Работно време'
  }
  if (route.kind === 'business-customers') return 'Клиенти'
  if (route.kind === 'business-customer-new') return 'Нов клиент'
  if (route.kind === 'business-customer-detail') return 'Клиент'
  if (route.kind === 'business-schedule-exception-new') return 'Нова промяна в графика'
  if (route.kind === 'business-schedule-exception-detail') return 'Промяна в графика'
  return 'Услуги'
}

export function BusinessOwnerShell({
  route,
  activeBusiness,
  displayName,
  platformAdmin,
  hasOwnedBusinesses,
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
          aria-label="Навигация на бизнеса"
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
            selectedBusiness={activeBusiness}
            firstItemRef={firstNavigationItemRef}
            onNavigate={navigate}
          />
          <div className="mobile-account">
            {displayName && <span>{displayName}</span>}
            <Button type="button" variant="secondary" disabled={busy} onClick={onLogout}>
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
          <div className="business-identity-row">
            <p className="eyebrow">УПРАВЛЕНИЕ НА БИЗНЕСА</p>
          </div>
          <h1 ref={headingRef} tabIndex={-1}>
            {sectionTitle(route)}
          </h1>
        </div>
        {suspended && (
          <div className="platform-content">
            <p className="shell-banner" role="status">
              {suspendedNotice(route)}
            </p>
          </div>
        )}
        {children}
      </main>
    </div>
  )
}
