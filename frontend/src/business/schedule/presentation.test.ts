import { describe, expect, it } from 'vitest'
import type { WorkingPeriod } from './api'
import {
  WEEKDAY_LABELS,
  WEEKDAY_ORDER,
  WEEKDAY_SENTENCE_LABELS,
  formatPeriodRange,
  groupPeriodsByWeekday,
  periodValidationMessage,
  sortPeriodsForDisplay,
  validateDraftPeriods,
  type DraftPeriod,
} from './presentation'

function draft(overrides: Partial<DraftPeriod> & Pick<DraftPeriod, 'clientId' | 'weekday'>): DraftPeriod {
  return { startTime: '09:00', endTime: '10:00', ...overrides }
}

describe('schedule presentation — weekday order and labels', () => {
  it('lists all seven Bulgarian weekdays in the required order', () => {
    expect(WEEKDAY_ORDER).toEqual([
      'MONDAY',
      'TUESDAY',
      'WEDNESDAY',
      'THURSDAY',
      'FRIDAY',
      'SATURDAY',
      'SUNDAY',
    ])
    expect(WEEKDAY_ORDER.map((weekday) => WEEKDAY_LABELS[weekday])).toEqual([
      'Понеделник',
      'Вторник',
      'Сряда',
      'Четвъртък',
      'Петък',
      'Събота',
      'Неделя',
    ])
  })

  it('provides a lowercase sentence-form label for every weekday, for mid-sentence use', () => {
    expect(WEEKDAY_ORDER.map((weekday) => WEEKDAY_SENTENCE_LABELS[weekday])).toEqual([
      'понеделник',
      'вторник',
      'сряда',
      'четвъртък',
      'петък',
      'събота',
      'неделя',
    ])
    for (const weekday of WEEKDAY_ORDER) {
      expect(WEEKDAY_SENTENCE_LABELS[weekday]).toBe(WEEKDAY_LABELS[weekday].toLowerCase())
    }
  })
})

