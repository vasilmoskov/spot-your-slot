import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  BUSINESS_SERVICES_ROUTE,
  PROFILE_ROUTE,
  PLATFORM_BUSINESSES_ROUTE,
} from '../navigation'
import { PlatformAdminShell } from './PlatformAdminShell'

function renderShell(platformAdmin = true, businessOwner = false, activeBusinessName?: string) {
  const onNavigate = vi.fn()
  const onLogout = vi.fn()
  const { unmount } = render(
    <PlatformAdminShell
      route={PROFILE_ROUTE}
      platformAdmin={platformAdmin}
      businessOwner={businessOwner}
      displayName="Иван Иванов"
      activeBusinessName={activeBusinessName}
      busy={false}
      onNavigate={onNavigate}
      onLogout={onLogout}
    >
      <p>Съдържание</p>
    </PlatformAdminShell>,
  )
  return { onNavigate, onLogout, unmount }
}

describe('PlatformAdminShell', () => {
  it('shows platform navigation only to platform administrators', () => {
    const { unmount } = render(
      <PlatformAdminShell
        route={PROFILE_ROUTE}
        platformAdmin
        businessOwner={false}
        displayName="Администратор"
        activeBusinessName={undefined}
        busy={false}
        onNavigate={vi.fn()}
        onLogout={vi.fn()}
      >
        <p>Съдържание</p>
      </PlatformAdminShell>,
    )
    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Бизнеси',
      'Профил',
    ])

    unmount()
    renderShell(false)
    expect(screen.queryByRole('link', { name: 'Бизнеси' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual(['Профил'])
  })

  it('shows Business-owner configuration destinations only to an owner of the active Business', () => {
    const { unmount } = renderShell(false, true)
    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Услуги',
      'Екип',
      'Работно време',
      'Профил',
    ])

    unmount()
    const { onNavigate } = renderShell(true, true)
    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Бизнеси',
      'Услуги',
      'Екип',
      'Работно време',
      'Профил',
    ])
    fireEvent.click(screen.getByRole('link', { name: 'Услуги' }))
    expect(onNavigate).toHaveBeenCalledWith(BUSINESS_SERVICES_ROUTE)
  })

  it('focuses Businesses first for a platform administrator', () => {
    renderShell()
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

  it('focuses Profile first for a non-platform authenticated user', () => {
    renderShell(false)

    fireEvent.click(screen.getByRole('button', { name: 'Отвори навигацията' }))

    expect(screen.getByRole('link', { name: 'Профил' })).toHaveFocus()
  })

  it('closes mobile navigation after navigating', () => {
    const { onNavigate } = renderShell()
    fireEvent.click(screen.getByRole('button', { name: 'Отвори навигацията' }))
    fireEvent.click(screen.getByRole('link', { name: 'Бизнеси' }))

    expect(onNavigate).toHaveBeenCalledWith(PLATFORM_BUSINESSES_ROUTE)
    expect(screen.getByRole('button', { name: 'Отвори навигацията' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
  })

  it('keeps the current user and logout reachable', () => {
    const { onLogout } = renderShell()
    expect(screen.getAllByText('Иван Иванов')).not.toHaveLength(0)
    fireEvent.click(screen.getAllByRole('button', { name: 'Изход' })[0]!)
    expect(onLogout).toHaveBeenCalledOnce()
  })

  it('shows the active Business name above Logout for an owner, not the personal display name', () => {
    renderShell(false, true, 'Бизнес А')
    expect(document.querySelector('.sidebar-account')).toHaveTextContent('Бизнес А')
    expect(document.querySelector('.mobile-account')).toHaveTextContent('Бизнес А')
    expect(screen.queryByText('Иван Иванов')).not.toBeInTheDocument()
  })

  it('updates the sidebar identity immediately when the active Business changes, with no stale name', () => {
    const { rerender } = render(
      <PlatformAdminShell
        route={PROFILE_ROUTE}
        platformAdmin={false}
        businessOwner
        displayName="Иван Иванов"
        activeBusinessName="Бизнес А"
        busy={false}
        onNavigate={vi.fn()}
        onLogout={vi.fn()}
      >
        <p>Съдържание</p>
      </PlatformAdminShell>,
    )
    expect(document.querySelector('.sidebar-account')).toHaveTextContent('Бизнес А')

    rerender(
      <PlatformAdminShell
        route={PROFILE_ROUTE}
        platformAdmin={false}
        businessOwner
        displayName="Иван Иванов"
        activeBusinessName="Бизнес Б"
        busy={false}
        onNavigate={vi.fn()}
        onLogout={vi.fn()}
      >
        <p>Съдържание</p>
      </PlatformAdminShell>,
    )
    expect(document.querySelector('.sidebar-account')).toHaveTextContent('Бизнес Б')
    expect(screen.queryByText('Бизнес А')).not.toBeInTheDocument()
  })

  it('does not show a stale or invented Business name for a non-owner', () => {
    renderShell(false, false, undefined)
    expect(document.querySelector('.sidebar-account')).toHaveTextContent('Иван Иванов')
  })
})
