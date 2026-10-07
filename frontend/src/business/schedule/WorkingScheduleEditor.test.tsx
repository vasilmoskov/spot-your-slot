import '@testing-library/jest-dom/vitest'
import { fireEvent, render as rtlRender, screen, waitFor, within } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import type { ReactElement } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { UnsavedChangesGuardProvider, useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import { Button } from '../../ui/Button'
import { getWorkingSchedule, replaceWorkingSchedule, type WorkingSchedule } from './api'
import { WorkingScheduleEditor } from './WorkingScheduleEditor'

function GuardProbe() {
  const guard = useUnsavedChangesGuard()
  return (
    <Button type="button" onClick={() => guard.guard(() => undefined)}>
      Пробна навигация
    </Button>
  )
}

function render(ui: ReactElement) {
  return rtlRender(
    <UnsavedChangesGuardProvider>
      {ui}
      <GuardProbe />
    </UnsavedChangesGuardProvider>,
  )
}

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getWorkingSchedule: vi.fn(),
  replaceWorkingSchedule: vi.fn(),
}))

const mockedGetWorkingSchedule = vi.mocked(getWorkingSchedule)
const mockedReplaceWorkingSchedule = vi.mocked(replaceWorkingSchedule)

const emptySchedule: WorkingSchedule = {
  staffMemberId: 'staff-a',
  timezone: 'Europe/Sofia',
  periods: [],
  version: 0,
  createdAt: '2026-08-19T09:00:00Z',
  updatedAt: '2026-08-19T09:00:00Z',
}

const splitSchedule: WorkingSchedule = {
  staffMemberId: 'staff-a',
  timezone: 'Europe/Sofia',
  periods: [
    { weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' },
    { weekday: 'MONDAY', startTime: '14:00', endTime: '18:00' },
    { weekday: 'WEDNESDAY', startTime: '10:00', endTime: '16:00' },
  ],
  version: 2,
  createdAt: '2026-08-19T09:00:00Z',
  updatedAt: '2026-08-19T09:00:00Z',
}

async function renderEditing(schedule: WorkingSchedule) {
  mockedGetWorkingSchedule.mockResolvedValue(schedule)
  render(
    <WorkingScheduleEditor
      staffMemberId="staff-a"
      staffMemberActive
      readOnly={false}
      onAuthenticationRequired={vi.fn()}
    />,
  )
  await screen.findByRole('heading', { name: 'Понеделник' })
  fireEvent.click(screen.getByRole('button', { name: 'Редактирай графика' }))
}

function daySection(name: string): HTMLElement {
  return screen.getByRole('heading', { name }).closest('section') as HTMLElement
}

describe('WorkingScheduleEditor — layout and read-only display', () => {
  const onAuthenticationRequired = vi.fn()

  beforeEach(() => {
    mockedGetWorkingSchedule.mockReset()
    mockedReplaceWorkingSchedule.mockReset()
    onAuthenticationRequired.mockReset()
  })

  it('renders all seven weekdays in Monday-to-Sunday order inside one weekly grid', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(emptySchedule)
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    const grid = document.querySelector('.schedule-grid') as HTMLElement
    expect(grid).toBeInTheDocument()
    const headings = within(grid).getAllByRole('heading', { level: 3 })
    expect(headings.map((heading) => heading.textContent)).toEqual([
      'Понеделник',
      'Вторник',
      'Сряда',
      'Четвъртък',
      'Петък',
      'Събота',
      'Неделя',
    ])
    // Every weekday is a semantically distinct section inside the single grid
    // container — the same markup collapses to stacked cards at mobile width
    // purely through CSS, not a separate DOM structure.
    expect(grid.querySelectorAll('section.schedule-day')).toHaveLength(7)
  })

  it('shows "Почивен ден" for an empty weekday and compact chips for a split day, in chronological order', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(splitSchedule)
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    const monday = daySection('Понеделник')
    const chips = within(monday).getAllByText(/\d\d:\d\d\u2013\d\d:\d\d/)
    expect(chips.map((chip) => chip.textContent)).toEqual(['09:00\u201312:00', '14:00\u201318:00'])

    const tuesday = daySection('Вторник')
    expect(within(tuesday).getByText('Почивен ден')).toBeInTheDocument()

    // No permanent time inputs anywhere in read-only mode.
    expect(document.querySelectorAll('input[type="time"]')).toHaveLength(0)
  })

  it('does not show a StaffMember status badge or the raw timezone text', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(splitSchedule)
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    expect(screen.queryByText('Активен')).not.toBeInTheDocument()
    expect(screen.queryByText('Неактивен')).not.toBeInTheDocument()
    expect(screen.queryByText(/Часова зона на бизнеса/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Europe\/Sofia/)).not.toBeInTheDocument()
  })

  it('shows the corrected read-only wording for an inactive StaffMember, with no edit control', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(emptySchedule)
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive={false}
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    expect(
      screen.getByText('Работният график на неактивен член на екипа може само да бъде преглеждан.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('Неактивен член на екипа не може да получи работен график.'),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Редактирай графика' })).not.toBeInTheDocument()
  })

  it('shows no schedule-specific notice for a SUSPENDED Business (the shared shell banner covers it)', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(emptySchedule)
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly
        onAuthenticationRequired={onAuthenticationRequired}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    expect(screen.queryByText(/временно спрян/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Редактирай графика' })).not.toBeInTheDocument()
  })

  it('still loads and displays the schedule for an inactive StaffMember (read-only)', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(splitSchedule)
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive={false}
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
      />,
    )
    expect(mockedGetWorkingSchedule).toHaveBeenCalled()
    expect(await screen.findByText('09:00\u201312:00')).toBeInTheDocument()
  })
})

