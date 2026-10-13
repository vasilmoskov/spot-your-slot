import { describe, expect, it } from 'vitest'
import type { PublicService } from '../api'
import {
  MAX_SERVICES,
  MAX_VISIT_MINUTES,
  canonicalIds,
  disabledReason,
  reviewedTerms,
  selectedServices,
  selectionIsBookable,
  selectionKey,
  selectionTotals,
  sortByCanonicalId,
} from './selection'

function service(id: string, durationMinutes: number, price: number): PublicService {
  return { id, name: `Услуга ${id.slice(0, 2)}`, description: null, durationMinutes, price }
}

const A = '00000000-0000-4000-8000-000000000001'
const B = '7fffffff-ffff-4fff-bfff-ffffffffffff'
const C = '80000000-0000-4000-8000-000000000000'
const D = 'ffffffff-ffff-4fff-bfff-ffffffffffff'
const E = '22222222-2222-4222-8222-222222222222'
const F = '33333333-3333-4333-8333-333333333333'

describe('the selection limits', () => {
  it('allows one to five Services and at most 480 minutes', () => {
    expect(MAX_SERVICES).toBe(5)
    expect(MAX_VISIT_MINUTES).toBe(480)
    expect(selectionIsBookable([])).toBe(false)
    expect(selectionIsBookable([service(A, 30, 1)])).toBe(true)
    expect(selectionIsBookable([service(A, 96, 1), service(B, 96, 1), service(C, 96, 1), service(D, 96, 1), service(E, 96, 1)])).toBe(true)
    expect(selectionIsBookable([service(A, 96, 1), service(B, 96, 1), service(C, 96, 1), service(D, 96, 1), service(E, 97, 1)])).toBe(false)
    expect(selectionIsBookable([service(A, 240, 1), service(B, 240, 1)])).toBe(true)
    expect(selectionIsBookable([service(A, 240, 1), service(B, 241, 1)])).toBe(false)
  })

  it('disables an unselected Service after five and when it would pass 480 minutes', () => {
    const five = [A, B, C, D, E].map((id) => service(id, 10, 1))
    const sixth = service(F, 10, 1)
    expect(disabledReason(five, sixth)).toBe('count')
    expect(disabledReason(five.slice(0, 4), sixth)).toBeNull()
    expect(disabledReason([service(A, 400, 1)], service(B, 80, 1))).toBeNull()
    expect(disabledReason([service(A, 400, 1)], service(B, 81, 1))).toBe('duration')
  })

  it('never disables a selected Service, so it can always be removed', () => {
    const five = [A, B, C, D, E].map((id) => service(id, 96, 1))
    for (const chosen of five) expect(disabledReason(five, chosen)).toBeNull()
    const over = [service(A, 300, 1), service(B, 300, 1)]
    for (const chosen of over) expect(disabledReason(over, chosen)).toBeNull()
  })

  it('reports the count before the duration when both apply', () => {
    const five = [A, B, C, D, E].map((id) => service(id, 96, 1))
    expect(disabledReason(five, service(F, 1, 1))).toBe('count')
  })
})

describe('totals', () => {
  it('sums minutes and exact cents', () => {
    expect(selectionTotals([service(A, 45, 25), service(B, 120, 80.5)])).toEqual({
      count: 2,
      minutes: 165,
      cents: 10550,
    })
    const max = [A, B, C, D, E].map((id) => service(id, 96, 9999999999.99))
    expect(selectionTotals(max)).toEqual({ count: 5, minutes: 480, cents: 4_999_999_999_995 })
    expect(selectionTotals([])).toEqual({ count: 0, minutes: 0, cents: 0 })
  })
})

describe('order', () => {
  const catalog = [service(F, 10, 1), service(A, 10, 1), service(D, 10, 1), service(B, 10, 1)]

  it('keeps the selected Services in the public-profile order whatever order they were chosen in', () => {
    expect(selectedServices(catalog, [B, F, D]).map((item) => item.id)).toEqual([F, D, B])
    expect(selectedServices(catalog, new Set([D, B, F])).map((item) => item.id)).toEqual([F, D, B])
    expect(selectedServices(catalog, ['unknown']).map((item) => item.id)).toEqual([])
  })

  it('orders identifiers by the canonical (unsigned) order, never by the signed order', () => {
    expect(canonicalIds([D, C, B, A])).toEqual([A, B, C, D])
    expect(canonicalIds([C, D, A, B])).toEqual([A, B, C, D])
    // Case does not matter for identity.
    expect(canonicalIds([C.toUpperCase(), A])).toEqual([A, C])
  })

  it('gives one key to one set of Services', () => {
    expect(selectionKey([C, A, B])).toBe(selectionKey([B, C, A]))
    expect(selectionKey([A, B])).not.toBe(selectionKey([A, C]))
    expect(selectionKey([])).toBe('')
  })

  it('sorts request items by canonical identifier and leaves the input untouched', () => {
    const items = [{ serviceId: C }, { serviceId: A }, { serviceId: D }, { serviceId: B }]
    expect(sortByCanonicalId(items).map((item) => item.serviceId)).toEqual([A, B, C, D])
    expect(items.map((item) => item.serviceId)).toEqual([C, A, D, B])
  })
})

describe('reviewed terms', () => {
  it('takes the duration and the exact cents of each Service', () => {
    expect(reviewedTerms([service(A, 45, 25), service(B, 120, 80.5)])).toEqual([
      { serviceId: A, durationMinutes: 45, priceCents: 2500 },
      { serviceId: B, durationMinutes: 120, priceCents: 8050 },
    ])
  })
})
