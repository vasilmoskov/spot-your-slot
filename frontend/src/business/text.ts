// Mirrors the backend's approved-whitespace set and NFKC canonicalization
// (ServiceTextCanonicalizer / StaffMemberTextCanonicalizer) closely enough
// for pre-submit validation; the backend stays authoritative.
// Regular-expression character-class body of the approved whitespace set; shared with
// the contact policy so separator and trimming rules cannot drift apart.
export const APPROVED_WHITESPACE_CLASS =
  '\\t-\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000'
const WHITESPACE = APPROVED_WHITESPACE_CLASS
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

// Edge-trimmed with the approved whitespace set only, without NFKC: the form a search term is sent in.
export function trimApproved(value: string): string {
  return value.replace(EDGE_WHITESPACE, '')
}
