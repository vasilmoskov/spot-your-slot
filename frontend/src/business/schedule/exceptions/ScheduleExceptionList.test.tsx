import '@testing-library/jest-dom/vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../identity/api'
import {
  withListDefaults,
  type DateWindow,
  type ExceptionListState,
  type ListNavigationMode,
} from '../../../navigation'
import { listStaffMembers, type StaffMemberPage, type StaffMemberSummary } from '../../staff/api'
import {
  listScheduleExceptions,
  type ScheduleExceptionItem,
  type ScheduleExceptionWindow,
} from './api'
import { ScheduleExceptionList } from './ScheduleExceptionList'

vi.mock('../../staff/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../staff/api')>()),
  listStaffMembers: vi.fn(),
}))
vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  listScheduleExceptions: vi.fn(),
}))

const mockedList = vi.mocked(listScheduleExceptions)
const mockedStaff = vi.mocked(listStaffMembers)

// Noon UTC is the same calendar date in every ordinary browser zone, so the
// provisional (browser-local) window is 2026-10-05..2026-11-03 regardless of
// where the tests run.
const NOW = new Date('2026-10-05T12:00:00Z')
const PROVISIONAL: DateWindow = { from: '2026-10-05', to: '2026-11-03' }

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

function staffPage(members: StaffMemberSummary[], overrides: Partial<StaffMemberPage> = {}) {
  return { staffMembers: members, page: 0, size: 50, totalElements: members.length, ...overrides }
}

function item(overrides: Partial<ScheduleExceptionItem> = {}): ScheduleExceptionItem {
  return {
    id: 'exception-1',
    kind: 'BUSINESS_CLOSURE',
    staffMemberId: null,
    firstDate: '2026-10-10',
    lastDate: '2026-10-12',
    allDay: true,
    periods: [],
    version: 0,
    createdAt: '2026-09-29T08:00:00Z',
    updatedAt: '2026-09-29T08:00:00Z',
    ...overrides,
  }
}

function windowResponse(
  window: DateWindow,
  exceptions: ScheduleExceptionItem[] = [],
  timezone = 'Europe/Sofia',
): ScheduleExceptionWindow {
  return { from: window.from, to: window.to, timezone, exceptions }
}

type HarnessProps = {
  initialWindow?: (DateWindow & Partial<ExceptionListState>) | null
  readOnly?: boolean
  onChange?: (next: ExceptionListState, mode: ListNavigationMode) => void
  onOpen?: (id: string) => void
  onCreate?: () => void
  onAuthenticationRequired?: (detail: string) => void
}

// Plays the role of the router: adopts every window change into its own state.
function Harness({
  initialWindow = null,
  readOnly = false,
  onChange = () => undefined,
  onOpen,
  onCreate,
  onAuthenticationRequired = () => undefined,
}: HarnessProps) {
  const [window, setWindow] = useState<ExceptionListState | null>(() =>
    initialWindow ? { ...withListDefaults(initialWindow), ...initialWindow } : null,
  )
  return (
    <ScheduleExceptionList
      readOnly={readOnly}
      window={window}
      now={() => NOW}
      onWindowChange={(next, mode) => {
        onChange(next, mode)
        setWindow(next)
      }}
      onAuthenticationRequired={onAuthenticationRequired}
      {...(onOpen ? { onOpen } : {})}
      {...(onCreate ? { onCreate } : {})}
    />
  )
}

beforeEach(() => {
  mockedList.mockReset()
  mockedStaff.mockReset()
  mockedStaff.mockResolvedValue(staffPage([staff('staff-a', 'Анна Иванова')]))
})

