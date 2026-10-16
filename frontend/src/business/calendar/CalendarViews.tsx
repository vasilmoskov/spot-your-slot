import type { CSSProperties } from 'react'
import { routeHref, type CalendarState } from '../../navigation'
import { formatTimeDistinct, parseInstant } from '../../public/booking/dates'
import type {
  AgendaResponse,
  CalendarAppointment,
  CalendarResponse,
  CalendarStaffMember,
} from './api'
import { dateRange } from './dates'
import {
  businessDateOf,
  closureLabel,
  formatDateAndRange,
  formatDayHeading,
  formatTimeRange,
  formatTotalDuration,
  layoutLanes,
  percentOfDay,
  serviceNames,
  statusLabel,
} from './presentation'

type OpenAppointment = (appointmentId: string) => void

export function StatusBadge({
  status,
  quiet,
}: {
  status: CalendarAppointment['status']
  // Confirmed is the ordinary state: in a cramped grid block it stays in the text for assistive
  // technology and the legend explains it; a cancelled state is always visibly written.
  quiet?: boolean
}) {
  const cancelled = status === 'CANCELLED'
  return (
    <span
      className={`${
        cancelled
          ? 'status-badge status-badge-neutral calendar-status-cancelled'
          : 'status-badge status-badge-success calendar-status-confirmed'
      }${quiet && !cancelled ? ' visually-hidden' : ''}`}
    >
      {statusLabel(status)}
    </span>
  )
}

type EntryProps = {
  appointment: CalendarAppointment
  timezone: string
  calendar: CalendarState
  onOpen: OpenAppointment
  // Show the date before the time (lists that are not grouped by day).
  withDate?: boolean
  // Staff is implied by the surrounding column.
  hideStaff?: boolean
  style?: CSSProperties
  className?: string
}

/**
 * One Appointment. The whole visible summary is a real link to the detail; the status is spelled out in
 * text (a cancelled entry is also struck through and dashed, never distinguished by colour alone).
 */
export function AppointmentEntry({
  appointment,
  timezone,
  calendar,
  onOpen,
  withDate,
  hideStaff,
  style,
  className,
}: EntryProps) {
  const cancelled = appointment.status === 'CANCELLED'
  const range = withDate
    ? formatDateAndRange(appointment.start, appointment.end, timezone)
    : formatTimeRange(appointment.start, appointment.end, timezone)
  return (
    <article
      className={`calendar-entry${cancelled ? ' is-cancelled' : ''}${className ? ` ${className}` : ''}`}
      style={style}
    >
      <p className="calendar-entry-time">
        <a
          href={routeHref({
            kind: 'business-appointment-detail',
            appointmentId: appointment.id,
            origin: { kind: 'calendar', calendar },
          })}
          onClick={(event) => {
            event.preventDefault()
            onOpen(appointment.id)
          }}
        >
          {range}
        </a>
        <StatusBadge status={appointment.status} quiet={className === 'calendar-block'} />
      </p>
      <p className="calendar-entry-customer">{appointment.customerDisplayName}</p>
      <p className="calendar-entry-services">{serviceNames(appointment)}</p>
      <p className="calendar-entry-meta">
        {hideStaff ? '' : `${appointment.staffDisplayName} · `}
        {formatTotalDuration(appointment.durationMinutes)}
      </p>
    </article>
  )
}

function closuresOn(date: string, staffMembers: readonly CalendarStaffMember[]) {
  const business = staffMembers.some((staff) =>
    staff.closures.some((closure) => closure.date === date && closure.kind === 'BUSINESS_CLOSURE'),
  )
  const timeOff = staffMembers.filter((staff) =>
    staff.closures.some((closure) => closure.date === date && closure.kind === 'STAFF_TIME_OFF'),
  )
  return { business, timeOff }
}

function dayOf(response: CalendarResponse, date: string) {
  return response.days.find((day) => day.date === date)
}

function appointmentsOn(response: CalendarResponse, date: string) {
  return response.appointments.filter((appointment) => {
    const day = dayOf(response, date)
    if (!day) return businessDateOf(appointment.start, response.timezone) === date
    const start = parseInstant(appointment.start)
    const end = parseInstant(appointment.end)
    const from = parseInstant(day.start)
    const to = parseInstant(day.end)
    if (start === null || end === null || from === null || to === null) return false
    return start < to && end > from
  })
}

