import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from '../../identity/api'
import {
  DAY,
  back,
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
  CREATED_ID,
  FakeBackend,
  S_CUT,
  STAFF_A,
  STAFF_B,
  createdResponse,
  slot,
} from './testFixtures'
import { NOTE_MAX_CODE_POINTS } from './model'

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
  vi.restoreAllMocks()
})

const noteField = () => screen.getByLabelText('Бележка (по желание)')
const staffRadios = () => screen.findAllByRole('radio')
const alertText = () => screen.getByRole('alert').textContent ?? ''

describe('upstream choices clear every invalid dependent choice', () => {
  it('a changed Service set clears the StaffMember, the date and the start; the Customer and the note stay', async () => {
    await toNote()
    fireEvent.change(noteField(), { target: { value: 'Важна бележка' } })
    next()
    await screen.findByRole('button', { name: 'Създай час' })
    fireEvent.click(screen.getByRole('button', { name: 'Промени услугите' }))
    fireEvent.click(await screen.findByRole('checkbox', { name: /Маникюр/ }))
    next()
    // The review edit goes to the first incomplete step: the StaffMember.
    expect(heading()).toHaveTextContent('Избор на служител')
    expect((await staffRadios()).every((radio) => !(radio as HTMLInputElement).checked)).toBe(true)
    await chooseStaff()
    expect(heading()).toHaveTextContent('Дата и час')
    expect(screen.getByLabelText('Дата')).toHaveValue('')
    expect(screen.queryByRole('group', { name: 'Свободни часове' })).toBeNull()
    await chooseTime()
    // The note step is complete: the edit continues straight to the review, note intact.
    const review = await screen.findByRole('button', { name: 'Създай час' })
    expect(review).toBeInTheDocument()
    expect(document.querySelector('dl')).toHaveTextContent('Важна бележка')
    expect(document.querySelector('dl')).toHaveTextContent('Мария Тестова')
    expect(document.querySelector('dl')).toHaveTextContent('Дамско подстригване')
    expect(document.querySelector('dl')).toHaveTextContent('Маникюр')
  })

  it('a changed StaffMember clears the start but keeps the date; a changed date clears the start', async () => {
    renderForm()
    await chooseCustomer()
    await chooseServices('Дамско подстригване')
    await chooseStaff()
    await chooseTime(DAY, 1)
    next()
    await screen.findByRole('button', { name: 'Създай час' })

    fireEvent.click(screen.getByRole('button', { name: 'Промени члена на екипа' }))
    fireEvent.click(await screen.findByRole('radio', { name: /Георги/ }))
    next()
    expect(heading()).toHaveTextContent('Дата и час')
    expect(screen.getByLabelText('Дата')).toHaveValue(DAY)
    const group = await screen.findByRole('group', { name: 'Свободни часове' })
    expect(within(group).getAllByRole('radio').every((radio) => !(radio as HTMLInputElement).checked)).toBe(true)
    fireEvent.click(within(group).getAllByRole('radio')[2]!)
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-11' } })
    const again = await screen.findByRole('group', { name: 'Свободни часове' })
    expect(within(again).getAllByRole('radio').every((radio) => !(radio as HTMLInputElement).checked)).toBe(true)
    next()
    expect(screen.getByRole('alert')).toHaveTextContent('Изберете час.')
  })

  it('returns directly to the review after an edit whose dependents stay valid', async () => {
    renderForm()
    await chooseCustomer()
    await chooseServices('Дамско подстригване')
    await chooseStaff()
    await chooseTime()
    next()
    await screen.findByRole('button', { name: 'Създай час' })
    fireEvent.click(screen.getByRole('button', { name: 'Промени бележката' }))
    fireEvent.change(noteField(), { target: { value: 'Нова' } })
    next()
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    fireEvent.click(screen.getByRole('button', { name: 'Промени клиента' }))
    fireEvent.click(await screen.findByRole('radio', { name: /Борис/ }))
    next()
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(document.querySelector('dl')).toHaveTextContent('Борис Пробен')
    // The Customer change reloaded nothing about availability.
    expect(backend.count('/api/business/appointment-options/availability')).toBe(1)
  })

  it('drops a StaffMember that the refreshed options no longer offer and asks again', async () => {
    let changed = false
    backend.create = () => {
      if (!changed) {
        changed = true
        backend.options = (ids) => ({
          timezone: 'Europe/Sofia',
          durationMinutes: 45 * ids.length,
          staffMembers: [backend.staff[1]!],
        })
        throw backend.problem(409, 'APPOINTMENT_REVIEW_CHANGED')
      }
      return createdResponse()
    }
    await toReview()
    confirm()
    await waitFor(() => expect(heading()).toHaveTextContent('Избор на служител'))
    expect(await staffRadios()).toHaveLength(1)
    expect((await staffRadios())[0]).not.toBeChecked()
  })
})

