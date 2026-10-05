import { canonicalEmail, checkPhone } from '../../contact/contactPolicy'
import { canonicalName, canonicalOptional, codePointLength, trimApproved } from '../text'
import { CUSTOMER_FIELD_MESSAGES } from './errors'

export const DISPLAY_NAME_MAX_LENGTH = 200
export const EMAIL_INPUT_MAX_LENGTH = 320
export const PHONE_INPUT_MAX_LENGTH = 50
export const SEARCH_MAX_LENGTH = 100

export type CustomerField = 'displayName' | 'phone' | 'email' | 'contact'

// DOM order; `contact` (neither phone nor email) is reported on the contact group and focuses
// the phone control.
export const CUSTOMER_FIELD_ORDER: readonly CustomerField[] = [
  'displayName',
  'phone',
  'email',
  'contact',
]

export type CustomerFormValues = {
  displayName: string
  phone: string
  email: string
  contact: string
}

export const SEARCH_TOO_LONG_MESSAGE = `Търсенето може да съдържа най-много ${SEARCH_MAX_LENGTH} знака.`

const phoneBlank = (values: CustomerFormValues) => canonicalOptional(values.phone) === ''
const emailBlank = (values: CustomerFormValues) => canonicalOptional(values.email) === ''

// The contact values are judged by the shared contact policy (src/contact/contactPolicy.ts);
// the wording is the backend's approved Customer wording. The backend stays authoritative.
export function validateCustomerField(
  field: CustomerField,
  values: CustomerFormValues,
): string | undefined {
  switch (field) {
    case 'displayName': {
      const name = canonicalName(values.displayName)
      if (name === '' || codePointLength(name) > DISPLAY_NAME_MAX_LENGTH) {
        return CUSTOMER_FIELD_MESSAGES.displayName
      }
      return undefined
    }
    case 'phone':
      if (phoneBlank(values)) return undefined
      return checkPhone(values.phone).ok ? undefined : CUSTOMER_FIELD_MESSAGES.phone
    case 'email': {
      if (emailBlank(values)) return undefined
      return canonicalEmail(values.email) === null ? CUSTOMER_FIELD_MESSAGES.email : undefined
    }
    case 'contact':
      return phoneBlank(values) && emailBlank(values) ? CUSTOMER_FIELD_MESSAGES.contact : undefined
  }
}

export function validateCustomer(
  values: CustomerFormValues,
): Partial<Record<CustomerField, string>> {
  const errors: Partial<Record<CustomerField, string>> = {}
  for (const field of CUSTOMER_FIELD_ORDER) {
    const message = validateCustomerField(field, values)
    if (message) errors[field] = message
  }
  return errors
}

/** The search term as it is sent: edge-trimmed with the approved whitespace set. */
export function normalizeSearchTerm(value: string): string {
  return trimApproved(value)
}

export function searchTermError(term: string): string | undefined {
  return codePointLength(term) > SEARCH_MAX_LENGTH ? SEARCH_TOO_LONG_MESSAGE : undefined
}
