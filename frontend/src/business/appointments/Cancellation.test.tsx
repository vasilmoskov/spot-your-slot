import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from '../../identity/api'
import { APPOINTMENT_ONE, NOW, PAST_NOW, cancelled, confirmed, type FakeLifecycleBackend } from './lifecycleTestFixtures'
import {
  confirmCancel,
  deferred,
  dialog,
  installBackend,
  keepAppointment,
  navigate,
  openCancel,
  ready,
  renderDetail,
  rereadButton,
} from './lifecycleTestHarness'

vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
let backend: FakeLifecycleBackend

beforeEach(() => {
  backend = installBackend(mockedRequest)
})

afterEach(() => {
  cleanup()
})

const CANCEL_PATH = `/api/business/appointments/${APPOINTMENT_ONE}/cancel`
const cancelButton = () => screen.queryByRole('button', { name: 'Отмени часа' })

describe('Cancellation: where the control exists', () => {
  it('is offered to the owner of an ACTIVE Business for a CONFIRMED Appointment that has not started', async () => {
    renderDetail()
    await ready()
    expect(cancelButton()).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Отмяна на часа' })).toBeInTheDocument()
  })

  it.each([
    ['DRAFT', { businessStatus: 'DRAFT' as const }],
    ['SUSPENDED', { businessStatus: 'SUSPENDED' as const }],
  ])('is absent, not disabled, for a %s Business', async (_name, overrides) => {
    backend.current = confirmed(overrides)
    renderDetail()
    await ready()
    expect(cancelButton()).toBeNull()
    expect(screen.queryByRole('region', { name: 'Отмяна на часа' })).toBeNull()
    expect(screen.queryByText(/Отмяна на часа/)).toBeNull()
  })

  it('is absent for an already cancelled Appointment', async () => {
    backend.current = cancelled()
    renderDetail()
    await ready()
    expect(cancelButton()).toBeNull()
    expect(screen.queryByRole('region', { name: 'Отмяна на часа' })).toBeNull()
  })

  it('is absent for a started Appointment and for one starting exactly now', async () => {
    renderDetail({ now: PAST_NOW })
    await ready()
    expect(cancelButton()).toBeNull()
    cleanup()
    // 2026-10-14T10:00+03:00 is 07:00Z: an Appointment starting exactly at the instant has started.
    renderDetail({ now: new Date('2026-10-14T07:00:00Z') })
    await ready()
    expect(cancelButton()).toBeNull()
    cleanup()
    renderDetail({ now: new Date('2026-10-14T06:59:59Z') })
    await ready()
    expect(cancelButton()).toBeInTheDocument()
  })

  it('is absent for a viewer who may not change anything', async () => {
    renderDetail({ canChange: false })
    await ready()
    expect(cancelButton()).toBeNull()
    expect(screen.queryByRole('region', { name: 'Отмяна на часа' })).toBeNull()
  })
})