function intervalsOn(staff: CalendarStaffMember, day: { start: string; end: string }) {
  const from = parseInstant(day.start) ?? 0
  const to = parseInstant(day.end) ?? 0
  const today = staff.workingIntervals.filter((interval) => {
    const start = parseInstant(interval.start)
    const end = parseInstant(interval.end)
    return start !== null && end !== null && start < to && end > from
  })
  return today
}

// ---------------- day ----------------

const HOUR_MS = 3_600_000
const DEFAULT_OPEN_HOURS = 8
const DEFAULT_CLOSE_HOURS = 18

export type DayWindow = { start: string; end: string; hours: number }

/** The visible slice of a day: whole hours from the day's start covering all work and bookings. */
export function visibleWindow(
  day: { start: string; end: string },
  points: ReadonlyArray<{ start: string; end: string }>,
): DayWindow {
  const dayStart = parseInstant(day.start) ?? 0
  const dayEnd = parseInstant(day.end) ?? dayStart + 24 * HOUR_MS
  let from = Infinity
  let to = -Infinity
  for (const point of points) {
    const start = parseInstant(point.start)
    const end = parseInstant(point.end)
    if (start === null || end === null) continue
    from = Math.min(from, Math.max(start, dayStart))
    to = Math.max(to, Math.min(end, dayEnd))
  }
  if (from === Infinity || to <= from) {
    from = dayStart + DEFAULT_OPEN_HOURS * HOUR_MS
    to = dayStart + DEFAULT_CLOSE_HOURS * HOUR_MS
  }
  const firstHour = Math.max(0, Math.floor((from - dayStart) / HOUR_MS))
  const lastHour = Math.max(firstHour + 1, Math.ceil((to - dayStart) / HOUR_MS))
  const windowStart = dayStart + firstHour * HOUR_MS
  const windowEnd = Math.min(dayEnd, dayStart + lastHour * HOUR_MS)
  return {
    start: new Date(windowStart).toISOString(),
    end: new Date(windowEnd).toISOString(),
    hours: Math.max(1, Math.ceil((windowEnd - windowStart) / HOUR_MS)),
  }
}

type DayViewProps = {
  response: CalendarResponse
  date: string
  calendar: CalendarState
  wide: boolean
  onOpen: OpenAppointment
}

export function DayView({ response, date, calendar, wide, onOpen }: DayViewProps) {
  const day = dayOf(response, date)
  const timezone = response.timezone
  if (!day) return null
  const dayAppointments = appointmentsOn(response, date)
  const closures = closuresOn(date, response.staffMembers)
  const columns = response.staffMembers
  const windowInstants = visibleWindow(day, [
    ...dayAppointments,
    ...columns.flatMap((staff) => intervalsOn(staff, day)),
  ])

  return (
    <div className="calendar-day">
      {closures.business && (
        <p className="calendar-closure">{closureLabel('BUSINESS_CLOSURE')} за целия ден.</p>
      )}
      {columns.length === 0 && (
        <p className="business-list-state">Няма членове на екипа за показване.</p>
      )}
      {wide && columns.length > 0 && (
        <p className="calendar-legend">
          Потвърден час: плътна рамка със зелена лента. Отменен час: пунктирана рамка и зачертан
          текст. Защрихованата част е извън работното време.
        </p>
      )}
      <div className={wide ? 'calendar-grid' : 'calendar-staff-list'}>
        {wide && columns.length > 0 && (
          <div className="calendar-axis" aria-hidden="true" style={{ '--calendar-hours': windowInstants.hours } as CSSProperties}>
            {Array.from({ length: windowInstants.hours }, (_, index) => {
              const at = new Date((parseInstant(windowInstants.start) ?? 0) + index * HOUR_MS).toISOString()
              return (
                <span key={index} className="calendar-axis-label">
                  {formatTimeDistinct(at, timezone)}
                </span>
              )
            })}
          </div>
        )}
        {columns.map((staff) => {
          const staffAppointments = dayAppointments.filter(
            (appointment) => appointment.staffMemberId === staff.id,
          )
          const intervals = intervalsOn(staff, day)
          const dayClosure = staff.closures.find((closure) => closure.date === date)
          return (
            <section
              key={staff.id}
              className="calendar-staff-column"
              aria-labelledby={`calendar-staff-${staff.id}`}
            >
              <h3 id={`calendar-staff-${staff.id}`}>
                {staff.displayName}
                {!staff.active && <span className="calendar-inactive"> (неактивен)</span>}
              </h3>
              <p className="calendar-working-time">
                {dayClosure
                  ? closureLabel(dayClosure.kind)
                  : intervals.length === 0
                    ? 'Не работи'
                    : `Работно време: ${intervals
                        .map((interval) => formatTimeRange(interval.start, interval.end, timezone))
                        .join(', ')}`}
              </p>
              {wide ? (
                <TimelineColumn
                  window={windowInstants}
                  intervals={intervals}
                  appointments={staffAppointments}
                  timezone={timezone}
                  calendar={calendar}
                  onOpen={onOpen}
                />
              ) : staffAppointments.length === 0 ? (
                <p className="calendar-empty-note">Няма часове.</p>
              ) : (
                <div className="calendar-entry-list">
                  {staffAppointments.map((appointment) => (
                    <AppointmentEntry
                      key={appointment.id}
                      appointment={appointment}
                      timezone={timezone}
                      calendar={calendar}
                      onOpen={onOpen}
                      hideStaff
                    />
                  ))}
                </div>
              )}
            </section>
          )
        })}
      </div>
    </div>
  )
}

