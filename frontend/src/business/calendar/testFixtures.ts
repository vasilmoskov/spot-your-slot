import type {
  AgendaResponse,
  AppointmentDetails,
  CalendarAppointment,
  CalendarDay,
  CalendarResponse,
  CalendarStaffMember,
  CustomerAppointment,
  CustomerAppointmentPage,
} from './api'

// Synthetic data only: invented names, reserved example domains and documented sample numbers.
export const STAFF_ONE = '11111111-1111-4111-8111-111111111111'
export const STAFF_TWO = '22222222-2222-4222-8222-222222222222'
export const CUSTOMER_ONE = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
export const APPOINTMENT_ONE = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
export const ZONE = 'Europe/Sofia'

/** A Business-local day in Europe/Sofia with its real offsets (EET +02:00, EEST +03:00). */
export function day(date: string, startOffset = '+03:00', endOffset = startOffset): CalendarDay {
  const next = new Date(Date.parse(`${date}T00:00:00Z`) + 86_400_000).toISOString().slice(0, 10)
  return { date, start: `${date}T00:00:00${startOffset}`, end: `${next}T00:00:00${endOffset}` }
}

export function staff(overrides: Partial<CalendarStaffMember> = {}): CalendarStaffMember {
  return {
    id: STAFF_ONE,
    displayName: 'Мила Работникова',
    active: true,
    workingIntervals: [],
    closures: [],
    ...overrides,
  }
}

export function appointment(overrides: Partial<CalendarAppointment> = {}): CalendarAppointment {
  return {
    id: APPOINTMENT_ONE,
    status: 'CONFIRMED',
    source: 'ONLINE',
    start: '2026-10-14T10:00:00+03:00',
    end: '2026-10-14T10:45:00+03:00',
    staffMemberId: STAFF_ONE,
    staffDisplayName: 'Мила Работникова',
    customerId: CUSTOMER_ONE,
    customerDisplayName: 'Мария Тестова',
    durationMinutes: 45,
    services: [{ name: 'Дамско подстригване', durationMinutes: 45 }],
    ...overrides,
  }
}

export function calendarResponse(overrides: Partial<CalendarResponse> = {}): CalendarResponse {
  return {
    businessStatus: 'ACTIVE',
    timezone: ZONE,
    from: '2026-10-14',
    to: '2026-10-14',
    days: [day('2026-10-14')],
    staffMembers: [
      staff({
        workingIntervals: [
          { start: '2026-10-14T09:00:00+03:00', end: '2026-10-14T13:00:00+03:00' },
          { start: '2026-10-14T14:00:00+03:00', end: '2026-10-14T18:00:00+03:00' },
        ],
      }),
    ],
    appointments: [appointment()],
    ...overrides,
  }
}

export function agendaResponse(
  items: CalendarAppointment[] = [appointment()],
  overrides: Partial<AgendaResponse> = {},
): AgendaResponse {
  return {
    businessStatus: 'ACTIVE',
    timezone: ZONE,
    from: '2026-10-01',
    to: '2026-10-31',
    items,
    page: 0,
    size: 10,
    total: items.length,
    ...overrides,
  }
}

export function detailResponse(overrides: Partial<AppointmentDetails> = {}): AppointmentDetails {
  return {
    businessStatus: 'ACTIVE',
    timezone: ZONE,
    id: APPOINTMENT_ONE,
    reference: 'SYS-2026-0001',
    status: 'CONFIRMED',
    source: 'ONLINE',
    start: '2026-10-14T10:00:00+03:00',
    end: '2026-10-14T11:15:00+03:00',
    staffMemberId: STAFF_ONE,
    staffDisplayName: 'Мила Работникова',
    customer: {
      id: CUSTOMER_ONE,
      displayName: 'Мария Тестова',
      phone: '+359895555777',
      email: 'maria@example.test',
    },
    services: [
      { position: 2, name: 'Боядисване', durationMinutes: 45, price: 55 },
      { position: 1, name: 'Подстригване', durationMinutes: 30, price: 25.5 },
    ],
    durationMinutes: 75,
    price: 80.5,
    customerNote: null,
    internalNote: null,
    cancellation: null,
    version: 7,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-02T09:30:00Z',
    ...overrides,
  }
}

export function historyEntry(overrides: Partial<CustomerAppointment> = {}): CustomerAppointment {
  return {
    id: APPOINTMENT_ONE,
    status: 'CONFIRMED',
    source: 'ONLINE',
    start: '2026-10-14T10:00:00+03:00',
    end: '2026-10-14T10:45:00+03:00',
    staffDisplayName: 'Мила Работникова',
    serviceNames: ['Дамско подстригване'],
    ...overrides,
  }
}

export function historyPage(
  items: CustomerAppointment[] = [historyEntry()],
  overrides: Partial<CustomerAppointmentPage> = {},
): CustomerAppointmentPage {
  return { timezone: ZONE, items, page: 0, size: 10, total: items.length, ...overrides }
}
