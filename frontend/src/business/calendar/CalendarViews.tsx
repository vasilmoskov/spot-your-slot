import type { CSSProperties } from 'react'
import { routeHref, type CalendarState } from '../../navigation'
import {
  formatTimeDistinct,
  parseInstant,
  zoneOffsetMs,
} from '../../public/booking/dates'
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
  weekColumnLines,
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
  // How many Service names fit: a grid block computes it from its height (0 = none); a flowing card shows up to four.
  serviceLines?: number
  style?: CSSProperties
  className?: string
}

/**
 * One Appointment. The whole visible summary is a real link to the detail; the status is spelled out in
 * text (a cancelled entry is also struck through and dashed, never distinguished by colour alone).
 */
const MAX_FLOW_SERVICES = 4
// One text line of a grid block: 13 px at a line height of 1.25.
const BLOCK_LINE_PX = 16.25

export function AppointmentEntry({
  appointment,
  timezone,
  calendar,
  onOpen,
  withDate,
  hideStaff,
  serviceLines,
  style,
  className,
}: EntryProps) {
  const cancelled = appointment.status === 'CANCELLED'
  const names = appointment.services.map((service) => service.name)
  const limit = serviceLines ?? MAX_FLOW_SERVICES
  const visibleServices = names.slice(0, limit)
  const truncated = visibleServices.length < names.length
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
      {visibleServices.length > 0 && (
        <ul className="calendar-entry-services" aria-label="Услуги">
          {visibleServices.map((name, index) => (
            <li key={index}>{index === visibleServices.length - 1 && truncated ? `${name}…` : name}</li>
          ))}
        </ul>
      )}
      {truncated && <span className="visually-hidden">Услуги: {serviceNames(appointment)}.</span>}
      <p className="calendar-entry-meta">
        {!hideStaff && <span className="calendar-entry-staff">{appointment.staffDisplayName}</span>}
        <span className="calendar-entry-duration">{formatTotalDuration(appointment.durationMinutes)}</span>
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

/** The shared time scale of a day grid: exact start and end instants, and the elapsed time between them in hours. */
export type DayWindow = { start: string; end: string; hours: number }

/**
 * The visible slice of a day, one scale for every column: from the earliest start to the latest end of everything
 * shown (the effective working periods of every displayed StaffMember, whichever columns are off-screen, and every
 * displayed Appointment, cancelled ones included, so nothing outside working time is ever clipped), exactly, to the
 * minute: 08:15–19:30 is that, not 08:00–20:00. An empty day keeps the 08:00–18:00 default. Instants only, so a day
 * with 23 or 25 elapsed hours stays correct.
 */
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
  const windowStart = Math.max(dayStart, from)
  const windowEnd = Math.min(dayEnd, Math.max(to, windowStart + 15 * 60_000))
  return {
    start: new Date(windowStart).toISOString(),
    end: new Date(windowEnd).toISOString(),
    hours: (windowEnd - windowStart) / HOUR_MS,
  }
}

const HALF_HOUR_MS = 1_800_000

/**
 * The grid lines of a window: every local half hour strictly inside it, flagged as an hour when its local minute is 0.
 * Found from the zone's wall clock at each instant, never by adding a fixed 24 hours or assuming whole-hour offsets.
 */
export function gridMarks(window: DayWindow, timeZone: string): Array<{ at: number; hour: boolean }> {
  const start = parseInstant(window.start) ?? 0
  const end = parseInstant(window.end) ?? start
  const marks: Array<{ at: number; hour: boolean }> = []
  let at = start
  for (let guard = 0; guard < 200; guard += 1) {
    const offset = zoneOffsetMs(at + 1, timeZone)
    at = Math.floor((at + 1 + offset) / HALF_HOUR_MS) * HALF_HOUR_MS + HALF_HOUR_MS - offset
    if (at >= end) break
    const local = at + zoneOffsetMs(at, timeZone)
    marks.push({ at, hour: local % HOUR_MS === 0 })
  }
  return marks
}

const LABEL_CLEARANCE_MS = 15 * 60_000

/**
 * The axis labels: the exact start boundary, every whole hour inside, and the exact end boundary (an hour label that
 * would sit within 15 minutes of a boundary label is left out, so labels never overlap).
 */
