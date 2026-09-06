import type { BusinessStatus, BusinessType } from './api'

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

export const BUSINESS_STATUS_PRESENTATION: Record<
  BusinessStatus,
  { label: string; tone: 'neutral' | 'success' | 'warning' }
> = {
  DRAFT: { label: 'Предстои активиране', tone: 'neutral' },
  ACTIVE: { label: 'Активен', tone: 'success' },
  SUSPENDED: { label: 'Временно спрян', tone: 'warning' },
}

export function businessTypeLabel(type: BusinessType): string {
  return BUSINESS_TYPE_LABELS[type]
}
