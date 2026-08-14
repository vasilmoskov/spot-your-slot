import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { request } from './identity/api'

vi.mock('./identity/api', () => ({ request: vi.fn() }))

const mockedRequest = vi.mocked(request)
const session = {
  displayName: 'Иван',
  platformAdmin: false,
  businesses: [
    { id: 'a', displayName: 'Бизнес А', role: 'BUSINESS_OWNER' as const, status: 'ACTIVE' },
    { id: 'b', displayName: 'Бизнес Б', role: 'MANAGER' as const, status: 'ACTIVE' },
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
  it('supports accessible labels and keyboard login submission without browser storage', async () => {
    const localStorageWrite = vi.spyOn(Storage.prototype, 'setItem')
    mockedRequest.mockRejectedValueOnce(new Error('Необходим е вход.')).mockResolvedValueOnce(session)
    render(<App />)
    fireEvent.change(screen.getByLabelText('Имейл'), { target: { value: 'owner@example.invalid' } })
    fireEvent.change(screen.getByLabelText('Парола'), { target: { value: 'secure passphrase' } })
    fireEvent.keyDown(screen.getByLabelText('Парола'), { key: 'Enter', code: 'Enter' })
    fireEvent.submit(screen.getByRole('button', { name: 'Вход' }).closest('form')!)
    expect(screen.getByRole('button', { name: 'Вход' })).toBeDisabled()
    expect(await screen.findByRole('heading', { name: 'Здравей, Иван' })).toBeInTheDocument()
    expect(localStorageWrite).not.toHaveBeenCalled()
  })

  it('shows the generic login failure', async () => {
    mockedRequest
      .mockRejectedValueOnce(new Error('Необходим е вход.'))
      .mockRejectedValueOnce(new Error('Имейлът или паролата са невалидни.'))
    render(<App />)
    fireEvent.change(screen.getByLabelText('Имейл'), { target: { value: 'owner@example.invalid' } })
    fireEvent.change(screen.getByLabelText('Парола'), { target: { value: 'wrong password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Вход' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Имейлът или паролата са невалидни.')
  })

  it('submits forgot-password and shows the enumeration-safe response', async () => {
    mockedRequest.mockRejectedValueOnce(new Error('Необходим е вход.')).mockResolvedValueOnce(undefined)
    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: 'Забравена парола' }))
    fireEvent.change(screen.getByLabelText('Имейл'), { target: { value: 'person@example.invalid' } })
    fireEvent.click(screen.getByRole('button', { name: 'Изпрати инструкции' }))
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Ако съществува профил, ще получите инструкции.',
    )
  })

  it('renders and submits the invitation form', async () => {
    history.replaceState({}, '', '/invitation?token=invite-token')
    mockedRequest.mockRejectedValueOnce(new Error('Необходим е вход.')).mockResolvedValueOnce(undefined)
    render(<App />)
    fireEvent.change(screen.getByLabelText('Име'), { target: { value: 'Иван' } })
    fireEvent.change(screen.getByLabelText('Парола'), { target: { value: 'secure passphrase' } })
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
    fireEvent.change(screen.getByLabelText('Нова парола'), { target: { value: 'secure passphrase' } })
    fireEvent.click(screen.getByRole('button', { name: 'Промени паролата' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Паролата е променена.')
    expect(mockedRequest).toHaveBeenLastCalledWith(
      '/api/auth/password/reset',
      expect.objectContaining({ body: expect.stringContaining('reset-token') }),
    )
  })

  it('selects a Business, changes password and logs out', async () => {
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
    fireEvent.submit(screen.getByLabelText('Текуща парола').closest('form')!)
    await waitFor(() =>
      expect(mockedRequest).toHaveBeenCalledWith(
        '/api/auth/password/change',
        expect.objectContaining({ body: expect.stringContaining('new secure passphrase') }),
      ),
    )

    fireEvent.click(screen.getByRole('button', { name: 'Изход' }))
    await waitFor(() =>
      expect(mockedRequest).toHaveBeenCalledWith('/api/auth/logout', { method: 'POST' }),
    )
    expect(await screen.findByRole('heading', { name: 'Вход за бизнеса' })).toBeInTheDocument()
  })
})