describe('Cancellation: the confirmation dialog', () => {
  it('asks first, sends nothing, and puts the safe action first and focused', async () => {
    renderDetail()
    await ready()
    openCancel()
    const panel = dialog()
    expect(panel).toHaveAttribute('aria-modal', 'true')
    expect(within(panel).getByRole('heading', { name: 'Отмяна на час' })).toBeInTheDocument()
    // The warning once, each sentence on its own line, and nothing about creating a new Appointment.
    const warning = panel.querySelector('.cancel-dialog-warning')!
    expect([...warning.querySelectorAll('span')].map((line) => line.textContent)).toEqual([
      'Часът ще бъде отменен.',
      'Това действие не може да бъде върнато.',
    ])
    expect(panel.textContent).not.toMatch(/създава отделно|ще се освободи/)
    // The Appointment as separate labelled lines, not one «·» heading.
    const context = [...panel.querySelectorAll('.cancel-dialog-context div')].map((row) => row.textContent)
    expect(context).toEqual([
      'Клиент:Мария Тестова',
      expect.stringMatching(/^Дата:/),
      expect.stringMatching(/^Час:\d\d:\d\d–\d\d:\d\d$/),
      expect.stringMatching(/^Член на екипа:/),
    ])
    expect(panel.textContent).not.toContain('·')
    const buttons = within(panel).getAllByRole('button')
    // Affirmative first (solid red), «Отказ» rightmost as the neutral outlined secondary action: the documented
    // exception to the red «Отказ» rule for this dialog. The safe action still receives the initial focus.
    expect(buttons.map((button) => button.textContent)).toEqual(['Отмени часа', 'Отказ'])
    expect(buttons[1]).toHaveFocus()
    expect(buttons[0]).not.toHaveFocus()
    expect(buttons[0]).toHaveClass('button--destructive')
    expect(buttons[1]).toHaveClass('button--neutral')
    expect(buttons[1]).not.toHaveClass('button--destructive')
    expect(buttons[1]).not.toHaveClass('button--outline')
    // No reason, actor, or other input exists.
    expect(within(panel).queryByRole('textbox')).toBeNull()
    expect(backend.posts()).toHaveLength(0)
  })

  it('names the dialog and its description for assistive technology', async () => {
    renderDetail()
    await ready()
    openCancel()
    const panel = dialog()
    expect(panel).toHaveAccessibleName('Отмяна на час')
    expect(panel).toHaveAccessibleDescription(/Мария Тестова.*Това действие не може да бъде върнато/s)
  })

  it('closes with the safe action and with Escape, restores focus, and sends nothing', async () => {
    renderDetail()
    await ready()
    openCancel()
    keepAppointment()
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(cancelButton()).toHaveFocus()
    openCancel()
    fireEvent.keyDown(dialog(), { key: 'Escape' })
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(cancelButton()).toHaveFocus()
    expect(backend.posts()).toHaveLength(0)
  })

  it('keeps keyboard focus inside the open dialog', async () => {
    renderDetail()
    await ready()
    openCancel()
    const [safe, destructive] = within(dialog()).getAllByRole('button')
    destructive!.focus()
    fireEvent.keyDown(destructive!, { key: 'Tab' })
    expect(safe).toHaveFocus()
    fireEvent.keyDown(safe!, { key: 'Tab', shiftKey: true })
    expect(destructive).toHaveFocus()
  })

  it('keeps the page behind the dialog inert', async () => {
    const { view } = renderDetail()
    await ready()
    openCancel()
    expect(view.container.querySelector('.appointment-detail')).toHaveAttribute('inert')
    keepAppointment()
    expect(view.container.querySelector('.appointment-detail')).not.toHaveAttribute('inert')
  })

  it('ignores Escape and the safe action while the request is in flight and keeps the dialog open', async () => {
    const answer = deferred<ReturnType<FakeLifecycleBackend['result']>>()
    backend.cancel = () => answer.promise
    renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    const panel = dialog()
    expect(within(panel).getByRole('button', { name: 'Отказ' })).toBeDisabled()
    expect(within(panel).getByRole('button', { name: 'Отмени часа' })).toBeDisabled()
    expect(within(panel).getByRole('status')).toHaveTextContent('Отмяна на часа…')
    fireEvent.keyDown(panel, { key: 'Escape' })
    expect(dialog()).toBeInTheDocument()
    expect(panel).toHaveFocus()
    await act(async () => {
      answer.reject(new TypeError('Failed to fetch'))
    })
    await screen.findByRole('alert')
  })
})

