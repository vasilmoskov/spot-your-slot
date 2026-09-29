import '@testing-library/jest-dom/vitest'
import { fireEvent, render as rtlRender, screen, waitFor, within } from '@testing-library/react'
import type { ReactElement } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { UnsavedChangesGuardProvider } from '../../ui/UnsavedChangesGuard'
import { listStaffMembers, type StaffMemberPage } from '../staff/api'
import { getWorkingSchedule, replaceWorkingSchedule, type WorkingSchedule } from './api'
import { StaffWorkingSchedule } from './StaffWorkingSchedule'

function render(ui: ReactElement) {
  return rtlRender(ui, { wrapper: UnsavedChangesGuardProvider })
}

vi.mock('../staff/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../staff/api')>()),
  listStaffMembers: vi.fn(),
}))
vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getWorkingSchedule: vi.fn(),
  replaceWorkingSchedule: vi.fn(),
}))

const mockedListStaffMembers = vi.mocked(listStaffMembers)
const mockedGetWorkingSchedule = vi.mocked(getWorkingSchedule)
const mockedReplaceWorkingSchedule = vi.mocked(replaceWorkingSchedule)

function staffPage(overrides: Partial<StaffMemberPage> = {}): StaffMemberPage {
  return {
    staffMembers: [
      {
        id: 'staff-a',
        displayName: 'Анна Иванова',
        contactEmail: null,
        contactPhone: null,
        active: true,
        version: 0,
        createdAt: '2026-08-19T09:00:00Z',
        updatedAt: '2026-08-19T09:00:00Z',
      },
      {
        id: 'staff-b',
        displayName: 'Борис Петров',
        contactEmail: null,
        contactPhone: null,
        active: false,
        version: 0,
        createdAt: '2026-08-19T09:00:00Z',
        updatedAt: '2026-08-19T09:00:00Z',
      },
    ],
    page: 0,
    size: 50,
    totalElements: 2,
    ...overrides,
  }
}

function scheduleFor(staffMemberId: string, overrides: Partial<WorkingSchedule> = {}): WorkingSchedule {
  return {
    staffMemberId,
    timezone: 'Europe/Sofia',
    periods: [],
    version: 0,
    createdAt: '2026-08-19T09:00:00Z',
    updatedAt: '2026-08-19T09:00:00Z',
    ...overrides,
  }
}