describe('provisional window bootstrap', () => {
  it('requests the browser-local 30-date window, then keeps it when the Business date agrees and replaces history without refetching', async () => {
    mockedList.mockImplementation((from, to) =>
      Promise.resolve(windowResponse({ from, to }, [])),
    )
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    expect(await screen.findByText('Няма промени за избрания период.')).toBeInTheDocument()
    expect(mockedList).toHaveBeenCalledTimes(1)
    expect(mockedList.mock.calls[0]!.slice(0, 2)).toEqual([PROVISIONAL.from, PROVISIONAL.to])
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith(withListDefaults(PROVISIONAL), 'replace')
  })

  it('replaces (never pushes) and refetches exactly once when the Business-local date differs', async () => {
    // 12:00Z is already 2026-10-06 in Kiritimati (UTC+14).
    mockedList.mockImplementation((from, to) =>
      Promise.resolve(windowResponse({ from, to }, [], 'Pacific/Kiritimati')),
    )
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    await screen.findByText('Няма промени за избрания период.')

    expect(mockedList).toHaveBeenCalledTimes(2)
    expect(mockedList.mock.calls[0]!.slice(0, 2)).toEqual(['2026-10-05', '2026-11-03'])
    expect(mockedList.mock.calls[1]!.slice(0, 2)).toEqual(['2026-10-06', '2026-11-04'])
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith(
      withListDefaults({ from: '2026-10-06', to: '2026-11-04' }),
      'replace',
    )
    expect(onChange).not.toHaveBeenCalledWith(expect.anything(), 'push')
    expect(screen.getByLabelText('От')).toHaveValue('2026-10-06')
    expect(screen.getByLabelText('До')).toHaveValue('2026-11-04')
  })

  it('keeps the provisional window when the timezone is unusable, without looping', async () => {
    mockedList.mockImplementation((from, to) =>
      Promise.resolve(windowResponse({ from, to }, [], 'Not/AZone')),
    )
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    await screen.findByText('Няма промени за избрания период.')
    expect(mockedList).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith(withListDefaults(PROVISIONAL), 'replace')
  })

  it('does not renormalize a window that is already explicit in the URL', async () => {
    const explicit = { from: '2026-10-01', to: '2026-10-10' }
    mockedList.mockResolvedValue(windowResponse(explicit, [], 'Pacific/Kiritimati'))
    const onChange = vi.fn()
    render(<Harness initialWindow={explicit} onChange={onChange} />)

    await screen.findByText('Няма промени за избрания период.')
    expect(mockedList).toHaveBeenCalledTimes(1)
    expect(mockedList.mock.calls[0]!.slice(0, 2)).toEqual(['2026-10-01', '2026-10-10'])
    expect(onChange).not.toHaveBeenCalled()
  })

  it('discards a stale provisional response when the route window changes first', async () => {
    let resolveProvisional!: (value: ScheduleExceptionWindow) => void
    mockedList.mockImplementationOnce(
      () => new Promise((resolve) => (resolveProvisional = resolve)),
    )
    const explicit = { from: '2026-10-01', to: '2026-10-10' }
    mockedList.mockResolvedValueOnce(
      windowResponse(explicit, [item({ id: 'fresh', firstDate: '2026-10-02', lastDate: '2026-10-02' })]),
    )
    const onChange = vi.fn()
    function Switching() {
      const [window, setWindow] = useState<ExceptionListState | null>(null)
      return (
        <>
          <button type="button" onClick={() => setWindow(withListDefaults(explicit))}>
            adopt
          </button>
          <ScheduleExceptionList
            readOnly={false}
            window={window}
            now={() => NOW}
            onWindowChange={onChange}
            onAuthenticationRequired={() => undefined}
          />
        </>
      )
    }
    render(<Switching />)
    fireEvent.click(screen.getByRole('button', { name: 'adopt' }))
    await screen.findByRole('link', { name: /Отвори: Неработно време, 02.10.2026/ })

    await act(async () => {
      resolveProvisional(windowResponse(PROVISIONAL, [item({ id: 'stale' })]))
    })

    expect(screen.getAllByRole('link')).toHaveLength(1)
    expect(onChange).not.toHaveBeenCalled()
  })
})

