import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { parseInstant } from '../../public/booking/dates'
import type { AppointmentStatus } from './api'

/**
 * Where a CONFIRMED Appointment is on the clock, from exact instants: "upcoming" before its start, "current" from its
 * start up to (not including) its end, "past" from its end on. It describes elapsed time only: it is not a lifecycle
 * status, says nothing about attendance or completion, and is never stored. A cancelled Appointment has no position.
 */
export type TemporalPosition = 'upcoming' | 'current' | 'past'

export function temporalPosition(
  status: AppointmentStatus,
  start: string,
  end: string,
  nowMs: number,
): TemporalPosition | null {
  if (status === 'CANCELLED') return null
  const from = parseInstant(start)
  const to = parseInstant(end)
  if (from === null || to === null) return null
  if (nowMs < from) return 'upcoming'
  return nowMs < to ? 'current' : 'past'
}

export const TEMPORAL_LABEL: Record<TemporalPosition, string> = {
  upcoming: 'Предстоящ',
  current: 'В момента',
  past: 'Минал',
}

const TEMPORAL_CLASS: Record<TemporalPosition, string> = {
  upcoming: 'status-badge-info',
  current: 'status-badge-warning',
  past: 'status-badge-neutral',
}

/** The temporal badge of a CONFIRMED Appointment (blue, amber, grey); nothing for a cancelled one. */
export function TemporalBadge({
  status,
  start,
  end,
  nowMs,
}: {
  status: AppointmentStatus
  start: string
  end: string
  nowMs: number
}) {
  const position = temporalPosition(status, start, end, nowMs)
  if (position === null) return null
  return <span className={`status-badge calendar-temporal ${TEMPORAL_CLASS[position]}`}>{TEMPORAL_LABEL[position]}</span>
}

// setTimeout keeps its delay in a signed 32-bit integer; a longer wait is simply armed again when it fires.
const MAX_TIMER_MS = 2_147_483_647

/**
 * The current time for temporal badges, kept exact without polling: one timer is armed for the nearest start or end
 * instant of the given CONFIRMED Appointments that is still ahead, and the clock is read again when it fires and when the
 * tab becomes visible again (a hidden tab's timers may be late). Nothing is requested from the network. The timer and
 * the listener are removed on unmount and whenever the boundaries change.
 */
export function useBoundaryClock(
  appointments: ReadonlyArray<{ status: AppointmentStatus; start: string; end: string }>,
  clock: () => number = Date.now,
): number {
  const [nowMs, setNowMs] = useState(clock)

  let next = Infinity
  for (const appointment of appointments) {
    if (appointment.status === 'CANCELLED') continue
    for (const instant of [appointment.start, appointment.end]) {
      const at = parseInstant(instant)
      if (at !== null && at > nowMs && at < next) next = at
    }
  }

  useEffect(() => {
    if (next === Infinity) return undefined
    const timer = setTimeout(() => setNowMs(clock()), Math.min(Math.max(next - clock(), 0) + 1, MAX_TIMER_MS))
    return () => clearTimeout(timer)
    // `clock` is read at the moment the timer is armed and when it fires.
  }, [next, nowMs])

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') setNowMs(clock())
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [])

  return nowMs
}

const NowContext = createContext<number | null>(null)

/** Gives every Appointment entry below it the same exact current time, kept current by one boundary timer. */
export function TemporalClock({
  appointments,
  clock,
  children,
}: {
  appointments: ReadonlyArray<{ status: AppointmentStatus; start: string; end: string }>
  clock?: () => number
  children: ReactNode
}) {
  const nowMs = useBoundaryClock(appointments, clock)
  return <NowContext.Provider value={nowMs}>{children}</NowContext.Provider>
}

/** The shared current time of the surrounding `TemporalClock` (the wall clock when there is none). */
export function useSharedNow(): number {
  return useContext(NowContext) ?? Date.now()
}

/** The booking status badge and, for a CONFIRMED Appointment, the separate temporal badge, with room between them. */
export function AppointmentBadges({
  status,
  start,
  end,
  statusBadge,
}: {
  status: AppointmentStatus
  start: string
  end: string
  statusBadge: ReactNode
}) {
  const nowMs = useSharedNow()
  return (
    <>
      {statusBadge}
      <TemporalBadge status={status} start={start} end={end} nowMs={nowMs} />
    </>
  )
}
