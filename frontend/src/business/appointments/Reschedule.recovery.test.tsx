import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import {
  chooseStaff,
  chooseTime,
  confirm,
  deferred,
  heading,
  installBackend,
  navigate,
  next,
  renderForm,
  staffStep,
  toReview,
} from './rescheduleTestHarness'
import {
  DAY,
  FakeRescheduleBackend,
  STAFF_ONE,
  STAFF_TWO,
  appointmentDetail,
  optionsResponse,
} from './rescheduleTestFixtures'
import { slot } from './testFixtures'

vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
let backend: FakeRescheduleBackend

beforeEach(() => {
  backend = installBackend(mockedRequest)
  history.replaceState({}, '', '/')
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

const SECRET = 'backend text that must never be shown'

/** Makes the next POST fail with a problem, then succeed afterwards. */
function failOnce(status: number, code: string) {
  let failed = false
  backend.reschedule = (body) => {
    if (!failed) {
      failed = true
      throw backend.problem(status, code)
    }
    return {
      id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      status: 'CONFIRMED',
      start: body.start ?? `${DAY}T09:00:00+02:00`,
      end: `${DAY}T10:15:00+02:00`,
      staffMemberId: body.staffMemberId ?? STAFF_ONE,
      version: 8,
    }
  }
}

const alertText = () => screen.getByRole('alert').textContent
const reviewButton = () => screen.queryByRole('button', { name: 'Промени часа' })

describe('validation and a temporary refusal keep the proposal editable', () => {
  it('VALIDATION_ERROR keeps the review and safe feedback, and allows an explicit retry', async () => {
    failOnce(400, 'VALIDATION_ERROR')
    const { onRescheduled } = await toReview({ slot: 1 })
    confirm()

    expect(await screen.findByRole('alert')).toHaveTextContent('Проверете въведените данни.')
    expect(document.body.textContent).not.toContain(SECRET)
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(reviewButton()).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Промени датата и часа' })).toBeEnabled()
    expect(onRescheduled).not.toHaveBeenCalled()
    confirm()
    await waitFor(() => expect(onRescheduled).toHaveBeenCalledTimes(1))
    expect(backend.posts()).toHaveLength(2)
  })

  it('a temporary refusal states that nothing was changed and permits only an explicit retry', async () => {
    failOnce(503, 'APPOINTMENT_TEMPORARILY_UNAVAILABLE')
    const { onRescheduled } = await toReview({ slot: 1 })
    confirm()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Промяната не беше направена. Часът е непроменен. Опитайте отново след малко.',
    )
    // No automatic retry happened, and the proposal is intact.
    await act(async () => {})
    expect(backend.posts()).toHaveLength(1)
    expect(screen.getByRole('region', { name: 'Преглед и потвърждение' })).toHaveTextContent(/След.*10:00–11:15/)
    expect(reviewButton()).toBeEnabled()
    confirm()
    await waitFor(() => expect(onRescheduled).toHaveBeenCalledTimes(1))
    expect(backend.posts()).toHaveLength(2)
    expect(backend.posts()[1]!.body).toEqual(backend.posts()[0]!.body)
  })

  it('an expired session during the POST uses the shared recovery and shows no failure', async () => {
    failOnce(401, 'AUTH_REQUIRED')
    const { onAuthenticationRequired, onRescheduled } = await toReview({ slot: 1 })
    confirm()

    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(onRescheduled).not.toHaveBeenCalled()
  })
})

describe('refusals that block the mutation', () => {
  it.each([
    [403, 'ACCESS_DENIED', 'Нямате достъп до тази операция.'],
    [403, 'ACTIVE_BUSINESS_REQUIRED', 'Нямате достъп до тази операция.'],
    [409, 'BUSINESS_NOT_ACTIVE', 'Бизнесът не е активен. Часовете могат да се променят само в активен бизнес.'],
    [409, 'BUSINESS_SUSPENDED', 'Спрян бизнес може само да преглежда данните си.'],
    [404, 'APPOINTMENT_NOT_FOUND', 'Часът не е намерен.'],
  ])('%i %s blocks the confirmation and offers the way back', async (status, code, text) => {
    failOnce(status, code)
    const { onLeaveToOrigin } = await toReview({ slot: 1 })
    confirm()

    expect(await screen.findByRole('alert')).toHaveTextContent(text)
    expect(document.body.textContent).not.toContain(SECRET)
    expect(reviewButton()).toBeDisabled()
    fireEvent.click(reviewButton()!)
    expect(backend.posts()).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Към графика' }))
    expect(onLeaveToOrigin).toHaveBeenCalledTimes(1)
  })
})

describe('a concurrent update', () => {
  it('discards the stale proposal and options, reads the Appointment again and requires a new review with the new version', async () => {
    let version = 7
    backend.detail = () => appointmentDetail({ version, start: `${DAY}T09:30:00+02:00`, end: `${DAY}T10:45:00+02:00` })
    backend.reschedule = (body) => {
      if (body.expectedVersion === 7) {
        version = 8
        throw backend.problem(409, 'APPOINTMENT_CONCURRENT_UPDATE')
      }
      return {
        id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        status: 'CONFIRMED',
        start: body.start ?? '',
        end: `${DAY}T10:45:00+02:00`,
        staffMemberId: STAFF_ONE,
        version: 9,
      }
    }
    // Both versions of the options are different, to prove the stale list is not reused.
    let optionRead = 0
    backend.options = () => {
      optionRead += 1
      return optionRead === 1
        ? optionsResponse()
        : optionsResponse({ staffMembers: [{ id: STAFF_TWO, displayName: 'Георги Стилистов' }] })
    }
    const { onRescheduled } = await toReview({ replacement: 'Георги', slot: 1 })
    confirm()

    expect(await screen.findByText(/Часът е променен междувременно/)).toBeInTheDocument()
    // The form starts again: the StaffMember step, nothing proposed, the current StaffMember preselected.
    await staffStep()
    expect(heading()).toHaveTextContent('Избор на служител')
    expect((screen.getByRole('radio', { name: /Мила/ }) as HTMLInputElement).checked).toBe(true)
    expect(backend.count('/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')).toBe(2)
    expect(backend.count('/reschedule-options')).toBe(2)
    expect(onRescheduled).not.toHaveBeenCalled()
    expect(backend.posts()).toHaveLength(1)

    next()
    await chooseTime(1)
    next()
    await screen.findByRole('heading', { level: 2, name: 'Преглед и потвърждение' })
    confirm()
    await waitFor(() => expect(onRescheduled).toHaveBeenCalledTimes(1))
    expect(backend.posts()[1]!.body).toEqual({ expectedVersion: 8, start: `${DAY}T10:00:00+02:00` })
  })
})

describe('a state conflict', () => {
  it('reloads the Appointment, removes the mutation path and explains why', async () => {
    let cancelled = false
    backend.detail = () => appointmentDetail({ status: cancelled ? 'CANCELLED' : 'CONFIRMED', version: cancelled ? 8 : 7 })
    backend.reschedule = () => {
      cancelled = true
      throw backend.problem(409, 'APPOINTMENT_STATE_CONFLICT')
    }
    const { onBackToDetail } = await toReview({ slot: 1 })
    confirm()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Часът не може да бъде променен, защото е отменен или вече е започнал.',
    )
    await waitFor(() => expect(screen.getByRole('region', { name: 'Текущ час' })).toHaveTextContent('Отменен'))
    expect(backend.count('/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')).toBe(2)
    expect(reviewButton()).toBeNull()
    expect(screen.queryByRole('button', { name: 'Напред' })).toBeNull()
    expect(screen.queryByRole('radio')).toBeNull()
    expect(backend.posts()).toHaveLength(1)
    fireEvent.click(screen.getAllByRole('button', { name: 'Към часа' })[0]!)
    expect(onBackToDetail).toHaveBeenCalled()
  })
})

