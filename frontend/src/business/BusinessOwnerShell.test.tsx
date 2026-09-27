import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { BUSINESS_SERVICES_ROUTE, BUSINESS_STAFF_ROUTE, PROFILE_ROUTE } from '../navigation'
import { BusinessOwnerShell } from './BusinessOwnerShell'

function renderShell(status: string = 'ACTIVE') {
  const onNavigate = vi.fn()
  const onLogout = vi.fn()
  render(
    <BusinessOwnerShell
      route={BUSINESS_SERVICES_ROUTE}
      activeBusiness={{ displayName: 'Студио А', status }}
      busy={false}
      onNavigate={onNavigate}
      onLogout={onLogout}
    >
      <p>Съдържание</p>
    </BusinessOwnerShell>,
  )
  return { onNavigate, onLogout }
}

describe('BusinessOwnerShell', () => {
  it('identifies the active Business persistently above Logout, uses the stable eyebrow copy, and exposes only owner destinations', () => {
    renderShell()
    expect(
      within(document.querySelector('.sidebar-account') as HTMLElement).getByText('Студио А'),
    ).toBeInTheDocument()
    expect(
      within(document.querySelector('.mobile-account') as HTMLElement).getByText('Студио А'),
    ).toBeInTheDocument()
    const headerRow = document.querySelector('.business-identity-row') as HTMLElement
    expect(within(headerRow).getByText('УПРАВЛЕНИЕ НА БИЗНЕСА')).toBeInTheDocument()
    expect(within(headerRow).queryByText('Студио А')).not.toBeInTheDocument()
    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Услуги',
      'Екип',
      'Работно време',
      'Профил',
    ])
    expect(screen.queryByRole('link', { name: 'Бизнеси' })).not.toBeInTheDocument()
  })

  it('keeps Logout reachable alongside the Business name in the account areas', () => {
    renderShell()
    expect(document.querySelector('.sidebar-account')).toHaveTextContent('Изход')
    expect(document.querySelector('.mobile-account')).toHaveTextContent('Изход')
  })

  it('does not show any Business name when there is no active Business', () => {
    const onNavigate = vi.fn()
    const onLogout = vi.fn()
    render(
      <BusinessOwnerShell
        route={BUSINESS_SERVICES_ROUTE}
        activeBusiness={undefined}
        busy={false}
        onNavigate={onNavigate}
        onLogout={onLogout}
      >
        <p>Съдържание</p>
      </BusinessOwnerShell>,
    )
    expect(document.querySelector('.sidebar-account')?.querySelector('span')).toBeNull()
    expect(document.querySelector('.mobile-account')?.querySelector('span')).toBeNull()
  })

  it('associates the lifecycle status with the stable eyebrow, not the page title', () => {
    renderShell('SUSPENDED')
    const status = screen.getByText('Временно спрян')
    const headerRow = document.querySelector('.business-identity-row') as HTMLElement
    expect(within(headerRow).getByText('УПРАВЛЕНИЕ НА БИЗНЕСА')).toBeInTheDocument()
    expect(headerRow).toContainElement(status)
    expect(screen.getByRole('heading', { name: 'Услуги' })).not.toContainElement(status)
  })

  it('uses clearer Bulgarian wording for a DRAFT Business', () => {
    renderShell('DRAFT')
    expect(screen.getByText('Бизнесът е в подготовка')).toBeInTheDocument()
    expect(screen.queryByText('Предстои активиране')).not.toBeInTheDocument()
  })

  it('shows the active status and a read-only notice while Business is SUSPENDED', () => {
    renderShell('SUSPENDED')
    expect(screen.getByText('Временно спрян')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('временно спрян')
  })

  it('does not show a read-only notice for an ACTIVE Business', () => {
    renderShell('ACTIVE')
    expect(screen.getByText('Активен')).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('navigates to Staff and back to Profile', () => {
    const { onNavigate } = renderShell()
    fireEvent.click(screen.getByRole('link', { name: 'Екип' }))
    expect(onNavigate).toHaveBeenCalledWith(BUSINESS_STAFF_ROUTE)
    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    expect(onNavigate).toHaveBeenCalledWith(PROFILE_ROUTE)
  })

  it('supports keyboard-accessible mobile navigation and logout', () => {
    const { onLogout } = renderShell()
    fireEvent.click(screen.getByRole('button', { name: 'Отвори навигацията' }))
    expect(screen.getByRole('link', { name: 'Услуги' })).toHaveFocus()

    fireEvent.keyDown(screen.getByRole('navigation'), { key: 'Escape' })
    expect(screen.getByRole('button', { name: 'Отвори навигацията' })).toHaveFocus()

    fireEvent.click(screen.getAllByRole('button', { name: 'Изход' })[0]!)
    expect(onLogout).toHaveBeenCalledOnce()
  })
})