describe('WorkingScheduleEditor — add/edit period dialog', () => {
  beforeEach(() => {
    mockedGetWorkingSchedule.mockReset()
    mockedReplaceWorkingSchedule.mockReset()
  })

  it('opens the Add dialog for the correct weekday, focuses the start field, and adds a valid period', async () => {
    await renderEditing(emptySchedule)
    const wednesday = daySection('Сряда')
    fireEvent.click(within(wednesday).getByRole('button', { name: '+ Добави' }))

    const dialog = screen.getByRole('dialog', { name: 'Добавяне на работно време за сряда' })
    expect(within(dialog).getByLabelText('Начален час')).toHaveFocus()

    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '12:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(within(daySection('Сряда')).getByText('09:00\u201312:00')).toBeInTheDocument()
  })

  it('opens the Edit dialog pre-populated for an existing period and saves the change', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: /Редактирай периода 09:00–12:00/ }))

    const dialog = screen.getByRole('dialog', { name: 'Редактиране на работно време за понеделник' })
    expect(within(dialog).getByLabelText('Начален час')).toHaveValue('09:00')
    expect(within(dialog).getByLabelText('Краен час')).toHaveValue('12:00')

    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '08:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Запази' }))

    expect(within(daySection('Понеделник')).getByText('08:00\u201312:00')).toBeInTheDocument()
  })

  it('removes a single period immediately, with no confirmation', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: /Премахни периода 09:00–12:00/ }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(within(monday).queryByText('09:00\u201312:00')).not.toBeInTheDocument()
    expect(within(monday).getByText('14:00\u201318:00')).toBeInTheDocument()
  })

  it('rejects a required-field validation failure, keeps the dialog open, and preserves entered values', async () => {
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    const end = within(dialog).getByLabelText('Краен час')
    expect(within(dialog).getByText('Въведете краен час.')).toBeInTheDocument()
    expect(end).toHaveAttribute('aria-invalid', 'true')
    expect(end).toHaveAttribute('aria-describedby', 'period-dialog-end-error')
    expect(end).toHaveFocus()
    expect(within(dialog).getByLabelText('Начален час')).not.toHaveAttribute('aria-invalid')
    expect(within(dialog).getByLabelText('Начален час')).toHaveValue('09:00')
  })

  it('names each missing time next to its own field and focuses the first one', async () => {
    await renderEditing(emptySchedule)
    fireEvent.click(within(daySection('Понеделник')).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    const start = within(dialog).getByLabelText('Начален час')
    const end = within(dialog).getByLabelText('Краен час')
    expect(within(dialog).getByText('Въведете начален час.')).toBeInTheDocument()
    expect(within(dialog).getByText('Въведете краен час.')).toBeInTheDocument()
    expect(start).toHaveAttribute('aria-describedby', 'period-dialog-start-error')
    expect(end).toHaveAttribute('aria-describedby', 'period-dialog-end-error')
    expect(start).toHaveFocus()

    fireEvent.change(start, { target: { value: '09:00' } })
    expect(within(dialog).queryByText('Въведете начален час.')).not.toBeInTheDocument()
    expect(start).not.toHaveAttribute('aria-invalid')
    expect(within(dialog).getByText('Въведете краен час.')).toBeInTheDocument()
  })

  it('ties a range error to both time fields and clears it when either is corrected', async () => {
    await renderEditing(emptySchedule)
    fireEvent.click(within(daySection('Понеделник')).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    const start = within(dialog).getByLabelText('Начален час')
    const end = within(dialog).getByLabelText('Краен час')
    fireEvent.change(start, { target: { value: '12:00' } })
    fireEvent.change(end, { target: { value: '09:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    expect(within(dialog).getByText('Началният час трябва да бъде преди крайния.')).toBeInTheDocument()
    expect(start).toHaveAttribute('aria-invalid', 'true')
    expect(end).toHaveAttribute('aria-invalid', 'true')
    expect(start).toHaveAttribute('aria-describedby', 'period-dialog-range-error')
    expect(end).toHaveAttribute('aria-describedby', 'period-dialog-range-error')
    expect(start).toHaveFocus()

    fireEvent.change(end, { target: { value: '13:00' } })
    expect(
      within(dialog).queryByText('Началният час трябва да бъде преди крайния.'),
    ).not.toBeInTheDocument()
    expect(start).not.toHaveAttribute('aria-invalid')
    expect(end).not.toHaveAttribute('aria-invalid')
  })

  it('rejects a reversed range without saving', async () => {
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '12:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '09:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    expect(within(dialog).getByText('Началният час трябва да бъде преди крайния.')).toBeInTheDocument()
    expect(mockedReplaceWorkingSchedule).not.toHaveBeenCalled()
  })

  it('rejects an overlapping period against an existing period on the same weekday', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '11:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '15:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    expect(
      within(dialog).getByText('Периодът се припокрива с друг период за същия ден.'),
    ).toBeInTheDocument()
  })

  it('accepts an adjacent period (start equal to an existing period end)', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '12:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '14:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(within(monday).getByText('12:00\u201314:00')).toBeInTheDocument()
  })

  it('closes on Escape only when the dialog is clean, and restores focus to the invoking control', async () => {
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')
    const addButton = within(monday).getByRole('button', { name: '+ Добави' })
    addButton.focus()
    fireEvent.click(addButton)
    const dialog = screen.getByRole('dialog')

    // Dirty: typed a value, Escape must not silently discard it.
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.keyDown(dialog.parentElement as HTMLElement, { key: 'Escape' })
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    // Clean again (reverted the typed value): Escape closes and restores focus.
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '' } })
    fireEvent.keyDown(dialog.parentElement as HTMLElement, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(addButton).toHaveFocus()
  })
})

