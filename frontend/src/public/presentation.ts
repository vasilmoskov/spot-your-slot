import type { PublicAddress } from './api'

const MIN_DIALABLE_DIGITS = 3

/**
 * The value of a `tel:` link for a stored telephone, or null when nothing
 * dialable remains. Only an optional leading `+` and ASCII digits survive, so
 * arbitrary API text can never reach a URL. The visible text stays as entered.
 */
export function dialableNumber(phone: string): string | null {
  const trimmed = phone.trim()
  const digits = trimmed.replace(/[^0-9]/g, '')
  if (digits.length < MIN_DIALABLE_DIGITS) return null
  return trimmed.startsWith('+') ? `+${digits}` : digits
}

function joinPresent(parts: (string | null)[]): string {
  return parts
    .map((part) => part?.trim() ?? '')
    .filter((part) => part !== '')
    .join(' ')
}

/**
 * Readable lines of the structured address: `street number`, `postal code city`
 * and the extra directions. Missing parts are omitted; nothing is invented, so
 * an address without any usable part yields no lines at all.
 */
export function addressLines(address: PublicAddress | null): string[] {
  if (!address) return []
  return [
    joinPresent([address.street, address.streetNumber]),
    joinPresent([address.postalCode, address.city]),
    joinPresent([address.details]),
  ].filter((line) => line !== '')
}

const META_DESCRIPTION_LIMIT = 160

/** Whitespace-collapsed text cut at a word boundary with an ellipsis. */
export function truncateAtWord(text: string, limit = META_DESCRIPTION_LIMIT): string {
  const collapsed = text.replace(/\s+/g, ' ').trim()
  const characters = Array.from(collapsed)
  if (characters.length <= limit) return collapsed
  const head = characters.slice(0, limit - 1).join('')
  const lastSpace = head.lastIndexOf(' ')
  const cut = lastSpace > limit / 2 ? head.slice(0, lastSpace) : head
  return `${cut.replace(/[\s.,;:!?-]+$/, '')}…`
}