export function axisLabels(window: DayWindow, timeZone: string): Array<{ at: number; instant: string }> {
  const start = parseInstant(window.start) ?? 0
  const end = parseInstant(window.end) ?? start
  const at = (ms: number) => ({ at: ms, instant: new Date(ms).toISOString() })
  const labels = [at(start)]
  for (const mark of gridMarks(window, timeZone)) {
    if (mark.hour && mark.at - start >= LABEL_CLEARANCE_MS && end - mark.at >= LABEL_CLEARANCE_MS) labels.push(at(mark.at))
  }
  labels.push(at(end))
  return labels
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
      <div className={wide ? 'calendar-grid' : 'calendar-staff-list'}>
        {wide && columns.length > 0 && (
          <div className="calendar-axis" aria-hidden="true" style={{ '--calendar-hours': windowInstants.hours } as CSSProperties}>
            {axisLabels(windowInstants, timezone).map((label) => (
              <span
                key={label.at}
                className="calendar-axis-label"
                style={{ top: `${percentOfDay(label.instant, windowInstants.start, windowInstants.end)}%` }}
              >
                {formatTimeDistinct(label.instant, timezone)}
              </span>
            ))}
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
              <div className="calendar-working-time">
                {dayClosure ? (
                  <span>{closureLabel(dayClosure.kind)}</span>
                ) : intervals.length === 0 ? (
                  <span>Не работи</span>
                ) : (
                  <>
                    <span className="calendar-working-label">Работно време</span>
                    {intervals.map((interval) => (
                      <span key={interval.start}>{formatTimeRange(interval.start, interval.end, timezone)}</span>
                    ))}
                  </>
                )}
              </div>
              {wide ? (
                <TimelineColumn
                  window={windowInstants}
                  intervals={intervals}
                  appointments={staffAppointments}
                  timezone={timezone}
                  calendar={calendar}
                  onOpen={onOpen}
                  staff={staff}
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
      {wide && columns.length > 0 && (
        <ul className="calendar-legend" aria-label="Означения в графика">
          <li>Потвърден час — плътна рамка със зелена лента.</li>
          <li>Отменен час — пунктирана червена рамка и зачертан текст.</li>
          <li>Защриховано поле — извън работното време.</li>
        </ul>
      )}
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
  staff,
}: {
  window: DayWindow
  intervals: ReadonlyArray<{ start: string; end: string }>
  appointments: CalendarAppointment[]
  timezone: string
  calendar: CalendarState
  onOpen: OpenAppointment
  staff: CalendarStaffMember
}) {
  const place = (start: string, end: string) => {
    const top = percentOfDay(start, visible.start, visible.end)
    const bottom = percentOfDay(end, visible.start, visible.end)
    return { top, height: Math.max(0, bottom - top) }
  }
  const placed = layoutLanes(appointments)
  // The entries that did not get a lane of their own, per cluster of overlapping entries.
  const hiddenByCluster = new Map<number, typeof placed>()
  for (const entry of placed) {
    if (!entry.hidden) continue
    hiddenByCluster.set(entry.cluster, [...(hiddenByCluster.get(entry.cluster) ?? []), entry])
  }

  return (
    <div className="calendar-timeline" style={{ '--calendar-hours': visible.hours } as CSSProperties}>
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
      {gridMarks(visible, timezone).map((mark) => {
        const top = percentOfDay(new Date(mark.at).toISOString(), visible.start, visible.end)
        return (
          <div
            key={mark.at}
            aria-hidden="true"
            className={mark.hour ? 'calendar-hour-line' : 'calendar-half-line'}
            style={{ top: `${top}%` }}
          />
        )
      })}
      {placed
        .filter((entry) => !entry.hidden)
        .map(({ item, lane, lanes }) => {
          const { top, height } = place(item.start, item.end)
          // A block is as tall as its time range (6 rem, 96 px per hour). Whatever the time, the status and the Customer
          // need is reserved first (two lines for the time in a shared lane or a cancelled block); the Service names
          // get the lines that remain, and none when nothing fits.
          const visibleMinutes = (height / 100) * visible.hours * 60
          const reserved = (lanes > 1 || item.status === 'CANCELLED' ? 2 : 1) + 1
          const serviceLines = Math.max(0, Math.floor((visibleMinutes * 1.6 - 8 - reserved * BLOCK_LINE_PX) / BLOCK_LINE_PX))
          return (
            <AppointmentEntry
              key={item.id}
              appointment={item}
              timezone={timezone}
              calendar={calendar}
              onOpen={onOpen}
              hideStaff
              serviceLines={serviceLines}
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
      {[...hiddenByCluster.entries()].map(([cluster, entries]) => {
        const from = Math.min(...entries.map((entry) => place(entry.item.start, entry.item.end).top))
        const to = Math.max(...entries.map((entry) => place(entry.item.start, entry.item.end).top + place(entry.item.start, entry.item.end).height))
        return (
          <MoreEntries
            key={`more-${cluster}`}
            entries={entries.map((entry) => entry.item)}
            staffName={staff.displayName}
            timezone={timezone}
            calendar={calendar}
            onOpen={onOpen}
            style={{
              top: `${from}%`,
              height: `${to - from}%`,
              left: `${((entries[0]!.lanes - 1) / entries[0]!.lanes) * 100}%`,
              width: `${100 / entries[0]!.lanes}%`,
            }}
          />
        )
      })}
    </div>
  )
}

/**
 * «Още N»: the entries of a dense group that have no lane of their own. A native disclosure, so it opens with a click,
 * Enter or Space; every hidden Appointment is listed with its time, Customer and written status and opens its detail.
 */
function MoreEntries({
  entries,
  staffName,
  timezone,
  calendar,
  onOpen,
  style,
}: {
  entries: CalendarAppointment[]
  staffName: string
  timezone: string
  calendar: CalendarState
  onOpen: OpenAppointment
  style: CSSProperties
}) {
  return (
    <details className="calendar-more" style={style}>
      <summary aria-label={`Още ${entries.length} часа при ${staffName}`}>Още {entries.length}</summary>
      <ul className="calendar-more-list">
        {entries.map((appointment) => (
          <li key={appointment.id}>
            <AppointmentEntry
              appointment={appointment}
              timezone={timezone}
              calendar={calendar}
              onOpen={onOpen}
              hideStaff
              serviceLines={MAX_FLOW_SERVICES}
            />
          </li>
        ))}
      </ul>
    </details>
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
    <div className="calendar-week" role="region" aria-label="Седмичен график" tabIndex={0}>
      {dates.map((date) => {
        const entries = appointmentsOn(response, date)
        const closures = closuresOn(date, response.staffMembers)
        return (
          <section key={date} className="calendar-week-day" aria-labelledby={`calendar-day-${date}`}>
            <h3 id={`calendar-day-${date}`} aria-label={formatDayHeading(date)}>
              <span className="calendar-week-weekday">{weekColumnLines(date).weekday}</span>
              <span className="calendar-week-date">{weekColumnLines(date).dayMonth}</span>
            </h3>
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
