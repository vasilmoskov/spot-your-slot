// Mirrors the backend's approved-whitespace set and NFKC canonicalization
// (ServiceTextCanonicalizer / StaffMemberTextCanonicalizer) closely enough
// for pre-submit validation; the backend stays authoritative.
const WHITESPACE = '\\t-\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000'
const WHITESPACE_RUN = new RegExp(`[${WHITESPACE}]+`, 'gu')
const EDGE_WHITESPACE = new RegExp(`^[${WHITESPACE}]+|[${WHITESPACE}]+$`, 'gu')

// Trimmed with internal whitespace runs collapsed (required names).
export function canonicalName(value: string): string {
  return value.normalize('NFKC').replace(WHITESPACE_RUN, ' ').replace(EDGE_WHITESPACE, '')
}

// Trimmed only (optional free text and contact values).
export function canonicalOptional(value: string): string {
  return value.normalize('NFKC').replace(EDGE_WHITESPACE, '')
}

export function codePointLength(value: string): number {
  return Array.from(value).length
}
