import { canonicalEmail, checkPhone } from '../../contact/contactPolicy'
import { canonicalName, canonicalOptional, codePointLength } from '../text'

export const DISPLAY_NAME_MAX_LENGTH = 200
export const CONTACT_EMAIL_MAX_LENGTH = 320
export const CONTACT_PHONE_MAX_LENGTH = 50

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

export const PHONE_EXAMPLE = '+359 88 123 4567'

// Contact values are judged by the shared contact policy (src/contact/contactPolicy.ts), the
// mirror of the backend's bg.spotyourslot.shared.contact package; only the Bulgarian wording is
// StaffMember-specific. The backend stays authoritative and its field error is shown if it
// disagrees.
function phoneMessage(value: string): string | undefined {
  if (canonicalOptional(value) === '') return undefined
  const check = checkPhone(value)
  if (check.ok) return undefined
  if (check.reason === 'shape') {
    return `Въведете телефон, който започва с +, 00 или 0 и съдържа само цифри, например ${PHONE_EXAMPLE}.`
  }
  return `Въведете валиден телефонен номер, например ${PHONE_EXAMPLE}.`
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
      if (canonicalEmail(email) === null) {
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
