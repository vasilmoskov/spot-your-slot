import { fireEvent, render, screen, within } from '@testing-library/react'
import { StrictMode } from 'react'
import { vi, type Mock } from 'vitest'
import { UnsavedChangesGuardProvider } from '../../ui/UnsavedChangesGuard'
import { ManualCreate } from './ManualCreate'
import { FakeBackend } from './testFixtures'

// Shared driver of the manual-create tests: it renders the real form and walks it step by step through
// the real controls. The network helper is mocked by each test file (vi.mock is per file).
export const NOW = new Date('2026-10-02T08:00:00Z')
export const DAY = '2026-11-10'

export function installBackend(mocked: Mock): FakeBackend {
  const backend = new FakeBackend()
  mocked.mockReset()
  mocked.mockImplementation((path: string, options?: RequestInit) => backend.handle(path, options) as Promise<never>)
  return backend
}

export function renderForm(options: { strict?: boolean; initialDate?: string | null; now?: Date } = {}) {
  const handlers = {
    onCancel: vi.fn(),
    onCreated: vi.fn(),
    onAuthenticationRequired: vi.fn(),
  }
  const form = (
    <UnsavedChangesGuardProvider>
      <ManualCreate now={() => options.now ?? NOW} initialDate={options.initialDate ?? null} {...handlers} />
    </UnsavedChangesGuardProvider>
  )
  const view = render(options.strict ? <StrictMode>{form}</StrictMode> : form)
  return { ...handlers, view }
}

export const next = () => fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
export const back = () => fireEvent.click(screen.getByRole('button', { name: 'Назад' }))
export const confirm = () => fireEvent.click(screen.getByRole('button', { name: 'Създай час' }))
export const heading = () => screen.getByRole('heading', { level: 2 })

export async function chooseCustomer(name = 'Мария Тестова') {
  fireEvent.click(await screen.findByRole('radio', { name: new RegExp(name) }))
  next()
}

export async function chooseServices(...names: string[]) {
  await screen.findByRole('checkbox', { name: /Маникюр/ })
  for (const name of names) fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(name) }))
  next()
}

export async function chooseStaff(name = 'Мила Работникова') {
  fireEvent.click(await screen.findByRole('radio', { name: new RegExp(name) }))
  next()
}

export async function chooseTime(date = DAY, index = 0) {
  fireEvent.change(await screen.findByLabelText('Дата'), { target: { value: date } })
  const slots = within(await screen.findByRole('group', { name: 'Свободни часове' })).getAllByRole('radio')
  fireEvent.click(slots[index]!)
  next()
}

/** Renders the form and walks it to the note step. */
export async function toNote(services: string[] = ['Дамско подстригване']) {
  const handlers = renderForm()
  await chooseCustomer()
  await chooseServices(...services)
  await chooseStaff()
  await chooseTime()
  await screen.findByRole('heading', { level: 2, name: 'Вътрешна бележка' })
  return handlers
}

/** Renders the form and walks it to the review, ready to confirm. */
export async function toReview(services: string[] = ['Дамско подстригване']) {
  const handlers = await toNote(services)
  next()
  await screen.findByRole('heading', { level: 2, name: 'Преглед и потвърждение' })
  await screen.findByRole('button', { name: 'Създай час' })
  return handlers
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
