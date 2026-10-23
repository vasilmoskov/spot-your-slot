import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from '../../identity/api'
import { CustomerAppointmentHistory } from './CustomerAppointmentHistory'
import { backspace, paste, select, type } from './keyboardTestSupport'
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
      `/api/business/customers/${CUSTOMER_ONE}/appointments?page=0&size=10&direction=asc`,
      expect.anything(),
    )
    const items = within(list).getAllByRole('listitem')
    expect(items.map((item) => item.querySelector('.calendar-entry-services')?.textContent)).toEqual([
      'Бъдеща',
      'Минала',
      'Отказана',
    ])
    // The server's order is shown as it comes; each confirmed entry has its own temporal badge, a cancelled one has none.
    expect(items[0]).toHaveTextContent('Предстоящ')
    expect(items[1]).toHaveTextContent('Минал')
    expect(items[2]).not.toHaveTextContent(/Предстоящ|В момента|Минал/)
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
    fireEvent.click(screen.getByRole('button', { name: 'Следваща страница' }))
    await waitFor(() =>
      expect(mockedRequest).toHaveBeenLastCalledWith(
        `/api/business/customers/${CUSTOMER_ONE}/appointments?page=1&size=10&direction=asc`,
        expect.anything(),
      ),
    )
    fireEvent.change(screen.getByLabelText('На страница'), { target: { value: '50' } })
    await waitFor(() =>
      expect(mockedRequest).toHaveBeenLastCalledWith(
        `/api/business/customers/${CUSTOMER_ONE}/appointments?page=0&size=50&direction=asc`,
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
    fireEvent.click(screen.getByRole('button', { name: 'Следваща страница' }))
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

describe('Customer Appointment history: order and date', () => {
  const lastPath = () => String(mockedRequest.mock.calls.at(-1)![0])

  it('asks for the earlier ones first by default and offers both orders under a labelled control', async () => {
    renderHistory()
    await screen.findByRole('list')
    expect(lastPath()).toBe(`/api/business/customers/${CUSTOMER_ONE}/appointments?page=0&size=10&direction=asc`)
    const order = screen.getByLabelText('Подредба') as HTMLSelectElement
    expect(order.value).toBe('asc')
    expect([...order.options].map((option) => option.textContent)).toEqual(['От най-ранна дата', 'От най-късна дата'])
  })

  it('maps each visible order label to its server direction', async () => {
    renderHistory()
    await screen.findByRole('list')
    const order = screen.getByLabelText('Подредба') as HTMLSelectElement
    const choose = (label: string) =>
      fireEvent.change(order, { target: { value: [...order.options].find((option) => option.textContent === label)!.value } })
    choose('От най-късна дата')
    await waitFor(() => expect(lastPath()).toContain('direction=desc'))
    choose('От най-ранна дата')
    await waitFor(() => expect(lastPath()).toContain('direction=asc'))
    expect(screen.queryByText(/първо/)).toBeNull()
  })

  it('applies the other order on the server, returns to page one and keeps the chosen size', async () => {
    mockedRequest.mockResolvedValue(historyPage([historyEntry()], { total: 60 }))
    renderHistory()
    await screen.findByRole('list')
    fireEvent.change(screen.getByLabelText('На страница'), { target: { value: '25' } })
    await waitFor(() => expect(lastPath()).toContain('size=25'))
    fireEvent.click(screen.getByRole('button', { name: 'Следваща страница' }))
    await waitFor(() => expect(lastPath()).toContain('page=1&size=25'))
    fireEvent.change(screen.getByLabelText('Подредба'), { target: { value: 'desc' } })
    await waitFor(() => expect(lastPath()).toBe(`/api/business/customers/${CUSTOMER_ONE}/appointments?page=0&size=25&direction=desc`))
  })

  it('filters by one Business-local date, returns to page one, and clearing it restores every date', async () => {
    mockedRequest.mockResolvedValue(historyPage([historyEntry()], { total: 30 }))
    renderHistory()
    await screen.findByRole('list')
    fireEvent.click(screen.getByRole('button', { name: 'Следваща страница' }))
    await waitFor(() => expect(lastPath()).toContain('page=1'))
    const input = screen.getByLabelText('Дата') as HTMLInputElement
    expect(input).toHaveValue('')
    type(input, '25102026')
    await waitFor(() => expect(lastPath()).toBe(`/api/business/customers/${CUSTOMER_ONE}/appointments?page=0&size=10&direction=asc&date=2026-10-25`))
    select(input, 0, input.value.length)
    backspace(input)
    await waitFor(() => expect(lastPath()).toBe(`/api/business/customers/${CUSTOMER_ONE}/appointments?page=0&size=10&direction=asc`))
  })

  it('sends no request for incomplete or invalid dates, and one for each newly complete date', async () => {
    renderHistory()
    await screen.findByRole('list')
    const calls = () => mockedRequest.mock.calls.length
    const before = calls()
    const input = screen.getByLabelText('Дата') as HTMLInputElement
    type(input, '22.10.202')
    type(input, '')
    expect(calls()).toBe(before)
    select(input, 0, input.value.length)
    type(input, '31022026')
    expect(screen.getByText('Въведете валидна дата.')).toBeInTheDocument()
    select(input, 0, input.value.length)
    type(input, '29022026')
    expect(calls()).toBe(before)
    select(input, 0, input.value.length)
    type(input, '29022028')
    await waitFor(() => expect(lastPath()).toContain('date=2028-02-29'))
    expect(calls()).toBe(before + 1)
  })

  it('keeps the applied results on screen while the date is being replaced', async () => {
    mockedRequest.mockResolvedValue(historyPage([historyEntry({ serviceNames: ['Първият'] })], { total: 1 }))
    renderHistory()
    await screen.findByText('Първият')
    const input = screen.getByLabelText('Дата') as HTMLInputElement
    type(input, '25102026')
    await waitFor(() => expect(lastPath()).toContain('date=2026-10-25'))
    const applied = mockedRequest.mock.calls.length
    backspace(input)
    backspace(input)
    expect(screen.getByText('Първият')).toBeInTheDocument()
    expect(mockedRequest).toHaveBeenCalledTimes(applied)
  })

  it('a changed date keeps the chosen order and size and returns to the first page', async () => {
    mockedRequest.mockResolvedValue(historyPage([historyEntry()], { total: 60 }))
    renderHistory()
    await screen.findByRole('list')
    fireEvent.change(screen.getByLabelText('На страница'), { target: { value: '25' } })
    await waitFor(() => expect(lastPath()).toContain('size=25'))
    fireEvent.change(screen.getByLabelText('Подредба'), { target: { value: 'desc' } })
    fireEvent.click(await screen.findByRole('button', { name: 'Следваща страница' }))
    await waitFor(() => expect(lastPath()).toContain('page=1&size=25&direction=desc'))
    paste(screen.getByLabelText('Дата') as HTMLInputElement, '25.10.2026')
    await waitFor(() => expect(lastPath()).toBe(`/api/business/customers/${CUSTOMER_ONE}/appointments?page=0&size=25&direction=desc&date=2026-10-25`))
  })

  it('applies a date chosen in the calendar and shows it as text', async () => {
    renderHistory()
    await screen.findByRole('list')
    fireEvent.change(document.querySelector('input[type="date"]')!, { target: { value: '2026-12-05' } })
    await waitFor(() => expect(lastPath()).toContain('date=2026-12-05'))
    expect(screen.getByLabelText('Дата')).toHaveValue('05.12.2026')
  })

  it('says so when the chosen date has no Appointments, and keeps the controls to change it', async () => {
    renderHistory()
    await screen.findByRole('list')
    mockedRequest.mockResolvedValue(historyPage([], { total: 0 }))
    type(screen.getByLabelText('Дата') as HTMLInputElement, '24122026')
    expect(await screen.findByText('Няма часове на тази дата.')).toBeInTheDocument()
    expect(screen.queryByText('Клиентът няма часове.')).toBeNull()
    expect(screen.getByLabelText('Дата')).toHaveValue('24.12.2026')
    expect(screen.queryByRole('navigation', { name: /Странициране/ })).toBeNull()
  })

  it('keeps the choices in the component, never in the address or storage', async () => {
    renderHistory()
    await screen.findByRole('list')
    type(screen.getByLabelText('Дата') as HTMLInputElement, '25102026')
    fireEvent.change(screen.getByLabelText('Подредба'), { target: { value: 'desc' } })
    await waitFor(() => expect(lastPath()).toContain('direction=desc'))
    expect(window.location.href).not.toContain('2026-10-25')
    expect(JSON.stringify({ ...window.localStorage, ...window.sessionStorage })).not.toContain('2026-10-25')
  })
})
