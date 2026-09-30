import { describe, expect, it } from 'vitest'
import type { ScheduleExceptionItem } from './api'
import {
  KIND_EXPLANATIONS,
  KIND_GROUPS,
  KIND_LABELS,
  OVERLAP_MESSAGES,
  periodConflictMessage,
  SCHEDULE_CHANGE_STATUS_LABELS,
  businessToday,
  scheduleChangeStatus,
  sortScheduleExceptions,
  addDays,
  defaultWindowFrom,
  formatDate,
  formatDateRange,
  hoursSummary,
  localDateIn,
  periodErrorMessage,
  sortPeriods,
  staffNameOf,
  validatePeriods,
  validateWindow,
} from './presentation'

const period = (clientId: string, startTime: string, endTime: string) => ({
  clientId,
  startTime,
  endTime,
})

describe('schedule change presentation', () => {
  it('uses the approved Bulgarian labels and never the enum names', () => {
    expect(KIND_LABELS).toEqual({
      BUSINESS_CLOSURE: 'Неработно време',
      STAFF_TIME_OFF: 'Отсъствие',
      WORKING_DAY_OVERRIDE: 'Променени работни часове',
      ADDITIONAL_WORKING_PERIODS: 'Допълнителни работни часове',
    })
    for (const label of Object.values(KIND_LABELS)) {
      expect(label).not.toMatch(/[A-Z_]{4,}/)
      expect(label.toLowerCase()).not.toContain('exception')
    }
  })

  it('groups the kinds by scope and explains each one concisely', () => {
    expect(KIND_GROUPS).toEqual([
      { label: 'За целия бизнес', kinds: ['BUSINESS_CLOSURE'] },
      {
        label: 'За член на екипа',
        kinds: ['STAFF_TIME_OFF', 'WORKING_DAY_OVERRIDE', 'ADDITIONAL_WORKING_PERIODS'],
      },
    ])
    expect(KIND_EXPLANATIONS).toEqual({
      BUSINESS_CLOSURE: ['Блокира резервациите за всички членове на екипа през избрания период.'],
      STAFF_TIME_OFF: ['Блокира резервациите за избрания член на екипа през избрания период.'],
      WORKING_DAY_OVERRIDE: [
        'Заменя обичайните седмични часове за избраната дата.',
        'Неработното време и отсъствията продължават да блокират резервациите.',
      ],
      ADDITIONAL_WORKING_PERIODS: [
        'Добавя работни часове за избраната дата.',
        'Неработното време и отсъствията продължават да блокират резервациите.',
      ],
    })
  })

  it('states in the working kinds that blocking kinds still win, and never uses the internal term', () => {
    for (const kind of ['WORKING_DAY_OVERRIDE', 'ADDITIONAL_WORKING_PERIODS'] as const) {
      expect(KIND_EXPLANATIONS[kind]).toContain(
        'Неработното време и отсъствията продължават да блокират резервациите.',
      )
    }
    for (const line of [...Object.values(KIND_EXPLANATIONS).flat(), ...Object.values(OVERLAP_MESSAGES).flat()]) {
      expect(line.toLowerCase()).not.toContain('изключен')
    }
  })

  it('words the same-kind conflict per kind, two sentences on separate lines where needed', () => {
    expect(OVERLAP_MESSAGES).toEqual({
      BUSINESS_CLOSURE: ['Избраният период се застъпва с вече добавено неработно време.'],
      STAFF_TIME_OFF: [
        'Избраният период се застъпва с вече добавено отсъствие за този член на екипа.',
      ],
      WORKING_DAY_OVERRIDE: [
        'За тази дата вече има променени работни часове за избрания член на екипа.',
        'Редактирайте съществуващия запис, за да промените периодите.',
      ],
      ADDITIONAL_WORKING_PERIODS: [
        'За тази дата вече има допълнителни работни часове за избрания член на екипа.',
        'Редактирайте съществуващия запис, за да добавите или промените периодите.',
      ],
    })
  })

  it('formats single dates and inclusive ranges in Bulgarian day-first order', () => {
    expect(formatDate('2026-10-01')).toBe('01.10.2026')
    expect(formatDateRange('2026-10-01', '2026-10-01')).toBe('01.10.2026')
    expect(formatDateRange('2026-10-01', '2026-10-03')).toBe('01.10.2026 – 03.10.2026')
  })

  it('summarizes whole days, sorted periods, and a zero-period working-day override', () => {
    expect(
      hoursSummary({ kind: 'BUSINESS_CLOSURE', allDay: true, periods: [] }),
    ).toBe('Цял ден')
    expect(
      hoursSummary({
        kind: 'ADDITIONAL_WORKING_PERIODS',
        allDay: false,
        periods: [
          { startTime: '14:00', endTime: '18:00' },
          { startTime: '09:00', endTime: '12:00' },
        ],
      }),
    ).toBe('09:00–12:00, 14:00–18:00')
    expect(
      hoursSummary({ kind: 'WORKING_DAY_OVERRIDE', allDay: false, periods: [] }),
    ).toBe('Неработен ден')
  })

  it('resolves StaffMember names and never shows an identifier', () => {
    const names = new Map([['staff-a', 'Анна Иванова']])
    expect(staffNameOf({ staffMemberId: 'staff-a' }, names)).toBe('Анна Иванова')
    expect(staffNameOf({ staffMemberId: 'staff-x' }, names)).toBe('Член на екипа')
    expect(staffNameOf({ staffMemberId: null }, names)).toBe('—')
  })

  it('adds days across month and year boundaries and builds exactly 30 dates', () => {
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01')
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29')
    expect(defaultWindowFrom('2026-10-05')).toEqual({ from: '2026-10-05', to: '2026-11-03' })
  })

  it('computes the calendar date in an explicit time zone', () => {
    const instant = new Date('2026-10-05T21:30:00Z')
    expect(localDateIn(instant, 'Europe/Sofia')).toBe('2026-10-06')
    expect(localDateIn(instant, 'UTC')).toBe('2026-10-05')
    expect(localDateIn(instant, 'America/New_York')).toBe('2026-10-05')
    expect(localDateIn(instant, 'Not/AZone')).toBeNull()
  })

  it('sorts periods earliest first, then by end', () => {
    expect(
      sortPeriods([
        { startTime: '14:00', endTime: '15:00' },
        { startTime: '09:00', endTime: '12:00' },
        { startTime: '09:00', endTime: '10:00' },
      ]),
    ).toEqual([
      { startTime: '09:00', endTime: '10:00' },
      { startTime: '09:00', endTime: '12:00' },
      { startTime: '14:00', endTime: '15:00' },
    ])
  })
})

