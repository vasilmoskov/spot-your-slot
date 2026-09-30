import '@testing-library/jest-dom/vitest'
import { act, fireEvent, render as rtlRender, screen, waitFor, within } from '@testing-library/react'
import type { ReactElement } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../identity/api'
import { UnsavedChangesGuardProvider } from '../../../ui/UnsavedChangesGuard'
import { listStaffMembers, type StaffMemberSummary } from '../../staff/api'
import { createScheduleException, type ScheduleExceptionDetails } from './api'
import { ScheduleExceptionCreate } from './ScheduleExceptionCreate'

vi.mock('../../staff/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../staff/api')>()),
  listStaffMembers: vi.fn(),
}))
vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  createScheduleException: vi.fn(),
}))

const mockedStaff = vi.mocked(listStaffMembers)
const mockedCreate = vi.mocked(createScheduleException)

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

function created(id = 'created-1'): ScheduleExceptionDetails {
  return {
    id,
    kind: 'BUSINESS_CLOSURE',
    staffMemberId: null,
    firstDate: '2026-10-10',
    lastDate: '2026-10-10',
    allDay: true,
    periods: [],
    timezone: 'Europe/Sofia',
    version: 0,
    createdAt: '2026-09-29T08:00:00Z',
    updatedAt: '2026-09-29T08:00:00Z',
  }
}

const handlers = {
  onAuthenticationRequired: vi.fn(),
  onCreated: vi.fn(),
  onCancel: vi.fn(),
}

async function ready() {
  render(<ScheduleExceptionCreate readOnly={false} {...handlers} />)
  await screen.findByRole('radio', { name: 'Неработно време' })
}

function chooseKind(name: string) {
  fireEvent.click(screen.getByRole('radio', { name }))
}

function addPeriod(start: string, end: string) {
  fireEvent.click(screen.getByRole('button', { name: '+ Добави' }))
  const dialog = screen.getByRole('dialog')
  fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: start } })
  fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: end } })
  fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
}

beforeEach(() => {
  mockedStaff.mockReset()
  mockedCreate.mockReset()
  handlers.onAuthenticationRequired.mockReset()
  handlers.onCreated.mockReset()
  handlers.onCancel.mockReset()
  mockedStaff.mockResolvedValue({
    staffMembers: [
      staff('staff-a', 'Анна Иванова'),
      staff('staff-b', 'Борис Петров', false),
      staff('staff-c', 'Вера Николова'),
    ],
    page: 0,
    size: 50,
    totalElements: 3,
  })
  mockedCreate.mockResolvedValue(created())
})

