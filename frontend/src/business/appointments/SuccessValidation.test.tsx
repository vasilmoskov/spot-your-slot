import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, renderHook, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import { UnsavedChangesGuardProvider } from '../../ui/UnsavedChangesGuard'
import { confirmed, type FakeLifecycleBackend } from './lifecycleTestFixtures'
import {
  confirmCancel,
  editNote,
  installBackend,
  openCancel,
  ready,
  renderDetail,
  rereadButton,
  saveNote,
  typeNote,
} from './lifecycleTestHarness'
import { useAppointmentOperations, type RefreshResult } from './useAppointmentOperations'

// A 2xx answer is a success only when it is a valid acknowledgement of exactly the request that was sent.
// Anything else (an unchanged or malformed version, another Appointment, the wrong state, an old or another
// note) is contradictory evidence: unresolved, frozen after exactly one request, no success claim, and only
// the explicit authoritative re-read leaves the frozen state. The following GET is never trusted instead.

vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
let backend: FakeLifecycleBackend

const OTHER = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'

beforeEach(() => {
  backend = installBackend(mockedRequest, (fake) => {
    fake.current = confirmed({ internalNote: 'Стара бележка' })
  })
})

afterEach(() => {
  cleanup()
})

const UNCERTAIN_CANCEL = 'Не е ясно дали часът е отменен. Прочетете часа отново, преди да опитате пак.'
const UNCERTAIN_NOTE = 'Не е ясно дали бележката е променена. Прочетете часа отново, преди да опитате пак.'

const cancelBase = () => ({ id: backend.current.id, status: 'CANCELLED', version: 8, internalNote: 'Стара бележка' })
const noteBase = (note: string | null) => ({
  id: backend.current.id,
  status: 'CONFIRMED',
  version: 8,
  internalNote: note,
})

const BAD_VERSIONS: [string, unknown][] = [
  ['unchanged', 7],
  ['lower', 6],
  ['zero', 0],
  ['negative', -3],
  ['fractional', 8.5],
  ['not a number', Number.NaN],
  ['infinite', Number.POSITIVE_INFINITY],
  ['unsafe', Number.MAX_SAFE_INTEGER + 2],
  ['a string', '8'],
  ['missing', undefined],
]

async function frozenAfterCancel(answer: unknown) {
  backend.cancel = () => answer as never
  renderDetail()
  await ready()
  openCancel()
  confirmCancel()
  return screen.findByRole('alert')
}

async function frozenAfterNote(note: string, answer: unknown) {
  backend.note = () => answer as never
  renderDetail()
  await ready()
  editNote()
  typeNote(note)
  saveNote()
  return screen.findByRole('alert')
}

function expectNoSuccessClaim() {
  expect(screen.queryByText('Бележката е запазена.')).toBeNull()
  expect(screen.queryByText(/Този час е отменен/)).toBeNull()
  expect(screen.queryByText('Отменен')).toBeNull()
}