describe('window filter', () => {
  const explicit = { from: '2026-10-01', to: '2026-10-30' }

  async function open(props: HarnessProps = {}) {
    mockedList.mockImplementation((from, to) => Promise.resolve(windowResponse({ from, to })))
    render(<Harness initialWindow={explicit} {...props} />)
    await screen.findByText('Няма промени за избрания период.')
  }

  it('pushes history when a valid window is applied and refetches it', async () => {
    const onChange = vi.fn()
    await open({ onChange })

    fireEvent.change(screen.getByLabelText('От'), { target: { value: '2026-11-01' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-11-20' } })
    fireEvent.click(screen.getByRole('button', { name: 'Покажи' }))

    await waitFor(() => expect(mockedList).toHaveBeenCalledTimes(2))
    expect(onChange).toHaveBeenCalledWith(withListDefaults({ from: '2026-11-01', to: '2026-11-20' }), 'push')
    expect(mockedList.mock.calls[1]!.slice(0, 2)).toEqual(['2026-11-01', '2026-11-20'])
  })

  it('shares the date presentation: a "Период" group with "От", "До" and the apply button', async () => {
    await open()
    const group = screen.getByRole('group', { name: 'Период' })
    expect(screen.queryByText('Период')).not.toBeInTheDocument()
    expect(within(group).getByLabelText('От')).toHaveValue('2026-10-01')
    expect(within(group).getByLabelText('До')).toHaveValue('2026-10-30')
    expect(within(group).getByRole('button', { name: 'Покажи' })).toBeInTheDocument()
  })

  it('sends nothing when the applied window is unchanged', async () => {
    const onChange = vi.fn()
    await open({ onChange })

    fireEvent.click(screen.getByRole('button', { name: 'Покажи' }))

    expect(onChange).not.toHaveBeenCalled()
    expect(mockedList).toHaveBeenCalledTimes(1)
  })

  it('shows required errors under the fields only after submit and focuses the first', async () => {
    await open()
    expect(screen.queryByText('Въведете начална дата.')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('От'), { target: { value: '' } })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Покажи' }))

    expect(screen.getByText('Въведете начална дата.')).toBeInTheDocument()
    expect(screen.getByText('Въведете крайна дата.')).toBeInTheDocument()
    expect(screen.getByLabelText('От')).toHaveFocus()
    expect(screen.getByLabelText('От')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('От')).toHaveAccessibleDescription('Въведете начална дата.')
    expect(mockedList).toHaveBeenCalledTimes(1)
  })

  it('rejects a reversed window and a window over 93 dates without a request', async () => {
    const onChange = vi.fn()
    await open({ onChange })

    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-09-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Покажи' }))
    expect(
      screen.getByText('Крайната дата не може да бъде преди началната.'),
    ).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2027-01-02' } })
    expect(screen.getByText(/най-много 93 дни/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Покажи' }))

    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2027-01-01' } })
    expect(screen.queryByText(/най-много 93 дни/)).not.toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
    expect(mockedList).toHaveBeenCalledTimes(1)
  })
})

describe('list presentation', () => {
  const explicit = { from: '2026-10-01', to: '2026-10-30' }

  it('presents all four kinds in Bulgarian with names, ranges and hours', async () => {
    mockedStaff.mockResolvedValue(
      staffPage([staff('staff-a', 'Анна Иванова'), staff('staff-b', 'Борис Петров')]),
    )
    mockedList.mockResolvedValue(
      windowResponse(explicit, [
        item({ id: 'c' }),
        item({
          id: 't',
          kind: 'STAFF_TIME_OFF',
          staffMemberId: 'staff-a',
          firstDate: '2026-10-14',
          lastDate: '2026-10-14',
          allDay: false,
          periods: [{ startTime: '13:00', endTime: '14:30' }],
        }),
        item({
          id: 'o',
          kind: 'WORKING_DAY_OVERRIDE',
          staffMemberId: 'staff-b',
          firstDate: '2026-10-15',
          lastDate: '2026-10-15',
          allDay: false,
          periods: [],
        }),
        item({
          id: 'a',
          kind: 'ADDITIONAL_WORKING_PERIODS',
          staffMemberId: 'staff-b',
          firstDate: '2026-10-16',
          lastDate: '2026-10-16',
          allDay: false,
          periods: [
            { startTime: '09:00', endTime: '12:00' },
            { startTime: '14:00', endTime: '18:00' },
          ],
        }),
      ]),
    )
    render(<Harness initialWindow={explicit} />)

    const table = await screen.findByRole('table', { name: 'Промени в графика' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(4)
    expect(rows[0]).toHaveTextContent('Неработно време')
    expect(rows[0]).toHaveTextContent('10.10.2026 – 12.10.2026')
    expect(rows[0]).toHaveTextContent('Цял ден')
    expect(rows[1]).toHaveTextContent('Отсъствие')
    expect(rows[1]).toHaveTextContent('14.10.2026')
    expect(rows[1]).toHaveTextContent('Анна Иванова')
    expect(rows[1]).toHaveTextContent('13:00–14:30')
    expect(rows[2]).toHaveTextContent('Променени работни часове')
    expect(rows[2]).toHaveTextContent('Борис Петров')
    expect(rows[2]).toHaveTextContent('Неработен ден')
    expect(rows[3]).toHaveTextContent('Допълнителни работни часове')
    expect(rows[3]).toHaveTextContent('09:00–12:00, 14:00–18:00')
    expect(table).not.toHaveTextContent(/BUSINESS_CLOSURE|STAFF_TIME_OFF|exception/i)
    expect(screen.queryByText('Активен')).not.toBeInTheDocument()
  })

  it('names the link by kind and date and opens the record', async () => {
    mockedList.mockResolvedValue(windowResponse(explicit, [item()]))
    const onOpen = vi.fn()
    render(<Harness initialWindow={explicit} onOpen={onOpen} />)

    const link = await screen.findByRole('link', {
      name: 'Отвори: Неработно време, 10.10.2026 – 12.10.2026',
    })
    expect(link).toHaveAttribute(
      'href',
      '/#/business/schedule/exceptions/exception-1?from=2026-10-01&to=2026-10-30&page=0&size=10&sort=dates&direction=asc',
    )
    fireEvent.click(link)
    expect(onOpen).toHaveBeenCalledWith('exception-1')
  })

  it('loads every StaffMember page beyond 50 with one shared signal so late names resolve', async () => {
    const first = Array.from({ length: 50 }, (_, index) => staff(`s-${index}`, `Член ${index}`))
    mockedStaff.mockImplementation((page = 0) =>
      Promise.resolve(
        page === 0
          ? staffPage(first, { totalElements: 51 })
          : staffPage([staff('late', 'Последна Иванова')], { page: 1, totalElements: 51 }),
      ),
    )
    mockedList.mockResolvedValue(
      windowResponse(explicit, [
        item({ kind: 'STAFF_TIME_OFF', staffMemberId: 'late', allDay: true }),
      ]),
    )
    render(<Harness initialWindow={explicit} />)

    expect(await screen.findByText('Последна Иванова')).toBeInTheDocument()
    expect(mockedStaff).toHaveBeenCalledTimes(2)
    expect(mockedStaff.mock.calls.map((call) => [call[0], call[1]])).toEqual([
      [0, 50],
      [1, 50],
    ])
    const signals = mockedStaff.mock.calls.map((call) => call[4])
    expect(signals[0]).toBeInstanceOf(AbortSignal)
    expect(signals[1]).toBe(signals[0])
  })

  it('keeps the name of an inactive StaffMember without a status badge', async () => {
    mockedStaff.mockResolvedValue(staffPage([staff('staff-x', 'Стефан Стоянов', false)]))
    mockedList.mockResolvedValue(
      windowResponse(explicit, [
        item({ kind: 'STAFF_TIME_OFF', staffMemberId: 'staff-x', allDay: true }),
      ]),
    )
    render(<Harness initialWindow={explicit} />)

    expect(await screen.findByText('Стефан Стоянов')).toBeInTheDocument()
    expect(screen.queryByText(/неактив/i)).not.toBeInTheDocument()
    expect(screen.queryByText('Активен')).not.toBeInTheDocument()
  })

  it('shows the create action, and hides it for a SUSPENDED Business while keeping rows readable', async () => {
    mockedList.mockResolvedValue(windowResponse(explicit, [item()]))
    const { unmount } = render(<Harness initialWindow={explicit} onCreate={vi.fn()} />)
    expect(await screen.findByRole('button', { name: 'Добави промяна' })).toBeInTheDocument()
    unmount()

    render(<Harness initialWindow={explicit} readOnly />)
    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Добави промяна' })).not.toBeInTheDocument()
  })

  it('calls the create handler', async () => {
    mockedList.mockResolvedValue(windowResponse(explicit, []))
    const onCreate = vi.fn()
    render(<Harness initialWindow={explicit} onCreate={onCreate} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Добави промяна' }))
    expect(onCreate).toHaveBeenCalledTimes(1)
  })
})

describe('failure handling', () => {
  const explicit = { from: '2026-10-01', to: '2026-10-30' }

  it('offers a retry after a generic failure', async () => {
    mockedList.mockRejectedValueOnce(new Error('boom'))
    mockedList.mockResolvedValueOnce(windowResponse(explicit, [item()]))
    render(<Harness initialWindow={explicit} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Промените не могат да бъдат заредени.')
    fireEvent.click(screen.getByRole('button', { name: 'Опитай отново' }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
  })

  it('reports an authentication failure to the application', async () => {
    mockedList.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    const onAuthenticationRequired = vi.fn()
    render(<Harness initialWindow={explicit} onAuthenticationRequired={onAuthenticationRequired} />)

    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'))
  })

  it('shows the safe authorization message for a 403', async () => {
    mockedList.mockRejectedValue(new ApiError(403, 'ACCESS_DENIED', 'Нямате достъп до тази операция.'))
    render(<Harness initialWindow={explicit} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Нямате достъп до тази операция.')
  })

  it('aborts the pending request on unmount and ignores its late response', async () => {
    let signal: AbortSignal | undefined
    let resolve!: (value: ScheduleExceptionWindow) => void
    mockedList.mockImplementation((_from, _to, abort) => {
      signal = abort
      return new Promise((done) => (resolve = done))
    })
    const onChange = vi.fn()
    const { unmount } = render(<Harness initialWindow={explicit} onChange={onChange} />)
    await waitFor(() => expect(signal).toBeDefined())

    unmount()
    expect(signal!.aborted).toBe(true)
    await act(async () => resolve(windowResponse(explicit, [item()])))
    expect(onChange).not.toHaveBeenCalled()
  })
})

describe('status and sorting', () => {
  const explicit: DateWindow = { from: '2026-10-01', to: '2026-10-30' }
  const records = [
    item({ id: 'r-upcoming', kind: 'BUSINESS_CLOSURE', firstDate: '2026-10-20', lastDate: '2026-10-21' }),
    item({ id: 'r-past', kind: 'BUSINESS_CLOSURE', firstDate: '2026-10-01', lastDate: '2026-10-04' }),
    item({
      id: 'r-now',
      kind: 'STAFF_TIME_OFF',
      staffMemberId: 'staff-a',
      firstDate: '2026-10-04',
      lastDate: '2026-10-06',
    }),
  ]

  function rowKinds(): string[] {
    return within(screen.getAllByRole('rowgroup')[1]!)
      .getAllByRole('row')
      .map((row) => within(row).getAllByRole('cell')[1]!.textContent ?? '')
  }

  async function renderList(
    window: (DateWindow & Partial<ExceptionListState>) | null = explicit,
    onChange = vi.fn(),
  ) {
    mockedList.mockResolvedValue(windowResponse(explicit, records))
    render(<Harness initialWindow={window} onChange={onChange} />)
    await screen.findByRole('table')
    return onChange
  }

  it('shows a status badge with text in every row, derived from the Business-local date', async () => {
    await renderList()
    const rows = within(screen.getAllByRole('rowgroup')[1]!).getAllByRole('row')
    const statuses = rows.map((row) => within(row).getAllByRole('cell')[4]!.textContent)
    expect(statuses).toEqual(['Минала', 'В сила', 'Предстояща'])
    for (const row of rows) {
      expect(row.querySelector('.status-badge')).not.toBeNull()
      expect(within(row).getAllByRole('cell')[4]).toHaveAttribute('data-label', 'Статус')
    }
  })

  it('uses the Business timezone rather than the browser date for the badge', async () => {
    // 2026-10-05T12:00Z is 2026-10-06 in Kiritimati (UTC+14), so a record for the 6th is
    // in effect there and upcoming in Sofia — independent of the test machine's zone.
    const sixth = item({ id: 'r-six', firstDate: '2026-10-06', lastDate: '2026-10-06' })
    mockedList.mockResolvedValue(windowResponse(explicit, [sixth], 'Pacific/Kiritimati'))
    const { unmount } = render(<Harness initialWindow={explicit} />)
    await screen.findByRole('table')
    expect(screen.getByText('В сила')).toBeInTheDocument()
    unmount()

    mockedList.mockResolvedValue(windowResponse(explicit, [sixth], 'Europe/Sofia'))
    render(<Harness initialWindow={explicit} />)
    await screen.findByRole('table')
    expect(screen.getByText('Предстояща')).toBeInTheDocument()
  })

  it('ignores any status the API might send', async () => {
    const forged = { ...records[1]!, status: 'IN_EFFECT' } as ScheduleExceptionItem
    mockedList.mockResolvedValue(windowResponse(explicit, [forged]))
    render(<Harness initialWindow={explicit} />)
    await screen.findByRole('table')
    expect(screen.getByText('Минала')).toBeInTheDocument()
  })

  it('makes exactly Вид, Дати, Член на екипа and Статус sortable, never Часове', async () => {
    await renderList()
    const headers = screen.getAllByRole('columnheader')
    expect(headers.map((header) => header.textContent?.replace(/[▲▼]/g, '').trim())).toEqual([
      'Вид',
      'Дати',
      'Член на екипа',
      'Часове',
      'Статус',
    ])
    expect(within(headers[3]!).queryByRole('button')).toBeNull()
    expect(headers[3]).not.toHaveAttribute('aria-sort')
    for (const index of [0, 1, 2, 4]) {
      expect(within(headers[index]!).getByRole('button')).toBeInTheDocument()
      expect(headers[index]!.querySelectorAll('.sort-arrow')).toHaveLength(2)
    }
  })

  it('presents the default ordering as Дати ascending with only that arrow emphasized', async () => {
    await renderList()
    const dates = screen.getByRole('columnheader', { name: /Дати/ })
    expect(dates).toHaveAttribute('aria-sort', 'ascending')
    expect(dates.querySelectorAll('.sort-arrow.is-active')).toHaveLength(1)
    expect(dates.querySelector('.sort-arrow.is-active')?.textContent).toBe('▲')
    expect(screen.getByRole('columnheader', { name: /Вид/ })).toHaveAttribute('aria-sort', 'none')
    expect(document.querySelectorAll('.sort-arrow.is-active')).toHaveLength(1)
    expect(rowKinds()).toEqual(['01.10.2026 – 04.10.2026', '04.10.2026 – 06.10.2026', '20.10.2026 – 21.10.2026'])
  })

  it('pushes an explicit sort, toggles the direction and keeps the window', async () => {
    const onChange = await renderList()
    fireEvent.click(within(screen.getByRole('columnheader', { name: /Статус/ })).getByRole('button'))
    expect(onChange).toHaveBeenLastCalledWith(
      { ...withListDefaults(explicit), sort: 'status', direction: 'asc' },
      'push',
    )
    const status = screen.getByRole('columnheader', { name: /Статус/ })
    expect(status).toHaveAttribute('aria-sort', 'ascending')
    expect(status.querySelector('.sort-arrow.is-active')?.textContent).toBe('▲')
    expect(rowKinds()).toEqual(['04.10.2026 – 06.10.2026', '20.10.2026 – 21.10.2026', '01.10.2026 – 04.10.2026'])

    fireEvent.click(within(status).getByRole('button'))
    expect(onChange).toHaveBeenLastCalledWith(
      { ...withListDefaults(explicit), sort: 'status', direction: 'desc' },
      'push',
    )
    expect(screen.getByRole('columnheader', { name: /Статус/ })).toHaveAttribute('aria-sort', 'descending')
    expect(
      screen.getByRole('columnheader', { name: /Статус/ }).querySelector('.sort-arrow.is-active')
        ?.textContent,
    ).toBe('▼')
    expect(rowKinds()).toEqual(['01.10.2026 – 04.10.2026', '20.10.2026 – 21.10.2026', '04.10.2026 – 06.10.2026'])
    // Sorting is local: it never refetches.
    expect(mockedList).toHaveBeenCalledTimes(1)
  })

  it('sorts each sortable column in both directions', async () => {
    await renderList()
    const dates = () => rowKinds()
    const click = (name: RegExp) =>
      fireEvent.click(within(screen.getByRole('columnheader', { name })).getByRole('button'))

    click(/Вид/)
    // Неработно време ×2 (by date) then Отсъствие.
    expect(dates()).toEqual(['01.10.2026 – 04.10.2026', '20.10.2026 – 21.10.2026', '04.10.2026 – 06.10.2026'])
    click(/Вид/)
    expect(dates()).toEqual(['04.10.2026 – 06.10.2026', '01.10.2026 – 04.10.2026', '20.10.2026 – 21.10.2026'])

    click(/Член на екипа/)
    // Named member first; Business-wide records after, in both directions.
    expect(dates()[0]).toBe('04.10.2026 – 06.10.2026')
    click(/Член на екипа/)
    expect(dates()[0]).toBe('04.10.2026 – 06.10.2026')
    expect(dates()[2]).not.toBe('04.10.2026 – 06.10.2026')

    click(/Дати/)
    expect(dates()[0]).toBe('01.10.2026 – 04.10.2026')
    click(/Дати/)
    expect(dates()[0]).toBe('20.10.2026 – 21.10.2026')
  })

  it('sorts from the keyboard through the native header button', async () => {
    await renderList()
    const button = within(screen.getByRole('columnheader', { name: /Вид/ })).getByRole('button')
    button.focus()
    expect(button).toHaveFocus()
    expect(button.tagName).toBe('BUTTON')
    fireEvent.click(button) // Enter and Space activate a button through a click
    expect(screen.getByRole('columnheader', { name: /Вид/ })).toHaveAttribute('aria-sort', 'ascending')
  })

  it('restores a sort from the route and offers the same orderings in the responsive select', async () => {
    await renderList({ ...explicit, sort: 'kind', direction: 'desc' })
    expect(screen.getByRole('columnheader', { name: /Вид/ })).toHaveAttribute('aria-sort', 'descending')
    const select = screen.getByLabelText('Подреди по') as HTMLSelectElement
    expect(select.value).toBe('kind:desc')
    expect([...select.options].map((option) => option.value)).toEqual([
      'kind:asc',
      'kind:desc',
      'dates:asc',
      'dates:desc',
      'staff:asc',
      'staff:desc',
      'status:asc',
      'status:desc',
    ])
    fireEvent.change(select, { target: { value: 'staff:asc' } })
    expect(screen.getByRole('columnheader', { name: /Член на екипа/ })).toHaveAttribute(
      'aria-sort',
      'ascending',
    )
  })

  it('keeps the sort when a new window is applied', async () => {
    const onChange = await renderList({ ...explicit, sort: 'status', direction: 'desc' })
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-10-25' } })
    fireEvent.click(screen.getByRole('button', { name: 'Покажи' }))
    expect(onChange).toHaveBeenLastCalledWith(
      { ...withListDefaults({ from: '2026-10-01', to: '2026-10-25' }), sort: 'status', direction: 'desc' },
      'push',
    )
  })

  it('renders no trash icon, actions column or list-level delete', async () => {
    await renderList()
    expect(screen.queryByRole('button', { name: /Изтрий/ })).toBeNull()
    expect(screen.queryByRole('columnheader', { name: /Действия/ })).toBeNull()
    expect(document.querySelectorAll('thead th')).toHaveLength(5)
    expect(document.querySelector('tbody button')).toBeNull()
    expect(document.body.textContent).not.toMatch(/🗑|Изтрий/)
  })
})

describe('pagination', () => {
  const explicit: DateWindow = { from: '2026-10-01', to: '2026-10-30' }

  // Dates run backwards from the 29th so the default (ascending) order is the reverse of the
  // API order: pagination must slice the sorted list, not the received one.
  function many(count: number): ScheduleExceptionItem[] {
    return Array.from({ length: count }, (_, index) => {
      const day = String(29 - (index % 28)).padStart(2, '0')
      return item({
        id: `id-${String(index).padStart(3, '0')}`,
        firstDate: `2026-10-${day}`,
        lastDate: `2026-10-${day}`,
      })
    })
  }

  const rowCount = () => document.querySelectorAll('tbody tr').length

  async function renderMany(
    count: number,
    window: Partial<ExceptionListState> = {},
    onChange = vi.fn(),
  ) {
    mockedList.mockResolvedValue(windowResponse(explicit, many(count)))
    render(<Harness initialWindow={{ ...explicit, ...window }} onChange={onChange} />)
    await screen.findByRole('table')
    return onChange
  }

  it('renders 10 rows by default and 25 or 50 when selected', async () => {
    await renderMany(60)
    expect(rowCount()).toBe(10)
    fireEvent.change(screen.getByLabelText('Резултати на страница'), { target: { value: '25' } })
    expect(rowCount()).toBe(25)
    fireEvent.change(screen.getByLabelText('Резултати на страница'), { target: { value: '50' } })
    expect(rowCount()).toBe(50)
  })

  it('has exactly one page-size selector, inside the labelled pagination region', async () => {
    await renderMany(30)
    const selectors = screen.getAllByLabelText('Резултати на страница')
    expect(selectors).toHaveLength(1)
    const region = screen.getByRole('navigation', { name: 'Странициране на промените в графика' })
    expect(region).toContainElement(selectors[0]!)
    expect(within(region).getByRole('button', { name: 'Предишна' })).toBeInTheDocument()
    expect(within(region).getByRole('button', { name: 'Следваща' })).toBeInTheDocument()
  })

  it('shows correct ranges and totals and toggles Previous/Next at the ends', async () => {
    await renderMany(37)
    expect(screen.getByText('Показани 1–10 от 37')).toBeInTheDocument()
    expect(screen.getByText('Страница 1 от 4')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Предишна' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Следваща' })).toBeEnabled()

    for (let step = 0; step < 3; step += 1) fireEvent.click(screen.getByRole('button', { name: 'Следваща' }))
    expect(screen.getByText('Показани 31–37 от 37')).toBeInTheDocument()
    expect(rowCount()).toBe(7)
    expect(screen.getByRole('button', { name: 'Следваща' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Предишна' })).toBeEnabled()
  })

  it('sorts the whole result before slicing the page', async () => {
    await renderMany(37)
    const firstColumn = () =>
      [...document.querySelectorAll('tbody tr')].map((row) => (row as HTMLTableRowElement).cells[1]!.textContent)
    // Ascending by date: the earliest date overall leads page 1, although it is last in the API order.
    expect(firstColumn()[0]).toBe('02.10.2026')
    fireEvent.click(within(screen.getByRole('columnheader', { name: /Дати/ })).getByRole('button'))
    // Descending: the latest date leads page 1.
    expect(firstColumn()[0]).toBe('29.10.2026')
  })

  it('pushes page, size, sort and filter changes, each returning to page 0 where required', async () => {
    const onChange = await renderMany(60)
    fireEvent.click(screen.getByRole('button', { name: 'Следваща' }))
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 10 }), 'push')

    fireEvent.change(screen.getByLabelText('Резултати на страница'), { target: { value: '25' } })
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, size: 25 }), 'push')

    fireEvent.click(screen.getByRole('button', { name: 'Следваща' }))
    fireEvent.click(within(screen.getByRole('columnheader', { name: /Вид/ })).getByRole('button'))
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 0, size: 25, sort: 'kind', direction: 'asc' }),
      'push',
    )

    fireEvent.click(screen.getByRole('button', { name: 'Следваща' }))
    fireEvent.change(screen.getByLabelText('До'), { target: { value: '2026-10-20' } })
    fireEvent.click(screen.getByRole('button', { name: 'Покажи' }))
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ to: '2026-10-20', page: 0, size: 25, sort: 'kind', direction: 'asc' }),
      'push',
    )
  })

  it('recovers an out-of-range page by replacing history, keeping everything else', async () => {
    const onChange = await renderMany(37, { page: 9, size: 10, sort: 'kind', direction: 'desc' })
    // No empty or stale page is ever rendered.
    expect(rowCount()).toBe(7)
    expect(screen.getByText('Показани 31–37 от 37')).toBeInTheDocument()
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith(
        { ...withListDefaults(explicit), page: 3, sort: 'kind', direction: 'desc' },
        'replace',
      ),
    )
    expect(onChange).not.toHaveBeenCalledWith(expect.anything(), 'push')
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('recovers to page 0 for an empty result and offers no pagination', async () => {
    mockedList.mockResolvedValue(windowResponse(explicit, []))
    const onChange = vi.fn()
    render(<Harness initialWindow={{ ...explicit, page: 4 }} onChange={onChange} />)
    await screen.findByText('Няма промени за избрания период.')
    expect(screen.queryByRole('navigation', { name: /Странициране/ })).toBeNull()
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ page: 0 }), 'replace'),
    )
  })

  it('shows the same page in the table rows that the cards use (one DOM, no second list)', async () => {
    await renderMany(37)
    expect(document.querySelectorAll('table')).toHaveLength(1)
    expect(document.querySelectorAll('tbody tr')).toHaveLength(10)
  })

  it('keeps focus inside the region when the last page disables Next', async () => {
    await renderMany(11)
    const next = screen.getByRole('button', { name: 'Следваща' })
    next.focus()
    fireEvent.click(next)
    await waitFor(() => expect(screen.getByText('Показани 11–11 от 11')).toHaveFocus())
  })
})
