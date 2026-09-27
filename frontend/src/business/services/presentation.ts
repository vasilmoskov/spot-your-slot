export const SERVICE_STATUS_PRESENTATION = {
  active: { label: 'Активна', tone: 'success' as const },
  inactive: { label: 'Неактивна', tone: 'neutral' as const },
}

export function serviceStatusPresentation(active: boolean) {
  return active ? SERVICE_STATUS_PRESENTATION.active : SERVICE_STATUS_PRESENTATION.inactive
}

export function formatServicePrice(price: number): string {
  return `${price.toFixed(2)} €`
}

export function formatServiceDuration(minutes: number): string {
  return `${minutes} мин.`
}
