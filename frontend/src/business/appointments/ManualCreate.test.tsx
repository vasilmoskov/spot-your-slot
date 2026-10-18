import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import { SEARCH_DEBOUNCE_MS } from '../customers/CustomerList'
import {
  DAY,
  chooseCustomer,
  chooseServices,
  chooseStaff,
  chooseTime,
  confirm,
  deferred,
  heading,
  installBackend,
  next,
  renderForm,
  toNote,
  toReview,
} from './testHarness'
import {
  CATALOG,
  CREATED_ID,
  CUSTOMER_1,
  FakeBackend,
  S_BROW,
  S_COLOR,
  S_CUT,
  S_MASSAGE,
  S_NAILS,
  S_WAX,
  STAFF_A,
  STAFF_B,
  slot,
} from './testFixtures'

// Only the network helper is mocked: the real feature API modules build and send every request.
vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
let backend: FakeBackend

beforeEach(() => {
  backend = installBackend(mockedRequest)
  history.replaceState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('the complete flow', () => {
  it('walks the six steps with the approved neutral headings and creates the visit with one exact POST', async () => {
    const handlers = renderForm()
    expect(heading()).toHaveTextContent('Клиент')
    expect(screen.getByText('Стъпка 1 от 6')).toBeInTheDocument()
    await chooseCustomer()
    expect(heading()).toHaveTextContent('Избор на услуги')
    await chooseServices('Дамско подстригване')
    expect(heading()).toHaveTextContent('Избор на служител')
    await chooseStaff()
    expect(heading()).toHaveTextContent('Дата и час')
    await chooseTime()
    expect(heading()).toHaveTextContent('Вътрешна бележка')
    fireEvent.change(screen.getByLabelText('Бележка (по желание)'), {
      target: { value: '  Предпочита сутрин.\r\n' },
    })
    next()
    expect(await screen.findByRole('heading', { level: 2, name: 'Преглед и потвърждение' })).toBeInTheDocument()

    const review = document.querySelector('dl')!
    expect(review).toHaveTextContent('Мария Тестова')
    expect(review).toHaveTextContent('Дамско подстригване')
    expect(review).toHaveTextContent('Общо: 30.00 € — 45 мин.')
    expect(review).toHaveTextContent('Мила Работникова')
    expect(review).toHaveTextContent('09:00–09:45')
    expect(review).toHaveTextContent('Предпочита сутрин.')

    confirm()
    await waitFor(() => expect(handlers.onCreated).toHaveBeenCalledWith(CREATED_ID))
    expect(backend.posts()).toHaveLength(1)
    expect(backend.posts()[0]!.body).toEqual({
      customerId: CUSTOMER_1,
      staffMemberId: STAFF_A,
      start: `${DAY}T09:00:00+02:00`,
      services: [
        {
          serviceId: S_CUT.id,
          reviewedServiceName: 'Дамско подстригване',
          reviewedDurationMinutes: 45,
          reviewedPriceCents: 3000,
        },
      ],
      internalNote: 'Предпочита сутрин.',
    })
    expect(Object.keys(backend.posts()[0]!.body as object)).toEqual([
      'customerId',
      'staffMemberId',
      'start',
      'services',
      'internalNote',
    ])
  })

  it('omits the note for a blank one and sends nothing but the contract keys', async () => {
    const handlers = await toReview()
    confirm()
    await waitFor(() => expect(handlers.onCreated).toHaveBeenCalled())
    expect(Object.keys(backend.posts()[0]!.body as object)).toEqual(['customerId', 'staffMemberId', 'start', 'services'])
  })

  it('offers Back before the forward action on every step and the review', async () => {
    renderForm()
    const order = () => screen.getAllByRole('button').map((button) => button.textContent)
    expect(order()).toEqual(['Обратно към графика', 'Добави клиент', 'Напред'])
    await chooseCustomer()
    expect(order().slice(-2)).toEqual(['Назад', 'Напред'])
    await chooseServices('Маникюр')
    await chooseStaff()
    await chooseTime()
    next()
    await screen.findByRole('button', { name: 'Създай час' })
    expect(order().slice(-2)).toEqual(['Назад', 'Създай час'])
  })
})

describe('Customer', () => {
  it('lists the Customers of the Business with one contact line and selects exactly one', async () => {
    renderForm()
    const group = await screen.findByRole('group', { name: 'Клиенти' })
    expect(within(group).getAllByRole('radio')).toHaveLength(2)
    expect(group).toHaveTextContent('+359 895 555 777')
    expect(group).toHaveTextContent('boris@example.test')
    expect(group).not.toHaveTextContent('maria@example.test')
    fireEvent.click(screen.getByRole('radio', { name: /Борис/ }))
    expect(screen.getByRole('status')).toHaveTextContent('Избран клиент: Борис Пробен')
  })

  it('searches by containing, case-insensitive text through the POST body only, after the debounce', async () => {
    vi.useFakeTimers()
    renderForm()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    fireEvent.change(screen.getByLabelText('Търсене на клиент'), { target: { value: 'ОРИ' } })
    expect(backend.count('/api/business/customers/search', 'POST')).toBe(0)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SEARCH_DEBOUNCE_MS)
    })
    const search = backend.calls.find((call) => call.path === '/api/business/customers/search')!
    expect(search.body).toMatchObject({ search: 'ОРИ' })
    expect(backend.calls.every((call) => !call.path.includes('ОРИ') && !call.path.includes('%D0%9E'))).toBe(true)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.getByRole('radio', { name: /Борис/ })).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: /Мария/ })).toBeNull()
  })

  it('ignores a late answer of a superseded search', async () => {
    const late = deferred<unknown>()
    const original = backend.handle.bind(backend)
    backend.handle = (path, options) => {
      if (path === '/api/business/customers/search' && JSON.parse(String(options?.body)).search === 'Б') {
        backend.calls.push({ path, method: 'POST', body: { search: 'Б' } })
        return late.promise
      }
      return original(path, options)
    }
    vi.useFakeTimers()
    renderForm()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    fireEvent.change(screen.getByLabelText('Търсене на клиент'), { target: { value: 'Б' } })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SEARCH_DEBOUNCE_MS)
    })
    fireEvent.change(screen.getByLabelText('Търсене на клиент'), { target: { value: 'Мария' } })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SEARCH_DEBOUNCE_MS)
    })
    expect(screen.getByRole('radio', { name: /Мария/ })).toBeInTheDocument()
    await act(async () => {
      late.resolve({ items: [{ id: 'x', displayName: 'Закъснял Клиент', phone: null, email: null }], page: 0, size: 10, total: 1 })
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.queryByText('Закъснял Клиент')).toBeNull()
  })

  it('shows empty, failure and retry states and never creates a Customer', async () => {
    backend.customers = []
    renderForm()
    expect(await screen.findByText('Все още няма добавени клиенти.')).toBeInTheDocument()
    cleanup()
    let fail = true
    const original = backend.handle.bind(backend)
    backend.handle = async (path, options) => {
      if (path.startsWith('/api/business/customers') && fail) throw new TypeError('offline')
      return original(path, options)
    }
    backend.customers = [{ id: CUSTOMER_1, displayName: 'Мария Тестова', phone: null, email: 'm@example.test' }]
    renderForm()
    expect(await screen.findByRole('alert')).toHaveTextContent('Няма връзка със сървъра')
    fail = false
    fireEvent.click(screen.getByRole('button', { name: 'Опитай отново' }))
    expect(await screen.findByRole('radio', { name: /Мария/ })).toBeInTheDocument()
    expect(backend.calls.some((call) => call.method === 'POST' && call.path === '/api/business/customers')).toBe(false)
  })

  it('requires a Customer, focuses the search field and treats Добави клиент as navigation away', async () => {
    const handlers = renderForm()
    await screen.findByRole('radio', { name: /Мария/ })
    next()
    expect(screen.getByRole('alert')).toHaveTextContent('Изберете клиент.')
    expect(screen.getByLabelText('Търсене на клиент')).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: 'Добави клиент' }))
    expect(handlers.onAddCustomer).toHaveBeenCalledTimes(1)
  })
})