describe('schedule presentation — formatting', () => {
  it('formats a period range with an en dash', () => {
    expect(formatPeriodRange('09:00', '12:00')).toBe('09:00–12:00')
  })

  it('sorts periods by weekday then chronologically within a weekday', () => {
    const periods: WorkingPeriod[] = [
      { weekday: 'WEDNESDAY', startTime: '09:00', endTime: '10:00' },
      { weekday: 'MONDAY', startTime: '14:00', endTime: '18:00' },
      { weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' },
    ]
    expect(sortPeriodsForDisplay(periods)).toEqual([
      { weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' },
      { weekday: 'MONDAY', startTime: '14:00', endTime: '18:00' },
      { weekday: 'WEDNESDAY', startTime: '09:00', endTime: '10:00' },
    ])
  })

  it('uses end time as a deterministic tie-breaker when start times are equal', () => {
    const periods: WorkingPeriod[] = [
      { weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' },
      { weekday: 'MONDAY', startTime: '09:00', endTime: '10:00' },
    ]
    expect(sortPeriodsForDisplay(periods)).toEqual([
      { weekday: 'MONDAY', startTime: '09:00', endTime: '10:00' },
      { weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' },
    ])
  })

  it('sorts DraftPeriod values (with clientId) into the same deterministic order, preserving clientId', () => {
    const periods: DraftPeriod[] = [
      draft({ clientId: 'b', weekday: 'MONDAY', startTime: '14:00', endTime: '18:00' }),
      draft({ clientId: 'a', weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' }),
    ]
    expect(sortPeriodsForDisplay(periods)).toEqual([
      draft({ clientId: 'a', weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' }),
      draft({ clientId: 'b', weekday: 'MONDAY', startTime: '14:00', endTime: '18:00' }),
    ])
  })

  it('groups periods by weekday, including empty weekdays', () => {
    const periods: WorkingPeriod[] = [{ weekday: 'FRIDAY', startTime: '09:00', endTime: '10:00' }]
    const grouped = groupPeriodsByWeekday(periods)
    expect(grouped.FRIDAY).toEqual(periods)
    expect(grouped.MONDAY).toEqual([])
    expect(grouped.SUNDAY).toEqual([])
  })
})

describe('schedule presentation — draft period validation', () => {
  it('accepts an empty period list', () => {
    expect(validateDraftPeriods([]).valid).toBe(true)
  })

  it('accepts a single period and multiple non-overlapping periods per day', () => {
    const periods = [
      draft({ clientId: 'a', weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' }),
      draft({ clientId: 'b', weekday: 'MONDAY', startTime: '14:00', endTime: '18:00' }),
    ]
    const result = validateDraftPeriods(periods)
    expect(result.valid).toBe(true)
    expect(result.errorsByPeriod.size).toBe(0)
  })

  it('accepts adjacent periods (one starting exactly when the other ends)', () => {
    const periods = [
      draft({ clientId: 'a', weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' }),
      draft({ clientId: 'b', weekday: 'MONDAY', startTime: '12:00', endTime: '15:00' }),
    ]
    expect(validateDraftPeriods(periods).valid).toBe(true)
  })

  it('rejects overlapping periods on the same weekday', () => {
    const periods = [
      draft({ clientId: 'a', weekday: 'MONDAY', startTime: '09:00', endTime: '12:00' }),
      draft({ clientId: 'b', weekday: 'MONDAY', startTime: '11:00', endTime: '15:00' }),
    ]
    const result = validateDraftPeriods(periods)
    expect(result.valid).toBe(false)
    expect(result.errorsByPeriod.get('a')).toBe('OVERLAP')
    expect(result.errorsByPeriod.get('b')).toBe('OVERLAP')
  })

  it('rejects duplicate periods on the same weekday', () => {
    const periods = [
      draft({ clientId: 'a', weekday: 'TUESDAY', startTime: '09:00', endTime: '10:00' }),
      draft({ clientId: 'b', weekday: 'TUESDAY', startTime: '09:00', endTime: '10:00' }),
    ]
    const result = validateDraftPeriods(periods)
    expect(result.valid).toBe(false)
    expect(result.errorsByPeriod.get('a')).toBe('OVERLAP')
    expect(result.errorsByPeriod.get('b')).toBe('OVERLAP')
  })

  it('does not flag periods on different weekdays as overlapping', () => {
    const periods = [
      draft({ clientId: 'a', weekday: 'MONDAY', startTime: '09:00', endTime: '18:00' }),
      draft({ clientId: 'b', weekday: 'TUESDAY', startTime: '09:00', endTime: '18:00' }),
    ]
    expect(validateDraftPeriods(periods).valid).toBe(true)
  })

  it('rejects a reversed or equal period range', () => {
    const reversed = validateDraftPeriods([
      draft({ clientId: 'a', weekday: 'MONDAY', startTime: '12:00', endTime: '09:00' }),
    ])
    expect(reversed.errorsByPeriod.get('a')).toBe('REVERSED_RANGE')

    const equal = validateDraftPeriods([
      draft({ clientId: 'a', weekday: 'MONDAY', startTime: '09:00', endTime: '09:00' }),
    ])
    expect(equal.errorsByPeriod.get('a')).toBe('REVERSED_RANGE')
  })

  it('rejects missing start or end times', () => {
    const result = validateDraftPeriods([
      draft({ clientId: 'a', weekday: 'MONDAY', startTime: '', endTime: '10:00' }),
      draft({ clientId: 'b', weekday: 'MONDAY', startTime: '09:00', endTime: '' }),
    ])
    expect(result.errorsByPeriod.get('a')).toBe('MISSING_TIMES')
    expect(result.errorsByPeriod.get('b')).toBe('MISSING_TIMES')
  })

  it('rejects malformed times', () => {
    const result = validateDraftPeriods([
      draft({ clientId: 'a', weekday: 'MONDAY', startTime: '9:00', endTime: '10:00' }),
      draft({ clientId: 'b', weekday: 'MONDAY', startTime: '09:00:00', endTime: '10:00' }),
      draft({ clientId: 'c', weekday: 'MONDAY', startTime: '24:00', endTime: '10:00' }),
    ])
    expect(result.errorsByPeriod.get('a')).toBe('INVALID_FORMAT')
    expect(result.errorsByPeriod.get('b')).toBe('INVALID_FORMAT')
    expect(result.errorsByPeriod.get('c')).toBe('INVALID_FORMAT')
  })

  it('rejects more than 100 periods for the week', () => {
    const periods = Array.from({ length: 101 }, (_, index) =>
      draft({
        clientId: `p${index}`,
        weekday: WEEKDAY_ORDER[index % 7]!,
        startTime: '09:00',
        endTime: '09:15',
      }),
    )
    const result = validateDraftPeriods(periods)
    expect(result.tooManyPeriods).toBe(true)
    expect(result.valid).toBe(false)
  })

  it('accepts exactly 100 periods spread across the week without overlap', () => {
    const periods: DraftPeriod[] = []
    for (let index = 0; index < 100; index += 1) {
      const weekday = WEEKDAY_ORDER[index % 7]!
      const startHour = 6 + Math.floor(index / 7)
      const hour = String(startHour).padStart(2, '0')
      periods.push(
        draft({ clientId: `p${index}`, weekday, startTime: `${hour}:00`, endTime: `${hour}:30` }),
      )
    }
    const result = validateDraftPeriods(periods)
    expect(result.tooManyPeriods).toBe(false)
    expect(result.valid).toBe(true)
  })

  it('provides a Bulgarian message for every validation error', () => {
    expect(periodValidationMessage('MISSING_TIMES')).toMatch(/начален/i)
    expect(periodValidationMessage('INVALID_FORMAT')).toMatch(/ЧЧ:ММ/)
    expect(periodValidationMessage('REVERSED_RANGE')).toMatch(/преди крайния/)
    expect(periodValidationMessage('OVERLAP')).toMatch(/припокрива/)
  })
})
