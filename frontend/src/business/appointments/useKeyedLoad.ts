import { useEffect, useRef, useState } from 'react'

export type KeyedLoad<T> =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'loaded'; key: string; data: T }
  | { kind: 'error'; key: string; error: unknown }

/**
 * One abortable read of the data for exactly `key` (null: nothing to read). A new key or a new
 * `reloadToken` aborts the request in flight, and an answer that arrives late is ignored, so a result
 * is only ever shown for the key it was requested for. The loader and the failure callback are read
 * through refs: a parent re-render never causes a request.
 */
export function useKeyedLoad<T>(
  key: string | null,
  load: (signal: AbortSignal) => Promise<T>,
  reloadToken: number,
  onFailure?: (error: unknown) => void,
): KeyedLoad<T> {
  const [state, setState] = useState<KeyedLoad<T>>({ kind: 'idle' })
  const loader = useRef(load)
  loader.current = load
  const failure = useRef(onFailure)
  failure.current = onFailure

  useEffect(() => {
    if (key === null) {
      setState({ kind: 'idle' })
      return
    }
    const controller = new AbortController()
    setState({ kind: 'loading' })
    loader
      .current(controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setState({ kind: 'loaded', key, data })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setState({ kind: 'error', key, error })
        failure.current?.(error)
      })
    return () => controller.abort()
  }, [key, reloadToken])

  return state
}

/** The loaded data when, and only when, it answers the current key. */
export function dataFor<T>(state: KeyedLoad<T>, key: string | null): T | null {
  return state.kind === 'loaded' && state.key === key ? state.data : null
}
