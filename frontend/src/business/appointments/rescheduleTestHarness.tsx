import { fireEvent, render, screen, within } from '@testing-library/react'
import { vi, type Mock } from 'vitest'
import { CALENDAR_ORIGIN } from '../../navigation'
import { UnsavedChangesGuardProvider, useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import { Reschedule } from './Reschedule'
import { DAY, FakeRescheduleBackend, NOW } from './rescheduleTestFixtures'

// Shared driver of the reschedule tests: it renders the real form and walks it step by step through the
// real controls. The network helper is mocked by each test file (vi.mock is per file).
export function installBackend(mocked: Mock): FakeRescheduleBackend {
  const backend = new FakeRescheduleBackend()
  mocked.mockReset()
  mocked.mockImplementation((path: string, options?: RequestInit) => backend.handle(path, options) as Promise<never>)
  return backend
}

/** Stands in for the shell: a guarded navigation, as the sidebar and the browser history use. */
function Leave({ onProceed }: { onProceed: () => void }) {
  const guard = useUnsavedChangesGuard()
  return (
    <button type="button" onClick={() => guard.guard(onProceed)}>
      Навигация
    </button>
  )
}

export function renderForm() {
  const handlers = {
    onBackToDetail: vi.fn(),
    onLeaveToOrigin: vi.fn(),
    onRescheduled: vi.fn(),
    onAuthenticationRequired: vi.fn(),
  }
  const navigated = vi.fn()
  const view = render(
    <UnsavedChangesGuardProvider>
      <Leave onProceed={navigated} />
      <Reschedule appointmentId="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" origin={CALENDAR_ORIGIN} now={() => NOW} {...handlers} />
    </UnsavedChangesGuardProvider>,
  )
  return { ...handlers, navigated, view }
}

export const next = () => fireEvent.click(screen.getByRole('button', { name: 'Напред' }))
export const back = () => fireEvent.click(screen.getByRole('button', { name: 'Назад' }))
export const confirm = () => fireEvent.click(screen.getByRole('button', { name: 'Промени часа' }))
export const heading = () => screen.getByRole('heading', { level: 2 })
export const navigate = () => fireEvent.click(screen.getByRole('button', { name: 'Навигация' }))

export async function staffStep() {
  await screen.findByRole('heading', { level: 2, name: 'Избор на служител' })
  await screen.findByRole('radio', { name: /Мила/ })
}

export async function chooseStaff(name: string) {
  fireEvent.click(await screen.findByRole('radio', { name: new RegExp(name) }))
}

export async function chooseTime(index = 0, date = DAY) {
  const input = await screen.findByLabelText('Дата')
  if ((input as HTMLInputElement).value !== date) fireEvent.change(input, { target: { value: date } })
  const slots = within(await screen.findByRole('group', { name: 'Свободни часове' })).getAllByRole('radio')
  fireEvent.click(slots[index]!)
}

/** Renders the form and walks it, keeping the current StaffMember, to the review. */
export async function toReview(options: { replacement?: string; slot?: number } = {}) {
  const handlers = renderForm()
  await staffStep()
  if (options.replacement) await chooseStaff(options.replacement)
  next()
  await chooseTime(options.slot ?? 0)
  next()
  await screen.findByRole('heading', { level: 2, name: 'Преглед и потвърждение' })
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
