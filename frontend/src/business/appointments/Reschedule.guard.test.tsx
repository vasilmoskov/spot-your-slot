import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import {
  chooseStaff,
  confirm,
  installBackend,
  navigate,
  next,
  renderForm,
  staffStep,
} from './rescheduleTestHarness'
import {
  DAY,
  FakeRescheduleBackend,
  STAFF_ONE,
  STAFF_TWO,
  appointmentDetail,
  optionsResponse,
} from './rescheduleTestFixtures'

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

const OTHER_DAY = '2026-11-12'

function failOnce(status: number, code: string) {
  let failed = false
  backend.reschedule = (body) => {
    if (!failed) {
      failed = true
      throw backend.problem(status, code)
    }
    return { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', status: 'CONFIRMED', start: body.start ?? '', end: '', staffMemberId: STAFF_TWO, version: 8 }
  }
}

/** A replacement StaffMember and another date, one offered start chosen, ready to confirm. */
async function proposalOnAnotherDay() {
  const handlers = renderForm()
  await staffStep()
  await chooseStaff('Георги')
  next()
  fireEvent.change(await screen.findByLabelText('Дата'), { target: { value: OTHER_DAY } })
  const group = await screen.findByRole('group', { name: 'Свободни часове' })
  fireEvent.click(within(group).getAllByRole('radio')[1]!)
  next()
  await screen.findByRole('button', { name: 'Промени часа' })
  return handlers
}

const slots = () => within(screen.getByRole('group', { name: 'Свободни часове' })).getAllByRole('radio') as HTMLInputElement[]

describe('after APPOINTMENT_SLOT_UNAVAILABLE the preserved choices are still an unfinished proposal', () => {
  it('clears only the start, keeps the StaffMember and the date, and still warns before leaving', async () => {
    failOnce(409, 'APPOINTMENT_SLOT_UNAVAILABLE')
    const { navigated } = await proposalOnAnotherDay()
    const reads = backend.count('/availability')
    confirm()

    expect(await screen.findByText('Избраният час вече не е свободен. Изберете друг час.')).toBeInTheDocument()
    await screen.findByRole('group', { name: 'Свободни часове' })
    expect(screen.getByLabelText('Дата')).toHaveValue(OTHER_DAY)
    expect(slots().every((radio) => !radio.checked)).toBe(true)
    expect(backend.count('/availability')).toBe(reads + 1)
    // The StaffMember kept is visible on the way back to the first step.
    fireEvent.click(screen.getByRole('button', { name: 'Назад' }))
    expect(await screen.findByRole('radio', { name: /Георги/ })).toBeChecked()

    navigate()
    const dialog = await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })
    expect(within(dialog).getByRole('button', { name: 'Остани' })).toHaveFocus()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Остани' }))
    expect(navigated).not.toHaveBeenCalled()
    // Stay preserved the proposal.
    expect(screen.getByRole('radio', { name: /Георги/ })).toBeChecked()
    next()
    expect(screen.getByLabelText('Дата')).toHaveValue(OTHER_DAY)

    navigate()
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Напусни' }))
    expect(navigated).toHaveBeenCalledTimes(1)
  })

  it('becomes clean only when the original StaffMember and the original date are both selected again', async () => {
    failOnce(409, 'APPOINTMENT_SLOT_UNAVAILABLE')
    const { navigated } = await proposalOnAnotherDay()
    confirm()
    await screen.findByText('Избраният час вече не е свободен. Изберете друг час.')
    await screen.findByRole('group', { name: 'Свободни часове' })

    // The original date again: the replacement StaffMember is still a changed value.
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: DAY } })
    await screen.findByRole('group', { name: 'Свободни часове' })
    navigate()
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Остани' }))
    expect(navigated).not.toHaveBeenCalled()

    // The original StaffMember again as well: nothing differs, so the form is clean.
    fireEvent.click(screen.getByRole('button', { name: 'Назад' }))
    fireEvent.click(await screen.findByRole('radio', { name: /Мила/ }))
    navigate()
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(navigated).toHaveBeenCalledTimes(1)
  })

  it('the original StaffMember with another date is still dirty, and the original date with another StaffMember too', async () => {
    failOnce(409, 'APPOINTMENT_SLOT_UNAVAILABLE')
    const { navigated } = await proposalOnAnotherDay()
    confirm()
    await screen.findByText('Избраният час вече не е свободен. Изберете друг час.')
    fireEvent.click(screen.getByRole('button', { name: 'Назад' }))
    fireEvent.click(await screen.findByRole('radio', { name: /Мила/ }))
    navigate()

    // Only the date differs now.
    expect(await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })).toBeInTheDocument()
    expect(navigated).not.toHaveBeenCalled()
  })
})

