import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { request, type Session } from './identity/api'

vi.mock('./identity/api', () => ({ request: vi.fn() }))

const mockedRequest = vi.mocked(request)
const session: Session = {
  displayName: 'Иван',
  platformAdmin: false,
  businesses: [
    { id: 'a', displayName: 'Бизнес А', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
    { id: 'b', displayName: 'Бизнес Б', role: 'MANAGER', status: 'ACTIVE' },
  ],
  activeBusinessId: 'a',
}

beforeEach(() => {
  history.replaceState({}, '', '/')
  mockedRequest.mockReset()
  mockedRequest.mockRejectedValue(new Error('Необходим е вход.'))
})

afterEach(() => vi.restoreAllMocks())

describe('identity application', () => {
  it('supports accessible login and does not write authentication data to browser storage', async () => {
    const browserStorageWrite = vi.spyOn(Storage.prototype, 'setItem')
    const indexedDatabaseOpen = vi.fn()
    vi.stubGlobal('indexedDB', { open: indexedDatabaseOpen })
    mockedRequest.mockRejectedValueOnce(new Error('Необходим е вход.')).mockResolvedValueOnce(session)
    render(<App />)
    fireEvent.change(screen.getByLabelText('Имейл'), {
      target: { value: 'owner@example.invalid' },
    })
    fireEvent.change(screen.getByLabelText('Парола'), {
      target: { value: 'secure passphrase' },
    })
    fireEvent.keyDown(screen.getByLabelText('Парола'), { key: 'Enter', code: 'Enter' })
    fireEvent.submit(screen.getByRole('button', { name: 'Вход' }).closest('form')!)
    expect(screen.getByRole('button', { name: 'Вход' })).toBeDisabled()
    expect(await screen.findByRole('heading', { name: 'Профил' })).toBeInTheDocument()
    expect(browserStorageWrite).not.toHaveBeenCalled()
    expect(indexedDatabaseOpen).not.toHaveBeenCalled()
  })

  it('shows the safe login failure', async () => {
    mockedRequest
      .mockRejectedValueOnce(new Error('Необходим е вход.'))
      .mockRejectedValueOnce(new Error('Имейлът или паролата са невалидни.'))
    render(<App />)
    fireEvent.change(screen.getByLabelText('Имейл'), {
      target: { value: 'owner@example.invalid' },
    })
    fireEvent.change(screen.getByLabelText('Парола'), { target: { value: 'wrong password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Вход' }))
    const error = await screen.findByRole('alert')
    expect(error).toHaveTextContent(
      'Имейлът или паролата са невалидни.',
    )
    expect(error).toHaveClass('status-error')
  })

  it('shows recovery guidance and returns accessibly to login with browser history', async () => {
    render(<App />)
    expect(screen.getByRole('heading', { name: 'Вход' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Забравена парола' }))
    expect(
      screen.getByRole('heading', { name: 'Възстановяване на парола' }),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/Ако съществува профил с този имейл/),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Изпрати' })).toBeInTheDocument()

    const back = screen.getByRole('button', { name: 'Обратно към вход' })
    back.focus()
    expect(back).toHaveFocus()
    fireEvent.click(back)
    expect(screen.getByRole('heading', { name: 'Вход' })).toBeInTheDocument()

    history.back()
    expect(
      await screen.findByRole('heading', { name: 'Възстановяване на парола' }),
    ).toBeInTheDocument()
  })

  it('submits forgot-password and shows the enumeration-safe response', async () => {
    mockedRequest.mockRejectedValueOnce(new Error('Необходим е вход.')).mockResolvedValueOnce(undefined)
    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: 'Забравена парола' }))
    fireEvent.change(screen.getByLabelText('Имейл'), {
      target: { value: 'person@example.invalid' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Изпрати' }))
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Ако съществува профил, ще получите инструкции.',
    )
  })

  it('renders and submits the invitation form', async () => {
    history.replaceState({}, '', '/invitation?token=invite-token')
    mockedRequest.mockRejectedValueOnce(new Error('Необходим е вход.')).mockResolvedValueOnce(undefined)
    render(<App />)
    fireEvent.change(screen.getByLabelText('Име'), { target: { value: 'Иван' } })
    fireEvent.change(screen.getByLabelText('Парола'), {
      target: { value: 'secure passphrase' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Приеми поканата' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Профилът е създаден.')
    expect(mockedRequest).toHaveBeenLastCalledWith(
      '/api/auth/invitations/accept',
      expect.objectContaining({ body: expect.stringContaining('invite-token') }),
    )
  })

  it('renders and submits the reset form', async () => {
    history.replaceState({}, '', '/password-reset?token=reset-token')
    mockedRequest.mockRejectedValueOnce(new Error('Необходим е вход.')).mockResolvedValueOnce(undefined)
    render(<App />)
    fireEvent.change(screen.getByLabelText('Нова парола'), {
      target: { value: 'secure passphrase' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Промени паролата' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Паролата е променена.')
    expect(mockedRequest).toHaveBeenLastCalledWith(
      '/api/auth/password/reset',
      expect.objectContaining({ body: expect.stringContaining('reset-token') }),
    )
  })

  it('keeps Business selection, successful password change and logout reachable', async () => {
    mockedRequest.mockResolvedValue(session)
    render(<App />)
    const businessSelect = await screen.findByLabelText('Избери бизнес')
    fireEvent.change(businessSelect, { target: { value: 'b' } })
    await waitFor(() =>
      expect(mockedRequest).toHaveBeenCalledWith(
        '/api/auth/business',
        expect.objectContaining({ body: JSON.stringify({ businessId: 'b' }) }),
      ),
    )

    fireEvent.change(screen.getByLabelText('Текуща парола'), {
      target: { value: 'old secure passphrase' },
    })
    fireEvent.change(screen.getByLabelText('Нова парола'), {
      target: { value: 'new secure passphrase' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Запази' }))
    await waitFor(() =>
      expect(mockedRequest).toHaveBeenCalledWith(
        '/api/auth/password/change',
        expect.objectContaining({ body: expect.stringContaining('new secure passphrase') }),
      ),
    )
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Паролата е променена успешно.',
    )
    expect(screen.getByRole('status')).toHaveClass('status-success')
    expect(screen.getByLabelText('Текуща парола')).toHaveValue('')
    expect(screen.getByLabelText('Нова парола')).toHaveValue('')

    fireEvent.click(screen.getAllByRole('button', { name: 'Изход' })[0]!)
    await waitFor(() =>
      expect(mockedRequest).toHaveBeenCalledWith('/api/auth/logout', { method: 'POST' }),
    )
    expect(await screen.findByRole('heading', { name: 'Вход' })).toBeInTheDocument()
  })

  it('retains password fields and shows no false success after failure', async () => {
    mockedRequest
      .mockResolvedValueOnce(session)
      .mockRejectedValueOnce(new Error('Текущата парола е невалидна.'))
    render(<App />)

    const currentPassword = await screen.findByLabelText('Текуща парола')
    const newPassword = screen.getByLabelText('Нова парола')
    fireEvent.change(currentPassword, { target: { value: 'wrong current password' } })
    fireEvent.change(newPassword, { target: { value: 'new secure passphrase' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази' }))

    const error = await screen.findByRole('alert')
    expect(error).toHaveTextContent('Текущата парола е невалидна.')
    expect(error).toHaveClass('status-error')
    expect(currentPassword).toHaveValue('wrong current password')
    expect(newPassword).toHaveValue('new secure passphrase')
    expect(screen.queryByText('Паролата е променена успешно.')).not.toBeInTheDocument()

    fireEvent.change(newPassword, { target: { value: 'another secure passphrase' } })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows platform navigation to PLATFORM_ADMIN and synchronizes browser navigation', async () => {
    const adminSession = { ...session, platformAdmin: true }
    history.replaceState({}, '', '/#/platform/businesses')
    mockedRequest.mockResolvedValue(adminSession)
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Бизнеси' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Бизнеси' })).toHaveAttribute('aria-current', 'page')
    expect(mockedRequest).toHaveBeenCalledTimes(1)

    history.pushState({}, '', '/#/profile')
    window.dispatchEvent(new PopStateEvent('popstate'))
    expect(await screen.findByRole('heading', { name: 'Профил' })).toBeInTheDocument()

    history.pushState({}, '', '/#/platform/businesses')
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    expect(await screen.findByRole('heading', { name: 'Бизнеси' })).toBeInTheDocument()
  })

  it.each(['BUSINESS_OWNER', 'MANAGER', 'STAFF'] as const)(
    'does not grant platform navigation for Membership role %s',
    async (role) => {
      history.replaceState({}, '', '/#/platform/businesses')
      mockedRequest.mockResolvedValue({
        ...session,
        businesses: [{ ...session.businesses[0], role }],
      })
      render(<App />)

      expect(await screen.findByRole('heading', { name: 'Профил' })).toBeInTheDocument()
      expect(window.location.hash).toBe('#/profile')
      expect(screen.queryByRole('link', { name: 'Бизнеси' })).not.toBeInTheDocument()
      expect(mockedRequest).toHaveBeenCalledTimes(1)
      expect(mockedRequest).toHaveBeenCalledWith('/api/auth/session')
    },
  )
})
