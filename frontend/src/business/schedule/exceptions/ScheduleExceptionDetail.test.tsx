import '@testing-library/jest-dom/vitest'
import { act, fireEvent, render as rtlRender, screen, waitFor, within } from '@testing-library/react'
import type { ReactElement } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../identity/api'
import { UnsavedChangesGuardProvider } from '../../../ui/UnsavedChangesGuard'
import { listStaffMembers, type StaffMemberSummary } from '../../staff/api'
import {
  deleteScheduleException,
  getScheduleException,
  replaceScheduleException,
  type ScheduleExceptionDetails as Details,
} from './api'
import { ScheduleExceptionDetail } from './ScheduleExceptionDetail'

vi.mock('../../staff/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../staff/api')>()),
  listStaffMembers: vi.fn(),
}))
vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getScheduleException: vi.fn(),
  replaceScheduleException: vi.fn(),
  deleteScheduleException: vi.fn(),
}))

const mockedStaff = vi.mocked(listStaffMembers)
const mockedGet = vi.mocked(getScheduleException)
const mockedReplace = vi.mocked(replaceScheduleException)
const mockedDelete = vi.mocked(deleteScheduleException)

function render(ui: ReactElement) {
  return rtlRender(ui, { wrapper: UnsavedChangesGuardProvider })
}

function staff(id: string, displayName: string, active = true): StaffMemberSummary {
  return {
    id,
    displayName,
    contactEmail: null,
    contactPhone: null,
    active,
    version: 0,
    createdAt: '2026-08-19T09:00:00Z',
    updatedAt: '2026-08-19T09:00:00Z',
  }
}

function details(overrides: Partial<Details> = {}): Details {
  return {
    id: 'exception-1',
    kind: 'STAFF_TIME_OFF',
    staffMemberId: 'staff-a',
    firstDate: '2026-11-02',
    lastDate: '2026-11-04',
    allDay: true,
    periods: [],
    timezone: 'Europe/Sofia',
    version: 3,
    createdAt: '2026-09-29T08:00:00Z',
    updatedAt: '2026-09-29T08:00:00Z',
    ...overrides,
  }
}

const handlers = {
  onAuthenticationRequired: vi.fn(),
  onBack: vi.fn(),
  onDeleted: vi.fn(),
}

async function open(overrides: Partial<Details> = {}, readOnly = false) {
  mockedGet.mockResolvedValue(details(overrides))
  render(
    <ScheduleExceptionDetail exceptionId="exception-1" readOnly={readOnly} {...handlers} />,
  )
  await screen.findByText('Вид')
}

beforeEach(() => {
  mockedStaff.mockReset()
  mockedGet.mockReset()
  mockedReplace.mockReset()
  mockedDelete.mockReset()
  handlers.onAuthenticationRequired.mockReset()
  handlers.onBack.mockReset()
  handlers.onDeleted.mockReset()
  mockedStaff.mockResolvedValue({
    staffMembers: [staff('staff-a', 'Анна Иванова'), staff('staff-x', 'Стефан Стоянов', false)],
    page: 0,
    size: 50,
    totalElements: 2,
  })
})

