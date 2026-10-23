import { SCHEDULE_MAX_DATE, SCHEDULE_MIN_DATE, isCanonicalDate } from '../../navigation'

/**
 * The text form of the Customer-history date filter: `дд.мм.гггг`, typed or pasted. Nothing here depends on the
 * browser's locale or time zone: a date is only ever three numbers, checked as a calendar date and never rolled into
 * another month, and turned into the canonical `yyyy-MM-dd` the server filters by.
 */
export type ParsedDisplayDate =
  | { kind: 'empty' }
  | { kind: 'partial' }
  | { kind: 'invalid' }
  | { kind: 'valid'; iso: string }

/** A complete entry, and the shapes an entry in progress can have: `22`, `22.1`, `22.10.`, `22.10.20`. */
const COMPLETE = /^(\d{2})\.(\d{2})\.(\d{4})$/
const IN_PROGRESS = /^(\d{0,2}|\d{2}\.\d{0,2}|\d{2}\.\d{2}\.\d{0,3})$/

export function digitsOf(text: string): string {
  return text.replace(/\D/g, '')
}

/**
 * Places the separators between the day, month and year digits. While typing forward a completed day or month gets
 * its dot at once; while deleting it does not, so the dot can be erased.
 */
export function formatDigits(digits: string, trailingDot: boolean): string {
  let text = digits.slice(0, 2)
  if (digits.length > 2 || (digits.length === 2 && trailingDot)) text += `.${digits.slice(2, 4)}`
  if (digits.length > 4 || (digits.length === 4 && trailingDot)) text += `.${digits.slice(4)}`
  return text
}

/**
 * What the field shows after an edit. Only input that is exactly a date in progress is formatted: a bare run of up to
 * eight digits (typed, or pasted as `22102026`) or a prefix of `дд.мм.гггг`. Anything else (a ninth digit, a
 * misplaced dot, a letter, another separator, pasted prose) is kept exactly as entered and judged invalid, never
 * truncated or cleaned up into some other date.
 */
export function formatEntry(raw: string, trailingDot: boolean): { text: string; formatted: boolean } {
  const bareDigits = /^\d{1,8}$/.test(raw)
  if (!bareDigits && !IN_PROGRESS.test(raw)) return { text: raw, formatted: false }
  return { text: formatDigits(digitsOf(raw), trailingDot), formatted: true }
}

/** The caret position just after the `count`-th digit of `formatted`, past a dot that follows it when asked. */
export function caretAfterDigits(formatted: string, count: number, skipFollowingDot: boolean): number {
  if (count === 0) return 0
  let seen = 0
  for (let index = 0; index < formatted.length; index += 1) {
    if (/\d/.test(formatted[index]!)) seen += 1
    if (seen === count) {
      const next = index + 1
      return skipFollowingDot && formatted[next] === '.' ? next + 1 : next
    }
  }
  return formatted.length
}

export function parseDisplayDate(text: string): ParsedDisplayDate {
  if (text === '') return { kind: 'empty' }
  const match = COMPLETE.exec(text)
  if (!match) return IN_PROGRESS.test(text) ? { kind: 'partial' } : { kind: 'invalid' }
  const iso = `${match[3]}-${match[2]}-${match[1]}`
  return isCanonicalDate(iso) && iso >= SCHEDULE_MIN_DATE && iso <= SCHEDULE_MAX_DATE
    ? { kind: 'valid', iso }
    : { kind: 'invalid' }
}

export function displayFromIso(iso: string): string {
  return `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}`
}

/**
 * The text a native date input's value stands for, so a value from the calendar or the fallback is judged by exactly
 * the rules of typed text. A value of an unexpected shape (a five-digit year) is kept as it is and so judged invalid.
 */
export function displayFromNative(value: string): string {
  const match = /^(\d+)-(\d{2})-(\d{2})$/.exec(value)
  return match ? `${match[3]}.${match[2]}.${match[1]}` : value
}