describe('the StaffMember and slot results', () => {
  it('APPOINTMENT_STAFF_UNAVAILABLE reads the options again and clears only the invalid replacement', async () => {
    failOnce(409, 'APPOINTMENT_STAFF_UNAVAILABLE')
    backend.options = () => optionsResponse()
    await toReview({ replacement: 'Георги', slot: 1 })
    backend.options = () => optionsResponse({ staffMembers: [{ id: STAFF_TWO, displayName: 'Борис Нов' }] })
    confirm()

    expect(
      await screen.findByText('Избраният член на екипа не е наличен за тази промяна. Изберете отново.'),
    ).toBeInTheDocument()
    expect(heading()).toHaveTextContent('Избор на служител')
    await screen.findByRole('radio', { name: /Борис Нов/ })
    expect(backend.count('/reschedule-options')).toBe(2)
    expect((screen.getAllByRole('radio') as HTMLInputElement[]).every((radio) => !radio.checked)).toBe(true)
    // The date is kept, the start is gone, and no POST happened again.
    next()
    expect(screen.getByText('Изберете член на екипа.')).toBeInTheDocument()
    expect(backend.posts()).toHaveLength(1)
  })

  it('APPOINTMENT_SLOT_UNAVAILABLE reads the availability again and clears only the start', async () => {
    failOnce(409, 'APPOINTMENT_SLOT_UNAVAILABLE')
    await toReview({ replacement: 'Георги', slot: 1 })
    const reads = backend.count('/availability')
    backend.availability = (_staff, date) => ({
      timezone: 'Europe/Sofia',
      date,
      durationMinutes: 75,
      slots: [slot(`${date}T10:00:00+02:00`, 75), slot(`${date}T11:00:00+02:00`, 75)],
    })
    confirm()

    expect(await screen.findByRole('alert')).toHaveTextContent('Избраният час вече не е свободен. Изберете друг час.')
    expect(heading()).toHaveTextContent('Дата и час')
    const group = await screen.findByRole('group', { name: 'Свободни часове' })
    expect(within(group).getAllByRole('radio').map((radio) => radio.closest('label')?.textContent)).toEqual(['10:00', '11:00'])
    expect(within(group).getAllByRole('radio').every((radio) => !(radio as HTMLInputElement).checked)).toBe(true)
    expect(backend.count('/availability')).toBe(reads + 1)
    // The StaffMember and the date are kept.
    expect(screen.getByLabelText('Дата')).toHaveValue(DAY)
    fireEvent.click(within(group).getAllByRole('radio')[0]!)
    next()
    await screen.findByRole('button', { name: 'Промени часа' })
    expect(screen.getByRole('region', { name: 'Преглед и потвърждение' })).toHaveTextContent(/След.*Георги Стилистов/)
  })

  it('a StaffMember refusal found by the availability read returns to the StaffMember step', async () => {
    backend.availability = (staff) => {
      if (staff === STAFF_TWO) throw backend.problem(409, 'APPOINTMENT_STAFF_UNAVAILABLE')
      return { timezone: 'Europe/Sofia', date: DAY, durationMinutes: 75, slots: [] }
    }
    renderForm()
    await staffStep()
    await chooseStaff('Георги')
    next()

    expect(
      await screen.findByText('Избраният член на екипа не е наличен за тази промяна. Изберете отново.'),
    ).toBeInTheDocument()
    expect(heading()).toHaveTextContent('Избор на служител')
  })
})