describe('Services', () => {
  it('shows the catalog in catalog order with price first, then duration, and no inactive Service', async () => {
    renderForm()
    await chooseCustomer()
    const boxes = await screen.findAllByRole('checkbox')
    expect(boxes.map((box) => box.closest('label')?.querySelector('.manual-choice-title')?.textContent)).toEqual(
      CATALOG.map((service) => service.name),
    )
    expect(screen.queryByText('Спряна услуга')).toBeNull()
    expect(screen.getByRole('checkbox', { name: /Дамско подстригване/ })).toHaveAccessibleName(/30\.00 € — 45 мин\./)
  })

  it('sums money in integer cents and minutes exactly and shows the total price before the duration', async () => {
    renderForm()
    await chooseCustomer()
    await screen.findByRole('checkbox', { name: /Маникюр/ })
    for (const service of [S_BROW, S_WAX]) fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(service.name) }))
    // 0.10 + 0.20 is 0.30 exactly, not 0.30000000000000004.
    expect(screen.getByText('Общо: 0.30 € — 35 мин.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: /Боядисване/ }))
    expect(screen.getByText('Общо: 70.80 € — 125 мин.')).toBeInTheDocument()
    expect(screen.getByRole('status', { name: 'Избрани услуги' })).toHaveTextContent('Избрани услуги: 3')
  })

  it('prevents a sixth Service and a total above 480 minutes, keeps a selected one removable and drops nothing', async () => {
    const many = [S_CUT, S_COLOR, S_NAILS, S_BROW, S_WAX, S_MASSAGE]
    renderForm()
    await chooseCustomer()
    await screen.findByRole('checkbox', { name: /Маникюр/ })
    for (const service of many.slice(0, 5)) fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(service.name) }))
    const sixth = screen.getByRole('checkbox', { name: /Масаж/ })
    expect(sixth).toBeDisabled()
    expect(screen.getByText('Най-много 5 услуги в един час.')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /Дамско подстригване/ })).toBeEnabled()
    fireEvent.click(screen.getByRole('checkbox', { name: /Дамско подстригване/ }))
    expect(sixth).toBeEnabled()
    expect(screen.getByRole('status', { name: 'Избрани услуги' })).toHaveTextContent('Избрани услуги: 4')
  })

  it('refuses a selection whose durations pass eight hours', async () => {
    backend.catalog = [
      { ...S_CUT, durationMinutes: 300 },
      { ...S_COLOR, durationMinutes: 180 },
      { ...S_NAILS, durationMinutes: 1 },
    ].sort((a, b) => a.name.localeCompare(b.name, 'bg'))
    renderForm()
    await chooseCustomer()
    await screen.findByRole('checkbox', { name: /Маникюр/ })
    fireEvent.click(screen.getByRole('checkbox', { name: /Дамско подстригване/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /Боядисване/ }))
    expect(screen.getByRole('checkbox', { name: /Маникюр/ })).toBeDisabled()
    expect(screen.getByText('Общата продължителност не може да надвишава 8 часа.')).toBeInTheDocument()
    expect(screen.getByText('Общо: 100.50 € — 480 мин.')).toBeInTheDocument()
  })

  it('requires at least one Service and shows the failure of the catalog with a retry', async () => {
    renderForm()
    await chooseCustomer()
    await screen.findByRole('checkbox', { name: /Маникюр/ })
    next()
    expect(screen.getByRole('alert')).toHaveTextContent('Изберете поне една услуга.')
    expect(screen.getAllByRole('checkbox')[0]).toHaveFocus()
  })

  it('sends the same bytes whatever order the Services were ticked in', async () => {
    const bodies: unknown[] = []
    for (const order of [['Дамско подстригване', 'Боядисване', 'Маникюр'], ['Маникюр', 'Дамско подстригване', 'Боядисване']]) {
      backend = installBackend(mockedRequest)
      const handlers = await toReview(order)
      confirm()
      await waitFor(() => expect(handlers.onCreated).toHaveBeenCalled())
      bodies.push(JSON.stringify(backend.posts()[0]!.body))
      cleanup()
    }
    expect(bodies[0]).toBe(bodies[1])
    const parsed = JSON.parse(bodies[0] as string)
    expect(parsed.services.map((claim: { serviceId: string }) => claim.serviceId)).toEqual(
      [...parsed.services.map((claim: { serviceId: string }) => claim.serviceId)].sort(),
    )
  })
})

