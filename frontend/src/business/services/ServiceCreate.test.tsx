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
    expect(
      screen.getByText('Бизнесът е временно спрян — нови услуги не могат да бъдат създавани.'),
    ).toBeInTheDocument()
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
    fireEvent.click(screen.getByRole('button', { name: 'Продължи редактирането' }))

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