describe('an outcome that cannot be ruled out', () => {
  const unresolved: [string, () => void][] = [
    ['APPOINTMENT_OUTCOME_UNCERTAIN', () => {
      throw backend.problem(503, 'APPOINTMENT_OUTCOME_UNCERTAIN')
    }],
    ['an unexpected 500', () => {
      throw backend.problem(500, 'INTERNAL_ERROR')
    }],
    ['an unknown problem code', () => {
      throw backend.problem(418, 'SOMETHING_NEW')
    }],
    ['a lost response', () => {
      throw new TypeError('Failed to fetch')
    }],
    ['an unreadable success', () => {
      throw backend.problem(200, 'UNREADABLE', SECRET)
    }],
  ]

  it.each(unresolved)('%s freezes the submitted review permanently after exactly one POST', async (_label, fail) => {
    backend.reschedule = fail as never
    const { onRescheduled, onBackToDetail } = await toReview({ replacement: 'Георги', slot: 1 })
    const staleButton = reviewButton()!
    confirm()

    const alert = await screen.findByRole('alert')
    // It claims neither success nor failure of the change.
    expect(alert.textContent).toMatch(/Не е ясно дали промяната е направена|Възникна неочаквана грешка/)
    expect(alert.textContent).not.toMatch(/успешно|не беше направена|променен\.$/)
    expect(document.body.textContent).not.toContain(SECRET)
    expect(backend.posts()).toHaveLength(1)

    // No control that confirms, edits, or retries remains; the submitted facts stay visible.
    expect(reviewButton()).toBeNull()
    for (const name of ['Напред', 'Назад', 'Отказ', 'Промени члена на екипа', 'Промени датата и часа']) {
      expect(screen.queryByRole('button', { name })).toBeNull()
    }
    expect(screen.getByRole('region', { name: 'Преглед и потвърждение' })).toHaveTextContent(/След.*Георги Стилистов/)

    // A stale reference to the old button, repeated clicks and keys cannot send anything.
    fireEvent.click(staleButton)
    fireEvent.keyDown(document.body, { key: 'Enter' })
    await act(async () => {})
    expect(backend.posts()).toHaveLength(1)
    expect(onRescheduled).not.toHaveBeenCalled()

    // The only ways on are the authoritative detail and calendar, without the ordinary prompt.
    fireEvent.click(screen.getByRole('button', { name: 'Към часа' }))
    expect(onBackToDetail).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('a duplicate unreadable answer for a different Appointment is also treated as unresolved', async () => {
    backend.reschedule = () => ({ id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', status: 'CONFIRMED', start: '', end: '', staffMemberId: '', version: 1 })
    await toReview({ slot: 1 })
    confirm()

    expect(await screen.findByRole('alert')).toHaveTextContent('Не е ясно дали промяната е направена')
    expect(backend.posts()).toHaveLength(1)
    expect(reviewButton()).toBeNull()
  })

  it('stays frozen while a late answer to the same request arrives and while the owner returns to the calendar', async () => {
    const lost = deferred<never>()
    backend.reschedule = () => lost.promise
    const { onLeaveToOrigin } = await toReview({ slot: 1 })
    confirm()
    await act(async () => {
      lost.reject(new TypeError('Failed to fetch'))
    })

    expect(await screen.findByRole('alert')).toHaveTextContent('Не е ясно дали промяната е направена')
    fireEvent.click(screen.getByRole('button', { name: 'Към графика' }))
    expect(onLeaveToOrigin).toHaveBeenCalledTimes(1)
    expect(backend.posts()).toHaveLength(1)
  })
})

describe('the unsaved-changes guard', () => {
  it('lets a clean form go without a warning, also after the owner only looked at the steps', async () => {
    const { navigated } = renderForm()
    await staffStep()
    next()
    await screen.findByLabelText('Дата')

    navigate()
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(navigated).toHaveBeenCalledTimes(1)
  })

  it('warns before abandoning a changed proposal, with the safe action first and focused', async () => {
    const { navigated } = await toReview({ slot: 1 })
    navigate()

    const dialog = await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })
    expect(dialog).toHaveTextContent('Промяната на часа още не е направена.')
    const buttons = within(dialog).getAllByRole('button').map((button) => button.textContent)
    expect(buttons).toEqual(['Остани', 'Напусни'])
    expect(within(dialog).getByRole('button', { name: 'Остани' })).toHaveFocus()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Остани' }))
    expect(navigated).not.toHaveBeenCalled()
    navigate()
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Напусни' }))
    expect(navigated).toHaveBeenCalledTimes(1)
  })

  it('a changed StaffMember alone is already a changed proposal', async () => {
    const { navigated } = renderForm()
    await staffStep()
    await chooseStaff('Георги')
    navigate()

    expect(await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })).toBeInTheDocument()
    expect(navigated).not.toHaveBeenCalled()
  })

  it('a successful change leaves without any warning', async () => {
    const { navigated, onRescheduled } = await toReview({ slot: 1 })
    onRescheduled.mockImplementation(() => navigate())
    confirm()

    await waitFor(() => expect(navigated).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('a known rollback keeps the proposal and the warning', async () => {
    failOnce(503, 'APPOINTMENT_TEMPORARILY_UNAVAILABLE')
    const { navigated } = await toReview({ slot: 1 })
    confirm()
    await screen.findByRole('alert')
    navigate()

    expect(await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })).toBeInTheDocument()
    expect(navigated).not.toHaveBeenCalled()
  })

  it('an unresolved outcome uses the stronger warning when leaving elsewhere, but not on the recovery buttons', async () => {
    backend.reschedule = () => {
      throw backend.problem(503, 'APPOINTMENT_OUTCOME_UNCERTAIN')
    }
    const { navigated, onBackToDetail } = await toReview({ slot: 1 })
    confirm()
    await screen.findByRole('alert')
    navigate()

    const dialog = await screen.findByRole('alertdialog', { name: 'Неясен резултат' })
    expect(dialog).toHaveTextContent('Не е ясно дали промяната е направена.')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Остани' }))
    expect(navigated).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Към часа' }))
    expect(onBackToDetail).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })
})

describe('the document stays free of private values', () => {
  it('shows no identifier, version, or backend text anywhere in a failed flow', async () => {
    failOnce(409, 'APPOINTMENT_SLOT_UNAVAILABLE')
    await toReview({ slot: 1 })
    confirm()
    await screen.findByRole('alert')

    const text = document.body.textContent ?? ''
    expect(text).not.toContain(SECRET)
    expect(text).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-/i)
    expect(alertText()).toBe('Избраният час вече не е свободен. Изберете друг час.')
  })
})