describe('reading a record', () => {
  it('shows kind, resolved StaffMember, date range and hours without technical terms', async () => {
    await open()

    const list = document.querySelector('dl')!
    expect(within(list).getByText('Отсъствие')).toBeInTheDocument()
    expect(within(list).getByText('Анна Иванова')).toBeInTheDocument()
    expect(within(list).getByText('02.11.2026 – 04.11.2026')).toBeInTheDocument()
    expect(within(list).getByText('Цял ден')).toBeInTheDocument()
    expect(within(list).getByText('Дати')).toBeInTheDocument()
    expect(screen.queryByText(/STAFF_TIME_OFF|exception/i)).not.toBeInTheDocument()
    expect(screen.queryByText('Активен')).not.toBeInTheDocument()
  })

  it('shows the derived status from the Business-local date, never the browser date', async () => {
    mockedGet.mockResolvedValue(details({ firstDate: '2026-10-06', lastDate: '2026-10-06', timezone: 'Europe/Sofia' }))
    // 23:30 UTC on the 5th is already the 6th in Sofia.
    render(
      <ScheduleExceptionDetail
        exceptionId="exception-1"
        readOnly={false}
        now={() => new Date('2026-10-05T23:30:00Z')}
        {...handlers}
      />,
    )
    await screen.findByText('Вид')
    const term = screen.getByText('Статус')
    expect(term.nextElementSibling).toHaveTextContent('В сила')
    expect(term.nextElementSibling?.querySelector('.status-badge')).not.toBeNull()
  })

  it.each([
    ['2026-10-11', '2026-10-12', 'Предстояща'],
    ['2026-10-10', '2026-10-12', 'В сила'],
    ['2026-10-08', '2026-10-10', 'В сила'],
    ['2026-10-05', '2026-10-09', 'Минала'],
  ])('shows %s – %s as %s on the detail', async (firstDate, lastDate, label) => {
    mockedGet.mockResolvedValue(details({ firstDate, lastDate }))
    render(
      <ScheduleExceptionDetail
        exceptionId="exception-1"
        readOnly={false}
        now={() => new Date('2026-10-10T09:00:00Z')}
        {...handlers}
      />,
    )
    await screen.findByText('Вид')
    expect(screen.getByText('Статус').nextElementSibling).toHaveTextContent(label)
  })

  it('shows one date and the period summary for a working kind', async () => {
    await open({
      kind: 'ADDITIONAL_WORKING_PERIODS',
      firstDate: '2026-11-14',
      lastDate: '2026-11-14',
      allDay: false,
      periods: [
        { startTime: '09:00', endTime: '12:00' },
        { startTime: '14:00', endTime: '18:00' },
      ],
    })

    expect(screen.getByText('Дата')).toBeInTheDocument()
    expect(screen.getByText('14.11.2026')).toBeInTheDocument()
    expect(screen.getByText('09:00–12:00, 14:00–18:00')).toBeInTheDocument()
  })

  it('shows a zero-period working-day override as "Неработен ден"', async () => {
    await open({
      kind: 'WORKING_DAY_OVERRIDE',
      firstDate: '2026-11-11',
      lastDate: '2026-11-11',
      allDay: false,
      periods: [],
    })
    expect(screen.getByText('Неработен ден')).toBeInTheDocument()
  })

  it('omits the StaffMember row for a business closure', async () => {
    await open({ kind: 'BUSINESS_CLOSURE', staffMemberId: null })
    expect(screen.queryByText('Член на екипа')).not.toBeInTheDocument()
  })

  it('shows the name of an inactive StaffMember and reports an unavailable action only there', async () => {
    await open({ staffMemberId: 'staff-x' })

    expect(screen.getByText('Стефан Стоянов')).toBeInTheDocument()
    expect(
      screen.getByText('Промените на неактивен член на екипа могат само да бъдат преглеждани.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Редактирай' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Изтрий' })).not.toBeInTheDocument()
  })

  it('hides mutations for a SUSPENDED Business without extra copy', async () => {
    await open({}, true)

    expect(screen.queryByRole('button', { name: 'Редактирай' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Изтрий' })).not.toBeInTheDocument()
    expect(screen.queryByText(/неактивен/)).not.toBeInTheDocument()
    expect(screen.getByText('Анна Иванова')).toBeInTheDocument()
  })

  it('shows a retryable error and a way back when the record cannot be loaded', async () => {
    mockedGet.mockRejectedValueOnce(
      new ApiError(404, 'SCHEDULE_EXCEPTION_NOT_FOUND', 'Изключението от графика не е намерено.'),
    )
    render(<ScheduleExceptionDetail exceptionId="x" readOnly={false} {...handlers} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Промяната в графика не е намерена.')
    expect(document.body.textContent?.toLowerCase()).not.toContain('изключен')
    mockedGet.mockResolvedValueOnce(details())
    fireEvent.click(screen.getByRole('button', { name: 'Зареди отново' }))
    expect(await screen.findByText('Анна Иванова')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    expect(handlers.onBack).toHaveBeenCalledTimes(1)
  })

  it('reports an authentication failure and aborts the load on unmount', async () => {
    mockedGet.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    render(<ScheduleExceptionDetail exceptionId="x" readOnly={false} {...handlers} />)
    await waitFor(() =>
      expect(handlers.onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'),
    )

    let signal: AbortSignal | undefined
    mockedGet.mockImplementation((_id, abort) => {
      signal = abort
      return new Promise(() => undefined)
    })
    const { unmount } = render(
      <ScheduleExceptionDetail exceptionId="y" readOnly={false} {...handlers} />,
    )
    await waitFor(() => expect(signal).toBeDefined())
    unmount()
    expect(signal!.aborted).toBe(true)
  })

  it('discards a stale load when the record changes', async () => {
    let resolveFirst!: (value: Details) => void
    mockedGet.mockImplementationOnce(() => new Promise((done) => (resolveFirst = done)))
    mockedGet.mockResolvedValueOnce(details({ id: 'second', kind: 'BUSINESS_CLOSURE', staffMemberId: null }))
    const { rerender } = render(
      <ScheduleExceptionDetail exceptionId="first" readOnly={false} {...handlers} />,
    )
    rerender(<ScheduleExceptionDetail exceptionId="second" readOnly={false} {...handlers} />)
    await screen.findByText('Неработно време')

    await act(async () => resolveFirst(details({ id: 'first' })))
    expect(screen.queryByText('Отсъствие')).not.toBeInTheDocument()
  })
})

describe('editing', () => {
  async function startEdit(overrides: Partial<Details> = {}) {
    await open(overrides)
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
  }

  it('shows kind and StaffMember as read-only context and offers no way to change them', async () => {
    await startEdit()

    expect(screen.queryByRole('radio', { name: 'Неработно време' })).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(screen.getByText('Отсъствие')).toBeInTheDocument()
    expect(screen.getByText('Анна Иванова')).toBeInTheDocument()
    expect(screen.getByLabelText('От')).toHaveValue('2026-11-02')
    expect(screen.getByLabelText('До')).toHaveValue('2026-11-04')
    expect(screen.queryByRole('button', { name: 'Изтрий' })).not.toBeInTheDocument()
  })

  it('replaces with expectedVersion, sends neither kind nor StaffMember, and shows the new state', async () => {
    mockedReplace.mockResolvedValue(
      details({ version: 4, firstDate: '2026-11-03', lastDate: '2026-11-05' }),
    )
    await startEdit()
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-11-03' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-05' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

    await waitFor(() =>
      expect(mockedReplace).toHaveBeenCalledWith('exception-1', {
        expectedVersion: 3,
        firstDate: '2026-11-03',
        lastDate: '2026-11-05',
        allDay: true,
        periods: [],
      }),
    )
    expect(await screen.findByText('Промените са запазени.')).toBeInTheDocument()
    expect(screen.getByText('03.11.2026 – 05.11.2026')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Редактирай' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-06' } })
    mockedReplace.mockResolvedValue(details({ version: 5 }))
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await waitFor(() => expect(mockedReplace).toHaveBeenCalledTimes(2))
    expect(mockedReplace.mock.calls[1]![1].expectedVersion).toBe(4)
  })

  it('changes a whole-date block to part of a day', async () => {
    mockedReplace.mockResolvedValue(details({ version: 4 }))
    await startEdit()
    fireEvent.click(screen.getByRole('radio', { name: 'Част от деня' }))
    fireEvent.click(screen.getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '13:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '15:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

    await waitFor(() =>
      expect(mockedReplace).toHaveBeenCalledWith('exception-1', {
        expectedVersion: 3,
        firstDate: '2026-11-02',
        lastDate: '2026-11-02',
        allDay: false,
        periods: [{ startTime: '13:00', endTime: '15:00' }],
      }),
    )
  })

  it('turns a "Неработен ден" override into working hours and keeps the kind', async () => {
    mockedReplace.mockResolvedValue(details({ version: 1 }))
    await startEdit({
      kind: 'WORKING_DAY_OVERRIDE',
      firstDate: '2026-11-11',
      lastDate: '2026-11-11',
      allDay: false,
      periods: [],
      version: 0,
    })
    expect(screen.getByRole('radio', { name: 'Неработен ден' })).toBeChecked()

    fireEvent.click(screen.getByRole('radio', { name: 'Работни часове' }))
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(await screen.findByText('Добавете поне един период.')).toBeInTheDocument()
    expect(mockedReplace).not.toHaveBeenCalled()
  })

  it('preserves the entered values and shows a safe alert after a network failure', async () => {
    mockedReplace.mockRejectedValue(new Error('offline'))
    await startEdit()
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-09' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Промените не могат да бъдат запазени.')
    expect(screen.getByLabelText('До')).toHaveValue('2026-11-09')
    expect(screen.queryByRole('button', { name: 'Зареди актуалните данни' })).not.toBeInTheDocument()
  })

  it('maps a backend field error under its field', async () => {
    mockedReplace.mockRejectedValue(
      new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.', {
        firstDate: 'Въведете валидна начална дата от 01.01.2000 до 31.12.2100.',
      }),
    )
    await startEdit()
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-09' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

    expect(
      await screen.findByText('Въведете валидна дата между 01.01.2000 и 31.12.2100.'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('От')).toHaveFocus()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('maps the same-kind conflict of the edited record to its kind-specific message, on separate lines', async () => {
    mockedReplace.mockRejectedValue(
      new ApiError(409, 'SCHEDULE_EXCEPTION_OVERLAP', 'Вече има изключение от същия вид за тези дати.'),
    )
    await startEdit({
      kind: 'ADDITIONAL_WORKING_PERIODS',
      firstDate: '2026-11-14',
      lastDate: '2026-11-14',
      allDay: false,
      periods: [{ startTime: '09:00', endTime: '12:00' }],
    })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

    const alert = await screen.findByRole('alert')
    expect([...alert.querySelectorAll('p')].map((line) => line.textContent)).toEqual([
      'За тази дата вече има допълнителни работни часове за избрания член на екипа.',
      'Редактирайте съществуващия запис, за да добавите или промените периодите.',
    ])
    expect(document.body.textContent?.toLowerCase()).not.toContain('изключен')
  })

  it('offers guarded reload after a concurrent update and reloads only after discard is confirmed', async () => {
    mockedReplace.mockRejectedValue(
      new ApiError(
        409,
        'SCHEDULE_EXCEPTION_CONCURRENT_UPDATE',
        'Изключението от графика е променено от друга операция. Обновете данните и опитайте отново.',
      ),
    )
    await startEdit()
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-09' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await screen.findByRole('alert')

    mockedGet.mockResolvedValue(details({ version: 4, lastDate: '2026-11-20' }))
    fireEvent.click(screen.getByRole('button', { name: 'Зареди актуалните данни' }))
    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
    expect(mockedGet).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(screen.getByLabelText('До')).toHaveValue('2026-11-09')

    fireEvent.click(screen.getByRole('button', { name: 'Зареди актуалните данни' }))
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))
    expect(await screen.findByText('02.11.2026 – 20.11.2026')).toBeInTheDocument()
    expect(mockedGet).toHaveBeenCalledTimes(2)
  })

  it('cancels a clean edit at once and asks before discarding a dirty one', async () => {
    await startEdit()
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Редактирай' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-09' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
  })

  it('does not treat restoring the saved value as a change', async () => {
    await startEdit()
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-09' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-04' } })

    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(handlers.onBack).toHaveBeenCalledTimes(1)
  })

  it('guards going back while an edit is dirty', async () => {
    await startEdit()
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-09' } })

    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))

    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
    expect(handlers.onBack).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))
    expect(handlers.onBack).toHaveBeenCalledTimes(1)
  })

  it('sends one request for repeated saves', async () => {
    let resolve!: (value: Details) => void
    mockedReplace.mockImplementation(() => new Promise((done) => (resolve = done)))
    await startEdit()
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-09' } })
    const save = screen.getByRole('button', { name: 'Запази промените' })
    fireEvent.click(save)
    fireEvent.click(save)
    fireEvent.submit(save.closest('form')!)

    expect(mockedReplace).toHaveBeenCalledTimes(1)
    await act(async () => resolve(details({ version: 4 })))
  })
})

