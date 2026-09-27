import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { listServices, type ServicePage } from './api'
import { ServiceList } from './ServiceList'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  listServices: vi.fn(),
}))

const mockedListServices = vi.mocked(listServices)

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const populatedPage: ServicePage = {
  services: [
    {
      id: 'service-a',
      name: 'Подстригване',
      description: null,
      durationMinutes: 10,
      price: 19.9,
      active: true,
      version: 0,
      createdAt: '2026-08-19T09:00:00Z',
      updatedAt: '2026-08-19T09:00:00Z',
    },
  ],
  page: 0,
  size: 50,
  totalElements: 1,
}

describe('ServiceList', () => {
  const onAuthenticationRequired = vi.fn()

  beforeEach(() => {
    mockedListServices.mockReset()
    onAuthenticationRequired.mockReset()
  })

  it('shows a loading state before the list resolves', async () => {
    const { promise } = deferred<ServicePage>()
    mockedListServices.mockReturnValue(promise)
    render(<ServiceList readOnly={false} onAuthenticationRequired={onAuthenticationRequired} />)
    expect(screen.getByText('Зареждане на услугите…')).toBeInTheDocument()
  })

  it('shows the empty state when there are no Services', async () => {
    mockedListServices.mockResolvedValue({ services: [], page: 0, size: 50, totalElements: 0 })
    render(<ServiceList readOnly={false} onAuthenticationRequired={onAuthenticationRequired} />)
    expect(await screen.findByText('Все още няма създадени услуги.')).toBeInTheDocument()
  })

  it('renders loaded Services with active status and formatted price/duration', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    render(<ServiceList readOnly={false} onAuthenticationRequired={onAuthenticationRequired} />)
    expect(await screen.findByText('Подстригване')).toBeInTheDocument()
    expect(screen.getByText('10 мин.')).toBeInTheDocument()
    expect(screen.getByText('19.90 €')).toBeInTheDocument()
    expect(screen.getByText('Активна')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Нова услуга' })).toBeInTheDocument()
  })

  it('hides mutation actions and shows a notice when the Business is SUSPENDED', async () => {
    mockedListServices.mockResolvedValue(populatedPage)
    render(<ServiceList readOnly onAuthenticationRequired={onAuthenticationRequired} />)
    await screen.findByText('Подстригване')
    expect(screen.queryByRole('button', { name: 'Нова услуга' })).not.toBeInTheDocument()
    expect(
      screen.getByText('Бизнесът е временно спрян — услугите могат само да бъдат преглеждани.'),
    ).toBeInTheDocument()
  })

  it('shows a safe error and supports retry', async () => {
    mockedListServices.mockRejectedValueOnce(new Error('boom'))
    mockedListServices.mockResolvedValueOnce(populatedPage)
    render(<ServiceList readOnly={false} onAuthenticationRequired={onAuthenticationRequired} />)
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Списъкът с услуги не може да бъде зареден.',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Опитай отново' }))
    expect(await screen.findByText('Подстригване')).toBeInTheDocument()
  })

  it('redirects to authentication on a 401 response', async () => {
    mockedListServices.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    render(<ServiceList readOnly={false} onAuthenticationRequired={onAuthenticationRequired} />)
    await waitFor(() =>
      expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'),
    )
  })

  // The obsolete-vs-current race for this component is proven at the App
  // level (Business A -> Business B remount), which exercises a genuine
  // context change rather than an anonymous-callback rerender. See
  // App.test.tsx: "remounts Services and discards a stale Business-A
  // response after switching to Business B".
})