function TimelineColumn({
  window: visible,
  intervals,
  appointments,
  timezone,
  calendar,
  onOpen,
}: {
  window: DayWindow
  intervals: ReadonlyArray<{ start: string; end: string }>
  appointments: CalendarAppointment[]
  timezone: string
  calendar: CalendarState
  onOpen: OpenAppointment
}) {
  const place = (start: string, end: string) => {
    const top = percentOfDay(start, visible.start, visible.end)
    const bottom = percentOfDay(end, visible.start, visible.end)
    return { top, height: Math.max(0, bottom - top) }
  }
  return (
    <div
      className="calendar-timeline"
      style={{ '--calendar-hours': visible.hours } as CSSProperties}
    >
      {intervals.map((interval) => {
        const { top, height } = place(interval.start, interval.end)
        return (
          <div
            key={interval.start}
            className="calendar-working-band"
            aria-hidden="true"
            style={{ top: `${top}%`, height: `${height}%` }}
          />
        )
      })}
      {layoutLanes(appointments).map(({ item, lane, lanes }) => {
        const { top, height } = place(item.start, item.end)
        return (
          <AppointmentEntry
            key={item.id}
            appointment={item}
            timezone={timezone}
            calendar={calendar}
            onOpen={onOpen}
            hideStaff
            className="calendar-block"
            style={{
              top: `${top}%`,
              height: `${height}%`,
              left: `${(lane / lanes) * 100}%`,
              width: `${100 / lanes}%`,
            }}
          />
        )
      })}
    </div>
  )
}

// ---------------- week ----------------

type WeekViewProps = {
  response: CalendarResponse
  calendar: CalendarState
  onOpen: OpenAppointment
}

export function WeekView({ response, calendar, onOpen }: WeekViewProps) {
  const timezone = response.timezone
  const dates = dateRange(response.from, response.to)
  return (
    <div className="calendar-week">
      {dates.map((date) => {
        const entries = appointmentsOn(response, date)
        const closures = closuresOn(date, response.staffMembers)
        return (
          <section key={date} className="calendar-week-day" aria-labelledby={`calendar-day-${date}`}>
            <h3 id={`calendar-day-${date}`}>{formatDayHeading(date)}</h3>
            {closures.business && <p className="calendar-closure">{closureLabel('BUSINESS_CLOSURE')}</p>}
            {closures.timeOff.length > 0 && (
              <p className="calendar-closure">
                {closureLabel('STAFF_TIME_OFF')}: {closures.timeOff.map((staff) => staff.displayName).join(', ')}
              </p>
            )}
            {entries.length === 0 ? (
              <p className="calendar-empty-note">Няма часове.</p>
            ) : (
              <div className="calendar-entry-list">
                {entries.map((appointment) => (
                  <AppointmentEntry
                    key={appointment.id}
                    appointment={appointment}
                    timezone={timezone}
                    calendar={calendar}
                    onOpen={onOpen}
                  />
                ))}
              </div>
            )}
          </section>
        )
      })}
    </div>
  )
}

// ---------------- agenda ----------------

export function AgendaList({
  response,
  calendar,
  onOpen,
}: {
  response: AgendaResponse
  calendar: CalendarState
  onOpen: OpenAppointment
}) {
  return (
    <div className="calendar-entry-list calendar-agenda">
      {response.items.map((appointment) => (
        <AppointmentEntry
          key={appointment.id}
          appointment={appointment}
          timezone={response.timezone}
          calendar={calendar}
          onOpen={onOpen}
          withDate
        />
      ))}
    </div>
  )
}