describe('a failed availability read keeps the safe choices and the guard', () => {
  it('warns when a changed StaffMember or date is retained, and not when nothing differs', async () => {
    backend.availability = () => {
      throw backend.problem(500, 'INTERNAL_ERROR')
    }
    const { navigated } = renderForm()
    await staffStep()
    next()
    expect(await screen.findByText('Свободните часове не могат да бъдат заредени.')).toBeInTheDocument()
    // Nothing differs from the original yet: no warning.
    navigate()
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(navigated).toHaveBeenCalledTimes(1)

    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: OTHER_DAY } })
    expect(await screen.findByText('Свободните часове не могат да бъдат заредени.')).toBeInTheDocument()
    expect(screen.getByLabelText('Дата')).toHaveValue(OTHER_DAY)
    navigate()
    expect(await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })).toBeInTheDocument()
    expect(navigated).toHaveBeenCalledTimes(1)
  })

  it('a replacement StaffMember alone keeps the guard while its availability cannot be read', async () => {
    backend.availability = () => {
      throw backend.problem(500, 'INTERNAL_ERROR')
    }
    const { navigated } = renderForm()
    await staffStep()
    await chooseStaff('Георги')
    next()
    await screen.findByText('Свободните часове не могат да бъдат заредени.')
    navigate()
    expect(await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })).toBeInTheDocument()
    expect(navigated).not.toHaveBeenCalled()
  })
})

describe('APPOINTMENT_STAFF_UNAVAILABLE', () => {
  it('with another surviving difference (the date) keeps the guard', async () => {
    failOnce(409, 'APPOINTMENT_STAFF_UNAVAILABLE')
    const { navigated } = await proposalOnAnotherDay()
    confirm()
    expect(await screen.findByText('Избраният член на екипа не е наличен за тази промяна. Изберете отново.')).toBeInTheDocument()
    // The invalid StaffMember is gone, the changed date remains.
    await screen.findByRole('radio', { name: /Мила/ })
    expect((screen.getAllByRole('radio') as HTMLInputElement[]).every((radio) => !radio.checked)).toBe(true)
    navigate()
    expect(await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })).toBeInTheDocument()
    expect(navigated).not.toHaveBeenCalled()
  })

  it('without any other difference the form returns to the original state and is clean', async () => {
    failOnce(409, 'APPOINTMENT_STAFF_UNAVAILABLE')
    const { navigated } = renderForm()
    await staffStep()
    await chooseStaff('Георги')
    next()
    const group = await screen.findByRole('group', { name: 'Свободни часове' })
    fireEvent.click(within(group).getAllByRole('radio')[2]!)
    next()
    await screen.findByRole('button', { name: 'Промени часа' })
    confirm()
    expect(await screen.findByText('Избраният член на екипа не е наличен за тази промяна. Изберете отново.')).toBeInTheDocument()
    // The date is the original one and the start was cleared with the StaffMember: nothing differs.
    await screen.findByRole('radio', { name: /Мила/ })
    navigate()
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(navigated).toHaveBeenCalledTimes(1)
  })
})