describe('StaffWorkingSchedule', () => {
  const onAuthenticationRequired = vi.fn()

  beforeEach(() => {
    mockedListStaffMembers.mockReset()
    mockedGetWorkingSchedule.mockReset()
    mockedReplaceWorkingSchedule.mockReset()
    onAuthenticationRequired.mockReset()
  })

  it('loads the StaffMember list and defaults to the first entry, showing name and lifecycle state', async () => {
    mockedListStaffMembers.mockResolvedValue(staffPage())
    mockedGetWorkingSchedule.mockResolvedValue(scheduleFor('staff-a'))
    render(<StaffWorkingSchedule readOnly={false} onAuthenticationRequired={onAuthenticationRequired} />)

    expect(await screen.findByLabelText('Член на екипа')).toHaveValue('staff-a')
    expect(screen.getByRole('option', { name: 'Борис Петров (неактивен)' })).toBeInTheDocument()
    // The schedule header no longer shows a redundant StaffMember status
    // badge — only the option text distinguishes an inactive StaffMember.
    expect(screen.queryByText('Активен')).not.toBeInTheDocument()
  })

  it('loads every page of StaffMembers, not only the first', async () => {
    mockedListStaffMembers.mockImplementation((page = 0) => {
      if (page === 0) {
        return Promise.resolve(
          staffPage({
            staffMembers: Array.from({ length: 50 }, (_, index) => ({
              id: `staff-${index}`,
              displayName: `Член ${String(index).padStart(2, '0')}`,
              contactEmail: null,
              contactPhone: null,
              active: true,
              version: 0,
              createdAt: '2026-08-19T09:00:00Z',
              updatedAt: '2026-08-19T09:00:00Z',
            })),
            page: 0,
            totalElements: 51,
          }),
        )
      }
      return Promise.resolve(
        staffPage({
          staffMembers: [
            {
              id: 'staff-50',
              displayName: 'Член 50',
              contactEmail: null,
              contactPhone: null,
              active: true,
              version: 0,
              createdAt: '2026-08-19T09:00:00Z',
              updatedAt: '2026-08-19T09:00:00Z',
            },
          ],
          page: 1,
          totalElements: 51,
        }),
      )
    })
    mockedGetWorkingSchedule.mockResolvedValue(scheduleFor('staff-0'))
    render(<StaffWorkingSchedule readOnly={false} onAuthenticationRequired={onAuthenticationRequired} />)

    await screen.findByLabelText('Член на екипа')
    expect(screen.getByRole('option', { name: 'Член 50' })).toBeInTheDocument()
    expect(mockedListStaffMembers).toHaveBeenCalledTimes(2)
  })

  it('shows an empty-team message when there are no StaffMembers yet', async () => {
    mockedListStaffMembers.mockResolvedValue(staffPage({ staffMembers: [], totalElements: 0 }))
    render(<StaffWorkingSchedule readOnly={false} onAuthenticationRequired={onAuthenticationRequired} />)
    expect(
      await screen.findByText(/Все още няма добавени членове на екипа/),
    ).toBeInTheDocument()
    expect(mockedGetWorkingSchedule).not.toHaveBeenCalled()
  })

  it('switches StaffMembers and loads the newly selected schedule', async () => {
    mockedListStaffMembers.mockResolvedValue(staffPage())
    mockedGetWorkingSchedule.mockImplementation((staffMemberId) =>
      Promise.resolve(scheduleFor(staffMemberId)),
    )
    render(<StaffWorkingSchedule readOnly={false} onAuthenticationRequired={onAuthenticationRequired} />)

    const select = await screen.findByLabelText('Член на екипа')
    await screen.findByRole('heading', { name: 'Понеделник' })
    fireEvent.change(select, { target: { value: 'staff-b' } })

    await waitFor(() => expect(mockedGetWorkingSchedule).toHaveBeenCalledWith('staff-b', expect.anything()))
    // staff-b is inactive, so its schedule renders the inactive-specific
    // read-only notice instead of an edit control.
    expect(
      await screen.findByText('Работният график на неактивен член на екипа може само да бъде преглеждан.'),
    ).toBeInTheDocument()
  })

  it('ignores a stale schedule response after switching StaffMembers', async () => {
    mockedListStaffMembers.mockResolvedValue(staffPage())
    let resolveStaffA: ((schedule: WorkingSchedule) => void) | undefined
    mockedGetWorkingSchedule.mockImplementation((staffMemberId) => {
      if (staffMemberId === 'staff-a') {
        return new Promise((resolve) => {
          resolveStaffA = resolve
        })
      }
      return Promise.resolve(scheduleFor('staff-b'))
    })
    render(<StaffWorkingSchedule readOnly={false} onAuthenticationRequired={onAuthenticationRequired} />)

    const select = await screen.findByLabelText('Член на екипа')
    fireEvent.change(select, { target: { value: 'staff-b' } })
    await screen.findByRole('heading', { name: 'Понеделник' })
    expect(screen.getAllByText('Почивен ден')).toHaveLength(7)

    // The Business-A (staff-a) request resolves only after staff-b has
    // already rendered, with a distinctive period; it must not clobber the
    // current (empty) staff-b view.
    resolveStaffA?.(
      scheduleFor('staff-a', {
        periods: [{ weekday: 'MONDAY', startTime: '07:00', endTime: '08:00' }],
      }),
    )
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByText('07:00–08:00')).not.toBeInTheDocument()
    expect(screen.getAllByText('Почивен ден')).toHaveLength(7)
  })

  it('guards a StaffMember selection change while the schedule editor has unsaved changes', async () => {
    mockedListStaffMembers.mockResolvedValue(staffPage())
    mockedGetWorkingSchedule.mockImplementation((staffMemberId) =>
      Promise.resolve(scheduleFor(staffMemberId)),
    )
    render(<StaffWorkingSchedule readOnly={false} onAuthenticationRequired={onAuthenticationRequired} />)

    const select = await screen.findByLabelText('Член на екипа')
    await screen.findByRole('heading', { name: 'Понеделник' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай графика' }))
    const mondaySection = screen.getByRole('heading', { name: 'Понеделник' }).closest('section') as HTMLElement
    fireEvent.click(within(mondaySection).getByRole('button', { name: '+ Добави' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Начален час'), { target: { value: '09:00' } })
    fireEvent.change(within(dialog).getByLabelText('Краен час'), { target: { value: '12:00' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Добави' }))

    fireEvent.change(select, { target: { value: 'staff-b' } })
    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
    expect(mockedGetWorkingSchedule).not.toHaveBeenCalledWith('staff-b', expect.anything())

    fireEvent.click(screen.getByRole('button', { name: 'Откажи промените' }))
    await waitFor(() =>
      expect(mockedGetWorkingSchedule).toHaveBeenCalledWith('staff-b', expect.anything()),
    )
  })

  it('redirects to the authentication callback on a 401 StaffMember-list failure', async () => {
    mockedListStaffMembers.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    render(<StaffWorkingSchedule readOnly={false} onAuthenticationRequired={onAuthenticationRequired} />)
    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'))
  })
})
