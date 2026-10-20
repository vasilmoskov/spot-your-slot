import { expect, test } from '@playwright/test'
import { dateSurvivingFirstBooking, dateWithSlots, firstCommonDate } from './support/booking'

// Deterministic regression of the date rule of "a slot taken by another browser before confirming…": the date must
// still offer a start after the first start is taken. Late in the day today has only a few starts left, and the first
// visit (105 minutes) blocks every overlapping start, so taking it leaves today with nothing. No clock is involved:
// the answers are synthetic.

const slot = (start: string, minutes = 105) => ({
  start: `${start}:00+03:00`,
  end: new Date(Date.parse(`${start}:00+03:00`) + minutes * 60_000).toISOString(),
})

test.describe('the date of a slot-taken journey survives the first booking', () => {
  test('skips a date whose starts all overlap the first one (today, late in the day)', async () => {
    const today = [slot('2026-10-19T15:00'), slot('2026-10-19T15:15'), slot('2026-10-19T16:15')]
    const tomorrow = [slot('2026-10-20T09:00'), slot('2026-10-20T09:15'), slot('2026-10-20T12:00')]
    const answers: Record<string, ReturnType<typeof slot>[]> = { '2026-10-19': today, '2026-10-20': tomorrow }
    const asked: string[] = []
    const picked = await dateSurvivingFirstBooking(['2026-10-19', '2026-10-20'], async (date) => {
      asked.push(date)
      return answers[date] ?? []
    })
    expect(picked.date).toBe('2026-10-20')
    expect(picked.slot).toBe(tomorrow[0])
    expect(asked).toEqual(['2026-10-19', '2026-10-20'])
  })

  test('keeps today when a start remains after the first one is taken', async () => {
    const today = [slot('2026-10-19T09:00'), slot('2026-10-19T12:00')]
    const picked = await dateSurvivingFirstBooking(['2026-10-19', '2026-10-20'], async () => today)
    expect(picked.date).toBe('2026-10-19')
  })

  test('skips a date without any start and fails clearly when no date survives', async () => {
    const answers: Record<string, ReturnType<typeof slot>[]> = { '2026-10-19': [], '2026-10-20': [slot('2026-10-20T09:00')] }
    await expect(dateSurvivingFirstBooking(['2026-10-19', '2026-10-20'], async (date) => answers[date] ?? [])).rejects.toThrow(
      'No date keeps a bookable start',
    )
  })

  test('answers for the exact selection it is given, one call per candidate date', async () => {
    const calls: string[] = []
    await dateSurvivingFirstBooking(['2026-10-20'], async (date) => {
      calls.push(date)
      return [slot('2026-10-20T09:00'), slot('2026-10-20T12:00')]
    })
    expect(calls).toEqual(['2026-10-20'])
  })
})

test.describe('a journey that changes its selection uses a date bookable for both selections', () => {
  test('skips today when only the shorter first visit still fits today', () => {
    // 105 minutes still fits today late in the day, 120 minutes no longer does.
    expect(firstCommonDate(['2026-10-19', '2026-10-20', '2026-10-21'], ['2026-10-20', '2026-10-21'])).toBe('2026-10-20')
  })

  test('keeps the first date offered for both and fails clearly when there is none', () => {
    expect(firstCommonDate(['2026-10-19', '2026-10-20'], ['2026-10-19', '2026-10-20'])).toBe('2026-10-19')
    expect(() => firstCommonDate(['2026-10-19'], ['2026-10-20'])).toThrow('No date is bookable for both selections')
  })
})

test.describe('a journey that needs the Nth start of a date', () => {
  const one = (date: string) => [slot(`${date}T17:00`)]
  const three = (date: string) => [slot(`${date}T09:00`), slot(`${date}T09:15`), slot(`${date}T10:00`)]

  test('skips a first date that has no start at all', async () => {
    const answers: Record<string, ReturnType<typeof slot>[]> = { '2026-10-20': [], '2026-10-21': three('2026-10-21') }
    const picked = await dateWithSlots(['2026-10-20', '2026-10-21'], async (date) => answers[date] ?? [], 2)
    expect(picked.date).toBe('2026-10-21')
  })

  test('skips a first date that has fewer starts than needed (today, late in the day)', async () => {
    const answers: Record<string, ReturnType<typeof slot>[]> = { '2026-10-20': one('2026-10-20'), '2026-10-21': three('2026-10-21') }
    const asked: string[] = []
    const picked = await dateWithSlots(['2026-10-20', '2026-10-21'], async (date) => { asked.push(date); return answers[date] ?? [] }, 2)
    expect(picked.date).toBe('2026-10-21')
    expect(picked.slots).toHaveLength(3)
    expect(asked).toEqual(['2026-10-20', '2026-10-21'])
  })

  test('takes a later date that has enough starts, and stops asking once it found one', async () => {
    const answers: Record<string, ReturnType<typeof slot>[]> = {
      '2026-10-20': one('2026-10-20'), '2026-10-21': one('2026-10-21'), '2026-10-22': three('2026-10-22'), '2026-10-23': three('2026-10-23'),
    }
    const asked: string[] = []
    const picked = await dateWithSlots(['2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23'], async (date) => { asked.push(date); return answers[date] ?? [] }, 3)
    expect(picked.date).toBe('2026-10-22')
    expect(asked).toEqual(['2026-10-20', '2026-10-21', '2026-10-22'])
  })

  test('keeps the first date when it already has enough starts', async () => {
    expect((await dateWithSlots(['2026-10-20', '2026-10-21'], async (date) => three(date), 3)).date).toBe('2026-10-20')
  })

  test('fails clearly when no date satisfies the requirement', async () => {
    await expect(dateWithSlots(['2026-10-20', '2026-10-21'], async (date) => one(date), 2)).rejects.toThrow('No offered date has 2 starts')
    await expect(dateWithSlots([], async () => three('2026-10-20'), 1)).rejects.toThrow('No offered date has 1 starts')
  })

  test('is answered for the final Service selection and StaffMember it is built with, once per candidate date', async () => {
    const calls: string[] = []
    // How a journey builds `slotsFor`: closed over exactly the selection and StaffMember it later submits.
    const forSelection = (serviceIds: readonly string[], staffId: string | null) => async (date: string) => {
      calls.push(`${serviceIds.join('+')}|${staffId}|${date}`)
      return date === '2026-10-21' ? three(date) : one(date)
    }
    const picked = await dateWithSlots(['2026-10-20', '2026-10-21'], forSelection(['cut', 'nails'], 'anna'), 2)
    expect(picked.date).toBe('2026-10-21')
    expect(calls).toEqual(['cut+nails|anna|2026-10-20', 'cut+nails|anna|2026-10-21'])
  })
})
