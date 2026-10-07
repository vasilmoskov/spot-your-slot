import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { Button } from '../../ui/Button'
import {
  NAVIGATION_KEYS,
  addMonths,
  clampDate,
  compareMonths,
  monthOf,
  monthWeeks,
  moveDate,
  sameMonth,
  type Month,
} from './calendar'
import { WEEKDAY_LONG, WEEKDAY_SHORT, formatDateOnlyLong, formatMonthTitle } from './dates'

export type BookingCalendarProps = {
  // The first and last Business-local date of the booking horizon; nothing outside it can be shown as a choice.
  firstDate: string
  lastDate: string
  // Exactly the dates the availability answer lists. Every other date inside the horizon is unavailable.
  availableDates: readonly string[]
  selected: string | null
  // The Business-local current date, only to label it; null when it cannot be determined.
  today: string | null
  disabled: boolean
  onSelect: (date: string) => void
}

function firstAvailableIn(month: Month, available: ReadonlySet<string>): string | null {
  for (const week of monthWeeks(month)) {
    for (const day of week) if (day !== null && available.has(day)) return day
  }
  return null
}

/**
 * A monthly calendar following the WAI-ARIA authoring practices date picker grid: one tab stop with a
 * roving focus, arrow keys move by day and week, Home and End to the ends of the week, Page Up and
 * Page Down by month, Enter or Space selects. Monday is the first weekday. Month navigation stays inside
 * the booking horizon. A date is a choice only when the availability answer lists it; the selected,
 * available, unavailable and current-date states each have a shape or text cue besides their color.
 */