describe('WorkingScheduleEditor — clear one weekday (inside editing)', () => {
  beforeEach(() => {
    mockedGetWorkingSchedule.mockReset()
    mockedReplaceWorkingSchedule.mockReset()
  })

  it('offers the day overflow menu only for a non-empty weekday, and the menu opens Clear day with the corrected wording, order, and focus', async () => {
    await renderEditing(splitSchedule)
    const tuesday = daySection('Вторник')
    expect(within(tuesday).queryByRole('button', { name: /Още действия/ })).not.toBeInTheDocument()

    const monday = daySection('Понеделник')
    const menuButton = within(monday).getByRole('button', { name: 'Още действия за понеделник' })
    fireEvent.click(menuButton)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Изчисти деня' }))

    const dialog = screen.getByRole('alertdialog', {
      name: 'Изчистване на графика за понеделник',
    })
    // Two separate sentences, each its own paragraph.
    expect(
      within(dialog).getByText('Всички работни часове за понеделник ще бъдат премахнати.'),
    ).toBeInTheDocument()
    expect(within(dialog).getByText('Сигурни ли сте, че искате да продължите?')).toBeInTheDocument()
    // The safe "Отказ" comes before the destructive action (action-order standard, UI guide
    // section 6), and the safe action receives initial focus.
    const buttons = within(dialog).getAllByRole('button')
    expect(buttons.map((button) => button.textContent)).toEqual(['Отказ', 'Изчисти графика за деня'])
    expect(within(dialog).getByRole('button', { name: 'Отказ' })).toHaveFocus()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Изчисти графика за деня' }))
    const clearedMonday = daySection('Понеделник')
    expect(within(clearedMonday).getByText('Почивен ден')).toBeInTheDocument()
    // The "⋯" trigger only renders while the weekday has periods, so once it
    // is cleared, focus moves to the weekday's always-present "+ Добави"
    // control instead of the now-unmounted trigger.
    expect(within(clearedMonday).getByRole('button', { name: '+ Добави' })).toHaveFocus()
  })

  it('cancelling (Отказ or Escape) leaves the day unchanged and restores focus', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    const menuButton = within(monday).getByRole('button', { name: 'Още действия за понеделник' })
    fireEvent.click(menuButton)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Изчисти деня' }))
    const dialog = screen.getByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Отказ' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(within(daySection('Понеделник')).getByText('09:00–12:00')).toBeInTheDocument()
    expect(menuButton).toHaveFocus()
  })
})

