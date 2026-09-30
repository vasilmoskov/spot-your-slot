// Top-level public path roots that a Business slug may not use (ADR-0018).
//
// The backend (`ReservedBusinessSlugs`) is authoritative and rejects these on
// create, on a DRAFT slug change, and on activation. This mirror exists only
// for immediate form feedback and for routing a reserved path away from the
// public Business page. `reservedSlugs.test.ts` and the backend's
// `ReservedBusinessSlugsTests` pin the same 19 values, so a change to the list
// must be made in all three places.
export const RESERVED_BUSINESS_SLUGS: ReadonlySet<string> = new Set([
  'forgot-password',
  'password-reset',
  'invitation',
  'login',
  'logout',
  'profile',
  'platform',
  'business',
  'api',
  'actuator',
  'assets',
  'admin',
  'b',
  'book',
  'booking',
  'cancel',
  'cancellation',
  'confirmation',
  'appointments',
])

// The backend canonicalizes a slug by trimming and lowercasing before matching.
export function canonicalBusinessSlug(value: string): string {
  return value.trim().toLowerCase()
}

export function isReservedBusinessSlug(value: string): boolean {
  return RESERVED_BUSINESS_SLUGS.has(canonicalBusinessSlug(value))
}
