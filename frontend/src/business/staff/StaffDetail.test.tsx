import '@testing-library/jest-dom/vitest'
import { fireEvent, render as rtlRender, screen, waitFor, within } from '@testing-library/react'
import type { ReactElement } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { UnsavedChangesGuardProvider } from '../../ui/UnsavedChangesGuard'
import {
  deactivateStaffMember,
  getStaffMember,
  listStaffMemberAssignments,
  reactivateStaffMember,
  replaceStaffMemberAssignments,
  updateStaffMember,
  type StaffMemberAssignments,
  type StaffMemberDetails,
} from './api'
import { listServices, type ServicePage } from '../services/api'
import { StaffDetail } from './StaffDetail'

function render(ui: ReactElement) {
  return rtlRender(ui, { wrapper: UnsavedChangesGuardProvider })
}

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getStaffMember: vi.fn(),
  updateStaffMember: vi.fn(),
  deactivateStaffMember: vi.fn(),
  reactivateStaffMember: vi.fn(),
  listStaffMemberAssignments: vi.fn(),
  replaceStaffMemberAssignments: vi.fn(),
}))
vi.mock('../services/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../services/api')>()),
  listServices: vi.fn(),
}))

const mockedGetStaffMember = vi.mocked(getStaffMember)
const mockedUpdateStaffMember = vi.mocked(updateStaffMember)
const mockedDeactivateStaffMember = vi.mocked(deactivateStaffMember)
const mockedReactivateStaffMember = vi.mocked(reactivateStaffMember)
const mockedListStaffMemberAssignments = vi.mocked(listStaffMemberAssignments)
const mockedReplaceStaffMemberAssignments = vi.mocked(replaceStaffMemberAssignments)
const mockedListServices = vi.mocked(listServices)

const staffMember: StaffMemberDetails = {
  id: 'staff-a',
  displayName: 'Анна Иванова',
  contactEmail: 'anna@example.invalid',
  contactPhone: '+359888123456',
  active: true,
  version: 0,
  createdAt: '2026-08-19T09:00:00Z',
  updatedAt: '2026-08-19T09:00:00Z',
}

const noAssignments: StaffMemberAssignments = {
  staffMemberId: 'staff-a',
  version: 0,
  createdAt: '2026-08-19T09:00:00Z',
  updatedAt: '2026-08-19T09:00:00Z',
  services: [],
}

const servicesPage: ServicePage = {
  services: [
    {
      id: 'service-a',
      name: 'Подстригване',
      description: null,
      durationMinutes: 10,
      price: 19.9,
      active: true,
      version: 0,
      createdAt: '2026-08-19T09:00:00Z',
      updatedAt: '2026-08-19T09:00:00Z',
    },
    {
      id: 'service-b',
      name: 'Оформяне на брада',
      description: null,
      durationMinutes: 15,
      price: 9.9,
      active: false,
      version: 0,
      createdAt: '2026-08-19T09:00:00Z',
      updatedAt: '2026-08-19T09:00:00Z',
    },
  ],
  page: 0,
  size: 50,
  totalElements: 2,
}