describe('stale and late responses', () => {
  it('ignores the options of a superseded Service set', async () => {
    const late = deferred<unknown>()
    const original = backend.options
    backend.options = (ids) => (ids.length === 1 ? (late.promise as never) : original(ids))
    renderForm()
    await chooseCustomer()
    await chooseServices('Дамско подстригване')
    expect(await screen.findByText('Зареждане на екипа…')).toBeInTheDocument()
    back()
    fireEvent.click(await screen.findByRole('checkbox', { name: /Маникюр/ }))
    next()
    const radios = await staffRadios()
    expect(radios).toHaveLength(2)
    await act(async () => {
      late.resolve({ timezone: 'Europe/Sofia', durationMinutes: 45, staffMembers: [{ id: 'x', displayName: 'Закъсняла Служителка' }] })
    })
    expect(screen.queryByText('Закъсняла Служителка')).toBeNull()
  })

  it('ignores the starts of a superseded date', async () => {
    const late = deferred<unknown>()
    const original = backend.availability
    backend.availability = (ids, staff, date) => (date === '2026-11-10' ? (late.promise as never) : original(ids, staff, date))
    renderForm()
    await chooseCustomer()
    await chooseServices('Дамско подстригване')
    await chooseStaff()
    fireEvent.change(await screen.findByLabelText('Дата'), { target: { value: '2026-11-10' } })
    expect(await screen.findByText('Зареждане на свободните часове…')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-11' } })
    const group = await screen.findByRole('group', { name: 'Свободни часове' })
    await act(async () => {
      late.resolve({ timezone: 'Europe/Sofia', date: '2026-11-10', durationMinutes: 45, slots: [slot('2026-11-10T05:00:00+02:00', 45)] })
    })
    expect(within(group).getAllByRole('radio')).toHaveLength(3)
    expect(screen.queryByText('05:00')).toBeNull()
  })

  it('aborts the superseded request through its signal', async () => {
    const signals: AbortSignal[] = []
    mockedRequest.mockImplementation((path, options) => {
      const signal = (options as RequestInit | undefined)?.signal
      if (signal && path.includes('appointment-options')) signals.push(signal)
      return backend.handle(path, options as RequestInit | undefined) as Promise<never>
    })
    renderForm()
    await chooseCustomer()
    await chooseServices('Дамско подстригване')
    await chooseStaff()
    fireEvent.change(await screen.findByLabelText('Дата'), { target: { value: '2026-11-10' } })
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-11' } })
    await screen.findByRole('group', { name: 'Свободни часове' })
    expect(signals.filter((signal) => signal.aborted).length).toBeGreaterThanOrEqual(1)
    expect(signals.at(-1)!.aborted).toBe(false)
  })
})

describe('the internal note', () => {
  it('is optional, normalized, never truncated, and bounded without a technical counter', async () => {
    await toNote()
    fireEvent.change(noteField(), { target: { value: 'а'.repeat(NOTE_MAX_CODE_POINTS) } })
    next()
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    cleanup()
    backend = installBackend(mockedRequest)
    await toNote()
    fireEvent.change(noteField(), { target: { value: '😀'.repeat(NOTE_MAX_CODE_POINTS + 1) } })
    next()
    expect(screen.getByText('Бележката е твърде дълга. Съкратете я.')).toBeInTheDocument()
    expect(noteField()).toHaveFocus()
    expect(noteField()).toHaveAttribute('aria-invalid', 'true')
    expect(document.body.textContent).not.toMatch(/500|\b501\b/)
    expect((noteField() as HTMLTextAreaElement).value).toHaveLength(2 * (NOTE_MAX_CODE_POINTS + 1))
    fireEvent.change(noteField(), { target: { value: 'ab\u0001' } })
    next()
    expect(screen.getByText('Бележката съдържа непозволени знаци.')).toBeInTheDocument()
    fireEvent.change(noteField(), { target: { value: '   \n  ' } })
    next()
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(document.querySelector('dl')).toHaveTextContent('Няма бележка.')
  })

  it('sends a line break as LF and keeps interior text exactly', async () => {
    const handlers = await toNote()
    fireEvent.change(noteField(), { target: { value: ' ред 1\r\nред  2 ' } })
    next()
    await screen.findByRole('button', { name: 'Създай час' })
    confirm()
    await waitFor(() => expect(handlers.onCreated).toHaveBeenCalled())
    expect((backend.posts()[0]!.body as { internalNote: string }).internalNote).toBe('ред 1\nред  2')
  })
})

describe('the server is the authority over the client terms', () => {
  it('reads the Service terms again when the authoritative duration differs from the catalog', async () => {
    let catalogReads = 0
    const original = backend.handle.bind(backend)
    backend.handle = async (path, options) => {
      if (path.startsWith('/api/business/services')) {
        catalogReads += 1
        if (catalogReads > 1) {
          backend.catalog = backend.catalog.map((service) =>
            service.id === S_CUT.id ? { ...service, durationMinutes: 60 } : service,
          )
        }
      }
      return original(path, options)
    }
    backend.options = (ids) => ({
      timezone: 'Europe/Sofia',
      durationMinutes: ids.length === 1 && ids[0] === S_CUT.id ? 60 : 45 * ids.length,
      staffMembers: backend.staff,
    })
    renderForm()
    await chooseCustomer()
    await chooseServices('Дамско подстригване')
    await chooseStaff()
    await waitFor(() => expect(catalogReads).toBeGreaterThanOrEqual(2))
    fireEvent.change(await screen.findByLabelText('Дата'), { target: { value: DAY } })
    const group = await screen.findByRole('group', { name: 'Свободни часове' })
    fireEvent.click(within(group).getAllByRole('radio')[0]!)
    next()
    next()
    await screen.findByRole('button', { name: 'Създай час' })
    expect(document.querySelector('dl')).toHaveTextContent('Общо: 30.00 € — 60 мин.')
  })
})

describe('five Services', () => {
  it('creates one visit of five distinct Services with sorted claims and one POST', async () => {
    const handlers = await toReview(['Дамско подстригване', 'Боядисване', 'Маникюр', 'Оформяне на вежди', 'Кола маска'])
    expect(document.querySelector('dl')).toHaveTextContent('Общо: 125.80 € — 230 мин.')
    confirm()
    await waitFor(() => expect(handlers.onCreated).toHaveBeenCalled())
    expect(backend.posts()).toHaveLength(1)
    const claims = (backend.posts()[0]!.body as { services: { serviceId: string }[] }).services
    expect(claims).toHaveLength(5)
    const ids = claims.map((claim) => claim.serviceId)
    expect(ids).toEqual([...ids].sort())
    expect(new Set(ids).size).toBe(5)
  })
})

describe('submission', () => {
  it('sends exactly one POST for a double click and disables the confirmation while it is pending', async () => {
    const pending = deferred<ReturnType<typeof createdResponse>>()
    backend.create = () => pending.promise
    const handlers = await toReview()
    const button = screen.getByRole('button', { name: 'Създай час' })
    // Three clicks inside one batch: the button is not yet disabled for the second and the third.
    act(() => {
      button.click()
      button.click()
      button.click()
    })
    expect(backend.posts()).toHaveLength(1)
    expect(button).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Назад' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Обратно към графика' })).toBeDisabled()
    expect(screen.getByText('Създаване на часа…')).toBeInTheDocument()
    await act(async () => {
      pending.resolve(createdResponse())
    })
    await waitFor(() => expect(handlers.onCreated).toHaveBeenCalledTimes(1))
    expect(handlers.onCreated).toHaveBeenCalledWith(CREATED_ID)
    expect(backend.posts()).toHaveLength(1)
  })

  it('does not navigate when the answer arrives after the form was left', async () => {
    const pending = deferred<ReturnType<typeof createdResponse>>()
    backend.create = () => pending.promise
    const handlers = await toReview()
    confirm()
    handlers.view.unmount()
    await act(async () => {
      pending.resolve(createdResponse())
    })
    expect(handlers.onCreated).not.toHaveBeenCalled()
  })

  it('does not show the confirmation before the choices are loaded and complete', async () => {
    renderForm()
    expect(screen.queryByRole('button', { name: 'Създай час' })).toBeNull()
  })
})