describe('WorkingScheduleEditor — day overflow menu lifecycle', () => {
  beforeEach(() => {
    mockedGetWorkingSchedule.mockReset()
    mockedReplaceWorkingSchedule.mockReset()
  })

  it('closes the day menu on Escape and restores focus to its "⋯" trigger', async () => {
    await renderEditing(splitSchedule)
    const menuButton = within(daySection('Понеделник')).getByRole('button', {
      name: 'Още действия за понеделник',
    })
    fireEvent.click(menuButton)
    expect(screen.getByRole('menu')).toBeInTheDocument()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(menuButton).toHaveFocus()
  })

  it('closes the day menu when clicking outside the trigger and menu', async () => {
    await renderEditing(splitSchedule)
    const menuButton = within(daySection('Понеделник')).getByRole('button', {
      name: 'Още действия за понеделник',
    })
    fireEvent.click(menuButton)
    expect(screen.getByRole('menu')).toBeInTheDocument()

    fireEvent.mouseDown(document.body)

    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('does not close the day menu when clicking inside it', async () => {
    await renderEditing(splitSchedule)
    const menuButton = within(daySection('Понеделник')).getByRole('button', {
      name: 'Още действия за понеделник',
    })
    fireEvent.click(menuButton)
    const menu = screen.getByRole('menu')

    fireEvent.mouseDown(menu)

    expect(screen.getByRole('menu')).toBeInTheDocument()
  })

  it('closes the day menu on scroll, so a `position: fixed` menu can never be left detached from its trigger', async () => {
    await renderEditing(splitSchedule)
    const menuButton = within(daySection('Понеделник')).getByRole('button', {
      name: 'Още действия за понеделник',
    })
    fireEvent.click(menuButton)
    expect(screen.getByRole('menu')).toBeInTheDocument()

    fireEvent.scroll(window)

    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })
})

describe('WorkingScheduleEditor — copy to weekdays', () => {
  beforeEach(() => {
    mockedGetWorkingSchedule.mockReset()
    mockedReplaceWorkingSchedule.mockReset()
  })

  it('copies a day to a single target weekday', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: 'Още действия за понеделник' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Копирай графика' }))

    const dialog = screen.getByRole('dialog', { name: 'Копиране на график от понеделник' })
    expect(within(dialog).queryByLabelText('Понеделник')).not.toBeInTheDocument()
    fireEvent.click(within(dialog).getByLabelText('Вторник'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Копирай' }))

    const tuesday = daySection('Вторник')
    expect(within(tuesday).getByText('09:00\u201312:00')).toBeInTheDocument()
    expect(within(tuesday).getByText('14:00\u201318:00')).toBeInTheDocument()
  })

  it('copies a day to multiple target weekdays at once', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: 'Още действия за понеделник' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Копирай графика' }))

    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByLabelText('Вторник'))
    fireEvent.click(within(dialog).getByLabelText('Четвъртък'))
    fireEvent.click(within(dialog).getByLabelText('Петък'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Копирай' }))

    for (const name of ['Вторник', 'Четвъртък', 'Петък']) {
      const section = daySection(name)
      expect(within(section).getByText('09:00\u201312:00')).toBeInTheDocument()
      expect(within(section).getByText('14:00\u201318:00')).toBeInTheDocument()
    }
  })

  it('discloses that a non-empty target will be replaced, and requires explicit confirmation', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: 'Още действия за понеделник' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Копирай графика' }))

    const dialog = screen.getByRole('dialog')
    const wednesdayOption = within(dialog).getByLabelText(/Сряда/)
    expect(wednesdayOption.closest('label')).toHaveTextContent('ще замени 1 период')

    fireEvent.click(wednesdayOption)
    expect(within(dialog).getByRole('button', { name: 'Копирай и замени' })).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Копирай и замени' }))

    const wednesday = daySection('Сряда')
    expect(within(wednesday).queryByText('10:00\u201316:00')).not.toBeInTheDocument()
    expect(within(wednesday).getByText('09:00\u201312:00')).toBeInTheDocument()
    expect(within(wednesday).getByText('14:00\u201318:00')).toBeInTheDocument()
  })

  it('leaves the draft unchanged when copy is cancelled', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: 'Още действия за понеделник' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Копирай графика' }))

    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByLabelText('Вторник'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Отказ' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(within(daySection('Вторник')).getByText('Почивен ден')).toBeInTheDocument()
  })

  it('does not offer the source weekday as a copy target', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: 'Още действия за понеделник' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Копирай графика' }))

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).queryByLabelText('Понеделник')).not.toBeInTheDocument()
    expect(within(dialog).getAllByRole('checkbox')).toHaveLength(6)
  })

  it('marks the form dirty after a copy, and includes the copied periods in the saved payload', async () => {
    mockedReplaceWorkingSchedule.mockResolvedValue({ ...splitSchedule, version: 3 })
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: 'Още действия за понеделник' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Копирай графика' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByLabelText('Вторник'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Копирай' }))

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))

    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await waitFor(() =>
      expect(mockedReplaceWorkingSchedule).toHaveBeenCalledWith(
        'staff-a',
        expect.objectContaining({
          periods: expect.arrayContaining([
            { weekday: 'TUESDAY', startTime: '09:00', endTime: '12:00' },
            { weekday: 'TUESDAY', startTime: '14:00', endTime: '18:00' },
          ]),
        }),
      ),
    )
  })
})

describe('WorkingScheduleEditor — page-level save/cancel and error handling', () => {
  beforeEach(() => {
    mockedGetWorkingSchedule.mockReset()
    mockedReplaceWorkingSchedule.mockReset()
  })

  it('exits editing immediately when Cancel is pressed with no changes', async () => {
    await renderEditing(emptySchedule)
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(screen.queryByRole('alertdialog', { name: 'Незапазени промени' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Запази промените' })).not.toBeInTheDocument()
  })

  it('asks for confirmation before discarding a dirty draft on Cancel, and restores the persisted schedule on discard', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '19:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '20:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))
    expect(screen.queryByRole('button', { name: 'Запази промените' })).not.toBeInTheDocument()
    expect(await screen.findByText('09:00\u201312:00')).toBeInTheDocument()
  })

  it('guards navigation away from a dirty draft and clears the guard after a successful save', async () => {
    mockedReplaceWorkingSchedule.mockResolvedValue({ ...emptySchedule, version: 1 })
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '12:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))

    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await waitFor(() => expect(mockedReplaceWorkingSchedule).toHaveBeenCalled())
    await screen.findByText('Работният график е запазен.')

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.queryByRole('alertdialog', { name: 'Незапазени промени' })).not.toBeInTheDocument()
  })

  it('preserves the user\u2019s edits after a failed save', async () => {
    mockedReplaceWorkingSchedule.mockRejectedValue(
      new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.'),
    )
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '12:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(await screen.findByText('Проверете въведените данни.')).toBeInTheDocument()
    expect(within(monday).getByText('09:00\u201312:00')).toBeInTheDocument()
  })

  it('shows a stale-version conflict with a reload action and does not silently overwrite edits', async () => {
    mockedGetWorkingSchedule.mockResolvedValueOnce(emptySchedule)
    mockedReplaceWorkingSchedule.mockRejectedValue(
      new ApiError(
        409,
        'WORKING_SCHEDULE_CONCURRENT_UPDATE',
        'Работният график е променен от друга операция. Обновете данните и опитайте отново.',
      ),
    )
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '12:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(
      await screen.findByText(
        'Работният график е променен от друга операция. Обновете данните и опитайте отново.',
      ),
    ).toBeInTheDocument()
    const reloadButton = screen.getByRole('button', { name: 'Зареди актуалните данни' })

    // Reloading discards the still-dirty draft, so it must be confirmed.
    mockedGetWorkingSchedule.mockResolvedValueOnce(splitSchedule)
    fireEvent.click(reloadButton)
    expect(mockedGetWorkingSchedule).toHaveBeenCalledTimes(1)
    fireEvent.click(await screen.findByRole('button', { name: 'Напусни' }))
    expect(await screen.findByText('09:00\u201312:00')).toBeInTheDocument()
    expect(mockedGetWorkingSchedule).toHaveBeenCalledTimes(2)
  })

  it('redirects to the authentication callback on a 401 load failure', async () => {
    mockedGetWorkingSchedule.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    const onAuthenticationRequired = vi.fn()
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
      />,
    )
    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'))
  })
})

