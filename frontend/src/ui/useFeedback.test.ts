import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../identity/api'
import { errorCategory, useFeedback } from './useFeedback'

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('shared feedback lifecycle', () => {
  it.each(['success', 'error'] as const)('dismisses transient %s after five seconds', (kind) => {
    const storage = vi.spyOn(Storage.prototype, 'setItem')
    const { result } = renderHook(() => useFeedback('profile'))
    act(() => result.current.setFeedback({ kind, text: 'Резултат' }))
    act(() => vi.advanceTimersByTime(4_999))
    expect(result.current.feedback?.text).toBe('Резултат')
    act(() => vi.advanceTimersByTime(1))
    expect(result.current.feedback).toBeNull()
    expect(storage).not.toHaveBeenCalled()
  })

  it.each(['validation', 'blocking'] as const)('retains %s without a timer', (category) => {
    const { result } = renderHook(() => useFeedback('form'))
    act(() => result.current.setFeedback({ kind: 'error', text: 'Проверете данните', category }))
    expect(vi.getTimerCount()).toBe(0)
    act(() => vi.advanceTimersByTime(10_000))
    expect(result.current.feedback?.category).toBe(category)
    act(() => result.current.setFeedback(null))
    expect(result.current.feedback).toBeNull()
  })

  it.each(['route', 'tab', 'entity', 'operation'])('clears on %s changes and rejects late responses', (context) => {
    const { result, rerender } = renderHook(({ key }) => useFeedback(key), {
      initialProps: { key: `${context}:a` },
    })
    act(() => result.current.setFeedback({ kind: 'success', text: 'Запазено' }))
    rerender({ key: `${context}:b` })
    expect(result.current.feedback).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
    let publish: ReturnType<typeof result.current.beginFeedback>
    act(() => { publish = result.current.beginFeedback() })
    rerender({ key: `${context}:c` })
    act(() => expect(publish({ kind: 'error', text: 'Стара грешка' })).toBe(false))
    expect(result.current.feedback).toBeNull()
  })

  it('cleans up replaced timers, new attempts, and unmount', () => {
    const { result, unmount } = renderHook(() => useFeedback('profile'))
    act(() => result.current.setFeedback({ kind: 'success', text: 'Първи резултат' }))
    act(() => vi.advanceTimersByTime(4_000))
    act(() => result.current.setFeedback({ kind: 'error', text: 'Нов резултат' }))
    expect(vi.getTimerCount()).toBe(1)
    act(() => vi.advanceTimersByTime(1_000))
    expect(result.current.feedback?.text).toBe('Нов резултат')
    let publish: ReturnType<typeof result.current.beginFeedback>
    act(() => { publish = result.current.beginFeedback() })
    expect(result.current.feedback).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
    act(() => result.current.setFeedback({ kind: 'success', text: 'Следващ резултат' }))
    act(() => expect(publish({ kind: 'error', text: 'Стар резултат' })).toBe(false))
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('classifies public validation and reload failures separately from action failures', () => {
    for (const code of ['VALIDATION_ERROR', 'CURRENT_PASSWORD_INVALID', 'AUTH_FAILED', 'BUSINESS_SLUG_CONFLICT', 'INVITATION_CREDENTIAL_MISMATCH']) {
      expect(errorCategory(new ApiError(400, code, 'Проверете данните'))).toBe('validation')
    }
    expect(errorCategory(new ApiError(409, 'BUSINESS_CONCURRENT_UPDATE', 'Заредете отново'))).toBe('blocking')
    expect(errorCategory(new ApiError(400, 'INVITATION_INVALID', 'Нова покана'))).toBe('blocking')
    expect(errorCategory(new ApiError(409, 'BUSINESS_MISSING_ACTIVE_OWNER', 'Неуспешно активиране'))).toBe('transient')
    expect(errorCategory(new Error('network'))).toBe('transient')
  })
})
