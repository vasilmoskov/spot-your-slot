import type { PublicService } from '../api'
import { priceToCents } from './money'

// The Service selection of one visit (ADR-0027): one to five distinct Services whose durations sum to
// at most 480 minutes. The selection is a SET: the order in which the guest ticks the boxes has no
// meaning, so it is held in the public-profile order and requested in the canonical identifier order.

export const MAX_SERVICES = 5
export const MAX_VISIT_MINUTES = 480

export type SelectionTotals = { count: number; minutes: number; cents: number }

/** The selected Services of the catalog, always in the public-profile order. */
export function selectedServices(
  catalog: readonly PublicService[],
  ids: ReadonlySet<string> | readonly string[],
): PublicService[] {
  const wanted = new Set(ids)
  return catalog.filter((service) => wanted.has(service.id))
}

export function selectionTotals(services: readonly PublicService[]): SelectionTotals {
  let minutes = 0
  let cents = 0
  for (const service of services) {
    minutes += service.durationMinutes
    cents += priceToCents(service.price)
  }
  return { count: services.length, minutes, cents }
}

export type DisabledReason = 'count' | 'duration'

/**
 * Why an unselected Service cannot be added, or null when it can. A selected Service is never
 * disabled: it must always be possible to remove it.
 */
export function disabledReason(
  selected: readonly PublicService[],
  candidate: PublicService,
): DisabledReason | null {
  if (selected.some((service) => service.id === candidate.id)) return null
  if (selected.length >= MAX_SERVICES) return 'count'
  const minutes = selected.reduce((sum, service) => sum + service.durationMinutes, 0)
  if (minutes + candidate.durationMinutes > MAX_VISIT_MINUTES) return 'duration'
  return null
}

/** True when the selection can be booked: one to five Services within the duration ceiling. */
export function selectionIsBookable(services: readonly PublicService[]): boolean {
  const totals = selectionTotals(services)
  return totals.count >= 1 && totals.count <= MAX_SERVICES && totals.minutes <= MAX_VISIT_MINUTES
}

function compareText(first: string, second: string): number {
  return first < second ? -1 : first > second ? 1 : 0
}

/**
 * The canonical identifier order: the order of the lowercase canonical UUID text, which is exactly the
 * unsigned byte order of ADR-0027 (the hexadecimal digits sort like the bytes they encode).
 */
export function canonicalIds(ids: readonly string[]): string[] {
  return [...ids].map((id) => id.toLowerCase()).sort(compareText)
}

/** One key for one SET of Services, whatever the order it was ticked in. */
export function selectionKey(ids: readonly string[]): string {
  return canonicalIds(ids).join(',')
}

export function sortByCanonicalId<T extends { serviceId: string }>(items: readonly T[]): T[] {
  return [...items].sort((first, second) =>
    compareText(first.serviceId.toLowerCase(), second.serviceId.toLowerCase()),
  )
}

/** The facts the guest reviewed for one Service: the claims of the booking request. */
export type ReviewedTerm = { serviceId: string; durationMinutes: number; priceCents: number }

export function reviewedTerms(services: readonly PublicService[]): ReviewedTerm[] {
  return services.map((service) => ({
    serviceId: service.id,
    durationMinutes: service.durationMinutes,
    priceCents: priceToCents(service.price),
  }))
}