// Reads the visible time-range text of every period chip within a weekday,
// in DOM order — the first child of each chip is always the plain
// `formatPeriodRange` text, before the edit icon/remove button/hidden text.
function chipOrder(day: HTMLElement, editing: boolean): string[] {
  const selector = editing ? '.schedule-period-chip-label' : '.schedule-period-chip'
  return Array.from(day.querySelectorAll(selector)).map(
    (element) => element.childNodes[0]?.textContent?.trim() ?? '',
  )
}

describe('WorkingScheduleEditor — immediate chronological ordering', () => {
  beforeEach(() => {
    mockedGetWorkingSchedule.mockReset()
    mockedReplaceWorkingSchedule.mockReset()
  })

  it('renders newly added periods in chronological order regardless of add order', async () => {
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')

    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    let dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '14:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '15:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '10:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    expect(chipOrder(monday, true)).toEqual(['09:00–10:00', '14:00–15:00'])
  })

  it('re-sorts immediately when editing a period to an earlier time', async () => {
    const schedule: WorkingSchedule = {
      ...emptySchedule,
      periods: [
        { weekday: 'MONDAY', startTime: '09:00', endTime: '10:00' },
        { weekday: 'MONDAY', startTime: '14:00', endTime: '15:00' },
      ],
    }
    await renderEditing(schedule)
    const monday = daySection('Понеделник')
    expect(chipOrder(monday, true)).toEqual(['09:00–10:00', '14:00–15:00'])

    fireEvent.click(within(monday).getByRole('button', { name: /Редактирай периода 14:00–15:00/ }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '06:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '06:30' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Запази' }))

    expect(chipOrder(monday, true)).toEqual(['06:00–06:30', '09:00–10:00'])
  })

  it('sorts a copied day into chronological order at the target regardless of source array order', async () => {
    // The source array is intentionally not pre-sorted, matching an
    // authoritative backend response whose period order is not guaranteed.
    const schedule: WorkingSchedule = {
      ...emptySchedule,
      periods: [
        { weekday: 'MONDAY', startTime: '14:00', endTime: '18:00' },
        { weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' },
      ],
    }
    await renderEditing(schedule)
    const monday = daySection('Понеделник')
    expect(chipOrder(monday, true)).toEqual(['09:00–12:00', '14:00–18:00'])

    fireEvent.click(within(monday).getByRole('button', { name: 'Още действия за понеделник' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Копирай графика' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByLabelText('Вторник'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Копирай' }))

    const tuesday = daySection('Вторник')
    expect(chipOrder(tuesday, true)).toEqual(['09:00–12:00', '14:00–18:00'])
  })

  it('keeps the remaining periods sorted after removing one', async () => {
    const schedule: WorkingSchedule = {
      ...emptySchedule,
      periods: [
        { weekday: 'MONDAY', startTime: '07:00', endTime: '08:00' },
        { weekday: 'MONDAY', startTime: '09:00', endTime: '10:00' },
        { weekday: 'MONDAY', startTime: '14:00', endTime: '15:00' },
      ],
    }
    await renderEditing(schedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: /Премахни периода 09:00–10:00/ }))
    expect(chipOrder(monday, true)).toEqual(['07:00–08:00', '14:00–15:00'])
  })

  it('sorts periods added after clearing a weekday', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: 'Още действия за понеделник' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Изчисти деня' }))
    fireEvent.click(screen.getByRole('button', { name: 'Изчисти графика за деня' }))
    expect(within(monday).getByText('Почивен ден')).toBeInTheDocument()

    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    let dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '16:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '17:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '08:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '09:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    expect(chipOrder(monday, true)).toEqual(['08:00–09:00', '16:00–17:00'])
  })

  it('restores the persisted chronological order when a dirty draft is discarded', async () => {
    const schedule: WorkingSchedule = {
      ...emptySchedule,
      periods: [
        { weekday: 'MONDAY', startTime: '09:00', endTime: '10:00' },
        { weekday: 'MONDAY', startTime: '14:00', endTime: '15:00' },
      ],
    }
    await renderEditing(schedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '06:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '07:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))
    expect(chipOrder(monday, true)).toEqual(['06:00–07:00', '09:00–10:00', '14:00–15:00'])

    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))
    const restoredMonday = daySection('Понеделник')
    expect(chipOrder(restoredMonday, false)).toEqual(['09:00–10:00', '14:00–15:00'])
  })

  it('sends the final PUT payload in deterministic weekday/start/end order regardless of edit order', async () => {
    mockedReplaceWorkingSchedule.mockResolvedValue({ ...emptySchedule, version: 1 })
    await renderEditing(emptySchedule)

    // Add Wednesday first, then Monday, to build the draft out of weekday order.
    const wednesday = daySection('Сряда')
    fireEvent.click(within(wednesday).getByRole('button', { name: '+ Добави' }))
    let dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '10:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '11:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '14:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '15:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '10:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await waitFor(() =>
      expect(mockedReplaceWorkingSchedule).toHaveBeenCalledWith('staff-a', {
        expectedVersion: 0,
        periods: [
          { weekday: 'MONDAY', startTime: '09:00', endTime: '10:00' },
          { weekday: 'MONDAY', startTime: '14:00', endTime: '15:00' },
          { weekday: 'WEDNESDAY', startTime: '10:00', endTime: '11:00' },
        ],
      }),
    )
  })
})

