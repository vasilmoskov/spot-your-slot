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

// The public name messages show no numerical limit: a missing name and an invalid or overlong one are
// told apart by `nameMessage`; the 200-character rule itself is the shared contact policy.
export const NAME_REQUIRED_MESSAGE = 'Въведете име.'

export const DETAILS_MESSAGES: Record<DetailsField, string> = {
  displayName: 'Проверете въведеното име.',
  phone: 'Въведеният телефонен номер не е валиден.',
  email: 'Въведеният имейл адрес не е валиден.',
  contact: 'Въведете телефон или имейл.',
  note: `Бележката може да съдържа най-много ${NOTE_MAX_CODE_POINTS} знака.`,
}

/** «Въведете име.» for a blank name, «Проверете въведеното име.» for an invalid or overlong one. */
export function nameMessage(displayName: string): string {
  return trimApproved(displayName) === '' ? NAME_REQUIRED_MESSAGE : DETAILS_MESSAGES.displayName
}

/**
 * The public sentence for each field a rejected submission names. The backend's own sentence is not
 * shown: one fixed local wording serves both the local and the server validation of a field.
 */
export function localFieldErrors(
  serverErrors: Partial<Record<DetailsField, string>>,
  details: BookingDetails,
): Partial<Record<DetailsField, string>> {
  const result: Partial<Record<DetailsField, string>> = {}
  for (const field of DETAILS_FIELD_ORDER) {
    if (serverErrors[field] === undefined) continue
    result[field] = field === 'displayName' ? nameMessage(details.displayName) : DETAILS_MESSAGES[field]
  }
  return result
}

export const EMPTY_DETAILS: BookingDetails = { displayName: '', phone: '', email: '', note: '' }

export function validateDetails(details: BookingDetails): Partial<Record<DetailsField, string>> {
  const values = { ...details, contact: '' }
  const errors: Partial<Record<DetailsField, string>> = {}
  for (const field of ['displayName', 'phone', 'email', 'contact'] as const) {
    if (validateCustomerField(field, values)) {
      errors[field] = field === 'displayName' ? nameMessage(details.displayName) : DETAILS_MESSAGES[field]
    }
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

// The remaining characters are shown only near the limit, so an ordinary note has no counter.
export const NOTE_COUNTER_FROM = 400

export type NoteCounter = { text: string; over: boolean }  // `over` is true past the limit

/** The counter for a note of `length` code points, or null while it is far from the limit. */
export function noteCounter(length: number): NoteCounter | null {
  if (length < NOTE_COUNTER_FROM) return null
  if (length <= NOTE_MAX_CODE_POINTS) return { text: `Остават ${NOTE_MAX_CODE_POINTS - length} знака.`, over: false }
  return { text: `Надвишавате ограничението с ${length - NOTE_MAX_CODE_POINTS} знака.`, over: true }
}
