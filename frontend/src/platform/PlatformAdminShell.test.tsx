import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  BUSINESSES_ROUTE,
  BUSINESS_CUSTOMERS_ROUTE,
  BUSINESS_SERVICES_ROUTE,
  PROFILE_ROUTE,
  PLATFORM_BUSINESSES_ROUTE,
  type AuthenticatedRoute,
} from '../navigation'
import { PlatformAdminShell } from './PlatformAdminShell'

type ShellOptions = {
  platformAdmin?: boolean
  hasOwnedBusinesses?: boolean
  selectedBusiness?: string
  route?: AuthenticatedRoute
}

function shell({
  platformAdmin = false,
  hasOwnedBusinesses = false,
  selectedBusiness,
  route = PROFILE_ROUTE,
}: ShellOptions = {}) {
  const onNavigate = vi.fn()
  const onLogout = vi.fn()
  const element = (name?: string) => (
    <PlatformAdminShell
      route={route}
      platformAdmin={platformAdmin}
      hasOwnedBusinesses={hasOwnedBusinesses}
      displayName="Иван Иванов"
      selectedBusiness={name === undefined ? undefined : { displayName: name }}
      busy={false}
      onNavigate={onNavigate}
      onLogout={onLogout}
    >
      <p>Съдържание</p>
    </PlatformAdminShell>
  )
  const result = render(element(selectedBusiness))
  return {
    onNavigate,
    onLogout,
    unmount: result.unmount,
    reselect: (name?: string) => result.rerender(element(name)),
  }
}

const links = () => screen.getAllByRole('link').map((link) => link.textContent)

