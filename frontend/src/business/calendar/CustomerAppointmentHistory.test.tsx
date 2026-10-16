import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from '../../identity/api'
import { CustomerAppointmentHistory } from './CustomerAppointmentHistory'
import { CUSTOMER_ONE, historyEntry, historyPage } from './testFixtures'

vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
const NOW = Date.parse('2026-10-14T12:00:00Z')

beforeEach(() => {
  mockedRequest.mockReset()
  mockedRequest.mockResolvedValue(historyPage())
})

afterEach(cleanup)

function renderHistory() {
  const handlers = { onAuthenticationRequired: vi.fn(), onOpenAppointment: vi.fn() }
  render(<CustomerAppointmentHistory customerId={CUSTOMER_ONE} now={() => NOW} {...handlers} />)
  return handlers
}

describe('Customer Appointment history', () => {
  it('requests exactly one bounded server page and keeps the server order', async () => {
    mockedRequest.mockResolvedValue(
      historyPage(
        [
          historyEntry({ id: 'c', start: '2026-10-20T10:00:00+03:00', end: '2026-10-20T10:30:00+03:00', serviceNames: ['Бъдеща'] }),
          historyEntry({ id: 'b', start: '2026-10-10T10:00:00+03:00', end: '2026-10-10T10:30:00+03:00', serviceNames: ['Минала'] }),
          historyEntry({ id: 'a', status: 'CANCELLED', start: '2026-10-05T10:00:00+03:00', end: '2026-10-05T10:30:00+03:00', serviceNames: ['Отказана'] }),
        ],
        { total: 3 },
      ),
    )
    renderHistory()
    const list = await screen.findByRole('list')
    expect(mockedRequest).toHaveBeenCalledTimes(1)
    expect(mockedRequest).toHaveBeenCalledWith(
      `/api/business/customers/${CUSTOMER_ONE}/appointments?page=0&size=10`,
      expect.anything(),
    )
    const items = within(list).getAllByRole('listitem')
    expect(items.map((item) => item.querySelector('.calendar-entry-services')?.textContent)).toEqual([
      'Бъдеща',
      'Минала',
      'Отказана',
    ])
    expect(items[0]).toHaveTextContent('Предстоящ')
    expect(items[1]).toHaveTextContent('Минал')
    expect(items[2]).toHaveTextContent('Отменен')
    expect(items[2]).not.toHaveTextContent('Минал')
    expect(items[2]).toHaveClass('is-cancelled')
  })

  it('shows only the minimum facts: no note, contact, price, source or version', async () => {
    renderHistory()
    const item = (await screen.findByRole('listitem')) as HTMLElement
    expect(item).toHaveTextContent('Дамско подстригване')
    expect(item).toHaveTextContent('Мила Работникова')
    expect(item.textContent).not.toMatch(/бележка|@|\+359|€|версия|ONLINE|Онлайн/i)
  })

  it('explains an empty history', async () => {
    mockedRequest.mockResolvedValue(historyPage([], { total: 0 }))
    renderHistory()
    expect(await screen.findByText('Клиентът няма часове.')).toBeInTheDocument()
    expect(screen.queryByRole('list')).toBeNull()
  })

  it('links to the Appointment detail', async () => {
    const handlers = renderHistory()
    fireEvent.click(await screen.findByRole('link', { name: /14 октомври 2026/ }))
    expect(handlers.onOpenAppointment).toHaveBeenCalledWith(historyEntry().id)
  })

  it('pages exactly as the server does, with sizes 10, 25 and 50', async () => {
    mockedRequest.mockResolvedValue(historyPage([historyEntry()], { total: 30 }))
    renderHistory()
    await screen.findByRole('list')
    fireEvent.click(screen.getByRole('button', { name: 'Следваща' }))
    await waitFor(() =>
      expect(mockedRequest).toHaveBeenLastCalledWith(
        `/api/business/customers/${CUSTOMER_ONE}/appointments?page=1&size=10`,
        expect.anything(),
      ),
    )
    fireEvent.change(screen.getByLabelText('Резултати на страница'), { target: { value: '50' } })
    await waitFor(() =>
      expect(mockedRequest).toHaveBeenLastCalledWith(
        `/api/business/customers/${CUSTOMER_ONE}/appointments?page=0&size=50`,
        expect.anything(),
      ),
    )
  })

  it('recovers an empty page past the end to the last valid page', async () => {
    mockedRequest.mockImplementation(async (path) =>
      String(path).includes('page=3')
        ? historyPage([], { page: 3, total: 12 })
        : historyPage([historyEntry()], { page: 1, total: 12 }),
    )
    renderHistory()
    await screen.findByRole('list')
    fireEvent.click(screen.getByRole('button', { name: 'Следваща' }))
    await waitFor(() => expect(screen.getByRole('list')).toBeInTheDocument())
  })

  it('shows a safe failure with retry and hands an expired login to the application', async () => {
    mockedRequest.mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'SQL leak'))
    const handlers = renderHistory()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Възникна неочаквана грешка.')
    expect(alert).not.toHaveTextContent('SQL')
    fireEvent.click(screen.getByRole('button', { name: 'Опитай отново' }))
    expect(await screen.findByRole('list')).toBeInTheDocument()
    cleanup()
    mockedRequest.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Влезте отново.'))
    const second = renderHistory()
    await waitFor(() => expect(second.onAuthenticationRequired).toHaveBeenCalled())
    expect(handlers.onAuthenticationRequired).not.toHaveBeenCalled()
  })

  it('ignores a late answer of a superseded page', async () => {
    let resolveFirst!: (value: unknown) => void
    mockedRequest.mockImplementationOnce(
      () =>
        new Promise<never>((resolve) => {
          resolveFirst = (value) => resolve(value as never)
        }),
    )
    mockedRequest.mockResolvedValue(historyPage([historyEntry({ serviceNames: ['Новата'] })], { total: 30 }))
    renderHistory()
    await waitFor(() => expect(mockedRequest).toHaveBeenCalledTimes(1))
    // The first request is still pending: the effect cannot be superseded without a page change,
    // so unmounting must abort it and make its answer inert.
    cleanup()
    resolveFirst(historyPage([historyEntry({ serviceNames: ['Старата'] })]))
    expect(screen.queryByText('Старата')).toBeNull()
  })
})
