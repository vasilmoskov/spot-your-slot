import { canonicalName, canonicalOptional, codePointLength } from '../text'

export const NAME_MAX_LENGTH = 200
export const DESCRIPTION_MAX_LENGTH = 2_000
export const MIN_DURATION_MINUTES = 1
export const MAX_DURATION_MINUTES = 480
// sign, integer digits, fraction digits
const PRICE_SYNTAX = /^(-)?(\d+)(?:\.(\d+))?$/
const MAX_PRICE_INTEGER_DIGITS = 10
const MAX_PRICE_FRACTION_DIGITS = 2

export type ServiceField = 'name' | 'description' | 'durationMinutes' | 'price'

// DOM order, used to pick the first invalid field to focus.
export const SERVICE_FIELD_ORDER: readonly ServiceField[] = [
  'name',
  'description',
  'durationMinutes',
  'price',
]

export type ServiceFormValues = {
  name: string
  description: string
  durationMinutes: string
  price: string
  // A number input reports unparseable text as an empty value; the browser's
  // own bad-input flag is the only way to tell that apart from "left blank".
  durationBadInput?: boolean
}

export function validateServiceField(
  field: ServiceField,
  values: ServiceFormValues,
): string | undefined {
  switch (field) {
    case 'name': {
      const name = canonicalName(values.name)
      if (name === '') return 'Въведете име на услугата.'
      if (codePointLength(name) > NAME_MAX_LENGTH) {
        return `Името може да съдържа най-много ${NAME_MAX_LENGTH} знака.`
      }
      return undefined
    }
    case 'description': {
      if (codePointLength(canonicalOptional(values.description)) > DESCRIPTION_MAX_LENGTH) {
        return `Описанието може да съдържа най-много ${DESCRIPTION_MAX_LENGTH} знака.`
      }
      return undefined
    }
    case 'durationMinutes': {
      const raw = values.durationMinutes.trim()
      if (raw === '' && !values.durationBadInput) return 'Въведете продължителност в минути.'
      if (!/^-?\d+$/.test(raw)) return 'Въведете продължителността като цяло число минути.'
      const minutes = Number(raw)
      if (minutes < MIN_DURATION_MINUTES || minutes > MAX_DURATION_MINUTES) {
        return `Продължителността трябва да бъде между ${MIN_DURATION_MINUTES} и ${MAX_DURATION_MINUTES} минути.`
      }
      return undefined
    }
    case 'price': {
      const raw = values.price.trim()
      if (raw === '') return 'Въведете цена.'
      const match = PRICE_SYNTAX.exec(raw)
      if (!match || (match[3] ?? '').length > MAX_PRICE_FRACTION_DIGITS) {
        return 'Въведете цената като число с най-много 2 знака след десетичната точка, например 25.50.'
      }
      if (match[1]) return 'Цената не може да бъде отрицателна.'
      if ((match[2] ?? '').length > MAX_PRICE_INTEGER_DIGITS) {
        return `Цената може да има най-много ${MAX_PRICE_INTEGER_DIGITS} цифри преди десетичната точка.`
      }
      return undefined
    }
  }
}

export function validateService(values: ServiceFormValues): Partial<Record<ServiceField, string>> {
  const errors: Partial<Record<ServiceField, string>> = {}
  for (const field of SERVICE_FIELD_ORDER) {
    const message = validateServiceField(field, values)
    if (message) errors[field] = message
  }
  return errors
}

// Fallback only for a VALIDATION_ERROR without usable `fieldErrors`. Never
// guess a field.
// A field the backend can name in `fieldErrors` for a Service.
export const SERVICE_BACKEND_FIELDS: readonly ServiceField[] = SERVICE_FIELD_ORDER

export const SERVICE_REJECTED_MESSAGE =
  'Услугата не беше запазена, защото някоя от стойностите не е приета. Проверете името, описанието, продължителността и цената.'
