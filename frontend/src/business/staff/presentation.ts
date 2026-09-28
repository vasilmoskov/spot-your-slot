export const STAFF_STATUS_PRESENTATION = {
  active: { label: 'Активен', tone: 'success' as const },
  inactive: { label: 'Неактивен', tone: 'neutral' as const },
}

export function staffStatusPresentation(active: boolean) {
  return active ? STAFF_STATUS_PRESENTATION.active : STAFF_STATUS_PRESENTATION.inactive
}

const BULGARIAN_MOBILE_PHONE = /^\+359(\d{3})(\d{3})(\d{3})$/

/**
 * Formats a canonical, compact StaffMember telephone number (as returned by
 * the backend, e.g. "+359895555777") into a readable presentation.
 *
 * A canonical Bulgarian number with the expected nine national digits is
 * grouped as "+359 895 555 777". Every other international number (a
 * different explicit country code, or a Bulgarian number that does not have
 * exactly nine national digits) is shown as its compact canonical value,
 * since grouping every country's numbers correctly would require a phone-
 * number library this phase does not introduce.
 */
export function formatStaffPhone(contactPhone: string | null | undefined): string {
  if (!contactPhone) return '—'
  const match = BULGARIAN_MOBILE_PHONE.exec(contactPhone)
  if (!match) return contactPhone
  return `+359 ${match[1]} ${match[2]} ${match[3]}`
}
