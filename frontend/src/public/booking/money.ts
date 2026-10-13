// Exact EUR arithmetic for the multi-Service visit (ADR-0027). A price reaches the browser as a JSON
// number with at most two decimals (numeric(12,2) on the server), so it is converted to INTEGER cents
// once and every sum is an integer sum: five maximum Service prices (5 x 999 999 999 999 cents) stay far
// below Number.MAX_SAFE_INTEGER, which floating-point addition of the decimal values could not promise.

/** The integer cents of a server price; the rounding only removes binary representation noise. */
export function priceToCents(price: number): number {
  return Math.round(price * 100)
}

export function sumCents(prices: readonly number[]): number {
  let total = 0
  for (const price of prices) total += priceToCents(price)
  return total
}

/**
 * «123.45 €» from integer cents, character for character the format of `formatServicePrice`
 * (two decimals, no grouping), without ever going through a decimal fraction.
 */
export function formatCents(cents: number): string {
  const whole = Math.trunc(cents / 100)
  const fraction = cents % 100
  return `${whole}.${String(fraction).padStart(2, '0')} €`
}

// The approved capacities of ADR-0027: a Service price fits numeric(12,2) and a visit total is at most
// five of them.
export const MAX_SERVICE_PRICE_CENTS = 999_999_999_999
export const MAX_VISIT_PRICE_CENTS = 4_999_999_999_995

/**
 * The exact integer cents of a price that arrived on the wire, or null when the value is not a valid
 * EUR amount. Unlike `priceToCents` (which rounds, and is meant for values the client itself produced
 * from a validated source), this refuses to repair anything: the value must be a finite, non-negative
 * number that is a whole number of cents within `maxCents`.
 *
 * A JSON number such as 0.07 or 49999999999.95 is the nearest double of its decimal text, so the cents
 * are accepted only when the two-decimal text of those cents parses back to the very same double. That
 * tolerates exactly the binary representation of a valid two-decimal value and nothing else: 1.001,
 * 1.005 and 9999999999.999 do not round-trip and are refused instead of being rounded into validity.
 */
export function wirePriceToCents(value: unknown, maxCents: number): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return null
  const scaled = value * 100
  if (!Number.isFinite(scaled) || scaled > maxCents + 1) return null
  const cents = Math.round(scaled)
  if (!Number.isSafeInteger(cents) || cents < 0 || cents > maxCents) return null
  const text = `${Math.trunc(cents / 100)}.${String(cents % 100).padStart(2, '0')}`
  return Number(text) === value ? cents : null
}