describe('cancellation: an invalid 2xx answer is unresolved', () => {
  const cases: [string, () => unknown][] = [
    ...BAD_VERSIONS.map(([name, version]): [string, () => unknown] => [`${name} version`, () => ({ ...cancelBase(), version })]),
    ['another Appointment', () => ({ ...cancelBase(), id: OTHER })],
    ['a missing id', () => ({ ...cancelBase(), id: undefined })],
    ['a status that is not CANCELLED', () => ({ ...cancelBase(), status: 'CONFIRMED' })],
    ['a missing status', () => ({ ...cancelBase(), status: undefined })],
    ['no body', () => undefined],
    ['a string body', () => 'ok'],
  ]

  it.each(cases)('%s freezes after exactly one request with no success claim and no trusted GET', async (_name, answer) => {
    const alert = await frozenAfterCancel(answer())
    expect(alert.textContent).toBe(UNCERTAIN_CANCEL)
    expect(backend.posts()).toHaveLength(1)
    expect(backend.reads()).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Отмени часа' })).toBeNull()
    expect(screen.queryByRole('button', { name: /Редактирай|Добави бележка/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Промени часа' })).toBeNull()
    expectNoSuccessClaim()
    expect(rereadButton()).toBeInTheDocument()
  })

  it('warns with "Неясен резултат" on leaving and the explicit re-read shows only the authoritative facts', async () => {
    // The lying acknowledgement claims a cancellation; the server did not apply it.
    await frozenAfterCancel({ ...cancelBase(), version: 7 })
    fireEvent.click(screen.getByRole('button', { name: 'Навигация' }))
    expect(screen.getByRole('alertdialog', { name: 'Неясен резултат' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    fireEvent.click(rereadButton())
    expect(await screen.findByText('Потвърден')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByRole('button', { name: 'Отмени часа' })).toBeInTheDocument()
    expect(backend.posts()).toHaveLength(1)
  })
})

describe('internal note: an invalid 2xx answer is unresolved', () => {
  const cases: [string, string, () => unknown][] = [
    ...BAD_VERSIONS.map(([name, version]): [string, string, () => unknown] => [
      `${name} version`,
      'Нова бележка',
      () => ({ ...noteBase('Нова бележка'), version }),
    ]),
    ['another Appointment', 'Нова бележка', () => ({ ...noteBase('Нова бележка'), id: OTHER })],
    ['the old note', 'Нова бележка', () => noteBase('Стара бележка')],
    ['another note', 'Нова бележка', () => noteBase('Съвсем друга бележка')],
    ['the submitted text before normalization', '  Нова\r\nбележка  ', () => noteBase('  Нова\r\nбележка  ')],
    ['a non-null note after clearing', '   ', () => noteBase('Стара бележка')],
    ['a blank string after clearing', '   ', () => noteBase('')],
    ['a missing note after clearing', '   ', () => ({ ...noteBase(null), internalNote: undefined })],
    ['null after saving a note', 'Нова бележка', () => noteBase(null)],
    ['a status outside the contract', 'Нова бележка', () => ({ ...noteBase('Нова бележка'), status: 'DELETED' })],
    ['a missing status', 'Нова бележка', () => ({ ...noteBase('Нова бележка'), status: undefined })],
    ['no body', 'Нова бележка', () => undefined],
  ]

  it.each(cases)('%s freezes the exact submitted note after one request with no success claim', async (_name, typed, answer) => {
    const alert = await frozenAfterNote(typed, answer())
    expect(alert.textContent).toBe(UNCERTAIN_NOTE)
    expect(backend.posts()).toHaveLength(1)
    expect(backend.reads()).toHaveLength(1)
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByRole('button', { name: /Редактирай|Добави бележка|Запази бележката/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Отмени часа' })).toBeNull()
    expectNoSuccessClaim()
    expect(rereadButton()).toBeInTheDocument()
  })

  it('the explicit re-read releases the freeze and shows only the authoritative note, never the claimed one', async () => {
    await frozenAfterNote('Нова бележка', noteBase('Лъжлива бележка'))
    expect(document.body.textContent).not.toContain('Лъжлива бележка')
    fireEvent.click(rereadButton())
    expect(await screen.findByRole('button', { name: 'Редактирай' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Бележки' })).toHaveTextContent('Стара бележка')
    expect(document.body.textContent).not.toContain('Лъжлива бележка')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })

  it('accepts the valid normalized answer, including the explicit null of a cleared note', async () => {
    backend.note = (body) => {
      backend.current = confirmed({
        internalNote: body.internalNote === '' ? null : body.internalNote,
        version: body.expectedVersion + 1,
      })
      return backend.result()
    }
    renderDetail()
    await ready()
    editNote()
    typeNote('  Нова\r\nбележка  ')
    saveNote()
    expect(await screen.findByText('Бележката е запазена.')).toBeInTheDocument()
    expect(backend.current.internalNote).toBe('Нова\nбележка')
    editNote()
    typeNote('  ')
    saveNote()
    await screen.findByText('Бележката е запазена.')
    expect(backend.current.internalNote).toBeNull()
    expect(backend.posts()).toHaveLength(2)
  })
})

describe('stale captured handlers cannot submit after an invalid acknowledgement', () => {
  const refresh = vi.fn(async () => 'ok' as RefreshResult)
  const wrapper = ({ children }: { children: ReactNode }) => (
    <UnsavedChangesGuardProvider>{children}</UnsavedChangesGuardProvider>
  )
  const mount = () =>
    renderHook(
      () =>
        useAppointmentOperations({
          appointment: backend.current,
          refresh,
          onAuthenticationRequired: vi.fn(),
          onMissing: vi.fn(),
        }),
      { wrapper },
    )

  it('after an unchanged-version cancellation', async () => {
    backend.cancel = () => ({ ...cancelBase(), version: 7 })
    const view = mount()
    act(() => view.result.current.openCancel())
    const stale = view.result.current
    await act(async () => stale.confirmCancel())
    expect(view.result.current.unresolved?.operation).toBe('cancel')
    await act(async () => {
      stale.confirmCancel()
      stale.openCancel()
      view.result.current.openCancel()
    })
    await act(async () => view.result.current.confirmCancel())
    expect(backend.posts()).toHaveLength(1)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('after a note answer that carries the old note', async () => {
    backend.note = () => noteBase('Стара бележка')
    const view = mount()
    act(() => view.result.current.startEdit())
    act(() => view.result.current.changeDraft('Нова бележка'))
    const stale = view.result.current
    await act(async () => stale.saveNote())
    expect(view.result.current.unresolved).toEqual({ operation: 'note', note: 'Нова бележка' })
    await act(async () => {
      stale.saveNote()
      view.result.current.startEdit()
      view.result.current.saveNote()
    })
    expect(backend.posts()).toHaveLength(1)
    expect(refresh).not.toHaveBeenCalled()
  })
})

describe('a strictly valid success followed by a failed re-read', () => {
  async function failingRead() {
    let reads = 0
    const real = backend.read
    backend.read = () => {
      reads += 1
      if (reads === 2) throw new TypeError('Failed to fetch')
      return real()
    }
  }

  it('cancellation: states the success from the acknowledgement and shows no Appointment fact', async () => {
    await failingRead()
    renderDetail()
    await ready()
    openCancel()
    confirmCancel()
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe(
      'Часът е отменен, но актуалните данни не можаха да бъдат заредени. Прочетете часа отново.',
    )
    // Neither the stale confirmed state nor an invented cancelled one is displayed.
    expect(screen.queryByRole('region', { name: 'Детайли' })).toBeNull()
    expect(screen.queryByText('Потвърден')).toBeNull()
    expect(screen.queryByText('Отменен')).toBeNull()
    expect(screen.queryByText('Мария Тестова')).toBeNull()
    expect(screen.queryByRole('button', { name: /Отмени часа|Редактирай|Добави бележка|Промени часа/ })).toBeNull()
    expect(backend.posts()).toHaveLength(1)
    fireEvent.click(rereadButton())
    expect(await screen.findByText('Отменен')).toBeInTheDocument()
    expect(backend.posts()).toHaveLength(1)
  })

  it('note: states the success from the acknowledgement and shows neither the old nor the new note', async () => {
    await failingRead()
    renderDetail()
    await ready()
    editNote()
    typeNote('Нова бележка')
    saveNote()
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe(
      'Бележката е запазена, но актуалните данни не можаха да бъдат заредени. Прочетете часа отново.',
    )
    expect(document.body.textContent).not.toContain('Стара бележка')
    expect(document.body.textContent).not.toContain('Нова бележка')
    expect(screen.queryByRole('region', { name: 'Бележки' })).toBeNull()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByRole('button', { name: /Редактирай|Добави бележка|Отмени часа/ })).toBeNull()
    fireEvent.click(rereadButton())
    expect(await screen.findByRole('region', { name: 'Бележки' })).toHaveTextContent('Нова бележка')
    expect(backend.posts()).toHaveLength(1)
  })
})
