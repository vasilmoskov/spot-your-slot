import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from '../../identity/api'
import type { AppointmentOrigin } from '../../navigation'
import { UnsavedChangesGuardProvider } from '../../ui/UnsavedChangesGuard'
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
  render(
    <UnsavedChangesGuardProvider>
      <AppointmentDetail appointmentId={APPOINTMENT_ONE} origin={origin} {...handlers} />
    </UnsavedChangesGuardProvider>,
  )
  return handlers
}

describe('Appointment detail', () => {
  it('renders the authoritative read model with ordered Services, totals and the Customer', async () => {
    renderDetail()
    expect(
      await screen.findByRole('heading', { level: 2, name: /Сряда, 14 октомври 2026 г., 10:00–11:15/ }),
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
    // Price first, as everywhere: "25.50 € — 30 мин".
    expect(services[0]).toHaveTextContent('25.50 € — 30 мин')
    expect(services[1]).toHaveTextContent('55.00 € — 45 мин')
    const facts = screen.getByRole('region', { name: 'Детайли' })
    expect(facts).toHaveTextContent('1 ч 15 мин')
    expect(facts).toHaveTextContent('80.50 €')
    expect(facts).toHaveTextContent('Мила Работникова')
    expect(facts).toHaveTextContent('SYS-2026-0001')
    // No technical timezone identifier is shown.
    expect(document.body.textContent).not.toMatch(/Часова зона|Europe\/Sofia/)
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
    // Three separate lines, the timestamp in the Business timezone and without a weekday.
    expect([...notice.querySelectorAll('.cancelled-notice-line')].map((line) => line.textContent)).toEqual([
      'Този час е отменен.',
      'Времето е свободно за ново записване.',
      'Дата на отмяна: 10 октомври 2026 г., 09:30',
    ])
    expect(notice).not.toHaveTextContent('Отменен на')
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
    await screen.findByRole('region', { name: 'Детайли' })
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
    expect(await screen.findByRole('region', { name: 'Детайли' })).toBeInTheDocument()
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

describe('Appointment detail terminology (Phase 11 Part 1)', () => {
  it('names the inner section "Детайли", keeps the page title, and has no timezone field', async () => {
    renderDetail()
    expect(await screen.findByRole('region', { name: 'Детайли' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Час' })).toBeNull()
    expect(screen.getByRole('heading', { level: 3, name: 'Детайли' })).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/Часова зона|Europe\/Sofia/)
  })

  it('writes the source in plain words for both sources and never an enum value', async () => {
    renderDetail()
    const facts = await screen.findByRole('region', { name: 'Детайли' })
    // The fixture is a guest booking.
    expect(facts).toHaveTextContent('Източник')
    expect(facts).toHaveTextContent('Онлайн резервация')
    expect(facts).toHaveTextContent('Номер')
    cleanup()
    mockedRequest.mockResolvedValue(detailResponse({ source: 'MANUAL' }))
    renderDetail()
    const manual = await screen.findByRole('region', { name: 'Детайли' })
    expect(manual).toHaveTextContent('Въведен от бизнеса')
    expect(document.body.textContent).not.toMatch(/\bONLINE\b|\bMANUAL\b/)
  })

  it('numbers the Services in stored order with price first and a long name that wraps in its card', async () => {
    mockedRequest.mockResolvedValue(
      detailResponse({
        services: [
          { position: 2, name: 'Боядисване', durationMinutes: 90, price: 70.5 },
          { position: 1, name: 'Дамско-подстригване-с-изключително-дълго-име-без-интервали-за-проверка', durationMinutes: 45, price: 30 },
        ],
      }),
    )
    renderDetail()
    const list = (await screen.findByRole('region', { name: 'Услуги' })).querySelector('ol')!
    expect(list.tagName).toBe('OL')
    const items = within(list).getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('30.00 € — 45 мин')
    expect(items[1]).toHaveTextContent('Боядисване70.50 € — 1 ч 30 мин')
  })
})
