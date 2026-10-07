import type { SubmissionRejection } from './api'

// The approved public Bulgarian wording (ADR-0024, ADR-0026), formal register. The backend text is
// never rendered; each stable code maps to one fixed sentence here.

export const KNOWN_ROLLBACK_MESSAGE = 'Резервацията не беше направена. Опитайте отново.'
export const UNCERTAIN_MESSAGE = 'Не получихме потвърждение за резервацията. Опитайте отново.'
export const RATE_LIMITED_MESSAGE = 'Твърде много опити. Опитайте по-късно.'
export const UNEXPECTED_MESSAGE = 'Възникна неочаквана грешка.'
export const SERVICE_UNAVAILABLE_MESSAGE =
  'Избраната услуга вече не е налична. Изберете друга услуга.'
export const STAFF_UNAVAILABLE_MESSAGE =
  'Избраният служител вече не е наличен. Изберете друг или „Без предпочитание“.'
export const SLOT_UNAVAILABLE_MESSAGE = 'Избраният час вече не е свободен. Изберете друг час.'
export const IDENTITY_CONFLICT_MESSAGE =
  'Не можем да завършим резервацията онлайн. Моля, свържете се с бизнеса.'
export const ATTEMPT_MISMATCH_MESSAGE =
  'Тази заявка вече е използвана с други данни. Започнете резервацията отново.'
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
    case 'validation':
      return VALIDATION_MESSAGE
    case 'business-unavailable':
    case 'unexpected':
      return UNEXPECTED_MESSAGE
  }
}
