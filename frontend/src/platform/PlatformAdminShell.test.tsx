import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { PROFILE_ROUTE, PLATFORM_BUSINESSES_ROUTE } from '../navigation'
import { PlatformAdminShell } from './PlatformAdminShell'

function renderShell(platformAdmin = true) {
  const onNavigate = vi.fn()
  const onLogout = vi.fn()
  render(
    <PlatformAdminShell
      route={PROFILE_ROUTE}
      platformAdmin={platformAdmin}
      displayName="Иван Иванов"
      busy={false}
      onNavigate={onNavigate}
      onLogout={onLogout}
    >
      <p>Съдържание</p>
    </PlatformAdminShell>,
  )
  return { onNavigate, onLogout }
}

describe('PlatformAdminShell', () => {
  it('shows platform navigation only to platform administrators', () => {
    const { unmount } = render(
      <PlatformAdminShell
        route={PROFILE_ROUTE}
        platformAdmin
        displayName="Администратор"
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
})
