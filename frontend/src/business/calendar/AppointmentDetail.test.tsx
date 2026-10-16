import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from '../../identity/api'
import type { AppointmentOrigin } from '../../navigation'
import { AppointmentDetail } from './AppointmentDetail'
import { APPOINTMENT_ONE, CUSTOMER_ONE, detailResponse } from './testFixtures'

vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
const ORIGINAL_TZ = process.env['TZ']

beforeEach(() => {
  mockedRequest.mockReset()
  mockedRequest.mockResolvedValue(detailResponse())
})

afterEach(() => {
  cleanup()
  if (ORIGINAL_TZ === undefined) delete process.env['TZ']
  else process.env['TZ'] = ORIGINAL_TZ
})

function renderDetail(origin: AppointmentOrigin = { kind: 'calendar', calendar: { view: 'day', date: null, staffMemberId: null, page: 0, size: 10 } }) {
  const handlers = {
    onAuthenticationRequired: vi.fn(),
    onBack: vi.fn(),
    onOpenCustomer: vi.fn(),
  }
  render(<AppointmentDetail appointmentId={APPOINTMENT_ONE} origin={origin} {...handlers} />)
  return handlers
}

describe('Appointment detail', () => {
  it('renders the authoritative read model with ordered Services, totals and the Customer', async () => {
    renderDetail()
    expect(
      await screen.findByRole('heading', { level: 2, name: /сряда, 14 октомври 2026 г., 10:00–11:15/ }),
    ).toBeInTheDocument()
    expect(mockedRequest).toHaveBeenCalledWith(
      `/api/business/appointments/${APPOINTMENT_ONE}`,
      expect.anything(),
    )
    expect(screen.getByText('Потвърден')).toBeInTheDocument()
    const services = within(screen.getByRole('list')).getAllByRole('listitem')
    // Stored position order, not response order.
    expect(services.map((item) => item.querySelector('.appointment-service-name')?.textContent)).toEqual([
      'Подстригване',
      'Боядисване',
    ])
    expect(services[0]).toHaveTextContent('30 мин · 25.50 €')
    expect(services[1]).toHaveTextContent('45 мин · 55.00 €')
    const facts = screen.getByRole('region', { name: 'Час' })
    expect(facts).toHaveTextContent('1 ч 15 мин')
    expect(facts).toHaveTextContent('80.50 €')
    expect(facts).toHaveTextContent('Мила Работникова')
    expect(facts).toHaveTextContent('SYS-2026-0001')
    expect(facts).toHaveTextContent('Europe/Sofia')
    const customer = screen.getByRole('region', { name: 'Клиент' })
    expect(within(customer).getByRole('link', { name: 'Мария Тестова' })).toBeInTheDocument()
    expect(customer).toHaveTextContent('+359 895 555 777')
    expect(customer).toHaveTextContent('maria@example.test')
  })

  it('shows notes, an absent note as plain text, and never version or audit metadata', async () => {
    mockedRequest.mockResolvedValue(
      detailResponse({ customerNote: 'Алергия към боя.', internalNote: 'Втори час след месец.' }),
    )
    renderDetail()
    const notes = await screen.findByRole('region', { name: 'Бележки' })
    expect(notes).toHaveTextContent('Алергия към боя.')
    expect(notes).toHaveTextContent('Втори час след месец.')
    expect(document.body.textContent).not.toMatch(/версия|version|\b7\b|2026-10-01|createdAt/i)
  })

  it('distinguishes a cancelled Appointment and shows the cancellation time, never who cancelled', async () => {
    mockedRequest.mockResolvedValue(
      detailResponse({
        status: 'CANCELLED',
        cancellation: { cancelledAt: '2026-10-10T06:30:00Z', cancelledBy: 'BUSINESS' },
      }),
    )
    renderDetail()
    expect(await screen.findByText('Отменен')).toBeInTheDocument()
    const notice = screen.getByRole('status')
    expect(notice).toHaveTextContent('Отменен на')
    expect(notice).toHaveTextContent('10 октомври 2026')
    expect(notice).toHaveTextContent('09:30')
    expect(document.body.textContent).not.toMatch(/BUSINESS|UNKNOWN|бизнеса е отменил/i)
    expect(screen.queryByText('Потвърден')).toBeNull()
  })

  it('does not invent a cancellation time when the contract has none', async () => {
    mockedRequest.mockResolvedValue(
      detailResponse({ status: 'CANCELLED', cancellation: { cancelledAt: null, cancelledBy: 'UNKNOWN' } }),
    )
    renderDetail()
    const notice = await screen.findByRole('status')
    expect(notice).not.toHaveTextContent('Отменен на')
  })

  it('renders the same local times under any browser timezone', async () => {
    for (const zone of ['Pacific/Kiritimati', 'America/Los_Angeles']) {
      process.env['TZ'] = zone
      cleanup()
      renderDetail()
      expect(await screen.findByRole('heading', { level: 2, name: /10:00–11:15/ })).toBeInTheDocument()
    }
  })

  it('offers no mutation control: only the way back and the Customer link', async () => {
    renderDetail()
    await screen.findByRole('region', { name: 'Час' })
    expect(screen.getAllByRole('button').map((button) => button.textContent)).toEqual(['Обратно към графика'])
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(document.body.textContent).not.toMatch(/Отмени час|Пренасрочи|Смени|Запази|Редактирай/)
  })

  it('returns to the Customer when it was opened from the Customer history', async () => {
    const handlers = renderDetail({ kind: 'customer', customerId: CUSTOMER_ONE })
    fireEvent.click(await screen.findByRole('button', { name: 'Обратно към клиента' }))
    expect(handlers.onBack).toHaveBeenCalledTimes(1)
  })

  it('opens the Customer', async () => {
    const handlers = renderDetail()
    fireEvent.click(await screen.findByRole('link', { name: 'Мария Тестова' }))
    expect(handlers.onOpenCustomer).toHaveBeenCalledWith(CUSTOMER_ONE)
  })
})

describe('Appointment detail failures', () => {
  it('shows the same safe not-found state, focused, with the way back and no retry, for a missing or foreign record', async () => {
    for (const status of [404, 400]) {
      cleanup()
      mockedRequest.mockRejectedValue(
        new ApiError(status, status === 404 ? 'APPOINTMENT_NOT_FOUND' : 'VALIDATION_ERROR', 'Часът не е намерен.'),
      )
      const handlers = renderDetail()
      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('Часът не е намерен.')
      await waitFor(() => expect(alert).toHaveFocus())
      expect(screen.queryByRole('button', { name: 'Зареди отново' })).toBeNull()
      fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
      expect(handlers.onBack).toHaveBeenCalled()
    }
  })

  it('shows a retryable generic failure that never shows backend text', async () => {
    mockedRequest.mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'stack trace here'))
    renderDetail()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Възникна неочаквана грешка.')
    expect(alert).not.toHaveTextContent('stack trace')
    fireEvent.click(screen.getByRole('button', { name: 'Зареди отново' }))
    expect(await screen.findByRole('region', { name: 'Час' })).toBeInTheDocument()
  })

  it('reports a network failure and an expired login distinctly', async () => {
    mockedRequest.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    renderDetail()
    expect(await screen.findByRole('alert')).toHaveTextContent('Няма връзка със сървъра.')
    cleanup()
    mockedRequest.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Влезте отново.'))
    const handlers = renderDetail()
    await waitFor(() => expect(handlers.onAuthenticationRequired).toHaveBeenCalledWith('Влезте отново.'))
  })
})