describe('window validation', () => {
  it('accepts a 93-date window and rejects the 94th date', () => {
    expect(validateWindow({ from: '2026-10-01', to: '2027-01-01' })).toEqual({})
    expect(validateWindow({ from: '2026-10-01', to: '2027-01-02' }).to).toContain('93')
  })

  it('requires both dates, rejects reversed windows and out-of-bounds dates', () => {
    expect(validateWindow({ from: '', to: '' })).toEqual({
      from: 'Въведете начална дата.',
      to: 'Въведете крайна дата.',
    })
    expect(validateWindow({ from: '2026-10-05', to: '2026-10-04' }).to).toBe(
      'Крайната дата не може да бъде преди началната.',
    )
    expect(validateWindow({ from: '1999-12-31', to: '2026-01-01' }).from).toContain('2000')
    expect(validateWindow({ from: '2026-01-01', to: '2101-01-01' }).to).toContain('2100')
    expect(validateWindow({ from: '2026-02-30', to: '2026-03-01' }).from).toBeDefined()
  })

  it('accepts the exact technical bounds', () => {
    expect(validateWindow({ from: '2000-01-01', to: '2000-01-01' })).toEqual({})
    expect(validateWindow({ from: '2100-12-31', to: '2100-12-31' })).toEqual({})
  })
})

