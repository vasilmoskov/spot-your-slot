export type BusinessType =
  | 'HAIR_SALON'
  | 'BARBERSHOP'
  | 'NAIL_STUDIO'
  | 'MASSAGE_STUDIO'
  | 'MAKEUP_STUDIO'
  | 'BEAUTY_STUDIO'
  | 'OTHER'

// The one Bulgarian presentation of a Business type, shared by the platform
// administration screens and the public Business page.
export const BUSINESS_TYPE_LABELS: Record<BusinessType, string> = {
  HAIR_SALON: 'Фризьорски салон',
  BARBERSHOP: 'Бръснарница',
  NAIL_STUDIO: 'Студио за маникюр',
  MASSAGE_STUDIO: 'Масажно студио',
  MAKEUP_STUDIO: 'Студио за грим',
  BEAUTY_STUDIO: 'Козметично студио',
  OTHER: 'Друг',
}

export const BUSINESS_TYPE_OPTIONS = (
  Object.entries(BUSINESS_TYPE_LABELS) as [BusinessType, string][]
).map(([value, label]) => ({ value, label }))

// A value this build does not know (for example a type added later) degrades to
// the generic label instead of exposing the technical name.
export function businessTypeLabel(type: string): string {
  return Object.hasOwn(BUSINESS_TYPE_LABELS, type)
    ? BUSINESS_TYPE_LABELS[type as BusinessType]
    : BUSINESS_TYPE_LABELS.OTHER
}