describe('WorkingScheduleEditor — copy dialog shortcuts', () => {
  beforeEach(() => {
    mockedGetWorkingSchedule.mockReset()
    mockedReplaceWorkingSchedule.mockReset()
  })

  async function openCopyDialogFromMonday() {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: 'Още действия за понеделник' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Копирай графика' }))
    return screen.getByRole('dialog')
  }

  it('selects Tuesday through Friday with the "Понеделник–петък" shortcut, excluding the source', async () => {
    const dialog = await openCopyDialogFromMonday()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Понеделник–петък' }))

    for (const name of ['Вторник', 'Сряда', 'Четвъртък', 'Петък']) {
      expect(within(dialog).getByLabelText(new RegExp(name))).toBeChecked()
    }
    expect(within(dialog).getByLabelText('Събота')).not.toBeChecked()
    expect(within(dialog).getByLabelText('Неделя')).not.toBeChecked()
    // The shortcut only adjusts the selection; it must not copy by itself.
    expect(mockedReplaceWorkingSchedule).not.toHaveBeenCalled()
    expect(within(daySection('Вторник')).getByText('Почивен ден')).toBeInTheDocument()
  })

  it('selects every remaining weekday with "Всички останали дни"', async () => {
    const dialog = await openCopyDialogFromMonday()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Всички останали дни' }))

    for (const name of ['Вторник', 'Сряда', 'Четвъртък', 'Петък', 'Събота', 'Неделя']) {
      expect(within(dialog).getByLabelText(new RegExp(name))).toBeChecked()
    }
    expect(within(dialog).queryByLabelText('Понеделник')).not.toBeInTheDocument()
  })

  it('disables "Изчисти избора" with an empty selection, and enables it once targets are selected', async () => {
    const dialog = await openCopyDialogFromMonday()
    expect(within(dialog).getByRole('button', { name: 'Изчисти избора' })).toBeDisabled()

    fireEvent.click(within(dialog).getByLabelText('Вторник'))
    expect(within(dialog).getByRole('button', { name: 'Изчисти избора' })).not.toBeDisabled()
  })

  it('clears the selection with "Изчисти избора" without touching the draft', async () => {
    const dialog = await openCopyDialogFromMonday()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Всички останали дни' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Изчисти избора' }))

    for (const name of ['Вторник', 'Сряда', 'Четвъртък', 'Петък', 'Събота', 'Неделя']) {
      expect(within(dialog).getByLabelText(new RegExp(name))).not.toBeChecked()
    }
    expect(within(dialog).getByRole('button', { name: 'Копирай' })).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: 'Изчисти избора' })).toBeDisabled()
    // No copy target was ever confirmed, so no weekday other than the
    // source was touched.
    expect(within(daySection('Вторник')).getByText('Почивен ден')).toBeInTheDocument()
  })

  it('still discloses replacement warnings after using a shortcut, and copies the final selection on confirm', async () => {
    const dialog = await openCopyDialogFromMonday()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Понеделник–петък' }))

    // Сряда already has a period in splitSchedule.
    const wednesdayOption = within(dialog).getByLabelText(/Сряда/)
    expect(wednesdayOption.closest('label')).toHaveTextContent('ще замени 1 период')
    expect(within(dialog).getByRole('button', { name: 'Копирай и замени' })).toBeInTheDocument()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Копирай и замени' }))

    for (const name of ['Вторник', 'Сряда', 'Четвъртък', 'Петък']) {
      const section = daySection(name)
      expect(within(section).getByText('09:00–12:00')).toBeInTheDocument()
      expect(within(section).getByText('14:00–18:00')).toBeInTheDocument()
    }
    expect(within(daySection('Събота')).getByText('Почивен ден')).toBeInTheDocument()
  })
})

