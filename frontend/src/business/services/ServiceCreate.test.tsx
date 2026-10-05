import '@testing-library/jest-dom/vitest'
import { fireEvent, render as rtlRender, screen, waitFor } from '@testing-library/react'
import type { ReactElement } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { UnsavedChangesGuardProvider, useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import { Button } from '../../ui/Button'
import { createService, type ServiceDetails } from './api'
import { ServiceCreate } from './ServiceCreate'

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

// Mirrors the real App.tsx wiring: onCreated triggers a guarded navigation
// synchronously, in the same call as the successful-create handling, so this
// reproduces the exact race a plain unmount-timing fix would miss.
function GuardedOnCreated({
  onCreatedSpy,
  children,
}: {
  onCreatedSpy: (serviceId: string) => void
  children: (onCreated: (serviceId: string) => void) => ReactElement
}) {
  const guard = useUnsavedChangesGuard()
  const onCreated = (serviceId: string) => {
    guard.guard(() => onCreatedSpy(serviceId))
  }
  return children(onCreated)
}

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  createService: vi.fn(),
}))

const mockedCreateService = vi.mocked(createService)

const created: ServiceDetails = {
  id: 'service-a',
  name: 'Подстригване',
  description: null,
  durationMinutes: 10,
  price: 19.9,
  active: true,
  version: 0,
  createdAt: '2026-08-19T09:00:00Z',
  updatedAt: '2026-08-19T09:00:00Z',
}