describe('every documented result of the create', () => {
  async function fail(status: number, code: string) {
    backend.create = () => {
      throw backend.problem(status, code)
    }
    const handlers = await toReview()
    confirm()
    await screen.findByRole('alert')
    return handlers
  }

  it('slot unavailable: refreshes the starts, drops only the start and requires a new choice', async () => {
    const handlers = await fail(409, 'APPOINTMENT_SLOT_UNAVAILABLE')
    expect(alertText()).toBe('Избраният час вече не е свободен. Изберете друг час.')
    expect(heading()).toHaveTextContent('Дата и час')
    expect(screen.getByLabelText('Дата')).toHaveValue(DAY)
    await screen.findByRole('group', { name: 'Свободни часове' })
    expect(backend.count('/api/business/appointment-options/availability')).toBe(2)
    expect(backend.posts()).toHaveLength(1)
    expect(handlers.onCreated).not.toHaveBeenCalled()
    // The Customer, the Services and the StaffMember are kept: the review shows them again.
    backend.create = () => createdResponse()
    const radios = within(screen.getByRole('group', { name: 'Свободни часове' })).getAllByRole('radio')
    fireEvent.click(radios[1]!)
    next()
    expect(document.querySelector('dl')).toHaveTextContent('Мария Тестова')
    expect(document.querySelector('dl')).toHaveTextContent('Мила Работникова')
  })

  it('staff unavailable: refreshes the options and asks for a new StaffMember, keeping the rest', async () => {
    await fail(409, 'APPOINTMENT_STAFF_UNAVAILABLE')
    expect(alertText()).toContain('Избраният член на екипа не е наличен')
    expect(heading()).toHaveTextContent('Избор на служител')
    expect((await staffRadios()).every((radio) => !(radio as HTMLInputElement).checked)).toBe(true)
    expect(backend.count('/api/business/appointment-options?')).toBeGreaterThanOrEqual(2)
  })

  it('service unavailable: reads the catalog again and returns to the Services', async () => {
    backend.create = () => {
      backend.catalog = backend.catalog.filter((service) => service.id !== S_CUT.id)
      throw backend.problem(409, 'APPOINTMENT_SERVICE_UNAVAILABLE')
    }
    await toReview()
    confirm()
    await screen.findByRole('alert')
    expect(heading()).toHaveTextContent('Избор на услуги')
    await screen.findByRole('checkbox', { name: /Маникюр/ })
    expect(screen.queryByRole('checkbox', { name: /Дамско подстригване/ })).toBeNull()
    expect(screen.getByText('Не са избрани услуги.')).toBeInTheDocument()
  })

  it('reviewed terms changed: reads the terms again, shows the new total and never resubmits by itself', async () => {
    let changed = false
    backend.create = () => {
      if (!changed) {
        changed = true
        backend.catalog = backend.catalog.map((service) => (service.id === S_CUT.id ? { ...service, price: 33 } : service))
        throw backend.problem(409, 'APPOINTMENT_REVIEW_CHANGED')
      }
      return createdResponse()
    }
    const handlers = await toReview()
    confirm()
    expect(await screen.findByRole('alert')).toHaveTextContent('Услугите са променени. Прегледайте ги отново и опитайте пак.')
    await waitFor(() => expect(document.querySelector('dl')).toHaveTextContent('Общо: 33.00 € — 45 мин.'))
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(backend.posts()).toHaveLength(1)
    expect(handlers.onCreated).not.toHaveBeenCalled()
    // An explicit confirmation sends the new claims.
    confirm()
    await waitFor(() => expect(handlers.onCreated).toHaveBeenCalled())
    expect(backend.posts()).toHaveLength(2)
    expect((backend.posts()[1]!.body as { services: { reviewedPriceCents: number }[] }).services[0]!.reviewedPriceCents).toBe(3300)
  })

  it('reviewed terms changed with another duration: the start is dropped and a new one is required', async () => {
    let changed = false
    backend.create = () => {
      if (!changed) {
        changed = true
        backend.catalog = backend.catalog.map((service) => (service.id === S_CUT.id ? { ...service, durationMinutes: 60 } : service))
        throw backend.problem(409, 'APPOINTMENT_REVIEW_CHANGED')
      }
      return createdResponse()
    }
    await toReview()
    confirm()
    await screen.findByRole('alert')
    await waitFor(() => expect(heading()).toHaveTextContent('Дата и час'))
    expect(backend.posts()).toHaveLength(1)
  })

  it('customer not found: asks for another Customer and keeps everything else', async () => {
    await fail(404, 'CUSTOMER_NOT_FOUND')
    expect(alertText()).toBe('Клиентът не е намерен. Изберете клиент отново.')
    expect(heading()).toHaveTextContent('Клиент')
    expect(screen.queryByText(/Избран клиент/)).toBeNull()
  })

  it.each([
    ['BUSINESS_NOT_ACTIVE', 409, 'Бизнесът не е активен. Часове могат да се създават само в активен бизнес.'],
    ['BUSINESS_SUSPENDED', 409, 'Спрян бизнес може само да преглежда данните си.'],
    ['ACCESS_DENIED', 403, 'Нямате достъп до тази операция.'],
  ])('%s blocks the confirmation and points back to the calendar', async (code, status, text) => {
    const handlers = await fail(status, code)
    expect(alertText()).toBe(text)
    expect(screen.getByRole('button', { name: 'Създай час' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Към графика' }))
    expect(handlers.onCancel).toHaveBeenCalledTimes(1)
    expect(backend.posts()).toHaveLength(1)
  })

  it('validation error: a generic message, the review stays', async () => {
    await fail(400, 'VALIDATION_ERROR')
    expect(alertText()).toBe('Проверете въведените данни.')
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
  })

  it('temporarily unavailable: says nothing was created and allows an explicit retry only', async () => {
    await fail(503, 'APPOINTMENT_TEMPORARILY_UNAVAILABLE')
    expect(alertText()).toBe('Часът не беше създаден. Опитайте отново след малко.')
    expect(backend.posts()).toHaveLength(1)
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30))
    })
    expect(backend.posts()).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Създай час' })).toBeEnabled()
  })

  it('outcome uncertain: never claims success, keeps the review, guides to the calendar and never repeats the POST', async () => {
    const handlers = await fail(503, 'APPOINTMENT_OUTCOME_UNCERTAIN')
    expect(alertText()).toContain('Не е ясно дали часът е създаден. Проверете графика')
    expect(alertText()).not.toMatch(/създаден\.$/)
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(document.querySelector('dl')).toHaveTextContent('Мария Тестова')
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30))
    })
    expect(backend.posts()).toHaveLength(1)
    expect(handlers.onCreated).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Към графика' }))
    expect(handlers.onCancel).toHaveBeenCalled()
  })

  it('a lost response (network failure) is the uncertain outcome, not "nothing was created"', async () => {
    backend.create = () => {
      throw new TypeError('Failed to fetch')
    }
    await toReview()
    confirm()
    expect(await screen.findByRole('alert')).toHaveTextContent('Не е ясно дали часът е създаден.')
    expect(backend.posts()).toHaveLength(1)
  })

  it('an unexpected failure never shows backend text and advises to check the calendar', async () => {
    await fail(500, 'INTERNAL_ERROR')
    expect(alertText()).toBe('Възникна неочаквана грешка. Проверете графика, преди да опитате отново.')
    expect(document.body.textContent).not.toContain('backend text that must never be shown')
  })

  it('an expired login is handed to the application', async () => {
    backend.create = () => {
      throw backend.problem(401, 'AUTH_REQUIRED', 'Влезте отново.')
    }
    const handlers = await toReview()
    confirm()
    await waitFor(() => expect(handlers.onAuthenticationRequired).toHaveBeenCalledWith('Влезте отново.'))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('a lifecycle failure while choosing blocks the form with the same wording', async () => {
    backend.options = () => {
      throw backend.problem(409, 'BUSINESS_SUSPENDED')
    }
    renderForm()
    await chooseCustomer()
    await chooseServices('Маникюр')
    expect(await screen.findByRole('alert')).toHaveTextContent('Спрян бизнес може само да преглежда данните си.')
    expect(screen.getByRole('button', { name: 'Напред' })).toBeDisabled()
  })
})