describe('deleting', () => {
  it('asks for confirmation first, focusing the safe action and describing what is removed', async () => {
    await open()
    const trigger = screen.getByRole('button', { name: 'Изтрий' })
    trigger.focus()
    fireEvent.click(trigger)

    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleName('Изтриване на промяна')
    expect(dialog).toHaveTextContent('Промяната „Отсъствие, Анна Иванова“ за 02.11.2026 – 04.11.2026')
    expect(dialog).toHaveTextContent('ще бъде изтрита завинаги.')
    expect(within(dialog).getAllByText(/./, { selector: 'p' })).toHaveLength(2)
    expect(within(dialog).getByRole('button', { name: 'Отказ' })).toHaveFocus()
    expect(within(dialog).getByRole('button', { name: 'Изтрий промяната' })).not.toHaveFocus()
    expect(mockedDelete).not.toHaveBeenCalled()
  })

  it('closes on Cancel or Escape without deleting and returns focus to the trigger', async () => {
    await open()
    const trigger = screen.getByRole('button', { name: 'Изтрий' })
    fireEvent.click(trigger)
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()

    fireEvent.click(trigger)
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' })
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
    expect(mockedDelete).not.toHaveBeenCalled()
  })

  it('deletes with expectedVersion, blocks duplicates, keeps the dialog open while pending and returns to the list after success', async () => {
    let resolve!: () => void
    mockedDelete.mockImplementation(() => new Promise<void>((done) => (resolve = done)))
    await open()
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий' }))
    const confirm = screen.getByRole('button', { name: 'Изтрий промяната' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)

    expect(mockedDelete).toHaveBeenCalledTimes(1)
    expect(mockedDelete).toHaveBeenCalledWith('exception-1', 3)
    const pending = screen.getByRole('alertdialog')
    expect(within(pending).getByRole('button', { name: 'Изтриване…' })).toBeDisabled()
    expect(within(pending).getByRole('button', { name: 'Отказ' })).toBeDisabled()
    fireEvent.keyDown(pending, { key: 'Escape' })
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Обратно към графика' })).toBeDisabled()
    expect(handlers.onDeleted).not.toHaveBeenCalled()

    await act(async () => resolve())
    expect(handlers.onDeleted).toHaveBeenCalledTimes(1)
  })

  it('closes the dialog and shows a safe alert when deletion fails', async () => {
    mockedDelete.mockRejectedValue(new Error('SQL exploded'))
    await open()
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий' }))
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий промяната' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Промяната не може да бъде изтрита.')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.queryByText(/SQL/)).not.toBeInTheDocument()
    expect(handlers.onDeleted).not.toHaveBeenCalled()
  })

  it('offers reload after a concurrent delete or update', async () => {
    mockedDelete.mockRejectedValue(
      new ApiError(
        409,
        'SCHEDULE_EXCEPTION_CONCURRENT_UPDATE',
        'Изключението от графика е променено от друга операция. Обновете данните и опитайте отново.',
      ),
    )
    await open()
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий' }))
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий промяната' }))

    expect(await screen.findByRole('button', { name: 'Зареди актуалните данни' })).toBeInTheDocument()
  })

  it('does not report success for a response that arrives after unmount', async () => {
    let resolve!: () => void
    mockedDelete.mockImplementation(() => new Promise<void>((done) => (resolve = done)))
    mockedGet.mockResolvedValue(details())
    const { unmount } = render(
      <ScheduleExceptionDetail exceptionId="exception-1" readOnly={false} {...handlers} />,
    )
    await screen.findByText('Вид')
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий' }))
    fireEvent.click(screen.getByRole('button', { name: 'Изтрий промяната' }))
    unmount()

    await act(async () => resolve())
    expect(handlers.onDeleted).not.toHaveBeenCalled()
  })

  it('offers deletion as its own action, never inside edit mode', async () => {
    await open()
    expect(screen.getByRole('button', { name: 'Изтрий' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    expect(screen.queryByRole('button', { name: 'Изтрий' })).not.toBeInTheDocument()
  })
})
