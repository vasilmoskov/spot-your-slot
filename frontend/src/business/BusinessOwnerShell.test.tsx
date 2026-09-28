import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { BUSINESS_SERVICES_ROUTE, BUSINESS_STAFF_ROUTE, PROFILE_ROUTE } from '../navigation'
import { BusinessOwnerShell } from './BusinessOwnerShell'

function renderShell(status: string = 'ACTIVE') {
  const onNavigate = vi.fn()
  const onLogout = vi.fn()
  const result = render(
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
  return { onNavigate, onLogout, unmount: result.unmount }
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
  })

  it('does not show a read-only notice for an ACTIVE Business', () => {
    renderShell('ACTIVE')
    expect(screen.queryByText('Активен')).not.toBeInTheDocument()
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
