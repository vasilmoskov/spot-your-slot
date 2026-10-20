import { useEffect, useId, useRef, useState } from 'react'
import {
  CALENDAR_VIEWS,
  routeHref,
  SCHEDULE_MAX_DATE,
  SCHEDULE_MIN_DATE,
  isCanonicalDate,
  type CalendarState,
  type CalendarView,
  type ListNavigationMode,
  type ListPageSize,
} from '../../navigation'
import { Button } from '../../ui/Button'
import { ListPagination } from '../../ui/ListSortControls'
import { loadEveryStaffMember } from '../staff/fullCatalog'
import { localDateIn } from '../schedule/exceptions/presentation'
import {
  getAgenda,
  getCalendar,
  type AgendaResponse,
  type CalendarResponse,
} from './api'
import { rangeFor, shiftDate } from './dates'
import {
  calendarFailureMessage,
  isAuthenticationRequired,
  isStaffMemberMissing,
} from './errors'
import {
  formatDateHeading,
  formatMonthHeading,
  formatWeekRange,
  VIEW_LABELS,
} from './presentation'
import { AgendaList, DayView, WeekView } from './CalendarViews'
import { useWideViewport } from './useWideViewport'

type BusinessCalendarProps = {
  state: CalendarState
  // Injected clock, only used to bootstrap "today" until the Business timezone is known.
  now?: () => Date
  onStateChange: (next: CalendarState, mode?: ListNavigationMode) => void
  onOpenAppointment: (appointmentId: string, calendar: CalendarState) => void
  // Only an ACTIVE Business may create: otherwise no control exists at all (not a disabled one).
  canCreate?: boolean
  // A free place in the day grid was chosen: a proposal for the manual-create journey, never a creation.
  // The primary action of the page, under the title; offered only where the caller allows creating.
  onCreate?: (calendar: CalendarState) => void
  onAuthenticationRequired: (detail: string) => void
}

type Loaded =
  | { kind: 'calendar'; response: CalendarResponse }
  | { kind: 'agenda'; response: AgendaResponse }

type LoadState =
  | { kind: 'loading' }
  // `refreshing`: a newer request is in flight and the previous answer stays on screen.
  // `requested` is the exact calendar state the data answers, so a refresh in flight keeps the old
  // answer rendered as the view it was loaded for.
  | { kind: 'loaded'; data: Loaded; requested: CalendarState; refreshing: boolean }
  | { kind: 'error'; message: string }

type StaffOption = { id: string; label: string }

const defaultNow = () => new Date()

// The accessible names of the icon-only period controls, appropriate to the selected view.
const STEP_LABELS: Record<CalendarView, readonly [string, string]> = {
  day: ['Предишен ден', 'Следващ ден'],
  week: ['Предишна седмица', 'Следваща седмица'],
  agenda: ['Предишен месец', 'Следващ месец'],
}

function periodHeading(state: CalendarState, date: string): string {
  const range = rangeFor(state.view, date)
  if (state.view === 'day') return formatDateHeading(date)
  if (state.view === 'agenda') return formatMonthHeading(date)
  return formatWeekRange(range.from, range.to)
}

