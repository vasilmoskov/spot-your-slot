import { useCallback, useEffect, useRef, useState } from 'react'

export type ReadView<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; data: T }
  | { status: 'failed'; error: unknown }

type Settled<T> = { key: string; view: ReadView<T> }

/**
 * One public read, identified by `key` (null disables it). A change of key aborts the previous request,
 * and a response, a failure or an abort that belongs to any other key is never shown, so a late answer
 * for a superseded selection or another Business cannot reach the screen. When the read is disabled
 * its previous result is dropped, so the next enabling always loads fresh data. `reload` repeats the
 * same read. The loader is read from a ref, so a new function identity never restarts the request.
 */
export function useRead<T>(
  key: string | null,
  load: (signal: AbortSignal) => Promise<T>,
): { view: ReadView<T>; reload: () => void } {
  const [reloads, setReloads] = useState(0)
  const [settled, setSettled] = useState<Settled<T> | null>(null)
  const loader = useRef(load)
  loader.current = load
  const fullKey = key === null ? null : `${key}#${reloads}`

  useEffect(() => {
    if (fullKey === null) {
      setSettled(null)
      return undefined
    }
    const controller = new AbortController()
    loader.current(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setSettled({ key: fullKey, view: { status: 'ready', data } })
      },
      (error: unknown) => {
        if (!controller.signal.aborted) {
          setSettled({ key: fullKey, view: { status: 'failed', error } })
        }
      },
    )
    return () => controller.abort()
  }, [fullKey])

  const reload = useCallback(() => setReloads((count) => count + 1), [])

  let view: ReadView<T> = { status: 'idle' }
  if (fullKey !== null) view = settled?.key === fullKey ? settled.view : { status: 'loading' }
  return { view, reload }
}

/**
 * True until `delayMs` has elapsed since `token` last changed; false for a zero delay. It never starts
 * anything by itself: it only tells a control when it may be used again.
 */
export function useWaiting(delayMs: number, token: unknown): boolean {
  const [elapsed, setElapsed] = useState<unknown>(null)
  useEffect(() => {
    if (delayMs <= 0) return undefined
    const timer = setTimeout(() => setElapsed(token), delayMs)
    return () => clearTimeout(timer)
    // The delay is read once per token: a re-render must not restart the wait.
  }, [token])
  return delayMs > 0 && elapsed !== token
}
