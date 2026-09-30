import { canonicalBusinessSlug, isReservedBusinessSlug } from '../../reservedSlugs'

export type BusinessField = 'slug'

// DOM order, used to pick the first invalid field to focus.
export const BUSINESS_FIELD_ORDER: readonly BusinessField[] = ['slug']

// Fields the backend can name in `fieldErrors` for a Business.
export const BUSINESS_BACKEND_FIELDS: readonly BusinessField[] = BUSINESS_FIELD_ORDER

// The backend's approved wording for a reserved public address.
export const RESERVED_SLUG_MESSAGE = 'Изберете друг публичен адрес на бизнеса.'

export const IMMUTABLE_SLUG_NOTE = 'Публичният адрес не може да се променя след активиране.'

// A reserved slug is rejected unless it is the DRAFT's own unchanged, already
// stored value (a grandfathered slug that may stay while other fields change).
// The backend stays authoritative for every rule, including uniqueness.
export function validateBusinessSlug(
  value: string,
  storedSlug: string | undefined,
): string | undefined {
  if (!isReservedBusinessSlug(value)) return undefined
  if (storedSlug !== undefined && canonicalBusinessSlug(value) === canonicalBusinessSlug(storedSlug)) {
    return undefined
  }
  return RESERVED_SLUG_MESSAGE
}
