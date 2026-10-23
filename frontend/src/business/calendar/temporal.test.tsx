import '@testing-library/jest-dom/vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TEMPORAL_LABEL, TemporalBadge, temporalPosition, useBoundaryClock } from './temporal'

const START = '2026-10-14T10:00:00+03:00'
const END = '2026-10-14T10:45:00+03:00'
const at = (iso: string) => Date.parse(iso)

describe('temporalPosition: exact instants', () => {
  it('is upcoming before the start, current from the start up to the end, past from the end on', () => {
    expect(temporalPosition('CONFIRMED', START, END, at(START) - 1)).toBe('upcoming')
    expect(temporalPosition('CONFIRMED', START, END, at(START))).toBe('current')
    expect(temporalPosition('CONFIRMED', START, END, at(END) - 1)).toBe('current')
    expect(temporalPosition('CONFIRMED', START, END, at(END))).toBe('past')
    expect(temporalPosition('CONFIRMED', START, END, at(END) + 1)).toBe('past')
  })

  it('never gives a cancelled Appointment a temporal position', () => {
    for (const now of [at(START) - 1, at(START), at(END)]) {
      expect(temporalPosition('CANCELLED', START, END, now)).toBeNull()
    }
  })

  it('compares the instants of a repeated hour, not their wall-clock labels', () => {
    // 03:30+03:00 and 03:30+02:00 read the same but are an hour apart.
    const first = '2026-10-25T03:30:00+03:00'
    const second = '2026-10-25T03:30:00+02:00'
    expect(temporalPosition('CONFIRMED', second, '2026-10-25T04:15:00+02:00', at(first) + 1)).toBe('upcoming')
    expect(temporalPosition('CONFIRMED', first, '2026-10-25T03:45:00+03:00', at(first) + 1)).toBe('current')
  })

  it('uses the words «Предстоящ», «В момента» and «Минал» and no lifecycle status', () => {
    expect(TEMPORAL_LABEL).toEqual({ upcoming: 'Предстоящ', current: 'В момента', past: 'Минал' })
    expect(JSON.stringify(TEMPORAL_LABEL)).not.toMatch(/Завършен|COMPLETED|NO_SHOW|Неявил/)
  })
})

describe('TemporalBadge', () => {
  afterEach(cleanup)

  it('renders the three states with their own classes and nothing for a cancelled Appointment', () => {
    const states: Array<[number, string, string]> = [
      [at(START) - 1000, 'Предстоящ', 'status-badge-info'],
      [at(START) + 1000, 'В момента', 'status-badge-warning'],
      [at(END) + 1000, 'Минал', 'status-badge-neutral'],
    ]
    for (const [now, label, className] of states) {
      const { unmount } = render(<TemporalBadge status="CONFIRMED" start={START} end={END} nowMs={now} />)
      expect(screen.getByText(label)).toHaveClass('calendar-temporal', className)
      unmount()
    }
    const { container } = render(<TemporalBadge status="CANCELLED" start={START} end={END} nowMs={at(END) + 1000} />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('useBoundaryClock: an open screen follows the clock without polling or requests', () => {
  let wallClock = 0
  const clock = () => wallClock

  function Probe({ list }: { list: Array<{ status: 'CONFIRMED' | 'CANCELLED'; start: string; end: string }> }) {
    const nowMs = useBoundaryClock(list, clock)
    const position = temporalPosition('CONFIRMED', START, END, nowMs)
    return <p data-testid="state">{position}</p>
  }

  beforeEach(() => {
    vi.useFakeTimers()
    wallClock = at(START) - 60_000
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  const advanceTo = (instant: number) => {
    const delta = instant - wallClock
    wallClock = instant
    act(() => {
      vi.advanceTimersByTime(delta)
    })
  }

  it('moves from upcoming to current at the start and to past at the end, with exactly one timer ahead at a time', () => {
    render(<Probe list={[{ status: 'CONFIRMED', start: START, end: END }]} />)
    expect(screen.getByTestId('state')).toHaveTextContent('upcoming')
    expect(vi.getTimerCount()).toBe(1)
    advanceTo(at(START) + 5)
    expect(screen.getByTestId('state')).toHaveTextContent('current')
    expect(vi.getTimerCount()).toBe(1)
    advanceTo(at(END) + 5)
    expect(screen.getByTestId('state')).toHaveTextContent('past')
    // Nothing is left to wait for: no polling timer remains.
    expect(vi.getTimerCount()).toBe(0)
  })

  it('arms no timer for cancelled Appointments or when every boundary has passed', () => {
    wallClock = at(END) + 1000
    render(<Probe list={[{ status: 'CONFIRMED', start: START, end: END }, { status: 'CANCELLED', start: '2030-01-01T10:00:00+02:00', end: '2030-01-01T11:00:00+02:00' }]} />)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('reads the clock again when the tab becomes visible, and removes its timer and listener on unmount', () => {
    const remove = vi.spyOn(document, 'removeEventListener')
    const { unmount } = render(<Probe list={[{ status: 'CONFIRMED', start: START, end: END }]} />)
    // A hidden tab's timers may be late: the clock moved on without the timer firing.
    wallClock = at(START) + 10_000
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(screen.getByTestId('state')).toHaveTextContent('current')
    unmount()
    expect(vi.getTimerCount()).toBe(0)
    expect(remove).toHaveBeenCalledWith('visibilitychange', expect.any(Function))
  })

  it('sends no network request while following the clock', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    render(<Probe list={[{ status: 'CONFIRMED', start: START, end: END }]} />)
    advanceTo(at(END) + 5)
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
