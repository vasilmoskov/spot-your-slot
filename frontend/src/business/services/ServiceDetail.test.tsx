import '@testing-library/jest-dom/vitest'
import { fireEvent, render as rtlRender, screen, waitFor } from '@testing-library/react'
import type { ReactElement } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { UnsavedChangesGuardProvider } from '../../ui/UnsavedChangesGuard'
import {
  deactivateService,
  getService,
  reactivateService,
  updateService,
  type ServiceDetails,
} from './api'
import { ServiceDetail } from './ServiceDetail'

function render(ui: ReactElement) {
  return rtlRender(ui, { wrapper: UnsavedChangesGuardProvider })
}

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getService: vi.fn(),
  updateService: vi.fn(),
  deactivateService: vi.fn(),
  reactivateService: vi.fn(),
}))

const mockedGetService = vi.mocked(getService)
const mockedUpdateService = vi.mocked(updateService)
const mockedDeactivateService = vi.mocked(deactivateService)
const mockedReactivateService = vi.mocked(reactivateService)

const service: ServiceDetails = {
  id: 'service-a',
  name: 'Подстригване',
  description: 'Класическо подстригване',
  durationMinutes: 10,
  price: 19.9,
  active: true,
  version: 0,
  createdAt: '2026-08-19T09:00:00Z',
  updatedAt: '2026-08-19T09:00:00Z',
}