export function BookingCalendar({
  firstDate,
  lastDate,
  availableDates,
  selected,
  today,
  disabled,
  onSelect,
}: BookingCalendarProps) {
  const available = useMemo(() => new Set(availableDates), [availableDates])
  const firstMonth = monthOf(firstDate)
  const lastMonth = monthOf(lastDate)
  const startDate = selected ?? availableDates[0] ?? firstDate
  const [month, setMonth] = useState<Month>(
    () => monthOf(startDate) ?? firstMonth ?? { year: 1970, month: 1 },
  )
  const [focusDate, setFocusDate] = useState<string>(startDate)
  const cells = useRef(new Map<string, HTMLTableCellElement>())
  const focusRequested = useRef(false)

  // A selection made elsewhere (the first available date, or a reset) brings its month into view.
  useEffect(() => {
    if (selected === null) return
    const selectedMonth = monthOf(selected)
    if (selectedMonth && !sameMonth(selectedMonth, month)) setMonth(selectedMonth)
    setFocusDate(selected)
    // Only a change of the selection matters here, not of the month the guest is browsing.
  }, [selected])

  useEffect(() => {
    if (!focusRequested.current) return
    focusRequested.current = false
    cells.current.get(focusDate)?.focus()
  }, [focusDate, month])

  if (!firstMonth || !lastMonth) return null
  const canGoBack = compareMonths(month, firstMonth) > 0
  const canGoForward = compareMonths(month, lastMonth) < 0
  const inHorizon = (value: string) => value >= firstDate && value <= lastDate

  const showMonth = (next: Month) => {
    setMonth(next)
    // The tab stop moves with the month: the selected day, else the first bookable one, else its first day.
    const selectedHere = selected !== null && sameMonth(monthOf(selected) ?? next, next)
    const candidate =
      (selectedHere ? selected : null) ??
      firstAvailableIn(next, available) ??
      monthWeeks(next).flat().find((day): day is string => day !== null && inHorizon(day)) ??
      null
    if (candidate !== null) setFocusDate(candidate)
  }

  const onKeyDown = (event: KeyboardEvent<HTMLTableElement>) => {
    if (!(NAVIGATION_KEYS as readonly string[]).includes(event.key)) return
    event.preventDefault()
    const moved = moveDate(focusDate, event.key)
    if (moved === null) return
    const target = clampDate(moved, firstDate, lastDate)
    focusRequested.current = true
    const targetMonth = monthOf(target)
    if (targetMonth && !sameMonth(targetMonth, month)) setMonth(targetMonth)
    setFocusDate(target)
  }

  const activate = (value: string) => {
    if (disabled || !available.has(value)) return
    onSelect(value)
  }

  // The one tab stop is the roving focus day when it is shown, otherwise the first bookable day.
  const focusMonth = monthOf(focusDate)
  const tabStop =
    focusMonth && sameMonth(focusMonth, month) && inHorizon(focusDate)
      ? focusDate
      : (firstAvailableIn(month, available) ??
        monthWeeks(month).flat().find((day): day is string => day !== null && inHorizon(day)) ??
        null)
  const weeks = monthWeeks(month)
  const titleId = 'booking-calendar-title'
  return (
    <div className="booking-calendar">
      <div className="booking-calendar-header">
        <Button
          type="button"
          variant="secondary"
          className="booking-calendar-nav"
          aria-label="Предишен месец"
          aria-disabled={!canGoBack}
          onClick={() => {
            if (canGoBack) showMonth(addMonths(month, -1))
          }}
        >
          <span aria-hidden="true">‹</span>
        </Button>
        <p id={titleId} className="booking-calendar-title" aria-live="polite">
          {formatMonthTitle(month.year, month.month)}
        </p>
        <Button
          type="button"
          variant="secondary"
          className="booking-calendar-nav"
          aria-label="Следващ месец"
          aria-disabled={!canGoForward}
          onClick={() => {
            if (canGoForward) showMonth(addMonths(month, 1))
          }}
        >
          <span aria-hidden="true">›</span>
        </Button>
      </div>
      <table className="booking-calendar-grid" role="grid" aria-labelledby={titleId} onKeyDown={onKeyDown}>
        <thead>
          <tr>
            {WEEKDAY_SHORT.map((label, index) => (
              <th key={label} scope="col" abbr={WEEKDAY_LONG[index]}>
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {weeks.map((week, weekIndex) => (
            <tr key={weekIndex}>
              {week.map((day, dayIndex) => {
                if (day === null) return <td key={dayIndex} className="booking-day-empty" />
                const number = Number(day.slice(8))
                if (!inHorizon(day)) {
                  return (
                    <td key={day} role="gridcell" aria-disabled="true" className="booking-day booking-day--outside">
                      <span aria-hidden="true">{number}</span>
                      <span className="visually-hidden">
                        {formatDateOnlyLong(day)}, извън периода за записване
                      </span>
                    </td>
                  )
                }
                const isAvailable = available.has(day)
                const isSelected = day === selected
                const isToday = day === today
                const classes = [
                  'booking-day',
                  isAvailable ? 'booking-day--available' : 'booking-day--unavailable',
                  isSelected ? 'booking-day--selected' : '',
                  isToday ? 'booking-day--today' : '',
                ]
                  .filter(Boolean)
                  .join(' ')
                const label = [
                  formatDateOnlyLong(day),
                  isToday ? 'днес' : '',
                  isAvailable ? '' : 'няма свободни часове',
                ]
                  .filter(Boolean)
                  .join(', ')
                return (
                  <td
                    key={day}
                    ref={(element) => {
                      if (element) cells.current.set(day, element)
                      else cells.current.delete(day)
                    }}
                    role="gridcell"
                    tabIndex={day === tabStop ? 0 : -1}
                    aria-label={label}
                    aria-selected={isAvailable ? isSelected : undefined}
                    aria-disabled={isAvailable && !disabled ? undefined : true}
                    aria-current={isToday ? 'date' : undefined}
                    className={classes}
                    onClick={() => {
                      setFocusDate(day)
                      activate(day)
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault()
                        activate(day)
                      }
                    }}
                    onFocus={() => setFocusDate(day)}
                  >
                    <span aria-hidden="true">{number}</span>
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
