import { describe, expect, it } from 'vitest'
import {
  INITIAL_STATE,
  MAX_VISIT_MINUTES,
  buildBody,
  canonicalIds,
  claimsOf,
  disabledReason,
  firstIncomplete,
  isDirty,
  noteError,
  normalizeNote,
  reduce,
  selectionKey,
  summaryText,
  totalsOf,
  type FormState,
} from './model'
import { S_BROW, S_COLOR, S_CUT, S_MASSAGE, S_NAILS, S_WAX, service } from './testFixtures'

const filled: FormState = {
  ...INITIAL_STATE,
  step: 'review',
  customer: { id: 'c', name: 'Мария' },
  serviceIds: [S_CUT.id],
  staffMemberId: 's',
  date: '2026-11-10',
  start: { start: '2026-11-10T09:00:00+02:00', end: '2026-11-10T09:45:00+02:00' },
  note: 'бележка',
}

describe('invalidation', () => {
  it('a changed Service set clears the StaffMember, the date and the start only', () => {
    const next = reduce(filled, { type: 'services', ids: [S_CUT.id, S_NAILS.id] })
    expect(next).toMatchObject({ staffMemberId: null, date: '', start: null })
    expect(next.customer).toEqual(filled.customer)
    expect(next.note).toBe('бележка')
  })

  it('the same Service set in another order invalidates nothing', () => {
    const two = { ...filled, serviceIds: [S_CUT.id, S_NAILS.id] }
    expect(reduce(two, { type: 'services', ids: [S_NAILS.id, S_CUT.id] })).toMatchObject({
      staffMemberId: 's',
      date: '2026-11-10',
    })
  })

  it('a changed StaffMember clears the start but keeps the date; a changed date clears the start', () => {
    expect(reduce(filled, { type: 'staff', staffMemberId: 't' })).toMatchObject({ start: null, date: '2026-11-10' })
    expect(reduce(filled, { type: 'staff', staffMemberId: 's' })).toBe(filled)
    expect(reduce(filled, { type: 'date', date: '2026-11-11' }).start).toBeNull()
    expect(reduce(filled, { type: 'date', date: '2026-11-10' })).toBe(filled)
  })

  it('the Customer and the note depend on nothing', () => {
    expect(reduce(filled, { type: 'customer', customer: null })).toMatchObject({ staffMemberId: 's', start: filled.start })
    expect(reduce(filled, { type: 'note', note: 'x' })).toMatchObject({ staffMemberId: 's', start: filled.start })
  })
})

describe('step movement', () => {
  it('goes to the next incomplete step after an edit from the review, and to the review when nothing is missing', () => {
    const editing = reduce(filled, { type: 'go', step: 'services', fromReview: true })
    expect(reduce(editing, { type: 'advance' }).step).toBe('review')
    const cleared = reduce(editing, { type: 'services', ids: [S_NAILS.id] })
    const advanced = reduce(cleared, { type: 'advance' })
    expect(advanced).toMatchObject({ step: 'staff', fromReview: true })
    expect(firstIncomplete(cleared)).toBe('staff')
  })

  it('moves one step at a time outside a review edit and back one step', () => {
    expect(reduce(INITIAL_STATE, { type: 'advance' }).step).toBe('services')
    expect(reduce({ ...INITIAL_STATE, step: 'staff' }, { type: 'back' }).step).toBe('services')
    expect(reduce(INITIAL_STATE, { type: 'back' }).step).toBe('customer')
  })

  it('is dirty exactly when something was entered', () => {
    expect(isDirty(INITIAL_STATE)).toBe(false)
    expect(isDirty({ ...INITIAL_STATE, note: '   ' })).toBe(false)
    expect(isDirty({ ...INITIAL_STATE, note: 'x' })).toBe(true)
    expect(isDirty({ ...INITIAL_STATE, serviceIds: ['x'] })).toBe(true)
  })
})