describe('period validation', () => {
  it('accepts adjacent periods and keeps them separate', () => {
    const result = validatePeriods([period('a', '09:00', '12:00'), period('b', '12:00', '14:00')])
    expect(result.errors.size).toBe(0)
  })

  it('rejects missing, malformed, reversed, duplicate, and overlapping periods', () => {
    expect(validatePeriods([period('a', '', '10:00')]).errors.get('a')).toBe('MISSING_TIMES')
    expect(validatePeriods([period('a', '9:00', '10:00')]).errors.get('a')).toBe('INVALID_FORMAT')
    expect(validatePeriods([period('a', '09:00', '24:00')]).errors.get('a')).toBe('INVALID_FORMAT')
    expect(validatePeriods([period('a', '10:00', '10:00')]).errors.get('a')).toBe('REVERSED_RANGE')
    expect(validatePeriods([period('a', '11:00', '10:00')]).errors.get('a')).toBe('REVERSED_RANGE')
    const duplicate = validatePeriods([period('a', '09:00', '10:00'), period('b', '09:00', '10:00')])
    expect([...duplicate.errors.values()]).toEqual(['OVERLAP', 'OVERLAP'])
    const overlap = validatePeriods([period('a', '09:00', '11:00'), period('b', '10:30', '12:00')])
    expect(overlap.errors.get('a')).toBe('OVERLAP')
  })

  it('flags more than 24 periods and words every error in Bulgarian', () => {
    const many = Array.from({ length: 25 }, (_, index) => {
      const hour = String(Math.floor(index / 2)).padStart(2, '0')
      return period(`p${index}`, `${hour}:${index % 2 ? '30' : '00'}`, `${hour}:${index % 2 ? '59' : '29'}`)
    })
    expect(validatePeriods(many).tooMany).toBe(true)
    expect(validatePeriods(many.slice(0, 24)).tooMany).toBe(false)
    for (const code of ['MISSING_TIMES', 'INVALID_FORMAT', 'REVERSED_RANGE', 'OVERLAP'] as const) {
      expect(periodErrorMessage(code)).toMatch(/[а-я]/)
    }
  })
})

describe('local period conflicts', () => {
  const range = (startTime: string, endTime: string) => ({ startTime, endTime })

  it('names the repeated hours for an exact duplicate', () => {
    expect(periodConflictMessage(range('10:00', '12:00'), [range('10:00', '12:00')])).toBe(
      'Периодът 10:00–12:00 вече е добавен.',
    )
  })

  it('names both the proposed and the existing hours for an overlap', () => {
    expect(periodConflictMessage(range('11:00', '13:00'), [range('10:00', '12:00')])).toBe(
      'Периодът 11:00–13:00 се застъпва със съществуващия период 10:00–12:00.',
    )
    expect(periodConflictMessage(range('09:00', '15:00'), [range('10:00', '12:00')])).toBe(
      'Периодът 09:00–15:00 се застъпва със съществуващия период 10:00–12:00.',
    )
  })

  it('reports the earliest conflicting period first', () => {
    expect(
      periodConflictMessage(range('09:00', '18:00'), [range('14:00', '15:00'), range('10:00', '11:00')]),
    ).toBe('Периодът 09:00–18:00 се застъпва със съществуващия период 10:00–11:00.')
  })

  it('accepts adjacent periods and disjoint ones', () => {
    expect(periodConflictMessage(range('12:00', '14:00'), [range('09:00', '12:00')])).toBeNull()
    expect(periodConflictMessage(range('08:00', '09:00'), [range('09:00', '12:00')])).toBeNull()
    expect(periodConflictMessage(range('13:00', '14:00'), [range('09:00', '12:00')])).toBeNull()
  })
})

