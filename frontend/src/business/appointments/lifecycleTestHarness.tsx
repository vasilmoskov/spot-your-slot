import { fireEvent, render, screen, within } from '@testing-library/react'
import { vi, type Mock } from 'vitest'
import { CALENDAR_ORIGIN } from '../../navigation'
import { UnsavedChangesGuardProvider, useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import { AppointmentDetail } from '../calendar/AppointmentDetail'
import { FakeLifecycleBackend, NOW } from './lifecycleTestFixtures'

// Shared driver of the Phase 10 tests: it renders the real Appointment detail and uses the real controls.
// The network helper is mocked by each test file (vi.mock is per file).
export function installBackend(mocked: Mock, configure?: (backend: FakeLifecycleBackend) => void): FakeLifecycleBackend {
  const backend = new FakeLifecycleBackend()
  configure?.(backend)
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

export type RenderOptions = { canChange?: boolean; now?: Date }

export function renderDetail(options: RenderOptions = {}) {
  const handlers = {
    onAuthenticationRequired: vi.fn(),
    onBack: vi.fn(),
    onOpenCustomer: vi.fn(),
    onReschedule: vi.fn(),
  }
  const navigated = vi.fn()
  const clock = options.now ?? NOW
  const view = render(
    <UnsavedChangesGuardProvider>
      <Leave onProceed={navigated} />
      <AppointmentDetail
        appointmentId="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        origin={CALENDAR_ORIGIN}
        canChange={options.canChange ?? true}
        now={() => clock}
        {...handlers}
      />
    </UnsavedChangesGuardProvider>,
  )
  return { ...handlers, navigated, view }
}

export async function ready() {
  await screen.findByRole('region', { name: 'Час' })
}

export const navigate = () => fireEvent.click(screen.getByRole('button', { name: 'Навигация' }))
export const openCancel = () => fireEvent.click(screen.getByRole('button', { name: 'Отмени часа' }))
export const dialog = () => screen.getByRole('alertdialog')
export const confirmCancel = () => fireEvent.click(within(dialog()).getByRole('button', { name: 'Отмени часа' }))
export const keepAppointment = () => fireEvent.click(within(dialog()).getByRole('button', { name: 'Отказ' }))
export const editNote = (name: string | RegExp = /Редактирай бележката|Добави бележка/) =>
  fireEvent.click(screen.getByRole('button', { name }))
export const noteInput = () => screen.getByRole('textbox', { name: 'Вътрешна бележка' }) as HTMLTextAreaElement
export const typeNote = (value: string) => fireEvent.change(noteInput(), { target: { value } })
export const saveNote = () => fireEvent.click(screen.getByRole('button', { name: 'Запази бележката' }))
export const rereadButton = () => screen.getByRole('button', { name: 'Прочети часа отново' })

export function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}
