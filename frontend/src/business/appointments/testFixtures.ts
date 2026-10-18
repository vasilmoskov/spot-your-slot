import { ApiError } from '../../identity/api'
import type { ServiceSummary } from '../services/api'
import type {
  CreateAppointmentBody,
  CreatedAppointment,
  CreationAvailability,
  CreationOptions,
} from './api'

// Synthetic data only: invented names, reserved example domains and documented sample numbers.
export const STAFF_A = '11111111-1111-4111-8111-111111111111'
export const STAFF_B = '22222222-2222-4222-8222-222222222222'
export const CUSTOMER_1 = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
export const CUSTOMER_2 = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab'
export const CREATED_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
export const ZONE = 'Europe/Sofia'

// The ids are deliberately NOT in catalog (name) order, so the canonical identifier order differs
// from the visible order.
export function service(overrides: Partial<ServiceSummary> & { id: string; name: string }): ServiceSummary {
  return {
    description: null,
    durationMinutes: 30,
    price: 25,
    active: true,
    version: 1,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

export const S_CUT = service({ id: 'cccccccc-0000-4000-8000-000000000001', name: 'Дамско подстригване', durationMinutes: 45, price: 30 })
export const S_COLOR = service({ id: 'bbbbbbbb-0000-4000-8000-000000000002', name: 'Боядисване', durationMinutes: 90, price: 70.5 })
export const S_NAILS = service({ id: 'aaaaaaaa-0000-4000-8000-000000000003', name: 'Маникюр', durationMinutes: 60, price: 25 })
export const S_BROW = service({ id: 'dddddddd-0000-4000-8000-000000000004', name: 'Оформяне на вежди', durationMinutes: 15, price: 0.1 })
export const S_WAX = service({ id: 'eeeeeeee-0000-4000-8000-000000000005', name: 'Кола маска', durationMinutes: 20, price: 0.2 })
export const S_MASSAGE = service({ id: 'ffffffff-0000-4000-8000-000000000006', name: 'Масаж', durationMinutes: 30, price: 40 })
export const S_INACTIVE = service({ id: '99999999-0000-4000-8000-000000000007', name: 'Спряна услуга', active: false })
export const CATALOG = [S_COLOR, S_WAX, S_BROW, S_MASSAGE, S_CUT, S_NAILS].sort((a, b) => a.name.localeCompare(b.name, 'bg'))

export const CUSTOMERS = [
  { id: CUSTOMER_1, displayName: 'Мария Тестова', phone: '+359895555777', email: 'maria@example.test' },
  { id: CUSTOMER_2, displayName: 'Борис Пробен', phone: null, email: 'boris@example.test' },
]

export type Call = { path: string; method: string; body: unknown }

export function slot(start: string, minutes: number) {
  const end = new Date(Date.parse(start) + minutes * 60_000)
  const offset = start.slice(-6)
  const local = new Date(end.getTime() + (Number(offset.slice(1, 3)) * 60 + Number(offset.slice(4))) * 60_000 * (offset.startsWith('-') ? -1 : 1))
  return { start, end: `${local.toISOString().slice(0, 19)}${offset}` }
}

export function createdResponse(overrides: Partial<CreatedAppointment> = {}): CreatedAppointment {
  return {
    id: CREATED_ID,
    reference: 'SYS-2026-0001',
    status: 'CONFIRMED',
    source: 'MANUAL',
    start: '2026-11-10T09:00:00+02:00',
    end: '2026-11-10T09:45:00+02:00',
    timezone: ZONE,
    staffMemberId: STAFF_A,
    staffDisplayName: 'Мила Работникова',
    customerId: CUSTOMER_1,
    services: [{ position: 1, name: 'Дамско подстригване', durationMinutes: 45, price: 30 }],
    durationMinutes: 45,
    price: 30,
    internalNote: null,
    version: 0,
    createdAt: '2026-10-02T08:00:00Z',
    ...overrides,
  }
}

/** A scriptable stand-in for the Business API behind the network helper. */
export class FakeBackend {
  calls: Call[] = []
  catalog = CATALOG
  customers = CUSTOMERS
  staff = [
    { id: STAFF_A, displayName: 'Мила Работникова' },
    { id: STAFF_B, displayName: 'Георги Стилистов' },
  ]
  options: (ids: string[]) => CreationOptions | Promise<CreationOptions> = (ids) => ({
    timezone: ZONE,
    durationMinutes: this.catalog.filter((s) => ids.includes(s.id)).reduce((t, s) => t + s.durationMinutes, 0),
    staffMembers: this.staff,
  })
  availability: (ids: string[], staff: string, date: string) => CreationAvailability | Promise<CreationAvailability> = (
    ids,
    _staff,
    date,
  ) => {
    const minutes = this.catalog.filter((s) => ids.includes(s.id)).reduce((t, s) => t + s.durationMinutes, 0)
    return {
      timezone: ZONE,
      date,
      durationMinutes: minutes,
      slots: [slot(`${date}T09:00:00+02:00`, minutes), slot(`${date}T09:15:00+02:00`, minutes), slot(`${date}T10:00:00+02:00`, minutes)],
    }
  }
  create: (body: CreateAppointmentBody) => CreatedAppointment | Promise<CreatedAppointment> = () => createdResponse()

  problem(status: number, code: string, detail = 'backend text that must never be shown'): ApiError {
    return new ApiError(status, code, detail)
  }

  count(prefix: string, method = 'GET'): number {
    return this.calls.filter((call) => call.path.startsWith(prefix) && call.method === method).length
  }

  posts(): Call[] {
    return this.calls.filter((call) => call.path === '/api/business/appointments' && call.method === 'POST')
  }

  async handle(path: string, options: RequestInit | undefined): Promise<unknown> {
    const method = options?.method ?? 'GET'
    const body = options?.body ? JSON.parse(String(options.body)) : undefined
    this.calls.push({ path, method, body })
    const url = new URL(path, 'http://x')
    const ids = url.searchParams.getAll('serviceId')
    if (url.pathname === '/api/business/services') {
      const all = [...this.catalog, S_INACTIVE]
      return { services: all, page: 0, size: 50, totalElements: all.length }
    }
    if (url.pathname === '/api/business/customers/search') {
      const term = String(body.search).toLowerCase()
      const items = this.customers.filter((c) => c.displayName.toLowerCase().includes(term))
      return { items, page: 0, size: 10, total: items.length }
    }
    if (url.pathname === '/api/business/customers') {
      return { items: this.customers, page: 0, size: 10, total: this.customers.length }
    }
    if (url.pathname === '/api/business/appointment-options') return this.options(ids)
    if (url.pathname === '/api/business/appointment-options/availability') {
      return this.availability(ids, url.searchParams.get('staffMemberId') as string, url.searchParams.get('date') as string)
    }
    if (url.pathname === '/api/business/appointments' && method === 'POST') return this.create(body)
    throw new Error(`unexpected ${method} ${path}`)
  }
}
