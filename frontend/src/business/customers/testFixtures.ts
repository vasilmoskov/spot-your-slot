import type { CustomerDetails, CustomerPage, CustomerSummary } from './api'

// Synthetic data only: invented names, reserved example domains and a documented sample number.
export function summary(overrides: Partial<CustomerSummary> = {}): CustomerSummary {
  return {
    id: 'customer-1',
    displayName: 'Мария Тестова',
    phone: '+359895555777',
    email: 'maria@example.test',
    ...overrides,
  }
}

export function detail(overrides: Partial<CustomerDetails> = {}): CustomerDetails {
  return {
    ...summary(),
    version: 3,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-02T09:30:00Z',
    ...overrides,
  }
}

export function pageOf(
  items: CustomerSummary[],
  overrides: Partial<CustomerPage> = {},
): CustomerPage {
  return { items, page: 0, size: 10, total: items.length, ...overrides }
}

export function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}
