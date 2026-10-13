import type { SubmissionRejection } from './api'

// The approved public Bulgarian wording (ADR-0024, ADR-0026), formal register. No backend text is
// rendered: each stable problem code, and each field a 400 `fieldErrors` object names, maps to one fixed
// sentence in this frontend.

export const KNOWN_ROLLBACK_MESSAGE = 'Резервацията не беше направена. Опитайте отново.'
export const UNCERTAIN_MESSAGE = 'Не получихме потвърждение за резервацията. Опитайте отново.'
export const RATE_LIMITED_MESSAGE = 'Твърде много опити. Опитайте по-късно.'
export const UNEXPECTED_MESSAGE = 'Възникна неочаквана грешка.'
export const SERVICE_UNAVAILABLE_MESSAGE =
  'Избраната услуга вече не е налична. Изберете друга услуга.'
export const STAFF_UNAVAILABLE_MESSAGE =
  'Избраният служител вече не е наличен. Изберете друг или „Без предпочитание“.'
export const SLOT_UNAVAILABLE_MESSAGE = 'Избраният час вече не е свободен. Изберете друг час.'
// Shown only for 409 BOOKING_NOT_COMPLETED_ONLINE (the documented IdentityConflict). It names neither
// the contact that matched nor whether a Customer exists.
export const IDENTITY_CONFLICT_MESSAGE =
  'Телефонът и имейлът не съответстват. Проверете ги или въведете само единия контакт.'
export const ATTEMPT_MISMATCH_MESSAGE =
  'Тази заявка вече е използвана с други данни. Започнете резервацията отново.'
// ADR-0027. The wording of these sentences and of the selection hints is Proposed for the human review.
export const REVIEW_CHANGED_MESSAGE =
  'Условията на избраните услуги са променени. Прегледайте ги и потвърдете отново.'
export const REVIEW_CHANGED_FROZEN_MESSAGE =
  'Условията на услугите са променени. Ако резервацията вече е направена, тя е с условията, които прегледахте.'
export const SELECTION_TOO_LONG_MESSAGE =
  'Избраните услуги са с обща продължителност над 8 часа. Изберете по-малко услуги.'
export const SERVICES_REMOVED_MESSAGE =
  'Някои от избраните услуги вече не се предлагат и са премахнати от избора.'
export const NO_STAFF_FOR_SELECTION_MESSAGE =
  'В момента няма служител, който предлага всички избрани услуги.'
export const NO_STAFF_FOR_SERVICE_MESSAGE =
  'В момента няма служител, при когото можете да запазите тази услуга онлайн.'
export const SELECTION_HINT = 'Най-много 5 услуги и общо 8 часа за едно посещение.'
export const NO_SELECTION_HINT = 'Изберете поне една услуга.'
export const LIMIT_COUNT_REASON = 'Не може да се добави: най-много 5 услуги.'
export const LIMIT_DURATION_REASON = 'Не може да се добави: общо най-много 8 часа.'
export const SELECTION_OVER_LIMIT_MESSAGE =
  'Общата продължителност надвишава 8 часа. Премахнете услуга.'
export const SERVICES_FIELD_MESSAGE =
  'Изберете от 1 до 5 различни услуги с обща продължителност до 8 часа.'

export const VALIDATION_MESSAGE = 'Проверете въведените данни.'
export const READ_NETWORK_MESSAGE =
  'Данните не можаха да бъдат заредени. Проверете връзката си и опитайте отново.'

export const NO_PREFERENCE_LABEL = 'Без предпочитание'

export function rejectionMessage(reason: SubmissionRejection): string {
  switch (reason) {
    case 'service-unavailable':
      return SERVICE_UNAVAILABLE_MESSAGE
    case 'staff-unavailable':
      return STAFF_UNAVAILABLE_MESSAGE
    case 'slot-unavailable':
      return SLOT_UNAVAILABLE_MESSAGE
    case 'identity-conflict':
      return IDENTITY_CONFLICT_MESSAGE
    case 'attempt-mismatch':
      return ATTEMPT_MISMATCH_MESSAGE
    case 'review-changed':
      return REVIEW_CHANGED_MESSAGE
    case 'validation':
      return VALIDATION_MESSAGE
    case 'business-unavailable':
    case 'unexpected':
      return UNEXPECTED_MESSAGE
  }
}