describe('ServiceDetail', () => {
  const onAuthenticationRequired = vi.fn()
  const onBack = vi.fn()

  beforeEach(() => {
    mockedGetService.mockReset()
    mockedUpdateService.mockReset()
    mockedDeactivateService.mockReset()
    mockedReactivateService.mockReset()
    onAuthenticationRequired.mockReset()
    onBack.mockReset()
  })

  it('shows loaded details and hides mutation actions when read-only', async () => {
    mockedGetService.mockResolvedValue(service)
    render(
      <ServiceDetail
        serviceId="service-a"
        readOnly
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    expect(await screen.findByRole('heading', { name: 'Подстригване' })).toBeInTheDocument()
    expect(screen.getAllByText('19.90 €')).toHaveLength(1)
    expect(screen.getAllByText('10 мин.')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Редактирай' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Деактивирай' })).not.toBeInTheDocument()
  })

  it('edits, saves, and returns to read-only view', async () => {
    mockedGetService.mockResolvedValue(service)
    mockedUpdateService.mockResolvedValue({ ...service, name: 'Подстригване и оформяне', version: 1 })
    render(
      <ServiceDetail
        serviceId="service-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Подстригване' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име на услугата'), {
      target: { value: 'Подстригване и оформяне' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

    await waitFor(() =>
      expect(mockedUpdateService).toHaveBeenCalledWith('service-a', {
        name: 'Подстригване и оформяне',
        description: 'Класическо подстригване',
        durationMinutes: 10,
        price: '19.90',
        expectedVersion: 0,
      }),
    )
    expect(await screen.findByText('Промените са запазени.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Подстригване и оформяне' })).toBeInTheDocument()
  })

  it('exits editing immediately when Cancel is pressed with no changes', async () => {
    mockedGetService.mockResolvedValue(service)
    render(
      <ServiceDetail
        serviceId="service-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Подстригване' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))

    expect(mockedUpdateService).not.toHaveBeenCalled()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Име на услугата')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Подстригване' })).toBeInTheDocument()
  })

  it('asks for confirmation before discarding a dirty edit, and confirming discards it', async () => {
    mockedGetService.mockResolvedValue(service)
    render(
      <ServiceDetail
        serviceId="service-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Подстригване' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Незаписано' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))

    const confirmation = screen.getByRole('alertdialog', { name: 'Незапазени промени' })
    expect(confirmation).toHaveTextContent(
      'Направените промени няма да бъдат запазени. Сигурни ли сте, че искате да продължите?',
    )
    expect(mockedUpdateService).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Име на услугата')).toHaveValue('Незаписано')

    fireEvent.click(screen.getByRole('button', { name: 'Откажи промените' }))

    expect(mockedUpdateService).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('Име на услугата')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Подстригване' })).toBeInTheDocument()
  })

  it('returns safely to editing and preserves entered values when continuing to edit', async () => {
    mockedGetService.mockResolvedValue(service)
    render(
      <ServiceDetail
        serviceId="service-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Подстригване' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Незаписано' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    fireEvent.click(screen.getByRole('button', { name: 'Продължи редактирането' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Име на услугата')).toHaveValue('Незаписано')
  })

  it('returns to editing on Escape from the cancel confirmation', async () => {
    mockedGetService.mockResolvedValue(service)
    render(
      <ServiceDetail
        serviceId="service-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Подстригване' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Незаписано' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' })

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Име на услугата')).toHaveValue('Незаписано')
  })

  it('requires confirmation before deactivating and shows the Service name', async () => {
    mockedGetService.mockResolvedValue(service)
    mockedDeactivateService.mockResolvedValue({ ...service, active: false, version: 1 })
    render(
      <ServiceDetail
        serviceId="service-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Подстригване' })
    fireEvent.click(screen.getByRole('button', { name: 'Деактивирай' }))
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Подстригване')
    expect(mockedDeactivateService).not.toHaveBeenCalled()
    // The safe action, never the destructive one, receives initial focus.
    expect(screen.getByRole('button', { name: 'Отказ' })).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Потвърди деактивирането' })).not.toHaveFocus()

    fireEvent.click(screen.getByRole('button', { name: 'Потвърди деактивирането' }))
    await waitFor(() => expect(mockedDeactivateService).toHaveBeenCalledWith('service-a', 0))
    expect(await screen.findByText('Услугата е деактивирана.')).toBeInTheDocument()
    expect(screen.getByText('Неактивна')).toBeInTheDocument()
  })

  it('reactivates without confirmation', async () => {
    mockedGetService.mockResolvedValue({ ...service, active: false })
    mockedReactivateService.mockResolvedValue({ ...service, active: true, version: 1 })
    render(
      <ServiceDetail
        serviceId="service-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Подстригване' })
    fireEvent.click(screen.getByRole('button', { name: 'Активирай отново' }))
    await waitFor(() => expect(mockedReactivateService).toHaveBeenCalledWith('service-a', 0))
    expect(await screen.findByText('Услугата е активирана отново.')).toBeInTheDocument()
  })

  it('offers a reload action on a concurrent-update conflict', async () => {
    mockedGetService.mockResolvedValue(service)
    mockedUpdateService.mockRejectedValue(
      new ApiError(409, 'SERVICE_CONCURRENT_UPDATE', 'Услугата е променена.'),
    )
    render(
      <ServiceDetail
        serviceId="service-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Подстригване' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Услугата е променена.')
    expect(screen.getByRole('button', { name: 'Зареди актуалните данни' })).toBeInTheDocument()
  })

  it('confirms before a reload discards a dirty edit after a concurrent-update conflict', async () => {
    mockedGetService.mockResolvedValue(service)
    mockedUpdateService.mockRejectedValue(
      new ApiError(409, 'SERVICE_CONCURRENT_UPDATE', 'Услугата е променена.'),
    )
    render(
      <ServiceDetail
        serviceId="service-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Подстригване' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име на услугата'), { target: { value: 'Ново име' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await screen.findByRole('button', { name: 'Зареди актуалните данни' })

    fireEvent.click(screen.getByRole('button', { name: 'Зареди актуалните данни' }))
    expect(mockedGetService).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Продължи редактирането' }))
    expect(screen.getByLabelText('Име на услугата')).toHaveValue('Ново име')
    expect(mockedGetService).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Зареди актуалните данни' }))
    fireEvent.click(screen.getByRole('button', { name: 'Откажи промените' }))
    await waitFor(() => expect(mockedGetService).toHaveBeenCalledTimes(2))
  })

  it('redirects to authentication on a 401 load response', async () => {
    mockedGetService.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    render(
      <ServiceDetail
        serviceId="service-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'))
  })
})
