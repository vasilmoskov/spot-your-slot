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
    const loginPassword = screen.getByLabelText('Парола')
    expect(loginPassword).not.toHaveAttribute('minlength')
    expect(loginPassword.closest('form')).toHaveClass('compact-form')
    expect(screen.getByRole('button', { name: 'Вход' })).toHaveClass(
      'form-primary-action',
    )
    expect(screen.getByRole('button', { name: 'Забравена парола' })).toHaveClass(
      'form-secondary-action',
    )
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

  it('localizes required and email constraint validation in Bulgarian', () => {
    render(<App />)
    const email = screen.getByLabelText('Имейл') as HTMLInputElement
    const password = screen.getByLabelText('Парола')

    fireEvent.invalid(email)
    expect(email).toHaveProperty('validationMessage', 'Моля, въведете имейл адрес.')

    fireEvent.input(email, { target: { value: 'невалиден-имейл' } })
    expect(email.validity.customError).toBe(false)
    fireEvent.invalid(email)
    expect(email).toHaveProperty(
      'validationMessage',
      'Моля, въведете валиден имейл адрес.',
    )

    fireEvent.invalid(password)
    expect(password).toHaveProperty('validationMessage', 'Моля, въведете парола.')
    fireEvent.input(password, { target: { value: 'existing credential' } })
    expect(password).toHaveProperty('validationMessage', '')
    expect(password).not.toHaveAttribute('minlength')
  })

  it('shows recovery guidance and returns accessibly to login with browser history', async () => {
    render(<App />)
    expect(screen.getByRole('heading', { name: 'Вход' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Забравена парола' }))
    expect(
      screen.getByRole('heading', { name: 'Забравена парола?' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Въведи имейла си, за да получиш инструкции.'))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Изпрати' })).toHaveClass(
      'form-primary-action',
    )

    const back = screen.getByRole('button', { name: 'Обратно към вход' })
    expect(back).toHaveClass('form-secondary-action')
    expect(back.closest('form')).toHaveClass('compact-form')
    back.focus()
    expect(back).toHaveFocus()
    fireEvent.click(back)
    expect(screen.getByRole('heading', { name: 'Вход' })).toBeInTheDocument()

    history.back()
    expect(
      await screen.findByRole('heading', { name: 'Забравена парола?' }),
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
    expect(screen.getByLabelText('Парола')).toHaveAttribute('minlength', '8')
    expect(screen.getByLabelText('Парола').closest('form')).toHaveClass('compact-form')
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

  it('validates new-password length using Unicode code points', () => {
    history.replaceState({}, '', '/invitation?token=invite-token')
    render(<App />)
    const displayName = screen.getByLabelText('Име')
    const password = screen.getByLabelText('Парола') as HTMLInputElement

    fireEvent.invalid(displayName)
    expect(displayName).toHaveProperty(
      'validationMessage',
      'Моля, попълнете това поле.',
    )

    fireEvent.input(password, { target: { value: '1234567' } })
    expect(password).toHaveProperty(
      'validationMessage',
      'Паролата трябва да бъде поне 8 знака.',
    )

    fireEvent.input(password, { target: { value: '12345678' } })
    expect(password).toHaveProperty('validationMessage', '')

    fireEvent.input(password, { target: { value: '😀😀😀😀' } })
    expect(password.value.length).toBe(8)
    expect(Array.from(password.value)).toHaveLength(4)
    expect(password).toHaveProperty(
      'validationMessage',
      'Паролата трябва да бъде поне 8 знака.',
    )

    fireEvent.input(password, { target: { value: '😀😀😀😀😀😀😀😀' } })
    expect(Array.from(password.value)).toHaveLength(8)
    expect(password).toHaveProperty('validationMessage', '')

    fireEvent.input(password, { target: { value: '' } })
    expect(password.validity.customError).toBe(false)
    fireEvent.invalid(password)
    expect(password).toHaveProperty('validationMessage', 'Моля, въведете парола.')
    expect(password).toHaveAttribute('minlength', '8')
  })

  it('renders and submits the reset form', async () => {
    history.replaceState({}, '', '/password-reset?token=reset-token')
    mockedRequest.mockRejectedValueOnce(new Error('Необходим е вход.')).mockResolvedValueOnce(undefined)
    render(<App />)
    const resetPassword = screen.getByLabelText('Нова парола')
    expect(resetPassword).toHaveAttribute('minlength', '8')
    expect(resetPassword.closest('form')).toHaveClass('compact-form')
    fireEvent.change(resetPassword, {
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
    fireEvent.change(screen.getByLabelText('Потвърди новата парола'), {
      target: { value: 'new secure passphrase' },
    })
    expect(screen.getByRole('heading', { name: 'Смяна на парола' })).toBeInTheDocument()
    expect(screen.getByLabelText('Текуща парола')).not.toHaveAttribute('minlength')
    expect(screen.getByLabelText('Нова парола')).toHaveAttribute('minlength', '8')
    expect(screen.getByLabelText('Потвърди новата парола')).toBeRequired()
    expect(screen.getByLabelText('Потвърди новата парола')).toHaveAttribute(
      'minlength',
      '8',
    )
    expect(screen.getByLabelText('Потвърди новата парола')).toHaveAttribute(
      'type',
      'password',
    )
    expect(screen.getByRole('button', { name: 'Запази' }).closest('form')).toHaveClass(
      'compact-form',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Запази' }))
    await waitFor(() =>
      expect(mockedRequest).toHaveBeenCalledWith(
        '/api/auth/password/change',
        expect.objectContaining({ body: expect.stringContaining('new secure passphrase') }),
      ),
    )
    const passwordChangeCall = mockedRequest.mock.calls.find(
      ([path]) => path === '/api/auth/password/change',
    )
    expect(JSON.parse(String(passwordChangeCall?.[1]?.body))).toEqual({
      currentPassword: 'old secure passphrase',
      newPassword: 'new secure passphrase',
    })
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Паролата е променена успешно.',
    )
    expect(screen.getByRole('status')).toHaveClass('status-success')
    expect(screen.getByLabelText('Текуща парола')).toHaveValue('')
    expect(screen.getByLabelText('Нова парола')).toHaveValue('')
    expect(screen.getByLabelText('Потвърди новата парола')).toHaveValue('')

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
    const passwordConfirmation = screen.getByLabelText('Потвърди новата парола')
    fireEvent.change(currentPassword, { target: { value: 'wrong current password' } })
    fireEvent.change(newPassword, { target: { value: 'new secure passphrase' } })
    fireEvent.change(passwordConfirmation, {
      target: { value: 'new secure passphrase' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Запази' }))

    const error = await screen.findByRole('alert')
    expect(error).toHaveTextContent('Текущата парола е невалидна.')
    expect(error).toHaveClass('status-error')
    expect(currentPassword).toHaveValue('wrong current password')
    expect(newPassword).toHaveValue('new secure passphrase')
    expect(passwordConfirmation).toHaveValue('new secure passphrase')
    expect(screen.queryByText('Паролата е променена успешно.')).not.toBeInTheDocument()

    fireEvent.change(newPassword, { target: { value: 'another secure passphrase' } })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('rejects mismatched password confirmation without calling the backend', async () => {
    mockedRequest.mockResolvedValueOnce(session)
    render(<App />)

    const currentPassword = await screen.findByLabelText('Текуща парола')
    const newPassword = screen.getByLabelText('Нова парола')
    const passwordConfirmation = screen.getByLabelText('Потвърди новата парола')
    fireEvent.change(currentPassword, { target: { value: 'current passphrase' } })
    fireEvent.change(newPassword, { target: { value: 'new password' } })
    fireEvent.change(passwordConfirmation, { target: { value: 'different password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Новата парола и потвърждението не съвпадат.',
    )
    expect(screen.getByRole('alert')).toHaveClass('status-error')
    expect(currentPassword).toHaveValue('current passphrase')
    expect(newPassword).toHaveValue('new password')
    expect(passwordConfirmation).toHaveValue('different password')
    expect(mockedRequest).toHaveBeenCalledTimes(1)
    expect(mockedRequest).toHaveBeenCalledWith('/api/auth/session')
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
