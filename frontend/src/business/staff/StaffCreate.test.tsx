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
    // The shared banner explains the state once; the page keeps only the way back.
    expect(screen.queryByText(/временно спрян/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Обратно към екипа' })).toBeInTheDocument()
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

    // No usable field metadata: a form-level message tells the user what to
    // review instead of repeating the generic text or guessing a field.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Проверете името, имейла и телефонния номер.',
    )
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
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))

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

describe('StaffCreate inline validation', () => {
  const onCreated = vi.fn()

  beforeEach(() => {
    mockedCreateStaffMember.mockReset()
    onCreated.mockReset()
  })

  function renderForm() {
    render(
      <StaffCreate
        readOnly={false}
        onAuthenticationRequired={vi.fn()}
        onCreated={onCreated}
        onCancel={vi.fn()}
      />,
    )
  }

  it('rejects a whitespace-only name with a field error and focus, without a request', () => {
    renderForm()
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), { target: { value: '  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))

    const name = screen.getByLabelText('Име на члена на екипа')
    expect(mockedCreateStaffMember).not.toHaveBeenCalled()
    expect(name).toHaveAttribute('aria-invalid', 'true')
    expect(name).toHaveAccessibleDescription('Въведете име на члена на екипа.')
    expect(name).toHaveFocus()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('accepts a blank email and telephone', async () => {
    mockedCreateStaffMember.mockResolvedValue(created)
    renderForm()
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Анна Иванова' },
    })
    fireEvent.change(screen.getByLabelText('Имейл за връзка (по избор)'), { target: { value: '  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))

    await waitFor(() =>
      expect(mockedCreateStaffMember).toHaveBeenCalledWith({
        displayName: 'Анна Иванова',
        contactEmail: undefined,
        contactPhone: undefined,
      }),
    )
  })

  it('reports a malformed email and telephone next to each field and focuses the first', () => {
    renderForm()
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Анна Иванова' },
    })
    fireEvent.change(screen.getByLabelText('Имейл за връзка (по избор)'), {
      target: { value: 'anna@' },
    })
    fireEvent.change(screen.getByLabelText('Телефон за връзка (по избор)'), {
      target: { value: '888123456' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))

    const email = screen.getByLabelText('Имейл за връзка (по избор)')
    const phone = screen.getByLabelText('Телефон за връзка (по избор)')
    expect(email).toHaveAccessibleDescription('Въведете валиден имейл, например ime@primer.bg.')
    expect(phone).toHaveAccessibleDescription(/започва с \+, 00 или 0/)
    expect(email).toHaveFocus()
    expect(mockedCreateStaffMember).not.toHaveBeenCalled()

    fireEvent.change(email, { target: { value: 'anna@example.invalid' } })
    expect(email).not.toHaveAttribute('aria-invalid')
    expect(phone).toHaveAttribute('aria-invalid', 'true')
    fireEvent.change(phone, { target: { value: '0888 123 456' } })
    expect(phone).not.toHaveAttribute('aria-invalid')
  })

  it('shows no error on the untouched form and validates on blur', () => {
    renderForm()
    expect(document.querySelectorAll('.field-error')).toHaveLength(0)
    const name = screen.getByLabelText('Име на члена на екипа')
    fireEvent.blur(name)
    expect(screen.getByText('Въведете име на члена на екипа.')).toBeInTheDocument()
    fireEvent.change(name, { target: { value: 'А' } })
    expect(screen.queryByText('Въведете име на члена на екипа.')).not.toBeInTheDocument()
  })

  it('corrects a malformed email and a real-number-invalid telephone while typing', () => {
    renderForm()
    const email = screen.getByLabelText('Имейл за връзка (по избор)')
    const phone = screen.getByLabelText('Телефон за връзка (по избор)')

    fireEvent.change(email, { target: { value: 'anna@' } })
    expect(email).toHaveAccessibleDescription('Въведете валиден имейл, например ime@primer.bg.')
    fireEvent.change(email, { target: { value: 'anna@example.invalid' } })
    expect(email).not.toHaveAttribute('aria-invalid')
    fireEvent.change(email, { target: { value: '' } })
    expect(email).not.toHaveAttribute('aria-invalid')

    fireEvent.change(phone, { target: { value: '+3598881234561' } })
    expect(phone).toHaveAccessibleDescription(
      'Въведете валиден телефонен номер, например +359 88 123 4567.',
    )
    fireEvent.change(phone, { target: { value: '0888 123 456' } })
    expect(phone).not.toHaveAttribute('aria-invalid')
  })

  it('rejects a single-label email live, blocks submit, and clears once it is corrected', () => {
    renderForm()
    const email = screen.getByLabelText('Имейл за връзка (по избор)')
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Анна Иванова' },
    })

    for (const bad of ['a@a', 'a@\u0430', 'a@xn--80a', 'a..b@primer.bg', '.ab@primer.bg', 'ab.@primer.bg']) {
      fireEvent.change(email, { target: { value: bad } })
      expect(email).toHaveAccessibleDescription('Въведете валиден имейл, например ime@primer.bg.')
    }
    fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))
    expect(mockedCreateStaffMember).not.toHaveBeenCalled()
    expect(email).toHaveFocus()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    fireEvent.change(email, { target: { value: 'ime.prezime@primer.bg' } })
    expect(email).not.toHaveAttribute('aria-invalid')
    expect(document.querySelectorAll('.field-error')).toHaveLength(0)
  })

  it('maps backend fieldErrors to the telephone and email without a form-level alert', async () => {
    mockedCreateStaffMember.mockRejectedValue(
      new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.', {
        contactEmail: 'Въведеният имейл адрес не е валиден.',
        contactPhone: 'Въведеният телефонен номер не е валиден.',
      }),
    )
    renderForm()
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Анна Иванова' },
    })
    fireEvent.change(screen.getByLabelText('Имейл за връзка (по избор)'), {
      target: { value: 'anna@example.invalid' },
    })
    fireEvent.change(screen.getByLabelText('Телефон за връзка (по избор)'), {
      target: { value: '+359 88 123 4567' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))

    const email = screen.getByLabelText('Имейл за връзка (по избор)')
    const phone = screen.getByLabelText('Телефон за връзка (по избор)')
    expect(await screen.findByText('Въведеният телефонен номер не е валиден.')).toBeInTheDocument()
    expect(email).toHaveAccessibleDescription('Въведеният имейл адрес не е валиден.')
    expect(phone).toHaveAccessibleDescription('Въведеният телефонен номер не е валиден.')
    expect(email).toHaveFocus()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(phone).toHaveValue('+359 88 123 4567')

    fireEvent.change(email, { target: { value: 'anna@example.bg' } })
    expect(email).not.toHaveAttribute('aria-invalid')
    expect(phone).toHaveAttribute('aria-invalid', 'true')
  })

  it('keeps non-field failures in the form-level alert', async () => {
    mockedCreateStaffMember.mockRejectedValue(
      new ApiError(409, 'BUSINESS_SUSPENDED', 'Спрян бизнес може само да преглежда данните си.'),
    )
    renderForm()
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Анна Иванова' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Спрян бизнес')
    expect(document.querySelectorAll('.field-error')).toHaveLength(0)
  })
})

describe('StaffCreate late responses', () => {
  beforeEach(() => {
    mockedCreateStaffMember.mockReset()
  })

  it('does not navigate to the created StaffMember when the form was left before the response arrived', async () => {
    let resolveCreate: ((staffMember: StaffMemberDetails) => void) | undefined
    mockedCreateStaffMember.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveCreate = resolve
        }),
    )
    const onCreated = vi.fn()
    const { unmount } = render(
      <StaffCreate
        readOnly={false}
        onAuthenticationRequired={vi.fn()}
        onCreated={onCreated}
        onCancel={vi.fn()}
      />,
    )
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Анна Иванова' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Добави член на екипа' }))
    fireEvent.click(screen.getByRole('button', { name: 'Запазване…' }))
    expect(mockedCreateStaffMember).toHaveBeenCalledTimes(1)

    unmount()
    resolveCreate?.(created)
    await Promise.resolve()
    await Promise.resolve()
    expect(onCreated).not.toHaveBeenCalled()
  })
})
