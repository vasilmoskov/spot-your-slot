import { validateCustomerField } from '../../business/customers/validation'
import { codePointLength, trimApproved } from '../../business/text'
import { NOTE_MAX_CODE_POINTS, type BookingDetails } from './attempt'

// The guest's details are judged by the same authoritative shared contact policies as the Business
// Customer form (src/contact/contactPolicy.ts). The wording is the public backend's approved
// `fieldErrors` text (ADR-0026), so a local and a server message for one field never differ.

export type DetailsField = 'displayName' | 'phone' | 'email' | 'contact' | 'note'

// DOM order: `contact` (neither phone nor email) is shown on the contact group and focuses the phone.
export const DETAILS_FIELD_ORDER: readonly DetailsField[] = [
  'displayName',
  'phone',
  'email',
  'contact',
  'note',
]

export const DETAILS_MESSAGES: Record<DetailsField, string> = {
  displayName: 'Въведете име до 200 знака.',
  phone: 'Въведеният телефонен номер не е валиден.',
  email: 'Въведеният имейл адрес не е валиден.',
  contact: 'Въведете телефон или имейл.',
  note: `Бележката може да съдържа най-много ${NOTE_MAX_CODE_POINTS} знака.`,
}

export const EMPTY_DETAILS: BookingDetails = { displayName: '', phone: '', email: '', note: '' }

export function validateDetails(details: BookingDetails): Partial<Record<DetailsField, string>> {
  const values = { ...details, contact: '' }
  const errors: Partial<Record<DetailsField, string>> = {}
  for (const field of ['displayName', 'phone', 'email', 'contact'] as const) {
    if (validateCustomerField(field, values)) errors[field] = DETAILS_MESSAGES[field]
  }
  if (codePointLength(trimApproved(details.note)) > NOTE_MAX_CODE_POINTS) {
    errors.note = DETAILS_MESSAGES.note
  }
  return errors
}

export function detailsAreValid(details: BookingDetails): boolean {
  return Object.keys(validateDetails(details)).length === 0
}

export function hasEnteredDetails(details: BookingDetails): boolean {
  return (
    trimApproved(details.displayName) !== '' ||
    trimApproved(details.phone) !== '' ||
    trimApproved(details.email) !== '' ||
    trimApproved(details.note) !== ''
  )
}