describe('a concurrent update establishes a new clean baseline', () => {
  it('compares against the reloaded Appointment, not the obsolete one', async () => {
    let version = 7
    backend.detail = () => appointmentDetail({ version, start: `${DAY}T09:30:00+02:00`, end: `${DAY}T10:45:00+02:00` })
    backend.options = () =>
      optionsResponse({ currentStaffMember: { id: STAFF_ONE, displayName: 'Мила Работникова', available: true } })
    backend.reschedule = () => {
      version = 8
      throw backend.problem(409, 'APPOINTMENT_CONCURRENT_UPDATE')
    }
    const { navigated } = await proposalOnAnotherDay()
    confirm()
    expect(await screen.findByText(/Часът е променен междувременно/)).toBeInTheDocument()
    await staffStep()

    // The reloaded form is clean: the discarded proposal does not warn.
    navigate()
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(navigated).toHaveBeenCalledTimes(1)
  })

  it('a change made after the reload is judged against the new Appointment', async () => {
    let version = 7
    backend.detail = () => appointmentDetail({ version, start: `${DAY}T09:30:00+02:00`, end: `${DAY}T10:45:00+02:00` })
    backend.reschedule = () => {
      version = 8
      throw backend.problem(409, 'APPOINTMENT_CONCURRENT_UPDATE')
    }
    renderForm()
    await staffStep()
    next()
    const group = await screen.findByRole('group', { name: 'Свободни часове' })
    fireEvent.click(within(group).getAllByRole('radio')[1]!)
    next()
    await screen.findByRole('button', { name: 'Промени часа' })
    confirm()
    await screen.findByText(/Часът е променен междувременно/)
    await staffStep()
    // The date of the reloaded Appointment is the new baseline: choosing it is no change.
    next()
    expect(await screen.findByLabelText('Дата')).toHaveValue(DAY)
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: OTHER_DAY } })
    await screen.findByRole('group', { name: 'Свободни часове' })
    navigate()
    expect(await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })).toBeInTheDocument()
  })
})

describe('a known rollback and an unresolved outcome', () => {
  it('a temporary refusal preserves the complete proposal and keeps the ordinary warning', async () => {
    failOnce(503, 'APPOINTMENT_TEMPORARILY_UNAVAILABLE')
    const { navigated } = await proposalOnAnotherDay()
    confirm()
    await screen.findByText(/Промяната не беше направена/)
    expect(screen.getByRole('region', { name: 'Преглед и потвърждение' })).toHaveTextContent(/Георги Стилистов/)
    navigate()

    expect(await screen.findByRole('alertdialog', { name: 'Непотвърдена промяна' })).toBeInTheDocument()
    expect(navigated).not.toHaveBeenCalled()
  })

  it('an uncertain outcome uses only the stronger unresolved warning, even for a form that would otherwise be clean', async () => {
    backend.reschedule = () => {
      throw backend.problem(503, 'APPOINTMENT_OUTCOME_UNCERTAIN')
    }
    const { navigated } = await proposalOnAnotherDay()
    confirm()
    await screen.findByText(/Не е ясно дали промяната е направена/)
    navigate()

    expect(await screen.findByRole('alertdialog', { name: 'Неясен резултат' })).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog', { name: 'Непотвърдена промяна' })).toBeNull()
    expect(navigated).not.toHaveBeenCalled()
    await waitFor(() => expect(backend.posts()).toHaveLength(1))
  })

  it('the stronger warning does not depend on any retained choice', async () => {
    backend.reschedule = () => {
      throw backend.problem(503, 'APPOINTMENT_OUTCOME_UNCERTAIN')
    }
    renderForm()
    await staffStep()
    next()
    const group = await screen.findByRole('group', { name: 'Свободни часове' })
    fireEvent.click(within(group).getAllByRole('radio')[0]!)
    next()
    await screen.findByRole('button', { name: 'Промени часа' })
    confirm()
    await screen.findByText(/Не е ясно дали промяната е направена/)
    navigate()
    expect(await screen.findByRole('alertdialog', { name: 'Неясен резултат' })).toBeInTheDocument()
  })
})