export function BusinessCalendar({
  state,
  now = defaultNow,
  onStateChange,
  onOpenAppointment,
  canCreate = false,
  onCreate,
  onAuthenticationRequired,
}: BusinessCalendarProps) {
  const [load, setLoad] = useState<LoadState>({ kind: 'loading' })
  const [reloadToken, setReloadToken] = useState(0)
  const [staffOptions, setStaffOptions] = useState<StaffOption[]>([])
  const [staffGone, setStaffGone] = useState(false)
  const wide = useWideViewport()
  const headingId = useId()
  const staffSelectId = useId()
  const dateInputId = useId()

  // Callbacks are read through a ref so the data effect depends only on the request itself: a parent
  // re-render can never trigger an extra request.
  const callbacks = useRef({ state, now, onStateChange, onAuthenticationRequired })
  callbacks.current = { state, now, onStateChange, onAuthenticationRequired }
  // A response already fetched for the date the route is about to adopt (the provisional "today").
  const resolved = useRef<{ key: string; data: Loaded } | null>(null)

  const { view, staffMemberId, page, size } = state
  const provisionalDate = state.date === null ? (localDateIn(now()) ?? '2000-01-01') : ''
  const date = state.date ?? provisionalDate
  const range = rangeFor(view, date)
  const agenda = view === 'agenda'
  const requestPage = agenda ? page : 0
  const requestSize = agenda ? size : 0
  const dateKnown = state.date !== null

  useEffect(() => {
    const requestKey = `${view}|${range.from}|${range.to}|${staffMemberId}|${requestPage}|${requestSize}`
    const requested: CalendarState = {
      ...callbacks.current.state,
      date: callbacks.current.state.date ?? date,
    }
    const ready = resolved.current
    if (dateKnown && ready && ready.key === requestKey) {
      resolved.current = null
      setLoad({ kind: 'loaded', data: ready.data, requested, refreshing: false })
      return
    }
    const controller = new AbortController()
    setLoad((current) =>
      current.kind === 'loaded' ? { ...current, refreshing: true } : { kind: 'loading' },
    )
    const request: Promise<Loaded> = agenda
      ? getAgenda(range.from, range.to, staffMemberId, requestPage, requestSize, controller.signal).then(
          (response) => ({ kind: 'agenda' as const, response }),
        )
      : getCalendar(range.from, range.to, staffMemberId, controller.signal).then((response) => ({
          kind: 'calendar' as const,
          response,
        }))
    request
      .then((data) => {
        if (controller.signal.aborted) return
        const current = callbacks.current
        if (current.state.date === null) {
          // The first answer names the Business timezone: adopt its "today" by replacing history.
          const today = localDateIn(current.now(), data.response.timezone) ?? provisionalDate
          const adopted = { ...current.state, date: today }
          const adoptedRange = rangeFor(view, today)
          if (adoptedRange.from === range.from && adoptedRange.to === range.to) {
            resolved.current = {
              key: `${view}|${adoptedRange.from}|${adoptedRange.to}|${staffMemberId}|${requestPage}|${requestSize}`,
              data,
            }
          }
          current.onStateChange(adopted, 'replace')
          return
        }
        if (
          data.kind === 'agenda' &&
          requestPage > 0 &&
          data.response.items.length === 0
        ) {
          const lastValid = Math.max(0, Math.ceil(data.response.total / requestSize) - 1)
          if (lastValid !== requestPage) {
            current.onStateChange({ ...current.state, page: lastValid }, 'replace')
            return
          }
        }
        setLoad({ kind: 'loaded', data, requested, refreshing: false })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        if (isAuthenticationRequired(error)) {
          callbacks.current.onAuthenticationRequired(error.detail)
          return
        }
        if (isStaffMemberMissing(error) && staffMemberId !== null) {
          setStaffGone(true)
          callbacks.current.onStateChange({ ...callbacks.current.state, staffMemberId: null, page: 0 }, 'replace')
          return
        }
        setLoad({ kind: 'error', message: calendarFailureMessage(error) })
      })
    return () => controller.abort()
  }, [view, range.from, range.to, staffMemberId, requestPage, requestSize, dateKnown, agenda, provisionalDate, reloadToken])

  // The StaffMember filter options: every StaffMember once; failure leaves only "all staff".
  useEffect(() => {
    const controller = new AbortController()
    loadEveryStaffMember(controller.signal)
      .then((everyone) => {
        if (controller.signal.aborted) return
        setStaffOptions(
          everyone.map((staff) => ({
            id: staff.id,
            label: staff.active ? staff.displayName : `${staff.displayName} (неактивен)`,
          })),
        )
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        if (isAuthenticationRequired(error)) callbacks.current.onAuthenticationRequired(error.detail)
      })
    return () => controller.abort()
  }, [])

  const timezone = load.kind === 'loaded' ? load.data.response.timezone : null
  const businessToday = timezone ? (localDateIn(now(), timezone) ?? date) : date

  const go = (next: Partial<CalendarState>) => {
    setStaffGone(false)
    onStateChange({ ...state, date, ...next })
  }

  const step = (direction: -1 | 1) => {
    const next = shiftDate(view, date, direction)
    if (next !== null) go({ date: next, page: 0 })
  }
  const canStep = (direction: -1 | 1) => shiftDate(view, date, direction) !== null

  const staffChoices = [...staffOptions]
  if (staffMemberId !== null && !staffChoices.some((option) => option.id === staffMemberId)) {
    staffChoices.push({ id: staffMemberId, label: 'Избран член на екипа' })
  }

  const refreshing = load.kind === 'loaded' && load.refreshing

  return (
    <div className="platform-content calendar-page">
      {canCreate && onCreate && (
        <div className="business-page-actions">
          <Button type="button" onClick={() => onCreate(state)}>
            Добави нов час
          </Button>
        </div>
      )}
      <div className="calendar-toolbar">
        <div className="calendar-toolbar-row calendar-group calendar-views-row">
          <nav className="schedule-tabs calendar-views" aria-label="Изглед на графика">
            {CALENDAR_VIEWS.map((option) => (
              <a
                key={option}
                className="schedule-tab"
                href={routeHref({
                  kind: 'business-calendar',
                  calendar: { ...state, view: option, date, page: 0 },
                })}
                aria-current={view === option ? 'page' : undefined}
                onClick={(event) => {
                  event.preventDefault()
                  if (view !== option) go({ view: option, page: 0 })
                }}
              >
                {VIEW_LABELS[option]}
              </a>
            ))}
          </nav>

          <span className="calendar-toolbar-separator" aria-hidden="true" />

          <div className="calendar-period-controls" role="group" aria-label="Навигация по период">
            <Button
              type="button"
              variant="neutral"
              className="calendar-chevron"
              aria-label={STEP_LABELS[view as CalendarView][0]}
              disabled={!canStep(-1)}
              onClick={() => step(-1)}
            >
              <span aria-hidden="true">‹</span>
            </Button>
            <Button
              type="button"
              variant="neutral"
              disabled={date === businessToday}
              onClick={() => go({ date: businessToday, page: 0 })}
            >
              Днес
            </Button>
            <Button
              type="button"
              variant="neutral"
              className="calendar-chevron"
              aria-label={STEP_LABELS[view as CalendarView][1]}
              disabled={!canStep(1)}
              onClick={() => step(1)}
            >
              <span aria-hidden="true">›</span>
            </Button>
          </div>
        </div>

        <div className="calendar-toolbar-row calendar-filters calendar-group">
          <div className="form-field">
            <label htmlFor={dateInputId}>Дата</label>
            <input
              id={dateInputId}
              type="date"
              min={SCHEDULE_MIN_DATE}
              max={SCHEDULE_MAX_DATE}
              value={date}
              onChange={(event) => {
                const value = event.target.value
                if (isCanonicalDate(value) && value >= SCHEDULE_MIN_DATE && value <= SCHEDULE_MAX_DATE) {
                  go({ date: value, page: 0 })
                }
              }}
            />
          </div>
          <div className="form-field">
            <label htmlFor={staffSelectId}>Член на екипа</label>
            <select
              id={staffSelectId}
              value={staffMemberId ?? ''}
              onChange={(event) =>
                go({ staffMemberId: event.target.value === '' ? null : event.target.value, page: 0 })
              }
            >
              <option value="">Всички членове на екипа</option>
              {staffChoices.map((option) => (
                <option key={option.id} value={option.id}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
        </div>
      </div>

      <div className="calendar-group calendar-heading-group">
        <h2 id={headingId} className="calendar-period" aria-live="polite">
          {periodHeading(state, date)}
        </h2>
      </div>
      <div className="calendar-group calendar-content-group">

      {staffGone && (
        <p className="status-message status-info" role="status">
          Избраният член на екипа вече не е наличен. Показани са всички членове на екипа.
        </p>
      )}

      {load.kind === 'loading' && (
        <p className="business-list-state" aria-live="polite" aria-busy="true">
          Зареждане на графика…
        </p>
      )}

      {load.kind === 'error' && (
        <div className="feedback-action-layout">
          <div className="status-message status-error" role="alert">
            <p>{load.message}</p>
          </div>
          <div className="feedback-action-controls">
            <Button
              type="button"
              variant="secondary"
              onClick={() => setReloadToken((token) => token + 1)}
            >
              Опитай отново
            </Button>
          </div>
        </div>
      )}

      {load.kind === 'loaded' && (
        <section
          className={refreshing ? 'calendar-results is-refreshing' : 'calendar-results'}
          aria-labelledby={headingId}
          aria-busy={refreshing}
        >
          {refreshing && (
            <p className="visually-hidden" role="status">
              Зареждане на графика…
            </p>
          )}
          <CalendarBody
            data={load.data}
            state={load.requested}
            wide={wide}
            onOpen={(appointmentId) => onOpenAppointment(appointmentId, load.requested)}
            onStateChange={(next, mode) => onStateChange(next, mode)}
          />
        </section>
      )}
      </div>
    </div>
  )
}

function CalendarBody({
  data,
  state,
  wide,
  onOpen,
  onStateChange,
}: {
  data: Loaded
  state: CalendarState
  wide: boolean
  onOpen: (appointmentId: string) => void
  onStateChange: (next: CalendarState, mode?: ListNavigationMode) => void
}) {
  const date = state.date as string
  if (data.kind === 'agenda') {
    const { response } = data
    if (response.total === 0) {
      return <p className="business-list-state" aria-live="polite">Няма часове в този период.</p>
    }
    return (
      <>
        <AgendaList response={response} calendar={state} onOpen={onOpen} />
        <ListPagination
          label="Странициране на часовете"
          idPrefix="agenda"
          page={response.page}
          size={response.size as ListPageSize}
          totalElements={response.total}
          onPageChange={(page) => onStateChange({ ...state, page })}
          onSizeChange={(size) => onStateChange({ ...state, page: 0, size })}
        />
      </>
    )
  }
  const { response } = data
  if (state.view === 'week') {
    return (
      <>
        {response.appointments.length === 0 && (
          <p className="business-list-state" aria-live="polite">Няма часове през тази седмица.</p>
        )}
        <WeekView response={response} calendar={state} onOpen={onOpen} />
      </>
    )
  }
  return (
    <>
      {response.appointments.length === 0 && (
        <p className="business-list-state" aria-live="polite">Няма часове за този ден.</p>
      )}
      <DayView response={response} date={date} calendar={state} wide={wide} onOpen={onOpen} />
    </>
  )
}
