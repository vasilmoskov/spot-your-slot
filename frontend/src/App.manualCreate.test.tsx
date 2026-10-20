import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App, AuthenticatedApplication } from './App'
import { request, type Session } from './identity/api'
import { UnsavedChangesGuardProvider } from './ui/UnsavedChangesGuard'
import { useFeedback } from './ui/useFeedback'
import { APPOINTMENT_ONE, calendarResponse, detailResponse } from './business/calendar/testFixtures'
import { CREATED_ID, FakeBackend, STAFF_A, createdResponse } from './business/appointments/testFixtures'

// Only the network helper is replaced: routing, the shell, the guard, the feature API modules and the
// session handling all run for real.
vi.mock('./identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)

const sessionOf = (status: string, role: 'BUSINESS_OWNER' | 'MANAGER' | 'STAFF' = 'BUSINESS_OWNER'): Session => ({
  email: 'ivan@example.invalid',
  displayName: 'Иван Собственик',
  platformAdmin: false,
  businesses: [
    { id: 'a', displayName: 'Студио А', role, status },
    { id: 'b', displayName: 'Студио Б', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
  ],
  activeBusinessId: 'a',
})

let session: Session
let backend: FakeBackend

beforeEach(() => {
  session = sessionOf('ACTIVE')
  backend = new FakeBackend()
  mockedRequest.mockReset()
  mockedRequest.mockImplementation(async (path: string, options?: RequestInit) => {
    if (path === '/api/auth/session') return session
    if (path.startsWith('/api/business/calendar')) return calendarResponse()
    if (path === `/api/business/appointments/${CREATED_ID}`) return detailResponse({ id: CREATED_ID })
    if (path.startsWith(`/api/business/appointments/${APPOINTMENT_ONE}`)) return detailResponse()
    if (path.startsWith('/api/business/staff-members')) {
      return { staffMembers: [], page: 0, size: 50, totalElements: 0 }
    }
    if (path.startsWith('/api/business/')) return backend.handle(path, options)
    throw new Error(`unexpected request ${path}`)
  })
  history.replaceState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

const CALENDAR = '#/business/calendar?view=week&date=2026-11-10'

async function openForm(hash = CALENDAR) {
  history.replaceState({}, '', `/${hash}`)
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Добави нов час' }))
  await screen.findByRole('heading', { level: 1, name: 'Нов час' })
}

async function walkToReview() {
  fireEvent.click(await screen.findByRole('radio', { name: /Мария/ }))
  fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
  fireEvent.click(await screen.findByRole('checkbox', { name: /Дамско подстригване/ }))
  fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
  fireEvent.click(await screen.findByRole('radio', { name: /Мила/ }))
  fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
  fireEvent.change(await screen.findByLabelText('Дата'), { target: { value: '2026-11-10' } })
  fireEvent.click(within(await screen.findByRole('group', { name: 'Свободни часове' })).getAllByRole('radio')[0]!)
  fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
  fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
  await screen.findByRole('button', { name: 'Създай час' })
}

describe('the entry', () => {
  it('shows Нов час to the owner of an ACTIVE Business and opens the form with the calendar state in the route', async () => {
    history.replaceState({}, '', `/${CALENDAR}&staff=${STAFF_A}`)
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Добави нов час' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Нов час' })).toBeInTheDocument()
    expect(window.location.hash).toBe(`#/business/appointments/new?view=week&date=2026-11-10&staff=${STAFF_A}`)
    expect(screen.getByRole('link', { name: 'График' })).toHaveAttribute('aria-current', 'page')
  })

  it('places «Добави нов час» under the page title, left-aligned in the page actions, and not in the calendar toolbar', async () => {
    history.replaceState({}, '', `/${CALENDAR}`)
    render(<App />)
    const button = await screen.findByRole('button', { name: 'Добави нов час' })
    expect(button.closest('.business-page-actions')).not.toBeNull()
    expect(button.closest('.platform-page-header')).toBeNull()
    expect(button.closest('.calendar-toolbar')).toBeNull()
  })

  it('a click on the day grid creates nothing and opens nothing', async () => {
    history.replaceState({}, '', '/#/business/calendar?view=day&date=2026-10-14')
    render(<App />)
    await screen.findByRole('link', { name: '10:00–10:45' })
    const timeline = document.querySelector('.calendar-timeline') as HTMLElement
    fireEvent.click(timeline, { clientY: 300 })
    expect(window.location.hash).toBe('#/business/calendar?view=day&date=2026-10-14')
    expect(backend.posts()).toHaveLength(0)
  })

  it.each(['DRAFT', 'SUSPENDED'])('%s: no control that creates exists, enabled or disabled', async (status) => {
    session = sessionOf(status)
    history.replaceState({}, '', `/${CALENDAR}`)
    render(<App />)
    await screen.findByRole('link', { name: '10:00–10:45' })
    expect(screen.queryByRole('button', { name: /Нов час/ })).toBeNull()
    expect(screen.queryByText('Нов час')).toBeNull()
    expect(document.querySelector('.shell-banner')).not.toBeNull()
  })

  it('shows no create control on the Appointment detail', async () => {
    history.replaceState({}, '', `/#/business/appointments/${APPOINTMENT_ONE}`)
    render(<App />)
    await screen.findByRole('region', { name: 'Детайли' })
    expect(screen.queryByRole('button', { name: 'Добави нов час' })).toBeNull()
  })

  it.each(['DRAFT', 'SUSPENDED'])('%s: a direct visit to the form returns to the calendar and reads nothing for it', async (status) => {
    session = sessionOf(status)
    history.replaceState({}, '', '/#/business/appointments/new?view=day&date=2026-11-10')
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'График' })).toBeInTheDocument()
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=day&date=2026-11-10'))
    expect(backend.calls).toHaveLength(0)
    expect(screen.queryByRole('heading', { name: 'Клиент' })).toBeNull()
  })

  it.each(['MANAGER', 'STAFF'] as const)('does not offer the form to a %s Membership', async (role) => {
    session = { ...sessionOf('ACTIVE', role), businesses: [{ id: 'a', displayName: 'Студио А', role, status: 'ACTIVE' }] }
    history.replaceState({}, '', '/#/business/appointments/new')
    render(<App />)
    await waitFor(() => expect(window.location.hash).toBe('#/profile'))
    expect(screen.queryByRole('button', { name: 'Добави нов час' })).toBeNull()
    expect(backend.calls).toHaveLength(0)
  })

  it('does not render the form before sign-in', async () => {
    mockedRequest.mockRejectedValue(new Error('no session'))
    history.replaceState({}, '', '/#/business/appointments/new')
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Вход' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Клиент' })).toBeNull()
  })

  it('a platform administrator without an owner Membership has no path to the form', async () => {
    session = { email: 'a@example.invalid', displayName: 'Админ', platformAdmin: true, businesses: [] }
    history.replaceState({}, '', '/#/business/appointments/new')
    render(<App />)
    await waitFor(() => expect(window.location.hash).toBe('#/profile'))
    expect(backend.calls).toHaveLength(0)
  })
})

