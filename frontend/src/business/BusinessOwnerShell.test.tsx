import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  BUSINESSES_ROUTE,
  BUSINESS_CUSTOMERS_ROUTE,
  BUSINESS_SERVICES_ROUTE,
  BUSINESS_STAFF_ROUTE,
  CUSTOMERS_DEFAULT_LIST,
  PROFILE_ROUTE,
  type AuthenticatedRoute,
} from '../navigation'
import { BusinessOwnerShell } from './BusinessOwnerShell'

function renderShell(status: string = 'ACTIVE', route: AuthenticatedRoute = BUSINESS_SERVICES_ROUTE) {
  const onNavigate = vi.fn()
  const onLogout = vi.fn()
  const result = render(
    <BusinessOwnerShell
      route={route}
      activeBusiness={{ displayName: 'Студио А', status }}
      displayName="Иван Собственик"
      platformAdmin={false}
      hasOwnedBusinesses
      busy={false}
      onNavigate={onNavigate}
      onLogout={onLogout}
    >
      <p>Съдържание</p>
    </BusinessOwnerShell>,
  )
  return { onNavigate, onLogout, unmount: result.unmount }
}

describe('BusinessOwnerShell', () => {
  it('separates the global links from the selected Business, which is named as their heading', () => {
    renderShell()
    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Бизнеси',
      'Профил',
      'Услуги',
      'Екип',
      'Работно време',
      'Клиенти',
    ])
    const group = screen.getByRole('group', { name: 'Студио А' })
    expect(within(group).getAllByRole('link')).toHaveLength(4)
    expect(screen.queryByRole('link', { name: 'Моите бизнеси' })).not.toBeInTheDocument()
  })

  it('uses the stable eyebrow copy and never puts the Business name in the page header', () => {
    renderShell()
    const headerRow = document.querySelector('.business-identity-row') as HTMLElement
    expect(within(headerRow).getByText('УПРАВЛЕНИЕ НА БИЗНЕСА')).toBeInTheDocument()
    expect(within(headerRow).queryByText('Студио А')).not.toBeInTheDocument()
  })

  it('shows the signed-in user, not the Business, above Logout in both account areas', () => {
    renderShell()
    expect(document.querySelector('.sidebar-account')).toHaveTextContent('Иван Собственик')
    expect(document.querySelector('.sidebar-account')).not.toHaveTextContent('Студио А')
    expect(document.querySelector('.mobile-account')).toHaveTextContent('Иван Собственик')
    expect(document.querySelector('.sidebar-account')).toHaveTextContent('Изход')
    expect(document.querySelector('.mobile-account')).toHaveTextContent('Изход')
  })

  it('shows no Business group when there is no active Business', () => {
    render(
      <BusinessOwnerShell
        route={BUSINESS_SERVICES_ROUTE}
        activeBusiness={undefined}
        displayName="Иван Собственик"
        platformAdmin={false}
        hasOwnedBusinesses
        busy={false}
        onNavigate={vi.fn()}
        onLogout={vi.fn()}
      >
        <p>Съдържание</p>
      </BusinessOwnerShell>,
    )
    expect(screen.queryByRole('group')).not.toBeInTheDocument()
    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Бизнеси',
      'Профил',
    ])
  })

  it('never shows a lifecycle badge in the page header, for any Business status', () => {
    for (const status of ['DRAFT', 'ACTIVE', 'SUSPENDED']) {
      const { unmount } = renderShell(status)
      const headerRow = document.querySelector('.business-identity-row') as HTMLElement
      expect(within(headerRow).getByText('УПРАВЛЕНИЕ НА БИЗНЕСА')).toBeInTheDocument()
      expect(headerRow.querySelector('.status-badge')).not.toBeInTheDocument()
      unmount()
    }
  })

  it('does not bring back the retired "Предстои активиране" copy for a DRAFT Business', () => {
    renderShell('DRAFT')
    expect(screen.queryByText('Предстои активиране')).not.toBeInTheDocument()
    expect(screen.queryByText('Бизнесът е в подготовка')).not.toBeInTheDocument()
  })

  it('communicates SUSPENDED only through the dedicated read-only notice, not a header badge', () => {
    renderShell('SUSPENDED')
    expect(screen.queryByText('Временно спрян')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('временно спрян')
    expect(screen.getByRole('status')).toHaveTextContent('конфигурацията')
  })

  it('does not show a read-only notice for an ACTIVE or DRAFT Business', () => {
    renderShell('ACTIVE')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('navigates between the global and the Business destinations', () => {
    const { onNavigate } = renderShell()
    fireEvent.click(screen.getByRole('link', { name: 'Екип' }))
    expect(onNavigate).toHaveBeenCalledWith(BUSINESS_STAFF_ROUTE)
    fireEvent.click(screen.getByRole('link', { name: 'Клиенти' }))
    expect(onNavigate).toHaveBeenCalledWith(BUSINESS_CUSTOMERS_ROUTE)
    fireEvent.click(screen.getByRole('link', { name: 'Бизнеси' }))
    expect(onNavigate).toHaveBeenCalledWith(BUSINESSES_ROUTE)
    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    expect(onNavigate).toHaveBeenCalledWith(PROFILE_ROUTE)
  })

  it.each([
    [{ kind: 'business-customers', list: CUSTOMERS_DEFAULT_LIST }, 'Клиенти'],
    [{ kind: 'business-customer-new', returnList: null }, 'Нов клиент'],
    [{ kind: 'business-customer-detail', customerId: 'c', returnList: null }, 'Клиент'],
  ] as const)('names the Customer page heading for %j and marks Клиенти current', (route, heading) => {
    renderShell('ACTIVE', route)
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(new RegExp(`^${heading}$`))
    expect(document.querySelector('.navigation-link[aria-current="page"]')).toHaveTextContent(
      'Клиенти',
    )
  })

  it('uses Customer-specific SUSPENDED wording exactly once on every Customer route', () => {
    for (const route of [
      { kind: 'business-customers', list: CUSTOMERS_DEFAULT_LIST },
      { kind: 'business-customer-new', returnList: null },
      { kind: 'business-customer-detail', customerId: 'c', returnList: null },
    ] as const) {
      const { unmount } = renderShell('SUSPENDED', route)
      const notices = screen.getAllByRole('status')
      expect(notices).toHaveLength(1)
      expect(notices[0]).toHaveTextContent(
        'Бизнесът е временно спрян. Можете да преглеждате и редактирате клиентите, но не можете да добавяте нови.',
      )
      expect(document.body.textContent).not.toContain('конфигурацията')
      unmount()
    }
  })

  it('keeps the generic SUSPENDED wording on the other Business screens', () => {
    renderShell('SUSPENDED', BUSINESS_STAFF_ROUTE)
    expect(screen.getByRole('status')).toHaveTextContent(
      'Бизнесът е временно спрян. Данните са видими, но конфигурацията не може да бъде променяна.',
    )
  })

  it('supports keyboard-accessible mobile navigation and logout', () => {
    const { onLogout } = renderShell()
    fireEvent.click(screen.getByRole('button', { name: 'Отвори навигацията' }))
    expect(screen.getByRole('link', { name: 'Бизнеси' })).toHaveFocus()

    fireEvent.keyDown(screen.getByRole('navigation'), { key: 'Escape' })
    expect(screen.getByRole('button', { name: 'Отвори навигацията' })).toHaveFocus()

    fireEvent.click(screen.getAllByRole('button', { name: 'Изход' })[0]!)
    expect(onLogout).toHaveBeenCalledOnce()
  })
})
