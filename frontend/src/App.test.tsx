import '@testing-library/jest-dom/vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { ApiError, request, type Session } from './identity/api'
import {
  createBusiness,
  getBusiness,
  listBusinesses,
  type BusinessDetails,
  type BusinessPage,
} from './platform/businesses/api'

vi.mock('./identity/api', async (importOriginal) => {
  const original = await importOriginal<typeof import('./identity/api')>()
  return { ...original, request: vi.fn() }
})
vi.mock('./platform/businesses/api', async (importOriginal) => {
  const original = await importOriginal<typeof import('./platform/businesses/api')>()
  return {
    ...original,
    createBusiness: vi.fn(),
    getBusiness: vi.fn(),
    listBusinesses: vi.fn(),
  }
})

const mockedRequest = vi.mocked(request)
const mockedCreateBusiness = vi.mocked(createBusiness)
const mockedGetBusiness = vi.mocked(getBusiness)
const mockedListBusinesses = vi.mocked(listBusinesses)
const businessPage: BusinessPage = {
  businesses: [
    {
      id: 'business-a',
      slug: 'studio-a',
      displayName: 'Студио А',
      businessType: 'BEAUTY_STUDIO',
      status: 'DRAFT',
      timezone: 'Europe/Sofia',
      version: 0,
      createdAt: '2026-08-19T09:00:00Z',
      updatedAt: '2026-08-20T12:30:00Z',
    },
  ],
  page: 0,
  size: 50,
  totalElements: 1,
}
const businessDetails: BusinessDetails = {
  ...businessPage.businesses[0]!,
  description: null,
  city: null,
  postalCode: null,
  street: null,
  streetNumber: null,
  addressDetails: null,
  phone: null,
  contactEmail: null,
}
const session: Session = {
  email: 'ivan@example.invalid',
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
  mockedListBusinesses.mockReset()
  mockedListBusinesses.mockResolvedValue(businessPage)
  mockedCreateBusiness.mockReset()
  mockedCreateBusiness.mockResolvedValue(businessDetails)
  mockedGetBusiness.mockReset()
  mockedGetBusiness.mockResolvedValue(businessDetails)
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('identity application', () => {
  it('supports accessible login and does not write authentication data to browser storage', async () => {
    const browserStorageWrite = vi.spyOn(Storage.prototype, 'setItem')
    const indexedDatabaseOpen = vi.fn()
    vi.stubGlobal('indexedDB', { open: indexedDatabaseOpen })
    mockedRequest.mockRejectedValueOnce(new Error('Необходим е вход.')).mockResolvedValueOnce(session)
    render(<App />)
    const loginPassword = screen.getByLabelText('Парола')
    const identityCard = loginPassword.closest('.identity-card')
    const loginContent = screen.getByRole('heading', { name: 'Вход' }).closest(
      '.compact-content',
    )
    expect(identityCard?.parentElement).toHaveClass('identity-main')
    expect(document.querySelector('.platform-content')).toBeNull()
    expect(loginPassword).not.toHaveAttribute('minlength')
    expect(loginContent).toContainElement(screen.getByText('SpotYourSlot'))
    expect(loginContent).toContainElement(loginPassword.closest('form'))
    expect(screen.getByRole('button', { name: 'Вход' })).toHaveClass(
      'button--primary',
    )
    expect(screen.getByRole('link', { name: 'Забравена парола' })).toHaveClass(
      'text-link',
    )
    expect(screen.getByRole('link', { name: 'Забравена парола' }))
      .toHaveAttribute('href', '/forgot-password')
    expect(screen.queryByRole('button', { name: 'Забравена парола' })).not.toBeInTheDocument()
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
      .mockRejectedValueOnce(new ApiError(400, 'AUTH_FAILED', 'Имейлът или паролата са невалидни.'))
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

    fireEvent.click(screen.getByRole('link', { name: 'Забравена парола' }))
    expect(
      screen.getByRole('heading', { name: 'Забравена парола?' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Въведи имейла си, за да получиш инструкции.'))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Изпрати' })).toHaveClass(
      'button--primary',
    )

    const back = screen.getByRole('link', { name: 'Обратно към вход' })
    const recoveryContent = screen
      .getByRole('heading', { name: 'Забравена парола?' })
      .closest('.compact-content')
    expect(recoveryContent).toContainElement(screen.getByText('SpotYourSlot'))
    expect(recoveryContent).toContainElement(
      screen.getByText('Въведи имейла си, за да получиш инструкции.'),
    )
    expect(recoveryContent).toContainElement(back.closest('form'))
    expect(back).toHaveClass('text-link')
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
    fireEvent.click(screen.getByRole('link', { name: 'Забравена парола' }))
    fireEvent.change(screen.getByLabelText('Имейл'), {
      target: { value: 'person@example.invalid' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Изпрати' }))
    const feedback = await screen.findByRole('status')
    expect(feedback).toHaveTextContent(
      'Ако съществува профил, ще получите инструкции.',
    )
    expect(feedback.closest('.compact-content')).toContainElement(
      screen.getByRole('heading', { name: 'Забравена парола?' }),
    )
  })

  it('renders and submits the invitation form', async () => {
    history.replaceState({}, '', '/invitation?token=invite-token')
    mockedRequest.mockRejectedValueOnce(new Error('Необходим е вход.')).mockResolvedValueOnce(undefined)
    render(<App />)
    expect(screen.getByLabelText('Парола')).toHaveAttribute('minlength', '8')
    expect(screen.getByLabelText('Парола').closest('.compact-content')).toContainElement(
      screen.getByRole('heading', { name: 'Приемане на покана' }),
    )
    expect(screen.getByLabelText('Име')).not.toHaveAttribute('label')
    expect(screen.getByLabelText('Име')).not.toHaveAttribute('requiretrimmedvalue')
    fireEvent.change(screen.getByLabelText('Име'), { target: { value: '  Иван ' } })
    fireEvent.change(screen.getByLabelText('Фамилия'), { target: { value: ' Иванов  ' } })
    fireEvent.change(screen.getByLabelText('Парола'), {
      target: { value: 'secure passphrase' },
    })
    fireEvent.change(screen.getByLabelText('Потвърди паролата'), {
      target: { value: 'secure passphrase' },
    })
    fireEvent.submit(screen.getByRole('button', { name: 'Приеми поканата' }).closest('form')!)
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Поканата е приета успешно. Бизнесът очаква активиране от администратор.',
    )
    expect(document.body).not.toHaveTextContent('Профилът е създаден.')
    expect(screen.queryByRole('button', { name: 'Приеми поканата' }))
      .not.toBeInTheDocument()
    expect(window.location.search).toContain('token=invite-token')
    fireEvent.click(screen.getByRole('link', { name: 'Към вход' }))
    expect(window.location.pathname).toBe('/')
    expect(window.location.search).toBe('')
    expect(screen.getByRole('heading', { name: 'Вход' })).toBeInTheDocument()
    expect(mockedRequest).toHaveBeenLastCalledWith(
      '/api/auth/invitations/accept',
      expect.objectContaining({
        body: JSON.stringify({
          token: 'invite-token',
          displayName: 'Иван Иванов',
          password: 'secure passphrase',
        }),
      }),
    )
  })

  it('keeps invitation password confirmation local and rejects a mismatch', async () => {
    history.replaceState({}, '', '/invitation?token=invite-token')
    mockedRequest.mockRejectedValueOnce(new Error('Необходим е вход.'))
    render(<App />)

    fireEvent.change(screen.getByLabelText('Име'), { target: { value: 'Иван' } })
    fireEvent.change(screen.getByLabelText('Фамилия'), { target: { value: 'Иванов' } })
    fireEvent.change(screen.getByLabelText('Парола'), {
      target: { value: 'secure passphrase' },
    })
    const confirmation = screen.getByLabelText('Потвърди паролата')
    fireEvent.change(confirmation, { target: { value: 'different password' } })
    fireEvent.submit(screen.getByRole('button', { name: 'Приеми поканата' }).closest('form')!)

    expect(confirmation).toHaveProperty('validationMessage', 'Паролите не съвпадат.')
    expect(mockedRequest).toHaveBeenCalledTimes(1)
  })

  it('shows one safe actionable message for an invalid invitation', async () => {
    history.replaceState({}, '', '/invitation?token=invite-token')
    mockedRequest
      .mockRejectedValueOnce(new Error('Необходим е вход.'))
      .mockRejectedValueOnce(
        new ApiError(400, 'INVITATION_INVALID', 'Backend invitation detail'),
      )
    render(<App />)

    fireEvent.change(screen.getByLabelText('Име'), { target: { value: 'Иван' } })
    fireEvent.change(screen.getByLabelText('Фамилия'), { target: { value: 'Иванов' } })
    fireEvent.change(screen.getByLabelText('Парола'), {
      target: { value: 'secure passphrase' },
    })
    fireEvent.change(screen.getByLabelText('Потвърди паролата'), {
      target: { value: 'secure passphrase' },
    })
    fireEvent.submit(screen.getByRole('button', { name: 'Приеми поканата' }).closest('form')!)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Поканата е невалидна, изтекла или вече е използвана. Поискайте нова покана.',
    )
    expect(document.body).not.toHaveTextContent('Backend invitation detail')
  })

  it('shows the distinct existing-user credential mismatch message', async () => {
    history.replaceState({}, '', '/invitation?token=invite-token')
    mockedRequest
      .mockRejectedValueOnce(new Error('Необходим е вход.'))
      .mockRejectedValueOnce(
        new ApiError(
          400,
          'INVITATION_CREDENTIAL_MISMATCH',
          'Backend credential detail',
        ),
      )
    render(<App />)

    fireEvent.change(screen.getByLabelText('Име'), { target: { value: 'Иван' } })
    fireEvent.change(screen.getByLabelText('Фамилия'), { target: { value: 'Иванов' } })
    fireEvent.change(screen.getByLabelText('Парола'), {
      target: { value: 'secure passphrase' },
    })
    fireEvent.change(screen.getByLabelText('Потвърди паролата'), {
      target: { value: 'secure passphrase' },
    })
    fireEvent.submit(screen.getByRole('button', { name: 'Приеми поканата' }).closest('form')!)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Паролата не съвпада със съществуващия профил за този имейл.',
    )
    expect(document.body).not.toHaveTextContent('Backend credential detail')
  })

  it('keeps ordinary invitation validation separate', async () => {
    history.replaceState({}, '', '/invitation?token=invite-token')
    mockedRequest
      .mockRejectedValueOnce(new Error('Необходим е вход.'))
      .mockRejectedValueOnce(
        new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.'),
      )
    render(<App />)

    fireEvent.change(screen.getByLabelText('Име'), { target: { value: 'Иван' } })
    fireEvent.change(screen.getByLabelText('Фамилия'), { target: { value: 'Иванов' } })
    fireEvent.change(screen.getByLabelText('Парола'), {
      target: { value: 'secure passphrase' },
    })
    fireEvent.change(screen.getByLabelText('Потвърди паролата'), {
      target: { value: 'secure passphrase' },
    })
    fireEvent.submit(screen.getByRole('button', { name: 'Приеми поканата' }).closest('form')!)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Проверете въведените данни.',
    )
    expect(document.body).not.toHaveTextContent('Поканата е невалидна')
  })

  it('rejects a whitespace-only invitation name with safe Bulgarian validation', () => {
    history.replaceState({}, '', '/invitation?token=invite-token')
    render(<App />)

    const firstName = screen.getByLabelText('Име')
    fireEvent.input(firstName, { target: { value: '   ' } })

    expect(firstName).toHaveProperty(
      'validationMessage',
      'Моля, попълнете това поле.',
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
    expect(resetPassword.closest('.compact-content')).toContainElement(
      screen.getByRole('heading', { name: 'Вход' }),
    )
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
    const browserStorageWrite = vi.spyOn(Storage.prototype, 'setItem')
    mockedRequest.mockResolvedValue(session)
    render(<App />)
    const businessSelect = await screen.findByLabelText('Избери бизнес')
    const profilePanel = businessSelect.closest('.profile-panel') as HTMLElement
    const profileCard = profilePanel.closest('.content-card') as HTMLElement
    const platformContent = profileCard?.parentElement
    expect(platformContent).toHaveClass('platform-content')
    expect(platformContent?.children).toHaveLength(1)
    expect(platformContent?.firstElementChild).toBe(profileCard)
    expect(profileCard).toHaveClass('profile-card')
    expect(profileCard).toContainElement(profilePanel)
    expect(profileCard.querySelector('details')).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Настройки' })).not.toBeInTheDocument()
    const personalNavigation = screen.getByRole('button', { name: 'Лични данни' })
    const passwordNavigation = screen.getByRole('button', { name: 'Смяна на парола' })
    expect(personalNavigation).toHaveAttribute('aria-pressed', 'true')
    expect(passwordNavigation).toHaveAttribute('aria-pressed', 'false')
    const personalDetails = screen.getByRole('heading', { name: 'Лични данни' })
      .closest('.profile-panel') as HTMLElement
    expect(personalDetails).toHaveTextContent('Иван')
    expect(personalDetails).toHaveTextContent('ivan@example.invalid')
    expect(screen.queryByLabelText('Текуща парола')).not.toBeInTheDocument()
    fireEvent.change(businessSelect, { target: { value: 'b' } })
    await waitFor(() =>
      expect(mockedRequest).toHaveBeenCalledWith(
        '/api/auth/business',
        expect.objectContaining({ body: JSON.stringify({ businessId: 'b' }) }),
      ),
    )

    fireEvent.click(passwordNavigation)
    expect(personalNavigation).toHaveAttribute('aria-pressed', 'false')
    expect(passwordNavigation).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('heading', { name: 'Лични данни' })).not.toBeInTheDocument()
    expect(screen.queryByText('ivan@example.invalid')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Текуща парола'), {
      target: { value: 'old secure passphrase' },
    })
    fireEvent.change(screen.getByLabelText('Нова парола'), {
      target: { value: 'new secure passphrase' },
    })
    fireEvent.change(screen.getByLabelText('Потвърди новата парола'), {
      target: { value: 'new secure passphrase' },
    })
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
    expect(profilePanel).toContainElement(
      screen.getByRole('button', { name: 'Запази' }).closest('form'),
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
    expect(screen.getByRole('status')).toHaveClass('status-message')
    expect(profilePanel).toContainElement(screen.getByRole('status'))
    expect(screen.getByLabelText('Текуща парола')).toHaveValue('')
    expect(screen.getByLabelText('Нова парола')).toHaveValue('')
    expect(screen.getByLabelText('Потвърди новата парола')).toHaveValue('')

    fireEvent.click(screen.getAllByRole('button', { name: 'Изход' })[0]!)
    await waitFor(() =>
      expect(mockedRequest).toHaveBeenCalledWith('/api/auth/logout', { method: 'POST' }),
    )
    expect(await screen.findByRole('heading', { name: 'Вход' })).toBeInTheDocument()
    expect(browserStorageWrite).not.toHaveBeenCalled()
  })

  it('edits the display name, refreshes Profile and sidebar, and keeps email read-only', async () => {
    const updatedSession = { ...session, displayName: 'Мария' }
    const browserStorageWrite = vi.spyOn(Storage.prototype, 'setItem')
    mockedRequest.mockResolvedValueOnce(session).mockResolvedValueOnce(updatedSession)
    render(<App />)

    await screen.findByRole('heading', { name: 'Профил' })
    const edit = screen.getByRole('button', { name: 'Редактирай' })
    expect(edit).toHaveClass('button', 'button--secondary')
    expect(edit).not.toHaveClass('button--navigation')
    expect(edit).not.toHaveClass('profile-navigation')
    fireEvent.click(edit)
    const displayName = screen.getByLabelText('Име')
    expect(displayName).toHaveValue('Иван')
    expect(screen.getByText('ivan@example.invalid')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Имейл' })).not.toBeInTheDocument()
    fireEvent.change(displayName, { target: { value: 'Мария' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

    await waitFor(() =>
      expect(mockedRequest).toHaveBeenCalledWith(
        '/api/auth/profile',
        expect.objectContaining({ body: JSON.stringify({ displayName: 'Мария' }) }),
      ),
    )
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Личните данни са запазени.',
    )
    expect(screen.getByRole('status')).toHaveClass('status-message')
    expect(screen.queryByLabelText('Име')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Лични данни' }).closest('.profile-panel'))
      .toHaveTextContent('Мария')
    expect(document.querySelector('.sidebar-account')).toHaveTextContent('Мария')
    expect(screen.getByText('ivan@example.invalid')).toBeInTheDocument()
    expect(browserStorageWrite).not.toHaveBeenCalled()
  })

  it('clears Profile feedback when the selected section changes', async () => {
    mockedRequest
      .mockResolvedValueOnce(session)
      .mockRejectedValueOnce(new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.'))
    render(<App />)

    await screen.findByRole('heading', { name: 'Профил' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име'), { target: { value: 'Ново име' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Смяна на парола' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Смяна на парола' })).toBeInTheDocument()
  })

  it('automatically dismisses successful Profile feedback without browser storage', async () => {
    const browserStorageWrite = vi.spyOn(Storage.prototype, 'setItem')
    mockedRequest.mockResolvedValueOnce(session).mockResolvedValueOnce({
      ...session,
      displayName: 'Мария',
    })
    render(<App />)

    await screen.findByRole('heading', { name: 'Профил' })
    vi.useFakeTimers()
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име'), { target: { value: 'Мария' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(screen.getByRole('status')).toHaveTextContent(
      'Личните данни са запазени.',
    )

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000)
    })
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(browserStorageWrite).not.toHaveBeenCalled()
    vi.useRealTimers()
  })

  it('dismisses a transient Profile request failure while retaining the entered name', async () => {
    mockedRequest.mockResolvedValueOnce(session).mockRejectedValueOnce(new Error('Internal detail'))
    render(<App />)
    await screen.findByRole('heading', { name: 'Профил' })
    vi.useFakeTimers()
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име'), { target: { value: 'Мария' } })
    screen.getByLabelText('Име').focus()
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await act(async () => { await Promise.resolve() })
    expect(screen.getByRole('alert')).toHaveTextContent('Възникна грешка. Опитайте отново.')
    expect(screen.getByRole('alert')).toHaveFocus()
    await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Име')).toHaveValue('Мария')
    expect(screen.getByLabelText('Име')).toHaveFocus()
  })

  it('does not restore late Profile feedback after changing tabs', async () => {
    let resolveUpdate!: (value: Session) => void
    mockedRequest.mockResolvedValueOnce(session).mockImplementationOnce(() =>
      new Promise<Session>((resolve) => { resolveUpdate = resolve }),
    )
    render(<App />)
    await screen.findByRole('heading', { name: 'Профил' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    fireEvent.click(screen.getByRole('button', { name: 'Смяна на парола' }))
    await act(async () => resolveUpdate({ ...session, displayName: 'Мария' }))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Смяна на парола' })).toHaveAttribute('aria-pressed', 'true')
    expect(document.querySelector('.sidebar-account')).toHaveTextContent('Мария')
  })

  it('clears Profile feedback through browser route navigation', async () => {
    mockedRequest.mockResolvedValueOnce({ ...session, platformAdmin: true })
      .mockResolvedValueOnce({ ...session, platformAdmin: true, displayName: 'Мария' })
    render(<App />)
    await screen.findByRole('heading', { name: 'Профил' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await screen.findByRole('status')
    act(() => {
      history.pushState({}, '', '/#/platform/businesses')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    await screen.findByRole('heading', { name: 'Бизнеси' })
    expect(screen.queryByText('Личните данни са запазени.')).not.toBeInTheDocument()
  })

  it('cancels display-name editing without a request or retained value', async () => {
    mockedRequest.mockResolvedValue(session)
    render(<App />)

    await screen.findByRole('heading', { name: 'Профил' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име'), { target: { value: 'Незаписано име' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))

    expect(mockedRequest).toHaveBeenCalledTimes(1)
    expect(screen.queryByLabelText('Име')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Лични данни' }).closest('.profile-panel'))
      .toHaveTextContent('Иван')
  })

  it('retains display-name editing and focuses safe feedback after a failed update', async () => {
    const browserStorageWrite = vi.spyOn(Storage.prototype, 'setItem')
    mockedRequest
      .mockResolvedValueOnce(session)
      .mockRejectedValueOnce(new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.'))
    render(<App />)

    await screen.findByRole('heading', { name: 'Профил' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    const displayName = screen.getByLabelText('Име')
    fireEvent.change(displayName, { target: { value: 'Ново име' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

    const feedback = await screen.findByRole('alert')
    expect(feedback).toHaveTextContent('Проверете въведените данни.')
    expect(feedback).toHaveFocus()
    vi.useFakeTimers()
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(feedback).toBeInTheDocument()
    vi.useRealTimers()
    expect(screen.getByLabelText('Име')).toHaveValue('Ново име')
    expect(screen.getByRole('button', { name: 'Запази промените' })).toBeInTheDocument()
    expect(browserStorageWrite).not.toHaveBeenCalled()
  })

  it('retains password fields and shows no false success after failure', async () => {
    mockedRequest
      .mockResolvedValueOnce(session)
      .mockRejectedValueOnce(new ApiError(400, 'CURRENT_PASSWORD_INVALID', 'Текущата парола е невалидна.'))
    render(<App />)

    const passwordNavigation = await screen.findByRole('button', {
      name: 'Смяна на парола',
    })
    fireEvent.click(passwordNavigation)
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
    expect(passwordNavigation).toHaveAttribute('aria-pressed', 'true')
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

    const passwordNavigation = await screen.findByRole('button', {
      name: 'Смяна на парола',
    })
    fireEvent.click(passwordNavigation)
    const currentPassword = await screen.findByLabelText('Текуща парола')
    const newPassword = screen.getByLabelText('Нова парола')
    const passwordConfirmation = screen.getByLabelText('Потвърди новата парола')
    fireEvent.change(currentPassword, { target: { value: 'current passphrase' } })
    fireEvent.change(newPassword, { target: { value: 'new password' } })
    fireEvent.change(passwordConfirmation, { target: { value: 'different password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Паролите не съвпадат.',
    )
    expect(screen.getByRole('alert')).toHaveClass('status-error')
    expect(passwordNavigation).toHaveAttribute('aria-pressed', 'true')
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
    expect(await screen.findByText('Студио А')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Бизнеси' })).toHaveAttribute('aria-current', 'page')
    expect(mockedRequest).toHaveBeenCalledTimes(1)
    expect(mockedListBusinesses).toHaveBeenCalledWith(0, 50, expect.any(AbortSignal))

    history.pushState({}, '', '/#/profile')
    window.dispatchEvent(new PopStateEvent('popstate'))
    expect(await screen.findByRole('heading', { name: 'Профил' })).toBeInTheDocument()

    history.pushState({}, '', '/#/platform/businesses')
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    expect(await screen.findByRole('heading', { name: 'Бизнеси' })).toBeInTheDocument()
  })

  it('navigates through list, creation and the created DRAFT detail', async () => {
    history.replaceState({}, '', '/#/platform/businesses')
    mockedRequest.mockResolvedValue({ ...session, platformAdmin: true })
    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Нов бизнес' }))
    expect(window.location.hash).toBe('#/platform/businesses/new')
    expect(screen.getByRole('heading', { name: 'Нов бизнес' })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Име на бизнеса'), {
      target: { value: 'Студио А' },
    })
    fireEvent.change(screen.getByLabelText(/^Идентификатор в уеб адреса/), {
      target: { value: 'studio-a' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Създай бизнес' }))

    await waitFor(() => expect(mockedCreateBusiness).toHaveBeenCalledOnce())
    expect(window.location.hash).toBe('#/platform/businesses/business-a')
    expect(await screen.findByRole('heading', { name: 'Студио А' }))
      .toBeInTheDocument()
    expect(screen.getAllByText('Предстои активиране')).toHaveLength(3)
    expect(document.body).not.toHaveTextContent(/Версия 0/)
  })

  it('clears a stale authenticated view when the Business list returns 401', async () => {
    const adminSession = { ...session, platformAdmin: true }
    history.replaceState({}, '', '/#/platform/businesses')
    mockedRequest.mockResolvedValueOnce(adminSession)
    mockedListBusinesses.mockRejectedValueOnce(
      new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'),
    )

    render(<App />)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Вход' })).toBeInTheDocument()
      expect(screen.getByRole('alert')).toHaveTextContent('Необходим е вход.')
    })
    expect(screen.queryByRole('link', { name: 'Бизнеси' })).not.toBeInTheDocument()
  })

  it('does not write Business or authentication state to browser storage', async () => {
    const browserStorageWrite = vi.spyOn(Storage.prototype, 'setItem')
    const indexedDatabaseOpen = vi.fn()
    vi.stubGlobal('indexedDB', { open: indexedDatabaseOpen })
    history.replaceState({}, '', '/#/platform/businesses')
    mockedRequest.mockResolvedValueOnce({ ...session, platformAdmin: true })

    render(<App />)

    expect(await screen.findByText('Студио А')).toBeInTheDocument()
    expect(browserStorageWrite).not.toHaveBeenCalled()
    expect(indexedDatabaseOpen).not.toHaveBeenCalled()
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