describe('WorkingScheduleEditor — dirty Add/Edit dialog participates in the shared guard', () => {
  beforeEach(() => {
    mockedGetWorkingSchedule.mockReset()
    mockedReplaceWorkingSchedule.mockReset()
  })

  it('does not guard a newly opened, untouched Add dialog', async () => {
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.queryByRole('alertdialog', { name: 'Незапазени промени' })).not.toBeInTheDocument()
  })

  it('guards navigation once either Add-dialog field is typed into, even though the weekly draft is still clean', async () => {
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
  })

  it('does not guard an unchanged Edit dialog, but does once a value is changed', async () => {
    await renderEditing(splitSchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: /Редактирай периода 09:00–12:00/ }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.queryByRole('alertdialog', { name: 'Незапазени промени' })).not.toBeInTheDocument()

    fireEvent.click(within(monday).getByRole('button', { name: /Редактирай периода 09:00–12:00/ }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '13:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
  })

  it('"Остани" preserves the open dialog and its entered values', async () => {
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Начален час')).toHaveValue('09:00')
  })

  it('confirming discard closes the dialog, restores the persisted weekly draft, and performs the pending action', async () => {
    const schedule: WorkingSchedule = {
      ...emptySchedule,
      periods: [{ weekday: 'MONDAY', startTime: '09:00', endTime: '10:00' }],
    }
    await renderEditing(schedule)
    const monday = daySection('Понеделник')
    // Dirty the weekly draft too (remove the existing period) so this proves
    // "if both the weekly draft and the dialog are dirty, discard covers both".
    fireEvent.click(within(monday).getByRole('button', { name: /Премахни периода 09:00–10:00/ }))
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '15:00' } })

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(within(daySection('Понеделник')).getByText('09:00–10:00')).toBeInTheDocument()
    // The guarded action itself (the GuardProbe's no-op) is considered
    // performed once the shared dialog is gone with no error thrown.
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('explicit local "Отказ" discards the dirty dialog immediately, with no stale guard registration', async () => {
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })

    fireEvent.click(within(dialog).getByRole('button', { name: 'Отказ' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.queryByRole('alertdialog', { name: 'Незапазени промени' })).not.toBeInTheDocument()
  })

  it('a successful Add transfers the change into the weekly draft, which then carries the guard', async () => {
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')
    fireEvent.click(within(monday).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '12:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Пробна навигация' }))
    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
  })

  it('Escape may close only a clean period dialog and never silently discards dirty values', async () => {
    await renderEditing(emptySchedule)
    const monday = daySection('Понеделник')
    const addButton = within(monday).getByRole('button', { name: '+ Добави' })
    addButton.focus()
    fireEvent.click(addButton)
    const dialog = screen.getByRole('dialog')

    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.keyDown(dialog.parentElement as HTMLElement, { key: 'Escape' })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Начален час')).toHaveValue('09:00')

    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '' } })
    fireEvent.keyDown(dialog.parentElement as HTMLElement, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

describe('WorkingScheduleEditor — standalone whole-schedule clearing (outside editing)', () => {
  beforeEach(() => {
    mockedGetWorkingSchedule.mockReset()
    mockedReplaceWorkingSchedule.mockReset()
  })

  it('shows "Редактирай графика" and "Изчисти графика" together in read-only mode', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(splitSchedule)
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly={false}
        onAuthenticationRequired={vi.fn()}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    const actions = within(screen.getByText('Изчисти графика').closest('.schedule-page-actions') as HTMLElement)
    expect(actions.getByRole('button', { name: 'Редактирай графика' })).toBeInTheDocument()
    expect(actions.getByRole('button', { name: 'Изчисти графика' })).toBeInTheDocument()
  })

  it('shows only "Запази промените" and "Отказ" in edit mode, with no clear-all action', async () => {
    await renderEditing(splitSchedule)
    const actions = document.querySelector('.schedule-page-actions') as HTMLElement
    expect(within(actions).getByRole('button', { name: 'Запази промените' })).toBeInTheDocument()
    expect(within(actions).getByRole('button', { name: 'Отказ' })).toBeInTheDocument()
    expect(within(actions).queryByRole('button', { name: 'Изчисти графика' })).not.toBeInTheDocument()
  })

  it('disables "Изчисти графика" when the saved schedule already has no periods', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(emptySchedule)
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly={false}
        onAuthenticationRequired={vi.fn()}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    expect(screen.getByRole('button', { name: 'Изчисти графика' })).toBeDisabled()
  })

  it('does not enter edit mode or build a draft when opening the clear-all confirmation', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(splitSchedule)
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly={false}
        onAuthenticationRequired={vi.fn()}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    fireEvent.click(screen.getByRole('button', { name: 'Изчисти графика' }))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '+ Добави' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Запази промените' })).not.toBeInTheDocument()
  })

  it('cancelling the confirmation performs no request', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(splitSchedule)
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly={false}
        onAuthenticationRequired={vi.fn()}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    fireEvent.click(screen.getByRole('button', { name: 'Изчисти графика' }))
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(mockedReplaceWorkingSchedule).not.toHaveBeenCalled()
    expect(within(daySection('Понеделник')).getByText('09:00–12:00')).toBeInTheDocument()
  })

  it('sends exactly { expectedVersion, periods: [] } on confirm, prevents duplicate submissions, and remains read-only on success', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(splitSchedule)
    let resolveReplace: ((schedule: WorkingSchedule) => void) | undefined
    mockedReplaceWorkingSchedule.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveReplace = resolve
        }),
    )
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly={false}
        onAuthenticationRequired={vi.fn()}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    fireEvent.click(screen.getByRole('button', { name: 'Изчисти графика' }))
    const confirmButton = within(screen.getByRole('alertdialog')).getByRole('button', {
      name: 'Изчисти графика',
    })
    fireEvent.click(confirmButton)
    fireEvent.click(confirmButton)

    expect(mockedReplaceWorkingSchedule).toHaveBeenCalledTimes(1)
    expect(mockedReplaceWorkingSchedule).toHaveBeenCalledWith('staff-a', {
      expectedVersion: 2,
      periods: [],
    })
    // The confirmation stays open (both actions disabled) while the request
    // is in flight, so a rapid second click cannot fire a second request.
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(screen.getByRole('alertdialog')).toHaveAttribute('aria-modal', 'true')
    expect(confirmButton).toBeDisabled()
    // Nothing behind the modal can start a competing operation meanwhile.
    expect(screen.getByRole('button', { name: 'Редактирай графика' })).toBeDisabled()

    resolveReplace?.({ ...splitSchedule, periods: [], version: 3 })
    expect(await screen.findByText('Работният график е изчистен.')).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getAllByText('Почивен ден')).toHaveLength(7)
    expect(screen.queryByRole('button', { name: 'Запази промените' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Редактирай графика' })).toBeInTheDocument()
  })

  it('ignores Escape while the clear-all request is pending, then behaves normally once it resolves', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(splitSchedule)
    let resolveReplace: ((schedule: WorkingSchedule) => void) | undefined
    mockedReplaceWorkingSchedule.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveReplace = resolve
        }),
    )
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly={false}
        onAuthenticationRequired={vi.fn()}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    fireEvent.click(screen.getByRole('button', { name: 'Изчисти графика' }))
    const dialog = screen.getByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Изчисти графика' }))

    fireEvent.keyDown(dialog, { key: 'Escape' })

    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(mockedReplaceWorkingSchedule).toHaveBeenCalledTimes(1)

    resolveReplace?.({ ...splitSchedule, periods: [], version: 3 })
    expect(await screen.findByText('Работният график е изчистен.')).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getAllByText('Почивен ден')).toHaveLength(7)
  })

  it('preserves the currently displayed schedule on failure', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(splitSchedule)
    mockedReplaceWorkingSchedule.mockRejectedValue(
      new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.'),
    )
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly={false}
        onAuthenticationRequired={vi.fn()}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    fireEvent.click(screen.getByRole('button', { name: 'Изчисти графика' }))
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Изчисти графика' }))

    expect(await screen.findByText('Проверете въведените данни.')).toBeInTheDocument()
    expect(within(daySection('Понеделник')).getByText('09:00–12:00')).toBeInTheDocument()
    expect(screen.getAllByText('Почивен ден').length).toBeLessThan(7)
  })

  it('shows a stale-version conflict with a reload action and does not silently overwrite the schedule', async () => {
    mockedGetWorkingSchedule.mockResolvedValueOnce(splitSchedule)
    mockedReplaceWorkingSchedule.mockRejectedValue(
      new ApiError(
        409,
        'WORKING_SCHEDULE_CONCURRENT_UPDATE',
        'Работният график е променен от друга операция. Обновете данните и опитайте отново.',
      ),
    )
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly={false}
        onAuthenticationRequired={vi.fn()}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    fireEvent.click(screen.getByRole('button', { name: 'Изчисти графика' }))
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Изчисти графика' }))

    expect(
      await screen.findByText(
        'Работният график е променен от друга операция. Обновете данните и опитайте отново.',
      ),
    ).toBeInTheDocument()
    const reloadButton = screen.getByRole('button', { name: 'Зареди актуалните данни' })
    expect(within(daySection('Понеделник')).getByText('09:00–12:00')).toBeInTheDocument()

    mockedGetWorkingSchedule.mockResolvedValueOnce(emptySchedule)
    fireEvent.click(reloadButton)
    expect(await screen.findAllByText('Почивен ден')).toHaveLength(7)
  })

  it('does not expose the clear action for an inactive StaffMember', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(splitSchedule)
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive={false}
        readOnly={false}
        onAuthenticationRequired={vi.fn()}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    expect(screen.queryByRole('button', { name: 'Изчисти графика' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Редактирай графика' })).not.toBeInTheDocument()
  })

  it('does not expose the clear action for a SUSPENDED Business', async () => {
    mockedGetWorkingSchedule.mockResolvedValue(splitSchedule)
    render(
      <WorkingScheduleEditor
        staffMemberId="staff-a"
        staffMemberActive
        readOnly
        onAuthenticationRequired={vi.fn()}
      />,
    )
    await screen.findByRole('heading', { name: 'Понеделник' })
    expect(screen.queryByRole('button', { name: 'Изчисти графика' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Редактирай графика' })).not.toBeInTheDocument()
  })
})

describe('WorkingScheduleEditor — schedule grid responsive column breakpoints', () => {
  // Real CSS media queries and grid layout cannot be exercised in jsdom, but
  // the fix for the edit-mode chip-overflow regression is entirely a
  // stylesheet change (narrower/fewer columns before seven genuinely fit),
  // so this guards the stylesheet's structure directly rather than
  // asserting brittle computed geometry.
  const css = readFileSync(join(process.cwd(), 'src/styles.css'), 'utf-8')

  it('does not switch the weekly grid to seven columns at ~1024px (64rem), where the chip overflow was found, but does at ordinary desktop widths', () => {
    const sevenColumnBreakpoints = [
      ...css.matchAll(/@media \(min-width:\s*(\d+(?:\.\d+)?)rem\)\s*{\s*\.schedule-grid\s*{\s*grid-template-columns:\s*repeat\(7,/g),
    ].map((match) => Number(match[1]))
    expect(sevenColumnBreakpoints.length).toBeGreaterThan(0)
    for (const breakpoint of sevenColumnBreakpoints) {
      expect(breakpoint).toBeGreaterThan(64)
      // Seven columns must kick in at an ordinary desktop width, not only at
      // an unusually wide one — the accepted design shows the complete week
      // in one row well before 96rem.
      expect(breakpoint).toBeLessThanOrEqual(80)
    }
  })

  it('uses a reduced, non-seven column count for the schedule grid at an intermediate (tablet/~1024px) width', () => {
    const intermediateMatch = css.match(
      /@media \(min-width:\s*64rem\)\s*{\s*\.schedule-grid\s*{\s*grid-template-columns:\s*repeat\((\d+),/,
    )
    expect(intermediateMatch).not.toBeNull()
    const columnCount = Number(intermediateMatch![1])
    expect(columnCount).toBeGreaterThan(0)
    expect(columnCount).toBeLessThan(7)
  })

  it('keeps the mobile-first single-column stacked layout below 48rem', () => {
    const mobileRule = css.slice(css.indexOf('.schedule-grid {'), css.indexOf('.schedule-grid {') + 200)
    expect(mobileRule).toMatch(/flex-direction:\s*column/)
  })
})
