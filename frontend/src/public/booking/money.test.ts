import { describe, expect, it } from 'vitest'
import { formatServicePrice } from '../../business/services/presentation'
import {
  MAX_SERVICE_PRICE_CENTS,
  MAX_VISIT_PRICE_CENTS,
  formatCents,
  priceToCents,
  sumCents,
  wirePriceToCents,
} from './money'

describe('exact EUR cents', () => {
  it('converts a server price to integer cents', () => {
    expect(priceToCents(25)).toBe(2500)
    expect(priceToCents(80.5)).toBe(8050)
    expect(priceToCents(0)).toBe(0)
    expect(priceToCents(0.01)).toBe(1)
    expect(priceToCents(9999999999.99)).toBe(999_999_999_999)
  })

  it('sums in integer cents where decimal floating-point addition drifts', () => {
    // 0.1 + 0.2 is 0.30000000000000004 in floating point; the cents are exact.
    expect(0.1 + 0.2).not.toBe(0.3)
    expect(sumCents([0.1, 0.2])).toBe(30)
    expect(formatCents(sumCents([0.1, 0.2]))).toBe('0.30 €')
    // 33 services' worth of 0.07 would accumulate error as decimals; five prices cannot.
    expect(sumCents([19.99, 19.99, 19.99, 0.01, 0.02])).toBe(6000)
  })

  it('sums five maximum Service prices without losing a cent', () => {
    const max = 9999999999.99
    const total = sumCents([max, max, max, max, max])
    expect(total).toBe(4_999_999_999_995)
    expect(Number.isSafeInteger(total)).toBe(true)
    expect(formatCents(total)).toBe('49999999999.95 €')
  })

  it('yields exact integers where the plain multiplication leaves binary noise', () => {
    // 0.07 * 100 is 7.000000000000001: sent as a claim it would not be an integer at all.
    expect(0.07 * 100).not.toBe(7)
    for (const [price, cents] of [
      [0.07, 7],
      [1.1, 110],
      [4.35, 435],
      [8.2, 820],
      [19.99, 1999],
      [1234.07, 123407],
      [57.13, 5713],
    ] as const) {
      expect(priceToCents(price)).toBe(cents)
      expect(Number.isInteger(priceToCents(price))).toBe(true)
    }
  })

  it('does not trust a decimal running total: a plain sum is 21234567890.109997, the cents are exact', () => {
    const prices = [3333333333.33, 3333333333.33, 3333333333.34, 9999999999.99, 1234567890.12]
    expect(prices.reduce((sum, price) => sum + price, 0)).not.toBe(21234567890.11)
    expect(sumCents(prices)).toBe(2_123_456_789_011)
    expect(formatCents(sumCents(prices))).toBe('21234567890.11 €')
  })

  it('formats like the server price formatting of a single Service', () => {
    for (const price of [0, 0.5, 25, 80.5, 99.99, 1234.07, 9999999999.99]) {
      expect(formatCents(priceToCents(price))).toBe(formatServicePrice(price))
    }
  })

  it('pads the fraction and never groups thousands', () => {
    expect(formatCents(5)).toBe('0.05 €')
    expect(formatCents(100)).toBe('1.00 €')
    expect(formatCents(123456)).toBe('1234.56 €')
  })
})

describe('exact validation of a price from the wire', () => {
  const service = (value: unknown) => wirePriceToCents(value, MAX_SERVICE_PRICE_CENTS)
  const visit = (value: unknown) => wirePriceToCents(value, MAX_VISIT_PRICE_CENTS)

  it('accepts whole cents, including the binary noise of ordinary two-decimal values', () => {
    expect(service(0)).toBe(0)
    expect(service(0.01)).toBe(1)
    expect(service(0.07)).toBe(7)
    expect(service(0.29)).toBe(29)
    expect(service(1.1)).toBe(110)
    expect(service(4.35)).toBe(435)
    expect(service(25)).toBe(2500)
    expect(service(80.5)).toBe(8050)
    expect(service(1234.07)).toBe(123407)
    expect(service(9999999999.99)).toBe(999_999_999_999)
  })

  it('accepts exactly the approved capacities', () => {
    expect(service(9999999999.99)).toBe(MAX_SERVICE_PRICE_CENTS)
    expect(visit(49999999999.95)).toBe(MAX_VISIT_PRICE_CENTS)
    expect(MAX_VISIT_PRICE_CENTS).toBe(5 * MAX_SERVICE_PRICE_CENTS)
  })

  it('refuses a genuine third decimal instead of rounding it into validity', () => {
    for (const value of [1.001, 1.005, 0.001, 0.015, 9999999999.999, 25.004, 1e-7, 0.1 + 0.2]) {
      expect(service(value), String(value)).toBeNull()
    }
  })

  it('refuses a value one cent above the capacity', () => {
    expect(service(10000000000)).toBeNull()
    expect(service(10000000000.0)).toBeNull()
    expect(service(9999999999.99 + 0.01)).toBeNull()
    expect(visit(49999999999.96)).toBeNull()
    expect(visit(50000000000)).toBeNull()
  })

  it('refuses negative, non-finite and non-number values', () => {
    for (const value of [-0.01, -1, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY, '1.00', '25', null, undefined, {}, [], true]) {
      expect(service(value), String(value)).toBeNull()
    }
  })

  it('refuses unsafe or overflowing conversions', () => {
    for (const value of [1e21, 1e300, Number.MAX_VALUE, Number.MAX_SAFE_INTEGER, 2 ** 60]) {
      expect(service(value), String(value)).toBeNull()
      expect(visit(value), String(value)).toBeNull()
    }
  })
})
