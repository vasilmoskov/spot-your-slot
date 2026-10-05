import { formatStaffPhone } from '../staff/presentation'

/** A canonical phone as returned by the backend, grouped like the other owner screens. */
export function formatCustomerPhone(phone: string): string {
  return formatStaffPhone(phone)
}