describe('leaving, returning and completing', () => {
  it('Cancel returns to the exact calendar state the owner came from', async () => {
    await openForm('#/business/calendar?view=agenda&date=2026-11-10&page=2&size=25')
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=agenda&date=2026-11-10&page=2&size=25'))
    expect(await screen.findByRole('button', { name: 'Добави нов час' })).toBeInTheDocument()
  })

  it('browser Back returns to the calendar and Forward to a fresh form', async () => {
    await openForm()
    act(() => history.back())
    await waitFor(() => expect(window.location.hash).toBe(CALENDAR))
    await screen.findByRole('button', { name: 'Добави нов час' })
    act(() => history.forward())
    expect(await screen.findByRole('heading', { level: 1, name: 'Нов час' })).toBeInTheDocument()
    expect(await screen.findByRole('radio', { name: /Мария/ })).not.toBeChecked()
  })

  it('asks before discarding a started form, and Остани keeps it', async () => {
    await openForm()
    fireEvent.click(await screen.findByRole('radio', { name: /Мария/ }))
    fireEvent.click(screen.getByRole('link', { name: 'Услуги' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Несъздаден час' })
    expect(dialog).toHaveTextContent('Часът още не е създаден.')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Остани' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(screen.getByRole('radio', { name: /Мария/ })).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Напусни' }))
    await waitFor(() => expect(window.location.hash).toBe(CALENDAR))
  })

  it('guards browser Back of a started form', async () => {
    await openForm()
    fireEvent.click(await screen.findByRole('radio', { name: /Мария/ }))
    act(() => history.back())
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(window.location.hash).toMatch(/^#\/business\/appointments\/new/)
  })

  it('does not ask when nothing was entered', async () => {
    await openForm()
    await screen.findByRole('radio', { name: /Мария/ })
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    await waitFor(() => expect(window.location.hash).toBe(CALENDAR))
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  describe('the inline Нов клиент dialog', () => {
    const openDialog = async () => {
      await openForm()
      fireEvent.click(await screen.findByRole('button', { name: 'Нов клиент' }))
      return screen.findByRole('dialog', { name: 'Нов клиент' })
    }
    const fill = (dialog: HTMLElement, name: string, phone: string) => {
      fireEvent.change(within(dialog).getByLabelText('Име'), { target: { value: name } })
      fireEvent.change(within(dialog).getByLabelText('Телефон'), { target: { value: phone } })
    }

    it('no longer offers the navigate-away Добави клиент', async () => {
      await openForm()
      await screen.findByRole('radio', { name: /Мария/ })
      expect(screen.queryByRole('button', { name: 'Добави клиент' })).toBeNull()
      expect(screen.getByRole('button', { name: 'Нов клиент' })).toBeInTheDocument()
    })

    it('lists Добави before the red Отказ and closes with Отказ without touching the form or the route', async () => {
      const dialog = await openDialog()
      expect(within(dialog).getAllByRole('button').map((button) => button.textContent)).toEqual(['Добави', 'Отказ'])
      fireEvent.click(within(dialog).getByRole('button', { name: 'Отказ' }))
      expect(screen.queryByRole('dialog')).toBeNull()
      expect(window.location.hash).toMatch(/^#\/business\/appointments\/new/)
      expect(screen.getByRole('heading', { name: 'Клиент' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Нов клиент' })).toHaveFocus()
      expect(backend.calls.some((call) => call.method === 'POST' && call.path === '/api/business/customers')).toBe(false)
    })

    it('keeps the Appointment draft and the previous Customer selection when the dialog is cancelled', async () => {
      await openForm()
      fireEvent.click(await screen.findByRole('radio', { name: /Мария/ }))
      fireEvent.click(screen.getByRole('button', { name: 'Нов клиент' }))
      const dialog = await screen.findByRole('dialog', { name: 'Нов клиент' })
      fireEvent.change(within(dialog).getByLabelText('Име'), { target: { value: 'Недовършен' } })
      fireEvent.click(within(dialog).getByRole('button', { name: 'Отказ' }))
      expect(screen.queryByRole('dialog')).toBeNull()
      expect(screen.getByRole('radio', { name: /Мария/ })).toBeChecked()
      // The draft is still protected by its own guard: leaving asks first.
      fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
      expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
    })

    it('validates with the Customer rules and sends nothing for an invalid form', async () => {
      const dialog = await openDialog()
      fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
      expect(within(dialog).getAllByText(/./, { selector: '.field-error' }).length).toBeGreaterThan(0)
      expect(backend.calls.some((call) => call.method === 'POST' && call.path === '/api/business/customers')).toBe(false)
    })

    it('creates the Customer, selects it, stays on the Customer step and keeps nothing in the URL or storage', async () => {
      const dialog = await openDialog()
      fill(dialog, 'Нина Новакова', '0888123456')
      fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
      expect(screen.getByRole('heading', { name: 'Клиент' })).toBeInTheDocument()
      expect(await screen.findByRole('radio', { name: /Нина Новакова/ })).toBeChecked()
      expect(screen.queryByText(/Избран клиент/)).toBeNull()
      expect(window.location.hash).not.toMatch(/Нина|0888/)
      expect(JSON.stringify({ ...window.localStorage })).not.toMatch(/Нина|0888/)
      expect(backend.calls.filter((call) => call.method === 'POST' && call.path === '/api/business/customers')).toHaveLength(1)
      // The Appointment draft continues with the new Customer.
      fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
      expect(await screen.findByRole('heading', { name: 'Избор на услуги' })).toBeInTheDocument()
    })

    it('shows a definite error inside the dialog and keeps what was typed', async () => {
      backend.createCustomer = () => {
        throw backend.problem(409, 'CUSTOMER_CONTACT_CONFLICT')
      }
      const dialog = await openDialog()
      fill(dialog, 'Нина Новакова', '0888123456')
      fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
      expect(await within(dialog).findByRole('alert')).toBeInTheDocument()
      expect(within(dialog).getByLabelText('Име')).toHaveValue('Нина Новакова')
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    it('does not retry or resubmit after an uncertain result', async () => {
      backend.createCustomer = () => {
        throw new Error('connection reset')
      }
      const dialog = await openDialog()
      fill(dialog, 'Нина Новакова', '0888123456')
      fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
      expect(await within(dialog).findByRole('alert')).toHaveTextContent('Не е ясно дали клиентът е добавен')
      expect(within(dialog).getByRole('button', { name: 'Добави' })).toBeDisabled()
      fireEvent.submit(within(dialog).getByLabelText('Име').closest('form')!)
      expect(backend.calls.filter((call) => call.method === 'POST' && call.path === '/api/business/customers')).toHaveLength(1)
    })

    it('does not close on Escape while something is typed', async () => {
      const dialog = await openDialog()
      fill(dialog, 'Нина', '')
      fireEvent.keyDown(dialog, { key: 'Escape' })
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    it('keeps one selected card when the search hides the chosen Customer', async () => {
      await openForm()
      fireEvent.click(await screen.findByRole('radio', { name: /Мария/ }))
      fireEvent.change(screen.getByLabelText('Търсене на клиент'), { target: { value: 'zzz' } })
      await waitFor(() => expect(screen.getAllByRole('radio', { name: /Мария/ })).toHaveLength(1))
      expect(screen.getAllByRole('radio', { name: /Мария/ })[0]).toBeChecked()
      expect(screen.queryByText(/Избран клиент/)).toBeNull()
    })
  })

  it('success replaces history with the created Appointment, keeps the calendar behind it and shows only read data', async () => {
    await openForm()
    await walkToReview()
    const before = history.length
    fireEvent.click(screen.getByRole('button', { name: 'Създай час' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Час' })).toBeInTheDocument()
    expect(window.location.hash).toBe(`#/business/appointments/${CREATED_ID}?view=week&date=2026-11-10`)
    expect(await screen.findByText('Часът е създаден.')).toBeInTheDocument()
    // Replaced, not pushed: no resubmittable form stays in history.
    expect(history.length).toBe(before)
    await screen.findByRole('region', { name: 'Детайли' })
    expect(mockedRequest.mock.calls.some((call) => call[0] === `/api/business/appointments/${CREATED_ID}`)).toBe(true)
    expect(screen.queryByRole('alertdialog')).toBeNull()
    act(() => history.back())
    await waitFor(() => expect(window.location.hash).toBe(CALENDAR))
    expect(screen.queryByRole('heading', { name: 'Преглед и потвърждение' })).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })

  it('keeps the form and the single POST when the server refuses with a lifecycle result', async () => {
    backend.create = () => {
      throw backend.problem(409, 'BUSINESS_SUSPENDED')
    }
    await openForm()
    await walkToReview()
    fireEvent.click(screen.getByRole('button', { name: 'Създай час' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Спрян бизнес може само да преглежда данните си.')
    expect(screen.getByRole('button', { name: 'Създай час' })).toBeDisabled()
    expect(window.location.hash).toMatch(/^#\/business\/appointments\/new/)
  })
})

describe('an unresolved outcome', () => {
  async function uncertain(hash = '#/business/calendar?view=agenda&date=2026-11-10&page=2&size=25') {
    backend.create = () => {
      throw backend.problem(503, 'APPOINTMENT_OUTCOME_UNCERTAIN')
    }
    await openForm(hash)
    await walkToReview()
    fireEvent.click(screen.getByRole('button', { name: 'Създай час' }))
    await screen.findByRole('alert')
  }

  it('the recovery returns to the originating calendar state without an ordinary unsaved-changes prompt', async () => {
    await uncertain()
    fireEvent.click(screen.getByRole('button', { name: 'Към графика' }))
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=agenda&date=2026-11-10&page=2&size=25'))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })

  it('leaving elsewhere uses the unresolved warning, and staying keeps the frozen form', async () => {
    await uncertain()
    fireEvent.click(screen.getByRole('link', { name: 'Услуги' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Неясен резултат' })
    expect(dialog).toHaveTextContent('Проверете графика, преди да създавате часа отново.')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Остани' }))
    expect(screen.queryByRole('button', { name: 'Създай час' })).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })

  it('browser Back is warned about, and Forward never reopens the submitted form or a POST', async () => {
    await uncertain()
    act(() => history.back())
    const dialog = await screen.findByRole('alertdialog', { name: 'Неясен резултат' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Напусни' }))
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=agenda&date=2026-11-10&page=2&size=25'))
    act(() => history.forward())
    expect(await screen.findByRole('heading', { level: 2, name: 'Клиент' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Създай час' })).toBeNull()
    expect(screen.queryByText('Мария Тестова', { selector: 'dd' })).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })
})

describe('Business context', () => {
  function Harness({ value }: { value: Session }) {
    const [busy, setBusy] = useState(false)
    const { feedback, setFeedback, beginFeedback } = useFeedback('harness')
    return (
      <UnsavedChangesGuardProvider>
        <AuthenticatedApplication
          session={value}
          setSession={() => undefined}
          busy={busy}
          setBusy={setBusy}
          feedback={feedback}
          setFeedback={setFeedback}
          beginFeedback={beginFeedback}
        />
      </UnsavedChangesGuardProvider>
    )
  }

  it('a lost Business context leaves the form for the Business selection', async () => {
    mockedRequest.mockImplementation(async (path: string) => {
      if (path === '/api/auth/session') {
        const { activeBusinessId: _gone, ...rest } = session
        void _gone
        session = rest
        return session
      }
      if (path.startsWith('/api/business/')) {
        throw new (await import('./identity/api')).ApiError(403, 'ACTIVE_BUSINESS_REQUIRED', 'x')
      }
      throw new Error(path)
    })
    history.replaceState({}, '', '/#/business/appointments/new?view=day&date=2026-11-10')
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Бизнеси' })).toBeInTheDocument()
    await waitFor(() => expect(window.location.hash).toBe('#/businesses'))
  })

  it('a switch of Business discards the form: nothing of it reaches the other Business', async () => {
    history.replaceState({}, '', '/#/business/appointments/new?view=day&date=2026-11-10')
    const { rerender } = render(<Harness value={session} />)
    fireEvent.click(await screen.findByRole('radio', { name: /Мария/ }))
    rerender(<Harness value={{ ...session, activeBusinessId: 'b' }} />)
    await waitFor(() => expect(window.location.hash).toBe('#/business/calendar?view=day'))
    expect(screen.queryByRole('radio', { name: /Мария/ })).toBeNull()
    expect(screen.queryByText(/Избран клиент/)).toBeNull()
  })
})

describe('privacy', () => {
  it('keeps every private value out of the URL, history state, title, storage and cookies across the whole flow', async () => {
    const title = document.title
    await openForm()
    await walkToReview()
    fireEvent.click(screen.getByRole('button', { name: 'Създай час' }))
    await screen.findByRole('heading', { level: 1, name: 'Час' })
    const haystack = [
      window.location.href,
      JSON.stringify(history.state),
      document.title,
      JSON.stringify({ ...window.localStorage }),
      JSON.stringify({ ...window.sessionStorage }),
      document.cookie,
    ].join('\n')
    expect(haystack).not.toMatch(/Мария|Тестова|maria|\+359|Подстригване/)
    expect(document.title).toBe(title)
    expect(createdResponse().id).toBe(CREATED_ID)
  })
})