describe('Services', () => {
  it('adds money as integer cents and minutes exactly', () => {
    const totals = totalsOf([S_BROW, S_WAX])
    expect(totals).toEqual({ count: 2, minutes: 35, cents: 30 })
    expect(summaryText(totals.cents, totals.minutes)).toBe('0.30 € — 35 мин.')
    expect(summaryText(4200, 75)).toBe('42.00 € — 75 мин.')
    const big = service({ id: 'x', name: 'Скъпа', price: 9999999999.99, durationMinutes: 96 })
    expect(totalsOf([big, big, big, big, big]).cents).toBe(4_999_999_999_995)
  })

  it('does not drift on prices a binary float cannot hold', () => {
    const a = service({ id: 'p1', name: 'А', price: 1.15, durationMinutes: 10 })
    const b = service({ id: 'p2', name: 'Б', price: 0.57, durationMinutes: 10 })
    expect(totalsOf([a, b]).cents).toBe(172)
    expect(summaryText(totalsOf([a, b]).cents, 20)).toBe('1.72 € — 20 мин.')
    expect(claimsOf([a])[0]!.reviewedPriceCents).toBe(115)
  })

  it('refuses a sixth Service and a total above 480 minutes but never a selected one', () => {
    const five = [S_CUT, S_COLOR, S_NAILS, S_BROW, S_WAX]
    expect(disabledReason(five, S_MASSAGE)).toBe('count')
    expect(disabledReason(five, S_CUT)).toBeNull()
    const long = [service({ id: 'l1', name: 'A', durationMinutes: 300 }), service({ id: 'l2', name: 'B', durationMinutes: 180 })]
    expect(totalsOf(long).minutes).toBe(MAX_VISIT_MINUTES)
    expect(disabledReason(long, S_BROW)).toBe('duration')
    expect(disabledReason(long, long[0]!)).toBeNull()
  })

  it('orders claims and keys by the canonical identifier whatever the click order', () => {
    const forward = claimsOf([S_CUT, S_COLOR, S_NAILS])
    const backward = claimsOf([S_NAILS, S_COLOR, S_CUT])
    expect(JSON.stringify(forward)).toBe(JSON.stringify(backward))
    expect(forward.map((claim) => claim.serviceId)).toEqual([S_NAILS.id, S_COLOR.id, S_CUT.id])
    expect(selectionKey([S_CUT.id, S_NAILS.id])).toBe(selectionKey([S_NAILS.id, S_CUT.id]))
    expect(canonicalIds(['B-1', 'a-2'])).toEqual(['a-2', 'b-1'])
    expect(forward[0]).toEqual({
      serviceId: S_NAILS.id,
      reviewedServiceName: 'Маникюр',
      reviewedDurationMinutes: 60,
      reviewedPriceCents: 2500,
    })
  })
})

describe('the note and the request', () => {
  it('normalizes line breaks and white space, blanks to absent and never truncates', () => {
    expect(normalizeNote('  а\r\nб\rв  ')).toBe('а\nб\nв')
    expect(normalizeNote(' \n\t ')).toBeNull()
    expect(normalizeNote('x'.repeat(600))).toHaveLength(600)
  })

  it('bounds the note in code points, not characters, and rejects control characters', () => {
    expect(noteError('😀'.repeat(500))).toBeUndefined()
    expect(noteError('😀'.repeat(501))).toBe('Бележката е твърде дълга. Съкратете я.')
    expect(noteError('a\u0000b')).toBe('Бележката съдържа непозволени знаци.')
    expect(noteError('a\tb\nc')).toBeUndefined()
    expect(noteError('\uD800')).toBe('Бележката съдържа непозволени знаци.')
    expect(noteError('')).toBeUndefined()
  })

  it('builds exactly the contract body and omits a blank note', () => {
    const body = buildBody('c', 's', '2026-11-10T09:00:00+02:00', [S_CUT], '  ')
    expect(Object.keys(body)).toEqual(['customerId', 'staffMemberId', 'start', 'services'])
    expect(Object.keys(body.services[0]!)).toEqual([
      'serviceId',
      'reviewedServiceName',
      'reviewedDurationMinutes',
      'reviewedPriceCents',
    ])
    expect(buildBody('c', 's', 'x', [S_CUT], ' а ').internalNote).toBe('а')
  })
})
