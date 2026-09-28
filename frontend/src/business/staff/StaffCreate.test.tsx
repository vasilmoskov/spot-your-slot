import '@testing-library/jest-dom/vitest'
import { fireEvent, render as rtlRender, screen, waitFor } from '@testing-library/react'
import type { ReactElement } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { UnsavedChangesGuardProvider, useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import { Button } from '../../ui/Button'
import { createStaffMember, type StaffMemberDetails } from './api'
import { StaffCreate } from './StaffCreate'

function GuardProbe() {
  const guard = useUnsavedChangesGuard()
  return (
    <Button type="button" onClick={() => guard.guard(() => undefined)}>
      Пробна навигация
    </Button>
  )
}

function render(ui: ReactElement) {
  return rtlRender(
    <UnsavedChangesGuardProvider>
      {ui}
      <GuardProbe />
    </UnsavedChangesGuardProvider>,
  )
}

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  createStaffMember: vi.fn(),
}))

const mockedCreateStaffMember = vi.mocked(createStaffMember)

const created: StaffMemberDetails = {
  id: 'staff-a',
  displayName: 'Анна Иванова',
  contactEmail: 'anna@example.invalid',
  contactPhone: null,
  active: true,
  version: 0,
  createdAt: '2026-08-19T09:00:00Z',
  updatedAt: '2026-08-19T09:00:00Z',
}

describe('StaffCreate', () => {
  const onAuthenticationRequired = vi.fn()
  const onCreated = vi.fn()
  const onCancel = vi.fn()

  beforeEach(() => {
    mockedCreateStaffMember.mockReset()
    onAuthenticationRequired.mockReset()
    onCreated.mockReset()
    onCancel.mockReset()
  })

  it('shows a read-only notice instead of the form when the Business is SUSPENDED', () => {
    render(
      <StaffCreate
        readOnly
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    expect(screen.queryByLabelText('Име на члена на екипа')).not.toBeInTheDocument()
    expect(
      screen.getByText(
        'Бизнесът е временно спрян — нови членове на екипа не могат да бъдат добавяни.',
      ),
    ).toBeInTheDocument()
  })

  it('starts with a completely empty form', () => {
    render(
      <StaffCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    expect(screen.getByLabelText('Име на члена на екипа')).toHaveValue('')
    expect(screen.getByLabelText('Имейл за връзка (по избор)')).toHaveValue('')
    expect(screen.getByLabelText('Телефон за връзка (по избор)')).toHaveValue('')
  })

  it('does not render the StaffMember resource/login-account explanatory copy', () => {
    render(
      <StaffCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    expect(
      screen.queryByText(
        'Член на екипа е ресурс на бизнеса, а не автоматично профил за вход в SpotYourSlot.',
      ),
    ).not.toBeInTheDocument()
  })

  it('submits only the current backend fields and navigates to the created StaffMember', async () => {
    mockedCreateStaffMember.mockResolvedValue(created)
    render(
      <StaffCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Анна Иванова' },
    })
    fireEvent.change(screen.getByLabelText('Имейл за връзка (по избор)'), {
      target: { value: 'anna@example.invalid' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))

    await waitFor(() =>
      expect(mockedCreateStaffMember).toHaveBeenCalledWith({
        displayName: 'Анна Иванова',
        contactEmail: 'anna@example.invalid',
        contactPhone: undefined,
      }),
    )
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith('staff-a'))
  })

  it('shows a safe validation error and keeps entered values', async () => {
    mockedCreateStaffMember.mockRejectedValue(
      new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.'),
    )
    render(
      <StaffCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Анна Иванова' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Проверете въведените данни.')
    expect(screen.getByLabelText('Име на члена на екипа')).toHaveValue('Анна Иванова')
    expect(onCreated).not.toHaveBeenCalled()
  })

  it('redirects to authentication on a 401 response', async () => {
    mockedCreateStaffMember.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    render(
      <StaffCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Анна Иванова' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))

    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'))
  })

  it('clears the dirty guard after a successful creation so a subsequent navigation does not prompt', async () => {
    mockedCreateStaffMember.mockResolvedValue(created)
    render(
      <StaffCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Анна Иванова' },
    })

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Продължи редактирането' }))

    fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith('staff-a'))

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('keeps the dirty guard active after a failed creation', async () => {
    mockedCreateStaffMember.mockRejectedValue(
      new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.'),
    )
    render(
      <StaffCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Анна Иванова' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))
    await screen.findByRole('alert')

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(screen.getByLabelText('Име на члена на екипа')).toHaveValue('Анна Иванова')
  })

  it('cancels back without submitting', () => {
    render(
      <StaffCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към екипа' }))
    expect(onCancel).toHaveBeenCalledOnce()
    expect(mockedCreateStaffMember).not.toHaveBeenCalled()
  })
})