describe('PlatformAdminShell navigation', () => {
  it('shows only the global destinations, without any Business link, when no Business is selected', () => {
    shell({ hasOwnedBusinesses: true })
    expect(links()).toEqual(['Бизнеси', 'Профил'])
    for (const name of ['Услуги', 'Екип', 'Работно време', 'Клиенти']) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument()
    }
    expect(screen.queryByRole('group')).not.toBeInTheDocument()
  })

  it('keeps Бизнеси permanent for a user who manages nothing yet', () => {
    shell()
    expect(links()).toEqual(['Бизнеси', 'Профил'])
  })

  it('names the selected Business as the heading of its own group below the global links', () => {
    shell({ hasOwnedBusinesses: true, selectedBusiness: 'Студио Активно' })
    expect(links()).toEqual([
      'Бизнеси',
      'Профил',
      'Услуги',
      'Екип',
      'Работно време',
      'Клиенти',
    ])
    const group = screen.getByRole('group', { name: 'Студио Активно' })
    expect(within(group).getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Услуги',
      'Екип',
      'Работно време',
      'Клиенти',
    ])
    // Global links are outside the Business group.
    expect(within(group).queryByRole('link', { name: 'Профил' })).toBeNull()
    expect(within(group).queryByRole('link', { name: 'Бизнеси' })).toBeNull()
  })

  it('never renders a technical role value', () => {
    shell({ hasOwnedBusinesses: true, selectedBusiness: 'Студио Активно' })
    expect(document.body.textContent).not.toMatch(/BUSINESS_OWNER|MANAGER|STAFF/)
  })

  it('shows the signed-in user, not the Business, above Logout', () => {
    shell({ hasOwnedBusinesses: true, selectedBusiness: 'Бизнес А' })
    expect(document.querySelector('.sidebar-account')).toHaveTextContent('Иван Иванов')
    expect(document.querySelector('.sidebar-account')).not.toHaveTextContent('Бизнес А')
    expect(document.querySelector('.mobile-account')).toHaveTextContent('Иван Иванов')
  })

  it('updates the Business context immediately when the selection changes, with no stale name', () => {
    const { reselect } = shell({ hasOwnedBusinesses: true, selectedBusiness: 'Бизнес А' })
    expect(screen.getByRole('group', { name: 'Бизнес А' })).toBeInTheDocument()
    reselect('Бизнес Б')
    expect(screen.getByRole('group', { name: 'Бизнес Б' })).toBeInTheDocument()
    expect(screen.queryByText('Бизнес А')).not.toBeInTheDocument()
    reselect(undefined)
    expect(screen.queryByRole('group')).not.toBeInTheDocument()
    expect(links()).toEqual(['Бизнеси', 'Профил'])
  })

  it('marks the current global destination and the current Business destination', () => {
    shell({ hasOwnedBusinesses: true, route: BUSINESSES_ROUTE, selectedBusiness: 'Бизнес А' })
    expect(screen.getByRole('link', { name: 'Бизнеси' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Профил' })).not.toHaveAttribute('aria-current')
  })

  it('navigates to the global and the Business destinations', () => {
    const { onNavigate } = shell({ hasOwnedBusinesses: true, selectedBusiness: 'Бизнес А' })
    fireEvent.click(screen.getByRole('link', { name: 'Бизнеси' }))
    expect(onNavigate).toHaveBeenLastCalledWith(BUSINESSES_ROUTE)
    fireEvent.click(screen.getByRole('link', { name: 'Услуги' }))
    expect(onNavigate).toHaveBeenLastCalledWith(BUSINESS_SERVICES_ROUTE)
    fireEvent.click(screen.getByRole('link', { name: 'Клиенти' }))
    expect(onNavigate).toHaveBeenLastCalledWith(BUSINESS_CUSTOMERS_ROUTE)
    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    expect(onNavigate).toHaveBeenLastCalledWith(PROFILE_ROUTE)
  })

  it('shows platform navigation only to platform administrators', () => {
    shell({ platformAdmin: true })
    expect(links()).toEqual(['Бизнеси', 'Профил'])
    fireEvent.click(screen.getByRole('link', { name: 'Бизнеси' }))
  })

  it('gives an administrator who also manages Businesses a second, differently named link', () => {
    const { onNavigate } = shell({
      platformAdmin: true,
      hasOwnedBusinesses: true,
      selectedBusiness: 'Бизнес А',
    })
    expect(links()).toEqual([
      'Бизнеси',
      'Моите бизнеси',
      'Профил',
      'Услуги',
      'Екип',
      'Работно време',
      'Клиенти',
    ])
    fireEvent.click(screen.getByRole('link', { name: 'Бизнеси' }))
    expect(onNavigate).toHaveBeenLastCalledWith(PLATFORM_BUSINESSES_ROUTE)
    fireEvent.click(screen.getByRole('link', { name: 'Моите бизнеси' }))
    expect(onNavigate).toHaveBeenLastCalledWith(BUSINESSES_ROUTE)
  })
})

describe('PlatformAdminShell mobile navigation and account', () => {
  it('focuses Businesses first for a platform administrator and restores focus on Escape', () => {
    shell({ platformAdmin: true })
    const trigger = screen.getByRole('button', { name: 'Отвори навигацията' })
    fireEvent.click(trigger)
    expect(screen.getByRole('button', { name: 'Затвори навигацията' })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    expect(screen.getByRole('link', { name: 'Бизнеси' })).toHaveFocus()
    fireEvent.keyDown(screen.getByRole('navigation'), { key: 'Escape' })
    expect(screen.getByRole('button', { name: 'Отвори навигацията' })).toHaveFocus()
  })

  it('focuses Businesses first for an owner', () => {
    shell({ hasOwnedBusinesses: true })
    fireEvent.click(screen.getByRole('button', { name: 'Отвори навигацията' }))
    expect(screen.getByRole('link', { name: 'Бизнеси' })).toHaveFocus()
  })

  it('closes mobile navigation after navigating', () => {
    const { onNavigate } = shell({ platformAdmin: true })
    fireEvent.click(screen.getByRole('button', { name: 'Отвори навигацията' }))
    fireEvent.click(screen.getByRole('link', { name: 'Бизнеси' }))
    expect(onNavigate).toHaveBeenCalledWith(PLATFORM_BUSINESSES_ROUTE)
    expect(screen.getByRole('button', { name: 'Отвори навигацията' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
  })

  it('keeps the current user and logout reachable', () => {
    const { onLogout } = shell({ platformAdmin: true })
    expect(screen.getAllByText('Иван Иванов')).not.toHaveLength(0)
    fireEvent.click(screen.getAllByRole('button', { name: 'Изход' })[0]!)
    expect(onLogout).toHaveBeenCalledOnce()
  })

  it('titles the Businesses page and the Profile page distinctly', () => {
    const { unmount } = shell({ hasOwnedBusinesses: true, route: BUSINESSES_ROUTE })
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/^Бизнеси$/)
    unmount()
    shell({ hasOwnedBusinesses: true })
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/^Профил$/)
  })
})