describe('derived schedule-change status', () => {
  const TODAY = '2026-10-10'

  it('uses exactly the approved Bulgarian labels', () => {
    expect(SCHEDULE_CHANGE_STATUS_LABELS).toEqual({
      UPCOMING: 'Предстояща',
      IN_EFFECT: 'В сила',
      PAST: 'Минала',
    })
  })

  it.each([
    ['a future record', '2026-10-11', '2026-10-13', 'UPCOMING'],
    ['a record starting today', '2026-10-10', '2026-10-12', 'IN_EFFECT'],
    ['a record ending today', '2026-10-08', '2026-10-10', 'IN_EFFECT'],
    ['a single-day record today', '2026-10-10', '2026-10-10', 'IN_EFFECT'],
    ['a multi-day record containing today', '2026-10-05', '2026-10-15', 'IN_EFFECT'],
    ['a record ending yesterday', '2026-10-05', '2026-10-09', 'PAST'],
  ] as const)('classifies %s inclusively', (_name, firstDate, lastDate, expected) => {
    expect(scheduleChangeStatus({ firstDate, lastDate }, TODAY)).toBe(expected)
  })

  it('takes the current date from the Business timezone, not the browser zone', () => {
    // 23:30 UTC is already the next day in Sofia but still the same day in Pago Pago.
    const instant = new Date('2026-10-05T23:30:00Z')
    expect(businessToday(instant, 'Europe/Sofia')).toBe('2026-10-06')
    expect(businessToday(instant, 'Pacific/Pago_Pago')).toBe('2026-10-05')
    const record = { firstDate: '2026-10-06', lastDate: '2026-10-06' }
    expect(scheduleChangeStatus(record, businessToday(instant, 'Europe/Sofia'))).toBe('IN_EFFECT')
    expect(scheduleChangeStatus(record, businessToday(instant, 'Pacific/Pago_Pago'))).toBe(
      'UPCOMING',
    )
  })

  it('falls back to a usable date for an unknown timezone', () => {
    expect(businessToday(new Date('2026-10-05T12:00:00Z'), 'Not/AZone')).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})

describe('sortScheduleExceptions', () => {
  const names = new Map([
    ['s-anna', 'Анна'],
    ['s-boris', 'Борис'],
    ['s-yana', 'Яна'],
  ])
  const TODAY = '2026-10-10'
  const base = {
    allDay: true,
    periods: [],
    version: 0,
    createdAt: '',
    updatedAt: '',
  }
  const record = (
    id: string,
    kind: ScheduleExceptionItem['kind'],
    staffMemberId: string | null,
    firstDate: string,
    lastDate: string,
  ): ScheduleExceptionItem => ({ ...base, id, kind, staffMemberId, firstDate, lastDate })

  const items = [
    record('7', 'STAFF_TIME_OFF', 's-yana', '2026-10-12', '2026-10-12'), // upcoming
    record('3', 'BUSINESS_CLOSURE', null, '2026-10-01', '2026-10-03'), // past
    record('5', 'STAFF_TIME_OFF', 's-anna', '2026-10-09', '2026-10-11'), // in effect
    record('1', 'ADDITIONAL_WORKING_PERIODS', 's-boris', '2026-10-12', '2026-10-12'), // upcoming
    record('2', 'STAFF_TIME_OFF', 's-anna', '2026-10-12', '2026-10-12'), // upcoming
  ]
  const ids = (sorted: ScheduleExceptionItem[]) => sorted.map((entry) => entry.id)
  const sort = (
    field: Parameters<typeof sortScheduleExceptions>[3],
    direction: 'asc' | 'desc' = 'asc',
  ) => ids(sortScheduleExceptions(items, names, TODAY, field, direction))

  it('orders by first date, last date, kind, name and then ID by default', () => {
    // Same dates 10-12: kinds "Допълнителни…" < "Отсъствие"; then Анна < Яна.
    expect(sort(undefined)).toEqual(['3', '5', '1', '2', '7'])
  })

  it('does not mutate its input', () => {
    const before = ids(items)
    sort('staff', 'desc')
    expect(ids(items)).toEqual(before)
  })

  it('sorts Дати by first date, last date and then ID, in both directions', () => {
    expect(sort('dates')).toEqual(['3', '5', '1', '2', '7'])
    // Reversal flips the date keys; the ID tie-breaker keeps its ascending order.
    expect(sort('dates', 'desc')).toEqual(['1', '2', '7', '5', '3'])
  })

  it('sorts Вид by the localized label, in both directions', () => {
    // Допълнителни работни часове < Неработно време < Отсъствие
    expect(sort('kind')).toEqual(['1', '3', '5', '2', '7'])
    expect(sort('kind', 'desc')).toEqual(['5', '2', '7', '3', '1'])
  })

  it('sorts Член на екипа by Bulgarian name and keeps Business-wide records last in both directions', () => {
    expect(sort('staff')).toEqual(['5', '2', '1', '7', '3'])
    expect(sort('staff', 'desc')).toEqual(['7', '1', '5', '2', '3'])
  })

  it('sorts Статус as В сила, Предстояща, Минала, and reverses only the primary order', () => {
    expect(sort('status')).toEqual(['5', '1', '2', '7', '3'])
    expect(sort('status', 'desc')).toEqual(['3', '1', '2', '7', '5'])
  })

  it('is deterministic for identical records and input order', () => {
    const twins = [
      record('b', 'STAFF_TIME_OFF', 's-anna', '2026-10-12', '2026-10-12'),
      record('a', 'STAFF_TIME_OFF', 's-anna', '2026-10-12', '2026-10-12'),
    ]
    for (const field of [undefined, 'kind', 'dates', 'staff', 'status'] as const) {
      expect(ids(sortScheduleExceptions(twins, names, TODAY, field))).toEqual(['a', 'b'])
      expect(ids(sortScheduleExceptions([...twins].reverse(), names, TODAY, field, 'desc'))).toEqual(
        ['a', 'b'],
      )
    }
  })
})