describe('Cancellation: the request and the authoritative re-read', () => {
  it('sends exactly one POST with exactly the expected version and nothing else', async () => {
    renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    await screen.findByText('Отменен')
    const posts = backend.posts()
    expect(posts).toHaveLength(1)
    expect(posts[0]).toEqual({ path: CANCEL_PATH, method: 'POST', body: { expectedVersion: 7 } })
    expect(Object.keys(posts[0]!.body as object)).toEqual(['expectedVersion'])
  })

  it('sends one request for a double click and for repeated key presses', async () => {
    const answer = deferred<ReturnType<FakeLifecycleBackend['result']>>()
    backend.cancel = () => answer.promise
    renderDetail()
    await ready()
    openCancel()
    const confirmButton = within(dialog()).getByRole('button', { name: 'Отмени часа' })
    fireEvent.click(confirmButton)
    fireEvent.click(confirmButton)
    fireEvent.doubleClick(confirmButton)
    fireEvent.keyDown(confirmButton, { key: 'Enter' })
    expect(backend.posts()).toHaveLength(1)
    await act(async () => {
      backend.current = cancelled()
      answer.resolve(backend.result())
    })
    await screen.findByText('Отменен')
    expect(backend.posts()).toHaveLength(1)
  })

  it('reads the Appointment again after the POST and shows the cancelled state only from that read', async () => {
    const reread = deferred<ReturnType<typeof cancelled>>()
    renderDetail()
    await ready()
    openCancel()
    backend.read = () => reread.promise
    confirmCancel()
    await waitFor(() => expect(backend.reads()).toHaveLength(2))
    // Optimistic success is forbidden: until the read answers, the page still shows what was read.
    expect(screen.getByText('Потвърден')).toBeInTheDocument()
    expect(screen.queryByText('Отменен')).toBeNull()
    expect(backend.calls.map((call) => call.method)).toEqual(['GET', 'POST', 'GET'])
    await act(async () => {
      reread.resolve(cancelled())
    })
    expect(await screen.findByText('Отменен')).toBeInTheDocument()
    expect(screen.queryByText('Потвърден')).toBeNull()
  })

  it('removes the control, closes the dialog, focuses the cancelled notice, and never shows who cancelled or the version', async () => {
    renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    const status = (await screen.findByText(/Този час е отменен/)).closest('[role="status"]') as HTMLElement
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(cancelButton()).toBeNull()
    expect(screen.queryByRole('region', { name: 'Отмяна на часа' })).toBeNull()
    await waitFor(() => expect(status).toHaveFocus())
    expect(document.body.textContent).not.toMatch(/BUSINESS|UNKNOWN|cancelled_by|cancelledBy|версия|version/i)
    expect(document.body.textContent).not.toMatch(/\b8\b/)
    // Exactly one cancellation notice: no duplicate lifecycle message.
    expect(screen.getAllByRole('status').filter((element) => /отменен/i.test(element.textContent ?? ''))).toHaveLength(1)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('treats a success whose re-read failed as a success without showing stale data, and offers the explicit re-read', async () => {
    renderDetail()
    await ready()
    openCancel()
    let reads = 0
    const realRead = backend.read
    backend.read = () => {
      reads += 1
      if (reads === 1) throw new TypeError('Failed to fetch')
      return realRead()
    }
    confirmCancel()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Часът е отменен, но актуалните данни не можаха да бъдат заредени.')
    expect(cancelButton()).toBeNull()
    expect(backend.posts()).toHaveLength(1)
    fireEvent.click(rereadButton())
    expect(await screen.findByText('Отменен')).toBeInTheDocument()
    expect(backend.posts()).toHaveLength(1)
  })

  it('does nothing after the screen was left while the request was pending', async () => {
    const answer = deferred<ReturnType<FakeLifecycleBackend['result']>>()
    backend.cancel = () => answer.promise
    const { view } = renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    view.unmount()
    const readsBefore = backend.reads().length
    await act(async () => {
      backend.current = cancelled()
      answer.resolve(backend.result())
    })
    expect(backend.reads()).toHaveLength(readsBefore)
    expect(backend.posts()).toHaveLength(1)
  })
})

type Case = {
  name: string
  error: () => unknown
  text: string
  // After the failure: the cancel control is still offered (an explicit retry is possible).
  retry: boolean
  rereadOffered?: boolean
}

const KNOWN: Case[] = [
  {
    name: 'validation',
    error: () => backend.problem(400, 'VALIDATION_ERROR'),
    text: 'Часът не беше отменен. Прочетете го отново и опитайте пак.',
    retry: true,
    rereadOffered: true,
  },
  {
    name: 'temporarily unavailable',
    error: () => backend.problem(503, 'APPOINTMENT_TEMPORARILY_UNAVAILABLE'),
    text: 'Часът не беше отменен. Той е непроменен. Опитайте отново след малко.',
    retry: true,
  },
  {
    name: 'concurrent update',
    error: () => backend.problem(409, 'APPOINTMENT_CONCURRENT_UPDATE'),
    text: 'Часът е променен междувременно. Прегледайте актуалните данни и опитайте отново, ако все още е нужно.',
    retry: true,
  },
]

const REFUSED: Case[] = [
  {
    name: 'access denied',
    error: () => backend.problem(403, 'ACCESS_DENIED'),
    text: 'Нямате достъп до тази операция.',
    retry: false,
  },
  {
    name: 'lost Business context',
    error: () => backend.problem(403, 'ACTIVE_BUSINESS_REQUIRED'),
    text: 'Нямате достъп до тази операция.',
    retry: false,
  },
  {
    name: 'Business not active',
    error: () => backend.problem(409, 'BUSINESS_NOT_ACTIVE'),
    text: 'Бизнесът не е активен. Часовете могат да се отменят само в активен бизнес.',
    retry: false,
  },
  {
    name: 'Business suspended',
    error: () => backend.problem(409, 'BUSINESS_SUSPENDED'),
    text: 'Спрян бизнес може само да преглежда данните си.',
    retry: false,
  },
  {
    name: 'state conflict',
    error: () => backend.problem(409, 'APPOINTMENT_STATE_CONFLICT'),
    text: 'Часът не може да бъде отменен, защото вече е отменен или е започнал.',
    retry: false,
  },
]

const UNRESOLVED: Case[] = [
  {
    name: 'outcome uncertain',
    error: () => backend.problem(503, 'APPOINTMENT_OUTCOME_UNCERTAIN'),
    text: 'Не е ясно дали часът е отменен. Прочетете часа отново, преди да опитате пак.',
    retry: false,
  },
  {
    name: 'internal error',
    error: () => backend.problem(500, 'INTERNAL_ERROR'),
    text: 'Възникна неочаквана грешка и не е ясно дали часът е отменен. Прочетете часа отново, преди да опитате пак.',
    retry: false,
  },
  {
    name: 'unknown code',
    error: () => backend.problem(409, 'SOMETHING_NEW'),
    text: 'Възникна неочаквана грешка и не е ясно дали часът е отменен. Прочетете часа отново, преди да опитате пак.',
    retry: false,
  },
  {
    name: 'gateway failure without a code',
    error: () => backend.problem(502, 'REQUEST_FAILED'),
    text: 'Възникна неочаквана грешка и не е ясно дали часът е отменен. Прочетете часа отново, преди да опитате пак.',
    retry: false,
  },
  {
    name: 'lost response',
    error: () => new TypeError('Failed to fetch'),
    text: 'Не е ясно дали часът е отменен. Прочетете часа отново, преди да опитате пак.',
    retry: false,
  },
  {
    name: 'unreadable success',
    error: () => new ApiError(200, 'REQUEST_FAILED', 'Заявката не може да бъде изпълнена.'),
    text: 'Не е ясно дали часът е отменен. Прочетете часа отново, преди да опитате пак.',
    retry: false,
  },
]

async function cancelWith(error: () => unknown) {
  backend.cancel = () => {
    throw error()
  }
  const view = renderDetail()
  await ready()
  openCancel()
  confirmCancel()
  return view
}

describe('Cancellation: every documented result stays distinct', () => {
  it.each([...KNOWN, ...REFUSED, ...UNRESOLVED])('$name shows only its own frontend-owned message', async (scenario) => {
    await cancelWith(scenario.error)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(scenario.text)
    expect(alert.textContent).toBe(scenario.text)
    expect(document.body.textContent).not.toContain('backend text that must never be shown')
    expect(screen.queryByRole('alertdialog')).toBeNull()
    // One alert, no duplicate lifecycle or error notice.
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(backend.posts()).toHaveLength(1)
    await waitFor(() => expect(alert).toHaveFocus())
  })

  it.each(KNOWN)('$name is a known rollback that never claims the cancellation and allows an explicit retry', async (scenario) => {
    await cancelWith(scenario.error)
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).not.toMatch(/Часът е отменен\b(?! междувременно)/)
    expect(screen.getByText('Потвърден')).toBeInTheDocument()
    expect(cancelButton()).toBeInTheDocument()
    expect(Boolean(screen.queryByRole('button', { name: 'Прочети часа отново' }))).toBe(Boolean(scenario.rereadOffered))
    // The retry is the owner's: it sends a second request, once.
    backend.cancel = () => {
      backend.current = cancelled({ version: backend.current.version + 1 })
      return backend.result()
    }
    openCancel()
    confirmCancel()
    await screen.findByText('Отменен')
    expect(backend.posts()).toHaveLength(2)
  })

  it.each(REFUSED)('$name removes the cancel control and every other mutation path', async (scenario) => {
    await cancelWith(scenario.error)
    await screen.findByRole('alert')
    await waitFor(() => expect(backend.reads().length).toBeGreaterThanOrEqual(2))
    expect(cancelButton()).toBeNull()
    if (scenario.name !== 'state conflict') {
      expect(screen.queryByRole('button', { name: /Редактирай|Добави бележка/ })).toBeNull()
      expect(screen.queryByRole('button', { name: 'Промени часа' })).toBeNull()
    }
    expect(backend.posts()).toHaveLength(1)
  })

  it.each(UNRESOLVED)('$name freezes the operation after exactly one request', async (scenario) => {
    await cancelWith(scenario.error)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(scenario.text)
    expect(cancelButton()).toBeNull()
    expect(screen.queryByRole('button', { name: 'Промени часа' })).toBeNull()
    expect(screen.queryByRole('button', { name: /Редактирай|Добави бележка/ })).toBeNull()
    expect(rereadButton()).toBeInTheDocument()
    // The message claims neither the success nor the failure.
    expect(alert.textContent).not.toMatch(/не беше отменен|е непроменен|Часът е отменен\./)
    // Nothing the owner can do sends another request.
    fireEvent.keyDown(document.body, { key: 'Enter' })
    fireEvent.click(rereadButton())
    await screen.findByRole('region', { name: 'Детайли' })
    expect(backend.posts()).toHaveLength(1)
  })

  it('an expired session hands over to the shared recovery without an own message', async () => {
    const { onAuthenticationRequired } = await cancelWith(() => backend.problem(401, 'AUTH_REQUIRED', 'Влезте отново.'))
    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledWith('Влезте отново.'))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })

  it('a missing Appointment replaces the page with the shared not-found state', async () => {
    await cancelWith(() => backend.problem(404, 'APPOINTMENT_NOT_FOUND'))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Часът не е намерен.')
    expect(screen.queryByRole('region', { name: 'Детайли' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Зареди отново' })).toBeNull()
  })
})

describe('Cancellation: recovery and reconciliation', () => {
  it('after a concurrent update re-reads the Appointment and the next request carries the new version', async () => {
    let attempts = 0
    backend.cancel = (body) => {
      attempts += 1
      if (attempts === 1) {
        // Somebody else changed the Appointment (version 9, a new internal note).
        backend.current = confirmed({ version: 9, internalNote: 'Нова бележка от колега' })
        throw backend.problem(409, 'APPOINTMENT_CONCURRENT_UPDATE')
      }
      expect(body).toEqual({ expectedVersion: 9 })
      backend.current = cancelled({ version: 10, internalNote: 'Нова бележка от колега' })
      return backend.result()
    }
    renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    await screen.findByRole('alert')
    expect(await screen.findByText('Нова бележка от колега')).toBeInTheDocument()
    openCancel()
    confirmCancel()
    await screen.findByText('Отменен')
    expect(backend.posts().map((call) => call.body)).toEqual([{ expectedVersion: 7 }, { expectedVersion: 9 }])
  })

  it('a concurrent update that follows a commit of this very cancellation shows the cancelled Appointment and no contradicting claim (Phase 11 regression)', async () => {
    backend.cancel = () => {
      backend.current = cancelled()
      throw backend.problem(409, 'APPOINTMENT_CONCURRENT_UPDATE')
    }
    renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    const alert = await screen.findByRole('alert')
    expect(await screen.findByText('Отменен')).toBeInTheDocument()
    expect(alert.textContent).not.toMatch(/не е отменен/)
    expect(cancelButton()).toBeNull()
  })

  it('after a state conflict the re-read cancelled Appointment is shown and no control remains', async () => {
    backend.cancel = () => {
      backend.current = cancelled()
      throw backend.problem(409, 'APPOINTMENT_STATE_CONFLICT')
    }
    renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    expect(await screen.findByText('Отменен')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Часът не може да бъде отменен, защото вече е отменен или е започнал.',
    )
    expect(cancelButton()).toBeNull()
  })

  it('removes the control after a state conflict even when the re-read still says confirmed', async () => {
    backend.cancel = () => {
      throw backend.problem(409, 'APPOINTMENT_STATE_CONFLICT')
    }
    renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    await screen.findByRole('alert')
    await waitFor(() => expect(backend.reads()).toHaveLength(2))
    expect(cancelButton()).toBeNull()
  })

  it('a failed re-read after a refusal removes every mutation control and offers the explicit re-read', async () => {
    let reads = 0
    const realRead = backend.read
    backend.read = () => {
      reads += 1
      if (reads === 2) throw new TypeError('Failed to fetch')
      return realRead()
    }
    backend.cancel = () => {
      throw backend.problem(409, 'APPOINTMENT_CONCURRENT_UPDATE')
    }
    renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Актуалните данни не можаха да бъдат заредени.')
    expect(cancelButton()).toBeNull()
    fireEvent.click(rereadButton())
    await screen.findByRole('region', { name: 'Детайли' })
    expect(cancelButton()).toBeInTheDocument()
    expect(backend.posts()).toHaveLength(1)
  })
})

describe('Cancellation: the unresolved outcome and leaving', () => {
  it('warns with "Неясен резултат" when leaving elsewhere, with the safe action first and focused', async () => {
    const { navigated } = await cancelWith(() => new TypeError('Failed to fetch'))
    await screen.findByRole('alert')
    navigate()
    const warning = screen.getByRole('alertdialog', { name: 'Неясен резултат' })
    expect(warning).toHaveTextContent('Не е ясно дали часът е отменен.')
    const [stay, leave] = within(warning).getAllByRole('button')
    expect(stay).toHaveTextContent('Остани')
    expect(stay).toHaveFocus()
    expect(leave).toHaveTextContent('Напусни')
    fireEvent.click(stay!)
    expect(navigated).not.toHaveBeenCalled()
    navigate()
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Напусни' }))
    expect(navigated).toHaveBeenCalledTimes(1)
  })

  it('returns to the calendar without the ordinary prompt', async () => {
    const { onBack } = await cancelWith(() => new TypeError('Failed to fetch'))
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(onBack).toHaveBeenCalledTimes(1)
  })

  it('shows the committed result after the explicit re-read and lets the owner act on fresh data when it did not commit', async () => {
    await cancelWith(() => new TypeError('Failed to fetch'))
    await screen.findByRole('alert')
    // The cancellation had not committed: the re-read shows the Appointment unchanged.
    fireEvent.click(rereadButton())
    await screen.findByRole('region', { name: 'Детайли' })
    expect(screen.queryByRole('alert')).toBeNull()
    expect(cancelButton()).toBeInTheDocument()
    navigate()
    // The frozen state ended with the re-read: leaving is ordinary again.
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('moves focus to the Appointment heading when the explicit re-read shows the page again', async () => {
    await cancelWith(() => new TypeError('Failed to fetch'))
    await screen.findByRole('alert')
    fireEvent.click(rereadButton())
    const heading = await screen.findByRole('heading', { level: 2, name: /10:00–11:15/ })
    await waitFor(() => expect(heading).toHaveFocus())
  })

  it('shows the cancelled Appointment after the re-read when the lost request had committed', async () => {
    await cancelWith(() => {
      backend.current = cancelled()
      return new TypeError('Failed to fetch')
    })
    await screen.findByRole('alert')
    fireEvent.click(rereadButton())
    expect(await screen.findByText('Отменен')).toBeInTheDocument()
    expect(cancelButton()).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })

  it('keeps a success whose body is for another Appointment unresolved', async () => {
    backend.cancel = () => ({ ...backend.result(), id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', status: 'CANCELLED' })
    renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    expect((await screen.findByRole('alert')).textContent).toContain('Не е ясно дали часът е отменен.')
    expect(backend.posts()).toHaveLength(1)
  })

  it('keeps a success that does not say cancelled unresolved', async () => {
    backend.cancel = () => ({ ...backend.result(), status: 'CONFIRMED' })
    renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    expect((await screen.findByRole('alert')).textContent).toContain('Не е ясно дали часът е отменен.')
  })

  it('keeps an empty success body unresolved', async () => {
    backend.cancel = () => undefined as never
    renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    expect((await screen.findByRole('alert')).textContent).toContain('Не е ясно дали часът е отменен.')
  })
})

describe('Cancellation: privacy and timing', () => {
  it('writes nothing to the URL, history state, storage, cookies, title, or console', async () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const before = {
      url: window.location.href,
      state: JSON.stringify(window.history.state),
      title: document.title,
    }
    window.localStorage.clear()
    window.sessionStorage.clear()
    await cancelWith(() => new TypeError('Failed to fetch'))
    await screen.findByRole('alert')
    expect(window.location.href).toBe(before.url)
    expect(JSON.stringify(window.history.state)).toBe(before.state)
    expect(document.title).toBe(before.title)
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
    expect(document.cookie).toBe('')
    for (const spy of [log, warn, error]) expect(spy).not.toHaveBeenCalled()
    vi.restoreAllMocks()
  })

  it('uses the injected clock and not the system date to decide whether the visit started', async () => {
    renderDetail({ now: NOW })
    await ready()
    expect(cancelButton()).toBeInTheDocument()
  })
})