describe('ServiceCreate', () => {
  const onAuthenticationRequired = vi.fn()
  const onCreated = vi.fn()
  const onCancel = vi.fn()

  beforeEach(() => {
    mockedCreateService.mockReset()
    onAuthenticationRequired.mockReset()
    onCreated.mockReset()
    onCancel.mockReset()
  })

  it('shows a read-only notice instead of the form when the Business is SUSPENDED', () => {
    render(
      <ServiceCreate
        readOnly
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    expect(screen.queryByLabelText('Име на услугата')).not.toBeInTheDocument()
    expect(screen.queryByText(/временно спрян/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Обратно към услугите' })).toBeInTheDocument()
  })

  it('starts with a completely empty form and no starter presets', () => {
    render(
      <ServiceCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    expect(screen.getByLabelText('Име на услугата')).toHaveValue('')
    expect(screen.getByLabelText('Описание (по избор)')).toHaveValue('')
    expect(screen.getByLabelText('Продължителност (минути)')).toHaveValue(null)
    expect(screen.getByLabelText('Цена (EUR)')).toHaveValue('')
    expect(screen.queryByRole('button', { name: 'Подстригване' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Брада' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Вежди' })).not.toBeInTheDocument()
  })

  it('sends the exact decimal price string on submit and navigates to the created Service', async () => {
    mockedCreateService.mockResolvedValue(created)
    render(
      <ServiceCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Подстригване' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '19.90' } })
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))

    await waitFor(() =>
      expect(mockedCreateService).toHaveBeenCalledWith({
        name: 'Подстригване',
        description: undefined,
        durationMinutes: 10,
        price: '19.90',
      }),
    )
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith('service-a'))
  })

  it('shows a safe conflict error and keeps entered values', async () => {
    mockedCreateService.mockRejectedValue(
      new ApiError(409, 'SERVICE_NAME_CONFLICT', 'Вече съществува услуга с това име.'),
    )
    render(
      <ServiceCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Подстригване' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '19.90' } })
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Вече съществува услуга с това име.',
    )
    expect(screen.getByLabelText('Име на услугата')).toHaveValue('Подстригване')
    expect(onCreated).not.toHaveBeenCalled()
  })

  it('redirects to authentication on a 401 response', async () => {
    mockedCreateService.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    render(
      <ServiceCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Подстригване' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '19.90' } })
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))

    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'))
  })

  it('clears the dirty guard after a successful creation so a subsequent navigation does not prompt', async () => {
    mockedCreateService.mockResolvedValue(created)
    render(
      <ServiceCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Подстригване' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '19.90' } })

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))

    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith('service-a'))

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('does not show a false prompt when the created-Service navigation itself is guarded synchronously', async () => {
    mockedCreateService.mockResolvedValue(created)
    render(
      <GuardedOnCreated onCreatedSpy={onCreated}>
        {(guardedOnCreated) => (
          <ServiceCreate
            readOnly={false}
            onAuthenticationRequired={onAuthenticationRequired}
            onCreated={guardedOnCreated}
            onCancel={onCancel}
          />
        )}
      </GuardedOnCreated>,
    )
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Подстригване' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '19.90' } })
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith('service-a'))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('keeps the dirty guard active after a failed creation', async () => {
    mockedCreateService.mockRejectedValue(
      new ApiError(409, 'SERVICE_NAME_CONFLICT', 'Вече съществува услуга с това име.'),
    )
    render(
      <ServiceCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Подстригване' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '19.90' } })
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))
    await screen.findByRole('alert')

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(screen.getByLabelText('Име на услугата')).toHaveValue('Подстригване')
  })

  it('cancels back without submitting', () => {
    render(
      <ServiceCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към услугите' }))
    expect(onCancel).toHaveBeenCalledOnce()
    expect(mockedCreateService).not.toHaveBeenCalled()
  })
})

describe('ServiceCreate inline validation', () => {
  const onAuthenticationRequired = vi.fn()
  const onCreated = vi.fn()
  const onCancel = vi.fn()

  beforeEach(() => {
    mockedCreateService.mockReset()
    onCreated.mockReset()
  })

  function renderForm() {
    render(
      <ServiceCreate
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={onCreated}
        onCancel={onCancel}
      />,
    )
  }

  it('rejects a whitespace-only name locally with a field error, focus, and ARIA wiring', () => {
    renderForm()
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: '   ' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))

    const name = screen.getByLabelText('Име на услугата')
    expect(mockedCreateService).not.toHaveBeenCalled()
    expect(screen.getByText('Въведете име на услугата.')).toBeInTheDocument()
    expect(name).toHaveAttribute('aria-invalid', 'true')
    expect(name).toHaveAccessibleDescription('Въведете име на услугата.')
    expect(name).toHaveFocus()
    // No generic form-level replacement for the specific message.
    expect(screen.queryByText('Проверете въведените данни.')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    // Entered safe values are preserved.
    expect(screen.getByLabelText('Продължителност (минути)')).toHaveValue(30)
    expect(screen.getByLabelText('Цена (EUR)')).toHaveValue('10')
  })

  it('shows every field error at once, focuses the first, and clears each on correction', () => {
    renderForm()
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))

    expect(screen.getByText('Въведете име на услугата.')).toBeInTheDocument()
    expect(screen.getByText('Въведете продължителност в минути.')).toBeInTheDocument()
    expect(screen.getByText('Въведете цена.')).toBeInTheDocument()
    expect(screen.getByLabelText('Име на услугата')).toHaveFocus()

    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Боядисване' } })
    expect(screen.queryByText('Въведете име на услугата.')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Име на услугата')).not.toHaveAttribute('aria-invalid')
    expect(screen.getByText('Въведете цена.')).toBeInTheDocument()
  })

  it('reports duration and price errors next to their own fields and revalidates live', () => {
    renderForm()
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Боядисване' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '481' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '12,50' } })
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))

    const duration = screen.getByLabelText('Продължителност (минути)')
    const price = screen.getByLabelText('Цена (EUR)')
    expect(duration).toHaveAccessibleDescription(
      'Продължителността трябва да бъде между 1 и 480 минути.',
    )
    expect(price).toHaveAccessibleDescription(/най-много 2 знака след десетичната точка/)
    expect(duration).toHaveFocus()
    expect(mockedCreateService).not.toHaveBeenCalled()

    // Still invalid: the message updates; valid: it disappears at once.
    fireEvent.change(duration, { target: { value: '1.5' } })
    expect(duration).toHaveAccessibleDescription(/цяло число минути/)
    fireEvent.change(duration, { target: { value: '45' } })
    expect(duration).not.toHaveAttribute('aria-invalid')
    fireEvent.change(price, { target: { value: '12.50' } })
    expect(price).not.toHaveAttribute('aria-invalid')
    expect(screen.queryByText(/най-много 2 знака/)).not.toBeInTheDocument()
  })

  it('shows no error on the untouched form and validates a field when it loses focus', () => {
    renderForm()
    expect(document.querySelectorAll('.field-error')).toHaveLength(0)
    expect(screen.getByLabelText('Име на услугата')).not.toHaveAttribute('aria-invalid')

    const name = screen.getByLabelText('Име на услугата')
    fireEvent.focus(name)
    fireEvent.blur(name)
    expect(screen.getByText('Въведете име на услугата.')).toBeInTheDocument()
    expect(name).toHaveAccessibleDescription('Въведете име на услугата.')
    // Other untouched fields stay quiet.
    expect(screen.queryByText('Въведете цена.')).not.toBeInTheDocument()

    fireEvent.change(name, { target: { value: 'А' } })
    expect(screen.queryByText('Въведете име на услугата.')).not.toBeInTheDocument()
    fireEvent.change(name, { target: { value: '   ' } })
    expect(screen.getByText('Въведете име на услугата.')).toBeInTheDocument()
  })

  it('shows a negative duration and a negative price immediately, before blur or submit', () => {
    renderForm()
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '-5' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '-1' } })

    expect(
      screen.getByText('Продължителността трябва да бъде между 1 и 480 минути.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Цената не може да бъде отрицателна.')).toBeInTheDocument()
    // The untouched empty name is still quiet.
    expect(screen.queryByText('Въведете име на услугата.')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '0' } })
    expect(screen.queryByText('Цената не може да бъде отрицателна.')).not.toBeInTheDocument()
  })

  it('maps backend fieldErrors to their fields without any form-level alert', async () => {
    mockedCreateService.mockRejectedValue(
      new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.', {
        price: 'Въведете валидна цена в евро с най-много 2 знака след десетичната точка.',
      }),
    )
    renderForm()
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Боядисване' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '45' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))

    const price = screen.getByLabelText('Цена (EUR)')
    expect(await screen.findByText(/Въведете валидна цена в евро/)).toBeInTheDocument()
    expect(price).toHaveAttribute('aria-invalid', 'true')
    expect(price).toHaveFocus()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText('Проверете въведените данни.')).not.toBeInTheDocument()

    // Correcting the field drops the backend message immediately.
    fireEvent.change(price, { target: { value: '31' } })
    expect(price).not.toHaveAttribute('aria-invalid')
  })

  it('falls back to a form-level message for unknown field names and never shows them inline', async () => {
    mockedCreateService.mockRejectedValue(
      new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.', {
        unknownField: 'Нещо',
      }),
    )
    renderForm()
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Боядисване' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '45' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Проверете името, описанието, продължителността и цената.',
    )
    expect(screen.queryByText('Нещо')).not.toBeInTheDocument()
    expect(document.querySelectorAll('.field-error')).toHaveLength(0)
  })

  it('keeps non-field failures in the form-level alert', async () => {
    mockedCreateService.mockRejectedValue(new Error('network'))
    renderForm()
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Боядисване' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '45' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Услугата не може да бъде създадена.')
    expect(document.querySelectorAll('.field-error')).toHaveLength(0)
  })

  it('replaces the backend generic validation text with an actionable form-level message', async () => {
    mockedCreateService.mockRejectedValue(
      new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.'),
    )
    renderForm()
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Боядисване' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '45' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Проверете името, описанието, продължителността и цената.',
    )
    expect(screen.queryByText('Проверете въведените данни.')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Име на услугата')).toHaveValue('Боядисване')
  })
})