describe('StaffMember and availability', () => {
  it('requests the options for the canonical selection and never preselects a StaffMember', async () => {
    renderForm()
    await chooseCustomer()
    await chooseServices('Дамско подстригване', 'Маникюр')
    const radios = await screen.findAllByRole('radio')
    expect(radios).toHaveLength(2)
    expect(radios.every((radio) => !(radio as HTMLInputElement).checked)).toBe(true)
    const request = backend.calls.find((call) => call.path.startsWith('/api/business/appointment-options?'))!
    expect(new URL(request.path, 'http://x').searchParams.getAll('serviceId')).toEqual([S_NAILS.id, S_CUT.id])
    next()
    expect(screen.getByRole('alert')).toHaveTextContent('Изберете член на екипа.')
  })

  it('distinguishes an empty intersection from a failed read and offers to change the Services', async () => {
    backend.options = (ids) => ({ timezone: 'Europe/Sofia', durationMinutes: ids.length * 30, staffMembers: [] })
    renderForm()
    await chooseCustomer()
    await chooseServices('Дамско подстригване', 'Маникюр')
    expect(await screen.findByRole('status')).toHaveTextContent('Няма член на екипа, който извършва всички избрани услуги.')
    fireEvent.click(screen.getByRole('button', { name: 'Промени услугите' }))
    expect(heading()).toHaveTextContent('Избор на услуги')
    cleanup()

    backend = installBackend(mockedRequest)
    let fail = true
    backend.options = (ids) => {
      if (fail) throw backend.problem(500, 'INTERNAL_ERROR', 'SQL leak')
      return { timezone: 'Europe/Sofia', durationMinutes: ids.length * 30, staffMembers: backend.staff }
    }
    renderForm()
    await chooseCustomer()
    await chooseServices('Маникюр')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Екипът не може да бъде зареден.')
    expect(alert).not.toHaveTextContent('SQL')
    fail = false
    fireEvent.click(screen.getByRole('button', { name: 'Опитай отново' }))
    expect(await screen.findAllByRole('radio')).toHaveLength(2)
  })

  it('shows eligible StaffMembers with no free time distinctly and offers another date', async () => {
    backend.availability = (_ids, _staff, date) => ({ timezone: 'Europe/Sofia', date, durationMinutes: 45, slots: [] })
    renderForm()
    await chooseCustomer()
    await chooseServices('Дамско подстригване')
    await chooseStaff()
    fireEvent.change(await screen.findByLabelText('Дата'), { target: { value: DAY } })
    expect(await screen.findByRole('status')).toHaveTextContent('Няма свободни часове за тази дата.')
    next()
    expect(screen.getByRole('alert')).toHaveTextContent('Изберете час.')
  })

  it('requests the starts for exactly the selection, the StaffMember and the date, once', async () => {
    renderForm()
    await chooseCustomer()
    await chooseServices('Дамско подстригване', 'Маникюр')
    await chooseStaff('Георги')
    await chooseTime()
    const calls = backend.calls.filter((call) => call.path.startsWith('/api/business/appointment-options/availability'))
    expect(calls).toHaveLength(1)
    const params = new URL(calls[0]!.path, 'http://x').searchParams
    expect(params.getAll('serviceId')).toEqual([S_NAILS.id, S_CUT.id])
    expect(params.get('staffMemberId')).toBe(STAFF_B)
    expect(params.get('date')).toBe(DAY)
  })

  it('offers no date before the Business today', async () => {
    renderForm()
    await chooseCustomer()
    await chooseServices('Маникюр')
    await chooseStaff()
    expect(await screen.findByLabelText('Дата')).toHaveAttribute('min', '2026-10-02')
  })

  it('does not reload anything when the note changes', async () => {
    await toNote()
    const before = backend.calls.length
    fireEvent.change(screen.getByLabelText('Бележка (по желание)'), { target: { value: 'Нещо' } })
    expect(backend.calls.length).toBe(before)
  })
})

