import type { BusinessStatus, BusinessType } from './api'

const BUSINESS_TYPE_LABELS: Record<BusinessType, string> = {
  HAIR_SALON: 'Фризьорски салон',
  BARBERSHOP: 'Бръснарски салон',
  NAIL_STUDIO: 'Студио за маникюр',
  MASSAGE_STUDIO: 'Масажно студио',
  MAKEUP_STUDIO: 'Студио за грим',
  BEAUTY_STUDIO: 'Козметично студио',
  OTHER: 'Друг',
}

export const BUSINESS_STATUS_PRESENTATION: Record<
  BusinessStatus,
  { label: string; tone: 'neutral' | 'success' | 'warning' }
> = {
  DRAFT: { label: 'Чернова', tone: 'neutral' },
  ACTIVE: { label: 'Активен', tone: 'success' },
  SUSPENDED: { label: 'Временно спрян', tone: 'warning' },
}

export function businessTypeLabel(type: BusinessType): string {
  return BUSINESS_TYPE_LABELS[type]
}

export function formatBusinessUpdatedAt(value: string, timezone: string): string {
  return new Intl.DateTimeFormat('bg-BG', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: timezone,
  }).format(new Date(value))
}