describe('StaffDetail', () => {
  const onAuthenticationRequired = vi.fn()
  const onBack = vi.fn()

  beforeEach(() => {
    mockedGetStaffMember.mockReset()
    mockedUpdateStaffMember.mockReset()
    mockedDeactivateStaffMember.mockReset()
    mockedReactivateStaffMember.mockReset()
    mockedListStaffMemberAssignments.mockReset()
    mockedReplaceStaffMemberAssignments.mockReset()
    mockedListServices.mockReset()
    mockedListServices.mockResolvedValue(servicesPage)
    mockedListStaffMemberAssignments.mockResolvedValue(noAssignments)
    onAuthenticationRequired.mockReset()
    onBack.mockReset()
  })

  it('shows loaded details and hides mutation actions when read-only', async () => {
    mockedGetStaffMember.mockResolvedValue(staffMember)
    render(
      <StaffDetail
        staffMemberId="staff-a"
        readOnly
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    expect(await screen.findByRole('heading', { name: 'Анна Иванова' })).toBeInTheDocument()
    expect(screen.getByText('anna@example.invalid')).toBeInTheDocument()
    expect(screen.getByText('+359 888 123 456')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Редактирай' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Деактивирай' })).not.toBeInTheDocument()
  })

  it('edits, saves, and returns to read-only view', async () => {
    mockedGetStaffMember.mockResolvedValue(staffMember)
    mockedUpdateStaffMember.mockResolvedValue({
      ...staffMember,
      displayName: 'Анна Петрова',
      version: 1,
    })
    render(
      <StaffDetail
        staffMemberId="staff-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Анна Иванова' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Анна Петрова' },
    })
    const form = document.querySelector('form.business-form') as HTMLElement
    fireEvent.click(within(form).getByRole('button', { name: 'Запази промените' }))

    await waitFor(() =>
      expect(mockedUpdateStaffMember).toHaveBeenCalledWith('staff-a', {
        displayName: 'Анна Петрова',
        contactEmail: 'anna@example.invalid',
        contactPhone: '+359888123456',
        expectedVersion: 0,
      }),
    )
    expect(await screen.findByText('Промените са запазени.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Анна Петрова' })).toBeInTheDocument()
  })

  it('exits editing immediately when Cancel is pressed with no changes', async () => {
    mockedGetStaffMember.mockResolvedValue(staffMember)
    render(
      <StaffDetail
        staffMemberId="staff-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Анна Иванова' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    const form = document.querySelector('form.business-form') as HTMLElement
    fireEvent.click(within(form).getByRole('button', { name: 'Отказ' }))

    expect(mockedUpdateStaffMember).not.toHaveBeenCalled()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Име на члена на екипа')).not.toBeInTheDocument()
  })

  it('asks for confirmation before discarding a dirty profile edit', async () => {
    mockedGetStaffMember.mockResolvedValue(staffMember)
    render(
      <StaffDetail
        staffMemberId="staff-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Анна Иванова' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Незаписано' },
    })
    const form = document.querySelector('form.business-form') as HTMLElement
    fireEvent.click(within(form).getByRole('button', { name: 'Отказ' }))

    expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
    expect(mockedUpdateStaffMember).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Откажи промените' }))
    expect(screen.queryByLabelText('Име на члена на екипа')).not.toBeInTheDocument()
  })

  it('confirms before a reload discards a dirty profile edit after a concurrent-update conflict', async () => {
    mockedGetStaffMember.mockResolvedValue(staffMember)
    mockedUpdateStaffMember.mockRejectedValue(
      new ApiError(409, 'STAFF_MEMBER_CONCURRENT_UPDATE', 'Членът на екипа е променен.'),
    )
    render(
      <StaffDetail
        staffMemberId="staff-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Анна Иванова' })
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
      target: { value: 'Ново име' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await screen.findByRole('button', { name: 'Зареди актуалните данни' })

    fireEvent.click(screen.getByRole('button', { name: 'Зареди актуалните данни' }))
    expect(mockedGetStaffMember).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Продължи редактирането' }))
    expect(screen.getByLabelText('Име на члена на екипа')).toHaveValue('Ново име')

    fireEvent.click(screen.getByRole('button', { name: 'Зареди актуалните данни' }))
    fireEvent.click(screen.getByRole('button', { name: 'Откажи промените' }))
    await waitFor(() => expect(mockedGetStaffMember).toHaveBeenCalledTimes(2))
  })

  it('requires confirmation before deactivating and shows the StaffMember name', async () => {
    mockedGetStaffMember.mockResolvedValue(staffMember)
    mockedDeactivateStaffMember.mockResolvedValue({ ...staffMember, active: false, version: 1 })
    render(
      <StaffDetail
        staffMemberId="staff-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Анна Иванова' })
    fireEvent.click(screen.getByRole('button', { name: 'Деактивирай' }))
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Анна Иванова')
    expect(mockedDeactivateStaffMember).not.toHaveBeenCalled()

    // The safe action, never the destructive one, receives initial focus.
    expect(screen.getByRole('button', { name: 'Отказ' })).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Потвърди деактивирането' })).not.toHaveFocus()

    fireEvent.click(screen.getByRole('button', { name: 'Потвърди деактивирането' }))
    await waitFor(() => expect(mockedDeactivateStaffMember).toHaveBeenCalledWith('staff-a', 0))
    expect(await screen.findByText('Членът на екипа е деактивиран.')).toBeInTheDocument()
  })

  it('reactivates without confirmation', async () => {
    mockedGetStaffMember.mockResolvedValue({ ...staffMember, active: false })
    mockedReactivateStaffMember.mockResolvedValue({ ...staffMember, active: true, version: 1 })
    render(
      <StaffDetail
        staffMemberId="staff-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await screen.findByRole('heading', { name: 'Анна Иванова' })
    fireEvent.click(screen.getByRole('button', { name: 'Активирай отново' }))
    await waitFor(() => expect(mockedReactivateStaffMember).toHaveBeenCalledWith('staff-a', 0))
    expect(await screen.findByText('Членът на екипа е активиран отново.')).toBeInTheDocument()
  })

  it('redirects to authentication on a 401 load response', async () => {
    mockedGetStaffMember.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    render(
      <StaffDetail
        staffMemberId="staff-a"
        readOnly={false}
        onAuthenticationRequired={onAuthenticationRequired}
        onBack={onBack}
      />,
    )
    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'))
  })

  describe('Service assignments', () => {
    async function openAssignmentEditor() {
      fireEvent.click(await screen.findByRole('button', { name: 'Редактирай услугите' }))
    }

    it('shows every active Service plus any assigned inactive Service in read-only view, with non-interactive check/cross indicators and no checkbox or status badge', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      mockedListStaffMemberAssignments.mockResolvedValue({
        ...noAssignments,
        // service-a (active) is assigned.
        services: [{ id: 'service-a', name: 'Подстригване', active: true }],
      })
      mockedListServices.mockResolvedValue({
        services: [
          servicesPage.services[0]!,
          {
            id: 'service-active-unassigned',
            name: 'Масаж',
            description: null,
            durationMinutes: 20,
            price: 29.9,
            active: true,
            version: 0,
            createdAt: '2026-08-19T09:00:00Z',
            updatedAt: '2026-08-19T09:00:00Z',
          },
          {
            id: 'service-inactive-unassigned',
            name: 'Скрита услуга',
            description: null,
            durationMinutes: 5,
            price: 5,
            active: false,
            version: 0,
            createdAt: '2026-08-19T09:00:00Z',
            updatedAt: '2026-08-19T09:00:00Z',
          },
        ],
        page: 0,
        size: 50,
        totalElements: 3,
      })
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      // active + assigned: "Подстригване" shows a check with accessible "Да".
      const active = await screen.findByText('Подстригване')
      const activeRow = active.closest('tr')!
      expect(within(activeRow).getByText('✓')).toBeInTheDocument()
      expect(within(activeRow).getByText('Да')).toBeInTheDocument()

      // active + unassigned: "Масаж" shows a cross with accessible "Не".
      const unassignedActiveRow = screen.getByText('Масаж').closest('tr')!
      expect(within(unassignedActiveRow).getByText('✕')).toBeInTheDocument()
      expect(within(unassignedActiveRow).getByText('Не')).toBeInTheDocument()

      // inactive + unassigned: dropped entirely, never shown.
      expect(screen.queryByText('Скрита услуга')).not.toBeInTheDocument()

      expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
      expect(screen.queryByText('Неактивна')).not.toBeInTheDocument()
      expect(screen.queryByText('Активна')).not.toBeInTheDocument()
      expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
        'Услуга',
        'Назначена',
      ])
      expect(mockedListServices).toHaveBeenCalled()
    })

    it('keeps an assigned inactive Service visible as a check in read-only view, even when absent from the catalog page', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      mockedListStaffMemberAssignments.mockResolvedValue({
        ...noAssignments,
        services: [{ id: 'service-b', name: 'Оформяне на брада', active: false }],
      })
      mockedListServices.mockResolvedValue({
        services: [servicesPage.services[0]!],
        page: 0,
        size: 50,
        totalElements: 1,
      })
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      const inactiveAssignedRow = (await screen.findByText('Оформяне на брада')).closest('tr')!
      expect(within(inactiveAssignedRow).getByText('✓')).toBeInTheDocument()
      expect(within(inactiveAssignedRow).getByText('Да')).toBeInTheDocument()
    })

    it('loads the complete Service catalog (not only page 1) for the read-only view', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      const page0Ids = Array.from({ length: 50 }, (_, index) => `service-${index}`)
      mockedListServices.mockImplementation(async (page = 0) => ({
        services: (page === 0 ? page0Ids : ['service-page-2']).map((id) => ({
          id,
          name: id,
          description: null,
          durationMinutes: 10,
          price: 9.9,
          active: true,
          version: 0,
          createdAt: '2026-08-19T09:00:00Z',
          updatedAt: '2026-08-19T09:00:00Z',
        })),
        page,
        size: 50,
        totalElements: 51,
      }))
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      expect(await screen.findByText('service-page-2')).toBeInTheDocument()
      expect(mockedListServices).toHaveBeenCalledWith(0, 50, 'name', 'asc', expect.any(AbortSignal))
      expect(mockedListServices).toHaveBeenCalledWith(1, 50, 'name', 'asc', expect.any(AbortSignal))
    })

    it('shows a reload action when the read-only catalog fails to load', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      mockedListServices.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'Грешка.'))
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      expect(
        await screen.findByText('Услугите не могат да бъдат заредени.'),
      ).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Зареди отново' })).toBeInTheDocument()
    })

    it('shows a clear empty state when no Services exist to assign', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      mockedListServices.mockResolvedValue({ services: [], page: 0, size: 50, totalElements: 0 })
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      await openAssignmentEditor()
      expect(
        await screen.findByText('Няма създадени услуги за назначаване.'),
      ).toBeInTheDocument()
    })

    it('does not render an unassigned inactive Service as an assignment candidate at all', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      await openAssignmentEditor()
      await screen.findByRole('checkbox', { name: /Подстригване/ })
      expect(
        screen.queryByRole('checkbox', { name: /Оформяне на брада/ }),
      ).not.toBeInTheDocument()
      expect(screen.queryByText('Оформяне на брада')).not.toBeInTheDocument()
    })

    it('shows exactly the Услуга/Назначена headers, in order, with no status column or badges', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      await openAssignmentEditor()
      await screen.findByRole('checkbox', { name: /Подстригване/ })
      expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
        'Услуга',
        'Назначена',
      ])
      expect(screen.queryByText('Активна')).not.toBeInTheDocument()
      expect(screen.queryByText('Неактивна')).not.toBeInTheDocument()
    })

    it('adds and saves a Service assignment, then clears the dirty guard and returns to view mode', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      mockedReplaceStaffMemberAssignments.mockResolvedValue({
        ...noAssignments,
        version: 1,
        services: [{ id: 'service-a', name: 'Подстригване', active: true }],
      })
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      await openAssignmentEditor()
      const checkbox = await screen.findByRole('checkbox', { name: /Подстригване/ })
      const saveButton = screen.getByRole('button', { name: 'Запази промените' })
      expect(saveButton).toBeDisabled()

      fireEvent.click(checkbox)
      expect(saveButton).toBeEnabled()
      fireEvent.click(saveButton)

      await waitFor(() =>
        expect(mockedReplaceStaffMemberAssignments).toHaveBeenCalledWith('staff-a', {
          serviceIds: ['service-a'],
          expectedVersion: 0,
        }),
      )
      expect(await screen.findByText('Назначените услуги са запазени.')).toBeInTheDocument()
      // Saving returns the editor to read-only view mode: checkboxes and the
      // Save/Cancel controls are gone, replaced by "Редактирай услугите".
      expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Редактирай услугите' })).toBeInTheDocument()
    })

    it('prevents duplicate assignments by construction (checkbox selection, not a free-text list)', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      mockedListStaffMemberAssignments.mockResolvedValue({
        ...noAssignments,
        services: [{ id: 'service-a', name: 'Подстригване', active: true }],
      })
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      await openAssignmentEditor()
      expect(await screen.findAllByRole('checkbox', { name: /Подстригване/ })).toHaveLength(1)
    })

    it('removes an assignment via unchecking, and the dirty guard blocks discarding it', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      mockedListStaffMemberAssignments.mockResolvedValue({
        ...noAssignments,
        services: [{ id: 'service-a', name: 'Подстригване', active: true }],
      })
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      await openAssignmentEditor()
      const checkbox = await screen.findByRole('checkbox', { name: /Подстригване/ })
      expect(checkbox).toBeChecked()
      fireEvent.click(checkbox)
      expect(checkbox).not.toBeChecked()

      const assignmentsCancel = screen.getByRole('button', { name: 'Запази промените' })
        .closest('.action-group')!
      fireEvent.click(within(assignmentsCancel as HTMLElement).getByRole('button', { name: 'Отказ' }))
      expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Откажи промените' }))
      // Confirming discard returns to read-only view mode with the
      // assignment untouched (the uncheck was discarded, not saved).
      expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
      expect(screen.getByText('Подстригване')).toBeInTheDocument()
    })

    it('shows a reload action on a concurrent-update conflict while saving assignments', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      mockedReplaceStaffMemberAssignments.mockRejectedValue(
        new ApiError(409, 'STAFF_MEMBER_CONCURRENT_UPDATE', 'Данните са променени.'),
      )
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      await openAssignmentEditor()
      fireEvent.click(await screen.findByRole('checkbox', { name: /Подстригване/ }))
      fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('Данните са променени.')
      expect(screen.getByRole('button', { name: 'Зареди актуалните данни' })).toBeInTheDocument()
    })

    it('hides assignment mutation controls when the Business is SUSPENDED', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      mockedListStaffMemberAssignments.mockResolvedValue({
        ...noAssignments,
        services: [{ id: 'service-a', name: 'Подстригване', active: true }],
      })
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      expect(await screen.findByText('Подстригване')).toBeInTheDocument()
      expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
      expect(
        screen.queryByRole('button', { name: 'Редактирай услугите' }),
      ).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Запази промените' })).not.toBeInTheDocument()
    })

    describe('loading every Service for assignment (not only the first page)', () => {
      function servicePage(page: number, size: number, totalElements: number, ids: string[]) {
        return {
          services: ids.map((id) => ({
            id,
            name: id,
            description: null,
            durationMinutes: 10,
            price: 9.9,
            active: true,
            version: 0,
            createdAt: '2026-08-19T09:00:00Z',
            updatedAt: '2026-08-19T09:00:00Z',
          })),
          page,
          size,
          totalElements,
        }
      }

      it('loads subsequent pages when there are more than 50 Services, keeping one page 2 Service assignable', async () => {
        mockedGetStaffMember.mockResolvedValue(staffMember)
        const page0Ids = Array.from({ length: 50 }, (_, index) => `service-${index}`)
        mockedListServices.mockImplementation(async (page = 0) => {
          if (page === 0) return servicePage(0, 50, 51, page0Ids)
          if (page === 1) return servicePage(1, 50, 51, ['service-page-2'])
          throw new Error(`unexpected page ${page}`)
        })
        render(
          <StaffDetail
            staffMemberId="staff-a"
            readOnly={false}
            onAuthenticationRequired={onAuthenticationRequired}
            onBack={onBack}
          />,
        )
        await screen.findByRole('heading', { name: 'Анна Иванова' })
        await openAssignmentEditor()

        const pageTwoCheckbox = await screen.findByRole('checkbox', { name: /service-page-2/ })
        expect(pageTwoCheckbox).toBeEnabled()
        fireEvent.click(pageTwoCheckbox)
        expect(pageTwoCheckbox).toBeChecked()

        expect(mockedListServices).toHaveBeenCalledWith(0, 50, 'name', 'asc', expect.any(AbortSignal))
        expect(mockedListServices).toHaveBeenCalledWith(1, 50, 'name', 'asc', expect.any(AbortSignal))
        // Every page request shares one AbortSignal.
        const signals = mockedListServices.mock.calls.map((call) => call[4])
        expect(signals[0]).toBe(signals[1])
      })

      it('keeps an assigned inactive Service visible and removable even when it is absent from the first Service page', async () => {
        mockedGetStaffMember.mockResolvedValue(staffMember)
        mockedListStaffMemberAssignments.mockResolvedValue({
          ...noAssignments,
          services: [{ id: 'service-inactive-assigned', name: 'Стара услуга', active: false }],
        })
        mockedListServices.mockResolvedValue(
          servicePage(0, 50, 1, ['service-only-active']),
        )
        render(
          <StaffDetail
            staffMemberId="staff-a"
            readOnly={false}
            onAuthenticationRequired={onAuthenticationRequired}
            onBack={onBack}
          />,
        )
        await screen.findByRole('heading', { name: 'Анна Иванова' })
        await openAssignmentEditor()

        const assignedInactive = await screen.findByRole('checkbox', { name: /Стара услуга/ })
        expect(assignedInactive).toBeChecked()
        expect(assignedInactive).toBeEnabled()
        fireEvent.click(assignedInactive)
        expect(assignedInactive).not.toBeChecked()
      })

      it('drops an inactive Service from the assignment candidates after it is unassigned and saved', async () => {
        mockedGetStaffMember.mockResolvedValue(staffMember)
        mockedListStaffMemberAssignments.mockResolvedValue({
          ...noAssignments,
          services: [{ id: 'service-inactive-assigned', name: 'Стара услуга', active: false }],
        })
        mockedListServices.mockResolvedValue(
          servicePage(0, 50, 1, ['service-only-active']),
        )
        mockedReplaceStaffMemberAssignments.mockResolvedValue({
          ...noAssignments,
          version: 1,
          services: [],
        })
        render(
          <StaffDetail
            staffMemberId="staff-a"
            readOnly={false}
            onAuthenticationRequired={onAuthenticationRequired}
            onBack={onBack}
          />,
        )
        await screen.findByRole('heading', { name: 'Анна Иванова' })
        await openAssignmentEditor()

        const assignedInactive = await screen.findByRole('checkbox', { name: /Стара услуга/ })
        fireEvent.click(assignedInactive)
        fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

        await waitFor(() => expect(mockedReplaceStaffMemberAssignments).toHaveBeenCalled())
        expect(await screen.findByText('Назначените услуги са запазени.')).toBeInTheDocument()
        // Back in read-only view: the now-unassigned inactive Service is gone.
        expect(screen.queryByText('Стара услуга')).not.toBeInTheDocument()

        // Re-entering edit mode must not offer it as a candidate either.
        await openAssignmentEditor()
        await screen.findByRole('checkbox', { name: /service-only-active/ })
        expect(
          screen.queryByRole('checkbox', { name: /Стара услуга/ }),
        ).not.toBeInTheDocument()
      })

      it('does not render a stale later-page response after the assignment editor is cancelled', async () => {
        mockedGetStaffMember.mockResolvedValue(staffMember)
        let resolvePageTwo: ((value: ReturnType<typeof servicePage>) => void) | undefined
        const page0Ids = Array.from({ length: 50 }, (_, index) => `service-${index}`)
        mockedListServices.mockImplementation(async (page = 0) => {
          if (page === 0) return servicePage(0, 50, 51, page0Ids)
          return new Promise((resolve) => {
            resolvePageTwo = resolve
          })
        })
        render(
          <StaffDetail
            staffMemberId="staff-a"
            readOnly={false}
            onAuthenticationRequired={onAuthenticationRequired}
            onBack={onBack}
          />,
        )
        await screen.findByRole('heading', { name: 'Анна Иванова' })
        await openAssignmentEditor()
        await screen.findByText('Зареждане на услугите…')

        // Leave edit mode before the page-2 request settles.
        fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
        expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()

        resolvePageTwo?.(servicePage(1, 50, 51, ['service-page-2']))
        await Promise.resolve()
        await Promise.resolve()

        expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
        expect(screen.getByRole('button', { name: 'Редактирай услугите' })).toBeInTheDocument()
      })
    })
  })

  describe('Mutually exclusive editors (profile vs. Service assignments)', () => {
    beforeEach(() => {
      mockedListStaffMemberAssignments.mockResolvedValue({
        ...noAssignments,
        services: [{ id: 'service-a', name: 'Подстригване', active: true }],
      })
    })

    it('guards opening the profile editor while Service assignments are dirty, and reject preserves the dirty assignments', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      fireEvent.click(await screen.findByRole('button', { name: 'Редактирай услугите' }))
      const checkbox = await screen.findByRole('checkbox', { name: /Подстригване/ })
      fireEvent.click(checkbox)
      expect(checkbox).not.toBeChecked()

      fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
      expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
      expect(screen.queryByLabelText('Име на члена на екипа')).not.toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: 'Продължи редактирането' }))
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Име на члена на екипа')).not.toBeInTheDocument()
      expect(screen.getByRole('checkbox', { name: /Подстригване/ })).not.toBeChecked()
    })

    it('confirming discard from dirty Service assignments opens the requested profile editor', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      fireEvent.click(await screen.findByRole('button', { name: 'Редактирай услугите' }))
      fireEvent.click(await screen.findByRole('checkbox', { name: /Подстригване/ }))

      fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
      fireEvent.click(screen.getByRole('button', { name: 'Откажи промените' }))

      expect(await screen.findByLabelText('Име на члена на екипа')).toBeInTheDocument()
      // The assignments editor unregistered cleanly: it is back in read-only
      // view with its original (unchecked-change discarded) assignment.
      expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    })

    it('guards opening the Service-assignment editor while the profile form is dirty, and reject preserves the dirty profile', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
      fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
        target: { value: 'Незаписано' },
      })

      fireEvent.click(await screen.findByRole('button', { name: 'Редактирай услугите' }))
      expect(screen.getByRole('alertdialog', { name: 'Незапазени промени' })).toBeInTheDocument()
      expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: 'Продължи редактирането' }))
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
      expect(screen.getByLabelText('Име на члена на екипа')).toHaveValue('Незаписано')
    })

    it('confirming discard from a dirty profile edit opens the requested Service-assignment editor', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
      fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
        target: { value: 'Незаписано' },
      })

      fireEvent.click(await screen.findByRole('button', { name: 'Редактирай услугите' }))
      fireEvent.click(screen.getByRole('button', { name: 'Откажи промените' }))

      expect(screen.queryByLabelText('Име на члена на екипа')).not.toBeInTheDocument()
      expect(await screen.findByRole('checkbox', { name: /Подстригване/ })).toBeChecked()
    })

    it('saving the profile form clears the guard so the Service-assignment editor opens without confirmation', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      mockedUpdateStaffMember.mockResolvedValue({ ...staffMember, version: 1 })
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
      fireEvent.change(screen.getByLabelText('Име на члена на екипа'), {
        target: { value: 'Анна Петрова' },
      })
      const form = document.querySelector('form.business-form') as HTMLElement
      fireEvent.click(within(form).getByRole('button', { name: 'Запази промените' }))
      await waitFor(() => expect(mockedUpdateStaffMember).toHaveBeenCalled())

      fireEvent.click(await screen.findByRole('button', { name: 'Редактирай услугите' }))
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
      expect(await screen.findByRole('checkbox', { name: /Подстригване/ })).toBeInTheDocument()
    })

    it('saving Service assignments clears the guard so the profile editor opens without confirmation', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      mockedReplaceStaffMemberAssignments.mockResolvedValue({
        ...noAssignments,
        version: 1,
        services: [],
      })
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      fireEvent.click(await screen.findByRole('button', { name: 'Редактирай услугите' }))
      fireEvent.click(await screen.findByRole('checkbox', { name: /Подстригване/ }))
      fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
      await waitFor(() => expect(mockedReplaceStaffMemberAssignments).toHaveBeenCalled())

      fireEvent.click(await screen.findByRole('button', { name: 'Редактирай' }))
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
      expect(await screen.findByLabelText('Име на члена на екипа')).toBeInTheDocument()
    })

    it('never shows two dirty editors at once: opening one always leaves the other in view mode', async () => {
      mockedGetStaffMember.mockResolvedValue(staffMember)
      render(
        <StaffDetail
          staffMemberId="staff-a"
          readOnly={false}
          onAuthenticationRequired={onAuthenticationRequired}
          onBack={onBack}
        />,
      )
      await screen.findByRole('heading', { name: 'Анна Иванова' })
      fireEvent.click(await screen.findByRole('button', { name: 'Редактирай услугите' }))
      fireEvent.click(await screen.findByRole('checkbox', { name: /Подстригване/ }))

      fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
      fireEvent.click(screen.getByRole('button', { name: 'Откажи промените' }))

      // Profile editor is open; assignments editor is back in view mode —
      // never both open (and dirty) simultaneously.
      expect(screen.getByLabelText('Име на члена на екипа')).toBeInTheDocument()
      expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Отказ', hidden: false })).toBeInTheDocument()
    })
  })
})
