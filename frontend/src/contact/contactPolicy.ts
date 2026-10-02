import { parsePhoneNumberFromString } from 'libphonenumber-js/max'
import { APPROVED_WHITESPACE_CLASS, canonicalOptional } from '../business/text'

// The frontend mirror of the backend's shared contact policy
// (bg.spotyourslot.shared.contact: ContactPhoneNumbers, ContactEmailPolicy). The backend is
// authoritative; this module only gives early feedback and is kept aligned by the shared
// golden vectors in shared-test-data/contact-policy-vectors.json, which both test suites run.
// A value the browser accepts that the backend rejects is shown as the backend field error.

export const EMAIL_MAX_LENGTH = 320
const EMAIL_LOCAL_MAX_LENGTH = 64
const EMAIL_LABEL_MAX_LENGTH = 63
const DEFAULT_REGION = 'BG'

const PHONE_SEPARATORS = new RegExp(`[${APPROVED_WHITESPACE_CLASS}().-]`, 'gu')
const PHONE_SHAPE = /^\+?[0-9]+$/u
// RFC 5322 atext (ASCII only); a local part is dot-separated atoms, quoted strings are not supported.
const EMAIL_LOCAL_ATOM = /^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+$/u
const EMAIL_LABEL = /^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$/u

export type PhoneCheck =
  | { ok: true; canonical: string }
  | { ok: false; reason: 'shape' | 'invalid' }

// `shape`: not digits with an approved +, 00 or 0 prefix (letters, extensions, ambiguous input).
// `invalid`: well formed, but not a valid number according to libphonenumber.
// Blank input is not a phone value; callers treat it as absent before calling.
export function checkPhone(value: string): PhoneCheck {
  const compact = canonicalOptional(value).replace(PHONE_SEPARATORS, '')
  if (!PHONE_SHAPE.test(compact)) return { ok: false, reason: 'shape' }
  let parsed
  if (compact.startsWith('+')) {
    parsed = parsePhoneNumberFromString(compact)
  } else if (compact.startsWith('00')) {
    parsed = parsePhoneNumberFromString(`+${compact.slice(2)}`)
  } else if (compact.startsWith('0')) {
    parsed = parsePhoneNumberFromString(compact, DEFAULT_REGION)
  } else {
    return { ok: false, reason: 'shape' }
  }
  if (!parsed?.isValid() || parsed.ext !== undefined) return { ok: false, reason: 'invalid' }
  return { ok: true, canonical: parsed.format('E.164') }
}

export function canonicalPhone(value: string): string | null {
  const check = checkPhone(value)
  return check.ok ? check.canonical : null
}

// Trim, NFKC, then validate; the canonical address is entirely lowercase. Blank input is not an
// email value; callers treat it as absent before calling.
export function canonicalEmail(value: string): string | null {
  const email = canonicalOptional(value)
  if (email === '' || Array.from(email).length > EMAIL_MAX_LENGTH) return null
  // ASCII only: internationalized addresses need SMTPUTF8 end to end and are deferred.
  if (Array.from(email).some((character) => (character.codePointAt(0) ?? 0) > 127)) return null
  if (new RegExp(`[${APPROVED_WHITESPACE_CLASS}]`, 'u').test(email)) return null
  const at = email.indexOf('@')
  if (at <= 0 || at !== email.lastIndexOf('@') || at === email.length - 1) return null
  const local = email.slice(0, at)
  if (local.length > EMAIL_LOCAL_MAX_LENGTH) return null
  if (!local.split('.').every((atom) => EMAIL_LOCAL_ATOM.test(atom))) return null
  const labels = email.slice(at + 1).split('.')
  const labelsValid = labels.every(
    (label) => label.length <= EMAIL_LABEL_MAX_LENGTH && EMAIL_LABEL.test(label),
  )
  if (labels.length < 2 || !labelsValid) return null
  return email.toLowerCase()
}