describe('progressive creation form', () => {
  it('starts with only the choice of kind and shows no errors before interaction', async () => {
    await ready()

    for (const name of [
      'Неработно време',
      'Отсъствие',
      'Променени работни часове',
      'Допълнителни работни часове',
    ]) {
      expect(screen.getByRole('radio', { name })).not.toBeChecked()
    }
    expect(screen.queryByLabelText('Дата')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('От')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Член на екипа')).not.toBeInTheDocument()
    expect(document.querySelector('.field-error')).toBeNull()
  })

  it('business closure: whole dates show a range, part of a day shows one date and periods', async () => {
    await ready()
    chooseKind('Неработно време')

    expect(screen.queryByLabelText('Член на екипа')).not.toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Цели дни' })).toBeChecked()
    expect(screen.getByLabelText('От')).toBeInTheDocument()
    expect(screen.getByLabelText('До')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '+ Добави' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('radio', { name: 'Част от деня' }))
    expect(screen.getByLabelText('Дата')).toBeInTheDocument()
    expect(screen.queryByLabelText('До')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '+ Добави' })).toBeInTheDocument()
  })

  it('time off: an active StaffMember is required and inactive ones are not selectable', async () => {
    await ready()
    chooseKind('Отсъствие')

    const select = screen.getByLabelText('Член на екипа')
    const options = within(select).getAllByRole('option').map((option) => option.textContent)
    expect(options).toEqual(['Изберете член на екипа', 'Анна Иванова', 'Вера Николова'])
    expect(options).not.toContain('Борис Петров')
    expect(screen.getByRole('radio', { name: 'Цели дни' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Част от деня' })).toBeInTheDocument()
  })

  it('working-day override: explains replacement and offers "Неработен ден" or working hours on one date', async () => {
    await ready()
    chooseKind('Променени работни часове')

    expect(screen.getByLabelText('Член на екипа')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Неработен ден' })).toBeChecked()
    expect(
      screen.getByText('Заменя обичайните седмични часове за избраната дата.'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Дата')).toBeInTheDocument()
    expect(screen.queryByLabelText('До')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '+ Добави' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('radio', { name: 'Работни часове' }))
    expect(screen.getByRole('button', { name: '+ Добави' })).toBeInTheDocument()
  })

  it('additional periods: explains addition, has no mode choice and always needs periods', async () => {
    await ready()
    chooseKind('Допълнителни работни часове')

    expect(
      screen.getByText('Добавя работни часове за избраната дата.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: 'Цели дни' })).not.toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: 'Неработен ден' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Член на екипа')).toBeInTheDocument()
    expect(screen.getByLabelText('Дата')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '+ Добави' })).toBeInTheDocument()
  })

  it('loads every StaffMember page so the selector is not limited to the first 50', async () => {
    mockedStaff.mockImplementation((page = 0) =>
      Promise.resolve({
        staffMembers:
          page === 0
            ? Array.from({ length: 50 }, (_, index) => staff(`s-${index}`, `Член ${index}`))
            : [staff('late', 'Последна Иванова')],
        page,
        size: 50,
        totalElements: 51,
      }),
    )
    await ready()
    chooseKind('Отсъствие')

    expect(
      within(screen.getByLabelText('Член на екипа')).getByRole('option', { name: 'Последна Иванова' }),
    ).toBeInTheDocument()
    expect(mockedStaff).toHaveBeenCalledTimes(2)
  })
})

describe('grouped kinds and shared controls', () => {
  it('groups the four kinds in one fieldset with two labelled groups and no nested fieldset', async () => {
    await ready()

    const fieldsets = document.querySelectorAll('fieldset')
    const kindFieldset = screen.getByRole('group', { name: 'Вид промяна' })
    expect(kindFieldset.tagName).toBe('FIELDSET')
    expect(kindFieldset.querySelectorAll('fieldset')).toHaveLength(0)
    expect(fieldsets.length).toBeGreaterThanOrEqual(1)

    const business = within(kindFieldset).getByRole('group', { name: 'За целия бизнес' })
    const team = within(kindFieldset).getByRole('group', { name: 'За член на екипа' })
    expect(within(business).getAllByRole('radio').map((radio) => radio.getAttribute('value'))).toEqual([
      'BUSINESS_CLOSURE',
    ])
    expect(within(team).getAllByRole('radio').map((radio) => radio.getAttribute('value'))).toEqual([
      'STAFF_TIME_OFF',
      'WORKING_DAY_OVERRIDE',
      'ADDITIONAL_WORKING_PERIODS',
    ])
    expect(within(business).getByRole('radio', { name: 'Неработно време' })).toBeInTheDocument()
    expect(within(team).getByRole('radio', { name: 'Отсъствие' })).toBeInTheDocument()
    expect(within(team).getByRole('radio', { name: 'Променени работни часове' })).toBeInTheDocument()
    expect(
      within(team).getByRole('radio', { name: 'Допълнителни работни часове' }),
    ).toBeInTheDocument()
  })

  it.each([
    ['Неработно време', ['Блокира резервациите за всички членове на екипа през избрания период.']],
    ['Отсъствие', ['Блокира резервациите за избрания член на екипа през избрания период.']],
    [
      'Променени работни часове',
      [
        'Заменя обичайните седмични часове за избраната дата.',
        'Неработното време и отсъствията продължават да блокират резервациите.',
      ],
    ],
    [
      'Допълнителни работни часове',
      [
        'Добавя работни часове за избраната дата.',
        'Неработното време и отсъствията продължават да блокират резервациите.',
      ],
    ],
  ])('explains %s, one sentence per paragraph', async (label, sentences) => {
    await ready()
    chooseKind(label)
    const paragraphs = [...document.querySelectorAll('.exception-hints > p')].map(
      (line) => line.textContent,
    )
    expect(paragraphs).toEqual(sentences)
  })

  it.each(['Отсъствие', 'Променени работни часове', 'Допълнителни работни часове'])(
    'requires a StaffMember for %s and offers only active ones',
    async (label) => {
      await ready()
      chooseKind(label)
      const select = screen.getByLabelText('Член на екипа')
      expect(
        within(select).getAllByRole('option').map((option) => option.textContent),
      ).toEqual(['Изберете член на екипа', 'Анна Иванова', 'Вера Николова'])
    },
  )

  it('does not ask for a StaffMember for the whole-business kind', async () => {
    await ready()
    chooseKind('Неработно време')
    expect(screen.queryByLabelText('Член на екипа')).not.toBeInTheDocument()
  })

  it('uses "Период" with "От" and "До" for a range and only "Дата" for one date', async () => {
    await ready()
    chooseKind('Неработно време')
    const group = screen.getByRole('group', { name: 'Период' })
    expect(screen.queryByText('Период')).not.toBeInTheDocument()
    expect(within(group).getByLabelText('От')).toBeInTheDocument()
    expect(within(group).getByLabelText('До')).toBeInTheDocument()
    expect(screen.queryByText(/Първа дата|Последна дата/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('radio', { name: 'Част от деня' }))
    expect(screen.queryByRole('group', { name: 'Период' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Дата')).toBeInTheDocument()
    expect(screen.queryByLabelText('До')).not.toBeInTheDocument()

    chooseKind('Отсъствие')
    fireEvent.click(screen.getByRole('radio', { name: 'Цели дни' }))
    expect(screen.getByRole('group', { name: 'Период' })).toBeInTheDocument()
  })

  it('shows every period on its own row, sorted, with a "+ Добави" action', async () => {
    await ready()
    chooseKind('Допълнителни работни часове')
    expect(screen.getByRole('button', { name: '+ Добави' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /период$/ })).not.toBeInTheDocument()
    addPeriod('15:00', '16:00')
    addPeriod('08:00', '09:30')
    addPeriod('12:00', '13:00')

    const rows = [...document.querySelectorAll('.exception-periods > .schedule-period-chip')]
    expect(rows).toHaveLength(3)
    expect(rows.map((row) => row.textContent?.match(/\d\d:\d\d–\d\d:\d\d/)?.[0])).toEqual([
      '08:00–09:30',
      '12:00–13:00',
      '15:00–16:00',
    ])
    expect(
      screen.getByRole('button', { name: /Редактирай периода 08:00–09:30/ }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Премахни периода 12:00–13:00' })).toBeInTheDocument()
  })

  it('shows none of the retired terminology', async () => {
    await ready()
    chooseKind('Неработно време')
    const page = document.body.textContent ?? ''
    for (const old of [
      'Затваряне на бизнеса',
      'Промяна на работния ден',
      'Нова промяна по дата',
      'Обратно към промените',
      'Добави промяната',
      'Първа дата',
      'Последна дата',
      '+ Добави период',
    ]) {
      expect(page).not.toContain(old)
    }
    expect(screen.getByRole('button', { name: 'Добави' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Обратно към графика' })).toBeInTheDocument()
  })
})

describe('creating every kind', () => {
  it('creates a whole-date business closure without periods or StaffMember', async () => {
    await ready()
    chooseKind('Неработно време')
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-12-24' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-12-26' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    await waitFor(() =>
      expect(mockedCreate).toHaveBeenCalledWith({
        kind: 'BUSINESS_CLOSURE',
        firstDate: '2026-12-24',
        lastDate: '2026-12-26',
        allDay: true,
        periods: [],
      }),
    )
    await waitFor(() => expect(handlers.onCreated).toHaveBeenCalledWith('created-1'))
  })

  it('creates a part-of-day closure on one date with sorted periods', async () => {
    await ready()
    chooseKind('Неработно време')
    fireEvent.click(screen.getByRole('radio', { name: 'Част от деня' }))
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-12-24' } })
    addPeriod('14:00', '16:00')
    addPeriod('09:00', '10:30')
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    await waitFor(() =>
      expect(mockedCreate).toHaveBeenCalledWith({
        kind: 'BUSINESS_CLOSURE',
        firstDate: '2026-12-24',
        lastDate: '2026-12-24',
        allDay: false,
        periods: [
          { startTime: '09:00', endTime: '10:30' },
          { startTime: '14:00', endTime: '16:00' },
        ],
      }),
    )
  })

  it('creates whole-date time off for the chosen StaffMember', async () => {
    await ready()
    chooseKind('Отсъствие')
    fireEvent.change(screen.getByLabelText('Член на екипа'), { target: { value: 'staff-c' } })
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-11-02' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-06' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    await waitFor(() =>
      expect(mockedCreate).toHaveBeenCalledWith({
        kind: 'STAFF_TIME_OFF',
        staffMemberId: 'staff-c',
        firstDate: '2026-11-02',
        lastDate: '2026-11-06',
        allDay: true,
        periods: [],
      }),
    )
  })

  it('creates a zero-period working-day override for "Неработен ден"', async () => {
    await ready()
    chooseKind('Променени работни часове')
    fireEvent.change(screen.getByLabelText('Член на екипа'), { target: { value: 'staff-a' } })
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-11' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    await waitFor(() =>
      expect(mockedCreate).toHaveBeenCalledWith({
        kind: 'WORKING_DAY_OVERRIDE',
        staffMemberId: 'staff-a',
        firstDate: '2026-11-11',
        lastDate: '2026-11-11',
        allDay: false,
        periods: [],
      }),
    )
  })

  it('creates a working-day override with hours', async () => {
    await ready()
    chooseKind('Променени работни часове')
    fireEvent.change(screen.getByLabelText('Член на екипа'), { target: { value: 'staff-a' } })
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-11' } })
    fireEvent.click(screen.getByRole('radio', { name: 'Работни часове' }))
    addPeriod('10:00', '15:00')
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    await waitFor(() =>
      expect(mockedCreate).toHaveBeenCalledWith({
        kind: 'WORKING_DAY_OVERRIDE',
        staffMemberId: 'staff-a',
        firstDate: '2026-11-11',
        lastDate: '2026-11-11',
        allDay: false,
        periods: [{ startTime: '10:00', endTime: '15:00' }],
      }),
    )
  })

  it('creates additional working periods', async () => {
    await ready()
    chooseKind('Допълнителни работни часове')
    fireEvent.change(screen.getByLabelText('Член на екипа'), { target: { value: 'staff-a' } })
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-14' } })
    addPeriod('09:00', '12:00')
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    await waitFor(() =>
      expect(mockedCreate).toHaveBeenCalledWith({
        kind: 'ADDITIONAL_WORKING_PERIODS',
        staffMemberId: 'staff-a',
        firstDate: '2026-11-14',
        lastDate: '2026-11-14',
        allDay: false,
        periods: [{ startTime: '09:00', endTime: '12:00' }],
      }),
    )
  })

  it('does not send a hidden last date or a stale StaffMember after the kind changes', async () => {
    await ready()
    chooseKind('Отсъствие')
    fireEvent.change(screen.getByLabelText('Член на екипа'), { target: { value: 'staff-a' } })
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-11-02' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-06' } })
    chooseKind('Неработно време')
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    await waitFor(() => expect(mockedCreate).toHaveBeenCalledTimes(1))
    expect(mockedCreate.mock.calls[0]![0]).not.toHaveProperty('staffMemberId')
  })
})

describe('period dialog', () => {
  async function openDialog() {
    await ready()
    chooseKind('Допълнителни работни часове')
    fireEvent.click(screen.getByRole('button', { name: '+ Добави' }))
    return screen.getByRole('dialog')
  }

  it('moves focus to the start time and labels the dialog by its operation', async () => {
    const dialog = await openDialog()

    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleName('Добавяне на период')
    expect(within(dialog).getByLabelText('Начален час')).toHaveFocus()
  })

  it('requires both times and focuses the first missing one', async () => {
    const dialog = await openDialog()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
    expect(within(dialog).getByText('Въведете начален час.')).toBeInTheDocument()
    expect(within(dialog).getByText('Въведете краен час.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Начален час')).toHaveFocus()

    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
    expect(within(dialog).getByLabelText('Краен час')).toHaveFocus()
    expect(within(dialog).getByLabelText('Краен час')).toHaveAccessibleDescription(
      'Въведете краен час.',
    )
  })

  it('rejects a reversed and a zero-length period', async () => {
    const dialog = await openDialog()
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '12:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '09:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
    expect(
      within(dialog).getByText('Началният час трябва да бъде преди крайния.'),
    ).toBeInTheDocument()

    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '12:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
    expect(
      within(dialog).getByText('Началният час трябва да бъде преди крайния.'),
    ).toBeInTheDocument()
  })

  it('rejects overlapping and duplicate periods but accepts adjacent ones', async () => {
    await ready()
    chooseKind('Допълнителни работни часове')
    addPeriod('09:00', '12:00')
    addPeriod('12:00', '14:00')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText('09:00–12:00')).toBeInTheDocument()
    expect(screen.getByText('12:00–14:00')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '11:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '13:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
    // The overlap names both the proposed and the existing hours.
    const overlap = within(dialog).getByText(
      'Периодът 11:00–13:00 се застъпва със съществуващия период 09:00–12:00.',
    )
    expect(within(dialog).getByLabelText('Начален час')).toHaveAttribute('aria-invalid', 'true')
    expect(within(dialog).getByLabelText('Начален час')).toHaveAccessibleDescription(overlap.textContent!)
    expect(within(dialog).getByLabelText('Начален час')).toHaveFocus()
    expect(within(dialog).getByLabelText('Начален час')).toHaveValue('11:00')

    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '12:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
    // An exact duplicate names the repeated hours.
    expect(within(dialog).getByText('Периодът 09:00–12:00 вече е добавен.')).toBeInTheDocument()
    expect(within(dialog).queryByText(/се застъпва/)).not.toBeInTheDocument()

    // Correcting the value clears the message at once.
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '14:00' } })
    expect(within(dialog).queryByText('Периодът 09:00–12:00 вече е добавен.')).not.toBeInTheDocument()
    expect(within(dialog).getByLabelText('Начален час')).not.toHaveAttribute('aria-invalid')

    fireEvent.click(within(dialog).getByRole('button', { name: 'Отказ' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('lists periods earliest first immediately and edits or removes them', async () => {
    await ready()
    chooseKind('Допълнителни работни часове')
    addPeriod('15:00', '16:00')
    addPeriod('08:00', '09:00')

    const chips = [...document.querySelectorAll('.schedule-period-chip-label')]
    expect(chips).toHaveLength(2)
    expect(chips[0]).toHaveTextContent('08:00–09:00')
    expect(chips[1]).toHaveTextContent('15:00–16:00')

    fireEvent.click(screen.getByRole('button', { name: /Редактирай периода 15:00–16:00/ }))
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAccessibleName('Редактиране на период')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Запази' }))
    const edited = [...document.querySelectorAll('.schedule-period-chip-label')]
    expect(edited).toHaveLength(2)
    expect(edited[0]).toHaveTextContent('08:00–09:00')
    expect(edited[1]).toHaveTextContent('09:00–16:00')

    fireEvent.click(screen.getByRole('button', { name: 'Премахни периода 09:00–16:00' }))
    expect(screen.queryByRole('button', { name: 'Премахни периода 09:00–16:00' })).not.toBeInTheDocument()
  })

  it('ignores Escape while a typed value is unsaved and closes on Escape when untouched', async () => {
    const dialog = await openDialog()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '+ Добави' }))
    const typed = screen.getByRole('dialog')
    fireEvent.change(within(typed).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.keyDown(typed, { key: 'Escape' })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('returns focus to the control that opened the dialog', async () => {
    await ready()
    chooseKind('Допълнителни работни часове')
    const add = screen.getByRole('button', { name: '+ Добави' })
    add.focus()
    fireEvent.click(add)
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Отказ' }))
    expect(add).toHaveFocus()
  })

  it('does not offer more than 24 periods', async () => {
    await ready()
    chooseKind('Допълнителни работни часове')
    for (let index = 0; index < 24; index += 1) {
      const hour = String(Math.floor(index / 2)).padStart(2, '0')
      const half = index % 2 === 1
      addPeriod(`${hour}:${half ? '30' : '00'}`, `${hour}:${half ? '59' : '29'}`)
    }
    expect(screen.getByText('Може да добавите най-много 24 периода.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '13:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '13:10' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
    expect(within(dialog).getByText('Може да добавите най-много 24 периода.')).toBeInTheDocument()
  })
})

describe('inline validation policy', () => {
  it('shows no error for an untouched required field, then every error on submit and focuses the first', async () => {
    await ready()
    chooseKind('Отсъствие')
    expect(document.querySelector('.field-error')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    const staffSelect = screen.getByLabelText('Член на екипа')
    expect(screen.getByText('Изберете член на екипа.')).toBeInTheDocument()
    expect(screen.getByText('Въведете начална дата.')).toBeInTheDocument()
    expect(screen.getByText('Въведете крайна дата.')).toBeInTheDocument()
    expect(staffSelect).toHaveFocus()
    expect(staffSelect).toHaveAttribute('aria-invalid', 'true')
    expect(staffSelect).toHaveAccessibleDescription('Изберете член на екипа.')
    expect(mockedCreate).not.toHaveBeenCalled()
  })

  it('asks for a kind on submit and focuses the first choice', async () => {
    await ready()

    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    expect(screen.getByText('Изберете вид промяна.')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Неработно време' })).toHaveFocus()
    expect(mockedCreate).not.toHaveBeenCalled()
  })

  it('requires at least one period and shows the message under the periods', async () => {
    await ready()
    chooseKind('Допълнителни работни часове')
    fireEvent.change(screen.getByLabelText('Член на екипа'), { target: { value: 'staff-a' } })
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-14' } })

    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    const add = screen.getByRole('button', { name: '+ Добави' })
    expect(screen.getByText('Добавете поне един период.')).toBeInTheDocument()
    expect(add).toHaveFocus()
    expect(add).toHaveAccessibleDescription('Добавете поне един период.')
    expect(mockedCreate).not.toHaveBeenCalled()
  })

  it('rejects a last date before the first and clears the error as soon as it is valid', async () => {
    await ready()
    chooseKind('Неработно време')
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-11-10' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-01' } })

    expect(screen.getByText('Крайната дата не може да бъде преди началната.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-10' } })
    expect(
      screen.queryByText('Крайната дата не може да бъде преди началната.'),
    ).not.toBeInTheDocument()
  })

  it('rejects a whole-date span over 366 dates', async () => {
    await ready()
    chooseKind('Неработно време')
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-01-01' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2027-01-02' } })
    expect(screen.getByText(/най-много 366 дни/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2027-01-01' } })
    expect(screen.queryByText(/най-много 366 дни/)).not.toBeInTheDocument()
  })

  it('validates a touched date after blur', async () => {
    await ready()
    chooseKind('Променени работни часове')
    const date = screen.getByLabelText('Дата')
    fireEvent.blur(date)
    expect(screen.getByText('Въведете дата.')).toBeInTheDocument()
    fireEvent.change(date, { target: { value: '2026-11-11' } })
    expect(screen.queryByText('Въведете дата.')).not.toBeInTheDocument()
  })
})

describe('failure handling and duplicate protection', () => {
  async function fillClosure() {
    await ready()
    chooseKind('Неработно време')
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-12-24' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-12-24' } })
  }

  it('maps backend field errors under their fields, focuses the first and shows no generic alert', async () => {
    mockedCreate.mockRejectedValue(
      new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.', {
        lastDate: 'Server text that is never shown.',
        unknown: 'Скрито',
      }),
    )
    await fillClosure()
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    expect(
      await screen.findByText('Крайната дата не може да е преди началната и периодът е до 366 дни.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('Server text that is never shown.')).not.toBeInTheDocument()
    expect(screen.getByLabelText('До')).toHaveFocus()
    expect(screen.getByLabelText('До')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText('Скрито')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-12-25' } })
    expect(
      screen.queryByText('Крайната дата не може да е преди началната и периодът е до 366 дни.'),
    ).not.toBeInTheDocument()
  })

  it.each([
    ['Неработно време', ['Избраният период се застъпва с вече добавено неработно време.']],
    [
      'Отсъствие',
      ['Избраният период се застъпва с вече добавено отсъствие за този член на екипа.'],
    ],
    [
      'Променени работни часове',
      [
        'За тази дата вече има променени работни часове за избрания член на екипа.',
        'Редактирайте съществуващия запис, за да промените периодите.',
      ],
    ],
    [
      'Допълнителни работни часове',
      [
        'За тази дата вече има допълнителни работни часове за избрания член на екипа.',
        'Редактирайте съществуващия запис, за да добавите или промените периодите.',
      ],
    ],
  ])('maps the same-kind conflict for %s to its own message, never the internal term', async (label, lines) => {
    mockedCreate.mockRejectedValue(
      new ApiError(409, 'SCHEDULE_EXCEPTION_OVERLAP', 'Вече има изключение от същия вид за тези дати.'),
    )
    await ready()
    chooseKind(label)
    if (label !== 'Неработно време') {
      fireEvent.change(screen.getByLabelText('Член на екипа'), { target: { value: 'staff-a' } })
    }
    if (label === 'Допълнителни работни часове') {
      fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-14' } })
      addPeriod('09:00', '12:00')
    } else if (label === 'Променени работни часове') {
      fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-11-14' } })
    } else {
      fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-11-14' } })
      fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-15' } })
    }
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    const alert = await screen.findByRole('alert')
    expect([...alert.querySelectorAll('p')].map((line) => line.textContent)).toEqual(lines)
    expect(document.body.textContent?.toLowerCase()).not.toContain('изключен')
    expect(screen.queryByText('Проверете въведените данни.')).not.toBeInTheDocument()
    expect(alert).toHaveFocus()
  })

  it('keeps entered values and shows a safe alert for a conflict without a field', async () => {
    mockedCreate.mockRejectedValue(
      new ApiError(
        409,
        'SCHEDULE_EXCEPTION_OVERLAP',
        'Вече има изключение от същия вид за тези дати.',
      ),
    )
    await fillClosure()
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Избраният период се застъпва с вече добавено неработно време.')
    expect(alert).not.toHaveTextContent('изключение')
    expect(alert).toHaveFocus()
    expect(screen.getByLabelText('От')).toHaveValue('2026-12-24')
    expect(handlers.onCreated).not.toHaveBeenCalled()
  })

  it('hides unexpected error text behind a safe message', async () => {
    mockedCreate.mockRejectedValue(new Error('SQL blew up: constraint x'))
    await fillClosure()
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Промяната не може да бъде добавена.')
    expect(screen.queryByText(/SQL/)).not.toBeInTheDocument()
  })

  it('reports an authentication failure without creating anything', async () => {
    mockedCreate.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    await fillClosure()
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

    await waitFor(() =>
      expect(handlers.onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'),
    )
  })

  it('sends only one request for repeated submits while one is in flight', async () => {
    let resolve!: (value: ScheduleExceptionDetails) => void
    mockedCreate.mockImplementation(() => new Promise((done) => (resolve = done)))
    await fillClosure()
    const submit = screen.getByRole('button', { name: 'Добави' })
    fireEvent.click(submit)
    fireEvent.click(submit)
    fireEvent.submit(submit.closest('form')!)

    expect(mockedCreate).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Запазване…' })).toBeDisabled()
    await act(async () => resolve(created()))
    expect(handlers.onCreated).toHaveBeenCalledTimes(1)
  })

  it('ignores a response that arrives after the form unmounted', async () => {
    let resolve!: (value: ScheduleExceptionDetails) => void
    mockedCreate.mockImplementation(() => new Promise((done) => (resolve = done)))
    const { unmount } = render(<ScheduleExceptionCreate readOnly={false} {...handlers} />)
    await screen.findByRole('radio', { name: 'Неработно време' })
    chooseKind('Неработно време')
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-12-24' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-12-24' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добави' }))
    unmount()

    await act(async () => resolve(created()))
    expect(handlers.onCreated).not.toHaveBeenCalled()
  })
})

describe('unsaved changes and loading', () => {
  it('asks before cancelling a dirty form and continues editing without losing values', async () => {
    await ready()
    chooseKind('Неработно време')
    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-12-24' } })

    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Остани' })).toHaveFocus()
    expect(handlers.onCancel).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(screen.getByLabelText('От')).toHaveValue('2026-12-24')

    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))
    expect(handlers.onCancel).toHaveBeenCalledTimes(1)
  })

  it('cancels immediately when nothing was entered', async () => {
    await ready()
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(handlers.onCancel).toHaveBeenCalledTimes(1)
  })

  it('treats typed text in an open period dialog as unsaved', async () => {
    await ready()
    chooseKind('Допълнителни работни часове')
    fireEvent.click(screen.getByRole('button', { name: '+ Добави' }))
    fireEvent.change(within(screen.getByRole('dialog')).getByLabelText('Начален час'), {
      target: { value: '09:00' },
    })

    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))

    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
  })

  it('shows a retryable error when the team cannot be loaded', async () => {
    mockedStaff.mockRejectedValueOnce(new Error('boom'))
    render(<ScheduleExceptionCreate readOnly={false} {...handlers} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Екипът не може да бъде зареден.')
    fireEvent.click(screen.getByRole('button', { name: 'Опитай отново' }))
    expect(await screen.findByRole('radio', { name: 'Отсъствие' })).toBeInTheDocument()
  })

  it('renders no form and loads nothing for a SUSPENDED Business', async () => {
    render(<ScheduleExceptionCreate readOnly {...handlers} />)

    expect(screen.getByText('Нови промени не могат да бъдат добавяни.')).toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    expect(mockedStaff).not.toHaveBeenCalled()
  })

  it('aborts the team request when it unmounts', async () => {
    let signal: AbortSignal | undefined
    mockedStaff.mockImplementation((_page, _size, _sort, _direction, abort) => {
      signal = abort
      return new Promise(() => undefined)
    })
    const { unmount } = render(<ScheduleExceptionCreate readOnly={false} {...handlers} />)
    await waitFor(() => expect(signal).toBeDefined())
    unmount()
    expect(signal!.aborted).toBe(true)
  })
})
