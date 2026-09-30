import type { BusinessStatus } from './api'

export {
  BUSINESS_TYPE_LABELS,
  BUSINESS_TYPE_OPTIONS,
  businessTypeLabel,
} from '../../business/businessType'

export const BUSINESS_STATUS_PRESENTATION: Record<
  BusinessStatus,
  { label: string; tone: 'neutral' | 'success' | 'warning' }
> = {
  DRAFT: { label: 'Предстои активиране', tone: 'neutral' },
  ACTIVE: { label: 'Активен', tone: 'success' },
  SUSPENDED: { label: 'Временно спрян', tone: 'warning' },
}