describe('an unresolved outcome freezes the form', () => {
  const unresolvedCases: [string, () => never][] = [
    ['APPOINTMENT_OUTCOME_UNCERTAIN', () => { throw new ApiError(503, 'APPOINTMENT_OUTCOME_UNCERTAIN', 'x') }],
    ['a network failure after the request was sent', () => { throw new TypeError('Failed to fetch') }],
    ['an unreadable success response', () => { throw new ApiError(201, 'REQUEST_FAILED', 'x') }],
    ['an unexpected server failure', () => { throw new ApiError(500, 'INTERNAL_ERROR', 'x') }],
    ['an unknown gateway failure', () => { throw new ApiError(502, 'REQUEST_FAILED', 'x') }],
  ]

  it.each(unresolvedCases)('%s: one POST, the submitted review stays, and nothing can send or change it', async (_name, boom) => {
    backend.create = boom as never
    const handlers = await toReview()
    const confirmButton = screen.getByRole('button', { name: 'Създай час' })
    // The click handler as rendered before the failure: a stale callback that some late event could still call.
    const reactProps = Object.keys(confirmButton).find((key) => key.startsWith('__reactProps'))!
    const staleClick = (confirmButton as unknown as Record<string, { onClick: () => void }>)[reactProps]!.onClick
    fireEvent.click(confirmButton)
    await screen.findByRole('alert')

    // The exact controls: the two ways to the calendar, and nothing else.
    expect(screen.getAllByRole('button').map((button) => button.textContent)).toEqual([
      'Обратно към графика',
      'Към графика',
    ])
    expect(screen.queryByRole('button', { name: 'Създай час' })).toBeNull()
    expect(screen.queryByRole('button', { name: /Промени/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Назад' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Напред' })).toBeNull()
    expect(screen.queryByRole('radio')).toBeNull()
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.queryByRole('textbox')).toBeNull()
    // The submitted facts stay visible, and the message claims neither success nor failure.
    const review = document.querySelector('dl')!
    expect(review).toHaveTextContent('Мария Тестова')
    expect(review).toHaveTextContent('Дамско подстригване')
    expect(review).toHaveTextContent('Мила Работникова')
    expect(alertText()).not.toMatch(/не беше създаден|е създаден\.$|успешно/)
    expect(alertText()).toContain('Проверете графика')

    // A stale reference to the old button, further clicks, rerenders and time change nothing.
    act(() => {
      confirmButton.click()
      confirmButton.click()
      staleClick()
      staleClick()
    })
    handlers.view.rerender(<></>)
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30))
    })
    expect(backend.posts()).toHaveLength(1)
    expect(handlers.onCreated).not.toHaveBeenCalled()
  })

  it('both ways out lead to the calendar and neither is an ordinary unsaved-changes prompt', async () => {
    backend.create = () => {
      throw backend.problem(503, 'APPOINTMENT_OUTCOME_UNCERTAIN')
    }
    const handlers = await toReview()
    confirm()
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: 'Към графика' }))
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    expect(handlers.onCancel).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('a known rollback keeps the form editable and an explicit retry is the only way to a second POST', async () => {
    let calls = 0
    backend.create = () => {
      calls += 1
      if (calls === 1) throw backend.problem(503, 'APPOINTMENT_TEMPORARILY_UNAVAILABLE')
      return createdResponse()
    }
    const handlers = await toReview()
    confirm()
    await screen.findByRole('alert')
    expect(screen.getByRole('button', { name: 'Назад' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Промени бележката' })).toBeEnabled()
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30))
    })
    expect(backend.posts()).toHaveLength(1)
    confirm()
    await waitFor(() => expect(handlers.onCreated).toHaveBeenCalledTimes(1))
    expect(backend.posts()).toHaveLength(2)
  })

  it('a deterministic rejection and a success keep their behavior', async () => {
    backend.create = () => {
      throw backend.problem(400, 'VALIDATION_ERROR')
    }
    await toReview()
    confirm()
    await screen.findByRole('alert')
    expect(screen.getByRole('button', { name: 'Създай час' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Назад' })).toBeEnabled()
  })
})

describe('validation and focus', () => {
  it('moves focus to the step heading when the step changes', async () => {
    renderForm()
    await chooseCustomer()
    await waitFor(() => expect(heading()).toHaveFocus())
  })

  it('requires the date before the start and focuses the date control', async () => {
    renderForm()
    await chooseCustomer()
    await chooseServices('Маникюр')
    await chooseStaff()
    await screen.findByLabelText('Дата')
    next()
    expect(screen.getByText('Изберете дата.')).toBeInTheDocument()
    expect(screen.getByLabelText('Дата')).toHaveFocus()
    expect(screen.getByLabelText('Дата')).toHaveAttribute('aria-invalid', 'true')
  })

  it('names every control: the groups, the date, the note and the review actions', async () => {
    renderForm()
    expect(await screen.findByRole('group', { name: 'Клиенти' })).toBeInTheDocument()
    await chooseCustomer()
    expect(await screen.findByRole('group', { name: 'Услуги' })).toBeInTheDocument()
    await chooseServices('Маникюр')
    expect(await screen.findByRole('group', { name: 'Избор на служител' })).toBeInTheDocument()
    await chooseStaff()
    expect(await screen.findByLabelText('Дата')).toBeInTheDocument()
  })
})

describe('privacy', () => {
  it('keeps every private value out of the URL, history state, title, storage, cookies and console', async () => {
    const log = vi.spyOn(console, 'log')
    const warn = vi.spyOn(console, 'warn')
    const errorSpy = vi.spyOn(console, 'error')
    const title = document.title
    const handlers = await toNote()
    fireEvent.change(noteField(), { target: { value: 'ЛИЧНА-БЕЛЕЖКА-4471' } })
    next()
    await screen.findByRole('button', { name: 'Създай час' })
    confirm()
    await waitFor(() => expect(handlers.onCreated).toHaveBeenCalled())

    const haystack = [
      window.location.href,
      JSON.stringify(history.state),
      document.title,
      JSON.stringify({ ...window.localStorage }),
      JSON.stringify({ ...window.sessionStorage }),
      document.cookie,
    ].join('\n')
    expect(haystack).not.toMatch(/Мария|Тестова|maria|ЛИЧНА|4471|\+359|Подстригване/)
    expect(document.title).toBe(title)
    expect(log).not.toHaveBeenCalled()
    expect(warn).not.toHaveBeenCalled()
    expect(errorSpy.mock.calls.filter((call) => !String(call[0]).includes('act('))).toEqual([])
    // Only the POST carries the note; no read request has any private value in its URL.
    for (const call of backend.calls.filter((c) => c.method === 'GET')) {
      expect(decodeURIComponent(call.path)).not.toMatch(/Мария|ЛИЧНА|maria|\+359/)
    }
    expect(JSON.stringify(backend.posts()[0]!.body)).toContain('ЛИЧНА-БЕЛЕЖКА-4471')
  })

  it('offers no control that changes an existing Appointment', async () => {
    await toReview()
    expect(screen.queryByRole('button', { name: /Отмени час|Пренасрочи|Смени служител/ })).toBeNull()
    expect(screen.getByText(/не могат да се променят/)).toBeInTheDocument()
  })

  it('uses only the three documented endpoints plus the Customer and Service reads', async () => {
    await toReview()
    confirm()
    await waitFor(() => expect(backend.posts()).toHaveLength(1))
    const paths = new Set(backend.calls.map((call) => new URL(call.path, 'http://x').pathname))
    expect([...paths].sort()).toEqual([
      '/api/business/appointment-options',
      '/api/business/appointment-options/availability',
      '/api/business/appointments',
      '/api/business/customers',
      '/api/business/services',
    ])
  })
})

void STAFF_A
void STAFF_B