describe('ServiceCreate late responses', () => {
  beforeEach(() => {
    mockedCreateService.mockReset()
  })

  it('does not navigate to the created Service when the form was left before the response arrived', async () => {
    let resolveCreate: ((service: ServiceDetails) => void) | undefined
    mockedCreateService.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveCreate = resolve
        }),
    )
    const onCreated = vi.fn()
    const { unmount } = render(
      <ServiceCreate
        readOnly={false}
        onAuthenticationRequired={vi.fn()}
        onCreated={onCreated}
        onCancel={vi.fn()}
      />,
    )
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Подстригване' } })
    fireEvent.change(screen.getByLabelText('Продължителност (минути)'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('Цена (EUR)'), { target: { value: '19.90' } })
    fireEvent.click(screen.getByRole('button', { name: 'Създай услуга' }))
    // A second activation while the request is pending is ignored.
    fireEvent.click(screen.getByRole('button', { name: 'Запазване…' }))
    expect(mockedCreateService).toHaveBeenCalledTimes(1)

    unmount()
    resolveCreate?.({
      id: 'service-a',
      name: 'Подстригване',
      description: null,
      durationMinutes: 10,
      price: 19.9,
      active: true,
      version: 0,
      createdAt: '2026-08-19T09:00:00Z',
      updatedAt: '2026-08-19T09:00:00Z',
    })
    await Promise.resolve()
    await Promise.resolve()
    expect(onCreated).not.toHaveBeenCalled()
  })
})
