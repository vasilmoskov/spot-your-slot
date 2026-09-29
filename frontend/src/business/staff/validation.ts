import { parsePhoneNumberFromString } from 'libphonenumber-js/max'
import { canonicalName, canonicalOptional, codePointLength } from '../text'

export const DISPLAY_NAME_MAX_LENGTH = 200
export const CONTACT_EMAIL_MAX_LENGTH = 320
export const CONTACT_PHONE_MAX_LENGTH = 50
const PHONE_SEPARATORS = /[\s().-]/gu
const PHONE_SHAPE = /^\+?\d+$/u

export type StaffField = 'displayName' | 'contactEmail' | 'contactPhone'

export const STAFF_FIELD_ORDER: readonly StaffField[] = [
  'displayName',
  'contactEmail',
  'contactPhone',
]

export type StaffFormValues = {
  displayName: string
  contactEmail: string
  contactPhone: string
}

// The same acceptance policy as the backend's StaffMemberEmailPolicy: exactly
// one "@", non-empty local part, no whitespace, and a dotted domain (at least
// two labels, none empty, none starting or ending with "-", each made of
// ASCII letters, digits, and "-"; any non-ASCII character is rejected), and the local part may not start or end with "."
// or contain "..". A single-label domain such as "a@a" is rejected;
// a browser submits an internationalized single label (Cyrillic "а") as
// "xn--80a", which is single-label too.
const EMAIL_LABEL = /^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$/u

export function isAcceptableEmail(email: string): boolean {
  const at = email.indexOf('@')
  if (at <= 0 || at !== email.lastIndexOf('@') || at === email.length - 1) return false
  // ASCII only for now: internationalized addresses need SMTPUTF8 support end
  // to end and are intentionally deferred, not accidentally unsupported.
  if (Array.from(email).some((character) => (character.codePointAt(0) ?? 0) > 127)) return false
  if (/\s/u.test(email)) return false
  const local = email.slice(0, at)
  if (local.startsWith('.') || local.endsWith('.') || local.includes('..')) return false
  const labels = email.slice(at + 1).split('.')
  return labels.length >= 2 && labels.every((label) => EMAIL_LABEL.test(label))
}

export const PHONE_EXAMPLE = '+359 88 123 4567'

// Mirrors the backend's telephone contract (StaffMemberPhoneNumbers): the
// approved visual separators are ignored, "+" and "00" mean an explicit
// international number, a single leading "0" means Bulgaria, and any other
// prefix is ambiguous and rejected. The number itself is then checked with
// libphonenumber-js (full metadata), the JavaScript port of the library the
// backend uses, so a value that only looks like a phone number is rejected.
// The two libraries ship separate metadata releases, so the backend stays
// authoritative and its field error is shown if it disagrees.
function phoneMessage(value: string): string | undefined {
  const trimmed = canonicalOptional(value)
  if (trimmed === '') return undefined
  const compact = trimmed.replace(PHONE_SEPARATORS, '')
  const shapeMessage = `Въведете телефон, който започва с +, 00 или 0 и съдържа само цифри, например ${PHONE_EXAMPLE}.`
  if (!PHONE_SHAPE.test(compact)) return shapeMessage
  let international: string
  if (compact.startsWith('+')) {
    international = compact
  } else if (compact.startsWith('00')) {
    international = `+${compact.slice(2)}`
  } else if (compact.startsWith('0')) {
    international = `+359${compact.slice(1)}`
  } else {
    return shapeMessage
  }
  const parsed = parsePhoneNumberFromString(international)
  if (!parsed?.isValid()) {
    return `Въведете валиден телефонен номер, например ${PHONE_EXAMPLE}.`
  }
  return undefined
}

export function validateStaffField(
  field: StaffField,
  values: StaffFormValues,
): string | undefined {
  switch (field) {
    case 'displayName': {
      const name = canonicalName(values.displayName)
      if (name === '') return 'Въведете име на члена на екипа.'
      if (codePointLength(name) > DISPLAY_NAME_MAX_LENGTH) {
        return `Името може да съдържа най-много ${DISPLAY_NAME_MAX_LENGTH} знака.`
      }
      return undefined
    }
    case 'contactEmail': {
      const email = canonicalOptional(values.contactEmail)
      if (email === '') return undefined
      if (codePointLength(email) > CONTACT_EMAIL_MAX_LENGTH) {
        return `Имейлът може да съдържа най-много ${CONTACT_EMAIL_MAX_LENGTH} знака.`
      }
      if (!isAcceptableEmail(email)) {
        return 'Въведете валиден имейл, например ime@primer.bg.'
      }
      return undefined
    }
    case 'contactPhone':
      return phoneMessage(values.contactPhone)
  }
}

export function validateStaff(values: StaffFormValues): Partial<Record<StaffField, string>> {
  const errors: Partial<Record<StaffField, string>> = {}
  for (const field of STAFF_FIELD_ORDER) {
    const message = validateStaffField(field, values)
    if (message) errors[field] = message
  }
  return errors
}

// A field the backend can name in `fieldErrors` for a StaffMember.
export const STAFF_BACKEND_FIELDS: readonly StaffField[] = STAFF_FIELD_ORDER

// Fallback only for a VALIDATION_ERROR without usable `fieldErrors`. Never
// guess a field.
export const STAFF_REJECTED_MESSAGE =
  'Членът на екипа не беше запазен, защото някоя от стойностите не е приета. Проверете името, имейла и телефонния номер.'
