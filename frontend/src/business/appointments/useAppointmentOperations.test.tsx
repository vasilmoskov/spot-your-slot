import { act, cleanup, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import { UnsavedChangesGuardProvider } from '../../ui/UnsavedChangesGuard'
import { APPOINTMENT_ONE, FakeLifecycleBackend, cancelled, confirmed } from './lifecycleTestFixtures'
import { deferred, installBackend } from './lifecycleTestHarness'
import { useAppointmentOperations, type RefreshResult } from './useAppointmentOperations'

// The hook is driven without the page, so that handlers captured from an earlier render ("stale
// callbacks": a double click, a queued event, a closure kept by a timer) can be called exactly as a
// browser could still call them. The page removes the controls of a frozen operation; these tests prove
// that the hook itself refuses even when something still holds the old handler.

vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
let backend: FakeLifecycleBackend
let refresh: ReturnType<typeof vi.fn<() => Promise<RefreshResult>>>
let onMissing: ReturnType<typeof vi.fn<() => void>>

beforeEach(() => {
  backend = installBackend(mockedRequest)
  refresh = vi.fn(async () => 'ok' as RefreshResult)
  onMissing = vi.fn<() => void>()
})

afterEach(() => {
  cleanup()
})

function wrapper({ children }: { children: ReactNode }) {
  return <UnsavedChangesGuardProvider>{children}</UnsavedChangesGuardProvider>
}

function mount(appointment = confirmed()) {
  return renderHook(
    () =>
      useAppointmentOperations({
        appointment,
        refresh,
        onAuthenticationRequired: vi.fn(),
        onMissing,
      }),
    { wrapper },
  )
}

async function flush() {
  await act(async () => {
    await Promise.resolve()
  })
}

describe('stale handlers cannot send a second request', () => {
  it('a confirm handler captured before an unresolved cancellation sends nothing afterwards', async () => {
    backend.cancel = () => {
      throw new TypeError('Failed to fetch')
    }
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
    expect(view.result.current.confirming).toBe(false)
  })

  it('a save handler captured while editing sends nothing after an unresolved note', async () => {
    backend.note = () => {
      throw new TypeError('Failed to fetch')
    }
    const view = mount()
    act(() => view.result.current.startEdit())
    act(() => view.result.current.changeDraft('Бележка'))
    const stale = view.result.current
    await act(async () => stale.saveNote())
    expect(view.result.current.unresolved).toEqual({ operation: 'note', note: 'Бележка' })
    await act(async () => {
      stale.saveNote()
      stale.startEdit()
      view.result.current.startEdit()
      view.result.current.saveNote()
    })
    expect(backend.posts()).toHaveLength(1)
    expect(view.result.current.editing).toBe(false)
  })

  it('a second confirmation while the first request is in flight is ignored', async () => {
    const answer = deferred<ReturnType<FakeLifecycleBackend['result']>>()
    backend.cancel = () => answer.promise
    const view = mount()
    act(() => view.result.current.openCancel())
    const stale = view.result.current
    await act(async () => {
      stale.confirmCancel()
      stale.confirmCancel()
      view.result.current.confirmCancel()
    })
    expect(backend.posts()).toHaveLength(1)
    await act(async () => {
      backend.current = cancelled()
      answer.resolve(backend.result())
    })
    await flush()
    expect(backend.posts()).toHaveLength(1)
  })

  it('a note save is refused while a cancellation is in flight, and the cancellation is refused while a note is saved', async () => {
    const answer = deferred<ReturnType<FakeLifecycleBackend['result']>>()
    backend.cancel = () => answer.promise
    const view = mount()
    act(() => view.result.current.startEdit())
    act(() => view.result.current.changeDraft('Бележка'))
    const editing = view.result.current
    act(() => view.result.current.openCancel())
    await act(async () => view.result.current.confirmCancel())
    await act(async () => {
      editing.saveNote()
      view.result.current.saveNote()
    })
    expect(backend.posts().map((call) => call.method)).toEqual(['POST'])
    await act(async () => {
      backend.current = cancelled()
      answer.resolve(backend.result())
    })
    await flush()
    expect(backend.posts()).toHaveLength(1)
  })

  it('a refusal that blocks the screen also blocks every later call of an old handler', async () => {
    backend.note = () => {
      throw backend.problem(403, 'ACCESS_DENIED')
    }
    const view = mount()
    act(() => view.result.current.startEdit())
    act(() => view.result.current.changeDraft('Бележка'))
    const stale = view.result.current
    await act(async () => stale.saveNote())
    expect(view.result.current.blocked).toBe(true)
    await act(async () => {
      stale.saveNote()
      view.result.current.openCancel()
      view.result.current.startEdit()
    })
    await act(async () => view.result.current.confirmCancel())
    expect(backend.posts()).toHaveLength(1)
  })
})

describe('a full re-read invalidates every request still in flight', () => {
  it('an answer that arrives after the reset changes nothing and triggers no re-read', async () => {
    const answer = deferred<ReturnType<FakeLifecycleBackend['result']>>()
    backend.cancel = () => answer.promise
    const view = mount()
    act(() => view.result.current.openCancel())
    await act(async () => view.result.current.confirmCancel())
    act(() => view.result.current.reset())
    expect(view.result.current.pending).toBeNull()
    await act(async () => {
      answer.reject(new TypeError('Failed to fetch'))
    })
    await flush()
    expect(view.result.current.unresolved).toBeNull()
    expect(view.result.current.notice).toBeNull()
    expect(view.result.current.locked).toBe(false)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('after the reset a fresh operation works once and a late success of the old one does not refresh', async () => {
    const first = deferred<ReturnType<FakeLifecycleBackend['result']>>()
    let calls = 0
    backend.cancel = () => {
      calls += 1
      return calls === 1 ? first.promise : (() => {
        backend.current = cancelled()
        return backend.result()
      })()
    }
    const view = mount()
    act(() => view.result.current.openCancel())
    await act(async () => view.result.current.confirmCancel())
    act(() => view.result.current.reset())
    act(() => view.result.current.openCancel())
    await act(async () => view.result.current.confirmCancel())
    expect(refresh).toHaveBeenCalledTimes(1)
    await act(async () => {
      backend.current = cancelled()
      first.resolve({ id: APPOINTMENT_ONE, status: 'CANCELLED', version: 8, internalNote: null })
    })
    await flush()
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(backend.posts()).toHaveLength(2)
  })
})