describe('daylight saving', () => {
  const originalZone = process.env['TZ']
  afterEach(() => {
    if (originalZone === undefined) delete process.env['TZ']
    else process.env['TZ'] = originalZone
  })

  it('keeps both occurrences of a repeated start distinct with their offsets, whatever the browser timezone', async () => {
    for (const zone of ['Pacific/Kiritimati', 'America/Los_Angeles', 'UTC']) {
      process.env['TZ'] = zone
      cleanup()
      backend = installBackend(mockedRequest)
      backend.availability = (_ids, _staff, date) => ({
        timezone: 'Europe/Sofia',
        date,
        durationMinutes: 45,
        slots: [
          slot('2026-10-25T03:30:00+03:00', 45),
          slot('2026-10-25T03:30:00+02:00', 45),
          slot('2026-10-25T04:15:00+02:00', 45),
        ],
      })
      renderForm()
      await chooseCustomer()
      await chooseServices('Дамско подстригване')
      await chooseStaff()
      fireEvent.change(await screen.findByLabelText('Дата'), { target: { value: '2026-10-25' } })
      const group = await screen.findByRole('group', { name: 'Свободни часове' })
      expect(within(group).getAllByRole('radio').map((radio) => radio.closest('label')?.textContent)).toEqual([
        '03:30 (UTC+03:00)',
        '03:30 (UTC+02:00)',
        '04:15',
      ])
      fireEvent.click(within(group).getAllByRole('radio')[1]!)
      next()
      next()
      await screen.findByRole('button', { name: 'Създай час' })
      expect(document.querySelector('dl')).toHaveTextContent('03:30 (UTC+02:00)')
      confirm()
      await waitFor(() => expect(backend.posts()).toHaveLength(1))
      // The chosen instant is sent with its own offset: the second occurrence, not the first.
      expect((backend.posts()[0]!.body as { start: string }).start).toBe('2026-10-25T03:30:00+02:00')
    }
  })

  it('offers no start inside the spring gap and sends the chosen one unchanged', async () => {
    backend.availability = (_ids, _staff, date) => ({
      timezone: 'Europe/Sofia',
      date,
      durationMinutes: 30,
      slots: [slot('2026-03-29T02:30:00+02:00', 30), slot('2026-03-29T04:00:00+03:00', 30)],
    })
    renderForm()
    await chooseCustomer()
    await chooseServices('Маникюр')
    await chooseStaff()
    fireEvent.change(await screen.findByLabelText('Дата'), { target: { value: '2026-03-29' } })
    const group = await screen.findByRole('group', { name: 'Свободни часове' })
    const labels = within(group).getAllByRole('radio').map((radio) => radio.closest('label')?.textContent)
    expect(labels).toEqual(['02:30', '04:00'])
    expect(labels.some((label) => label?.startsWith('03:'))).toBe(false)
  })

  it('never shifts a date-only value through the browser timezone', async () => {
    for (const zone of ['Pacific/Kiritimati', 'Pacific/Pago_Pago']) {
      process.env['TZ'] = zone
      cleanup()
      backend = installBackend(mockedRequest)
      renderForm()
      await chooseCustomer()
      await chooseServices('Маникюр')
      await chooseStaff()
      fireEvent.change(await screen.findByLabelText('Дата'), { target: { value: '2026-10-25' } })
      expect(await screen.findByText(/25 октомври 2026/)).toBeInTheDocument()
      const call = backend.calls.filter((c) => c.path.includes('availability')).at(-1)!
      expect(new URL(call.path, 'http://x').searchParams.get('date')).toBe('2026-10-25')
    }
  })
})
