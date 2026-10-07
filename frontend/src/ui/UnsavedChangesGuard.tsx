import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { Button } from './Button'

// Wording of the confirmation. The default is the shared "unsaved changes" text; a screen whose
// data is not a form (the public booking journey) supplies its own accessible name and sentences.
export type GuardNotice = {
  label: string
  lines: readonly string[]
}

const DEFAULT_NOTICE: GuardNotice = {
  label: 'Незапазени промени',
  lines: ['Имате незапазени промени.', 'Ако напуснете, те ще бъдат загубени.'],
}

type PendingNavigation = {
  proceed: () => void
  onCancel: (() => void) | undefined
  notice: GuardNotice
}

export type UnsavedChangesGuardApi = {
  isDirty: boolean
  registerDirty: (dirty: boolean, discard: () => void, notice?: GuardNotice) => void
  unregisterDirty: () => void
  guard: (proceed: () => void, onCancel?: () => void) => void
}

const UnsavedChangesGuardContext = createContext<UnsavedChangesGuardApi | null>(null)

export function UnsavedChangesGuardProvider({ children }: { children: ReactNode }) {
  const [isDirty, setIsDirty] = useState(false)
  // registerDirty/unregisterDirty must take effect for guard() immediately,
  // including when a caller unregisters and then synchronously triggers a
  // guarded navigation in the same event handler (e.g. a successful save
  // clearing the guard right before navigating to the created record).
  // React state updates are not visible until the next render, so guard()
  // reads this ref rather than the isDirty state value.
  const isDirtyRef = useRef(false)
  const discardRef = useRef<() => void>(() => undefined)
  const noticeRef = useRef<GuardNotice>(DEFAULT_NOTICE)
  const [pending, setPending] = useState<PendingNavigation | null>(null)
  const continueButtonRef = useRef<HTMLButtonElement>(null)
  const invokerRef = useRef<HTMLElement | null>(null)

  const registerDirty = useCallback((dirty: boolean, discard: () => void, notice?: GuardNotice) => {
    discardRef.current = discard
    noticeRef.current = notice ?? DEFAULT_NOTICE
    isDirtyRef.current = dirty
    setIsDirty(dirty)
  }, [])

  const unregisterDirty = useCallback(() => {
    discardRef.current = () => undefined
    noticeRef.current = DEFAULT_NOTICE
    isDirtyRef.current = false
    setIsDirty(false)
  }, [])

  // Only one pending navigation is ever retained: a guard() call that arrives
  // while a confirmation is already showing (repeated Back/Forward, another
  // nav click, a second logout attempt, ...) is dropped rather than silently
  // replacing the original request, so exactly one action can be confirmed.
  const guard = useCallback((proceed: () => void, onCancel?: () => void) => {
    if (!isDirtyRef.current) {
      proceed()
      return
    }
    setPending((current) => {
      if (current) return current
      invokerRef.current = document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null
      return { proceed, onCancel, notice: noticeRef.current }
    })
  }, [])

  useEffect(() => {
    if (!isDirty) return
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [isDirty])

  useLayoutEffect(() => {
    if (pending) continueButtonRef.current?.focus()
  }, [pending])

  const confirmDiscard = () => {
    const current = pending
    setPending(null)
    discardRef.current()
    discardRef.current = () => undefined
    isDirtyRef.current = false
    setIsDirty(false)
    current?.proceed()
  }

  const continueEditing = () => {
    const current = pending
    setPending(null)
    current?.onCancel?.()
    const invoker = invokerRef.current
    invokerRef.current = null
    // When the invoker is gone or hidden (a sidebar link inside a closed mobile menu), focus
    // goes to the nearest meaningful control instead of falling back to the page body.
    const visible =
      invoker !== null &&
      invoker.isConnected &&
      (typeof invoker.checkVisibility !== 'function' || invoker.checkVisibility())
    const target = visible ? invoker : document.querySelector<HTMLElement>('[data-focus-fallback]')
    target?.focus()
  }

  return (
    <UnsavedChangesGuardContext.Provider value={{ isDirty, registerDirty, unregisterDirty, guard }}>
      {children}
      {pending && (
        <div
          className="unsaved-changes-overlay"
          onKeyDown={(event) => {
            if (event.key === 'Escape') continueEditing()
          }}
        >
          <div
            className="confirmation-panel unsaved-changes-panel"
            role="alertdialog"
            aria-modal="true"
            aria-label={pending.notice.label}
            aria-describedby={pending.notice.lines
              .map((_, index) => `unsaved-changes-description-${index + 1}`)
              .join(' ')}
          >
            {pending.notice.lines.map((line, index) => (
              <p key={line} id={`unsaved-changes-description-${index + 1}`}>
                {line}
              </p>
            ))}
            <div className="action-group unsaved-changes-actions">
              <Button
                ref={continueButtonRef}
                type="button"
                variant="secondary"
                onClick={continueEditing}
              >
                Остани
              </Button>
              <Button type="button" variant="destructive" onClick={confirmDiscard}>
                Напусни
              </Button>
            </div>
          </div>
        </div>
      )}
    </UnsavedChangesGuardContext.Provider>
  )
}

export function useUnsavedChangesGuard(): UnsavedChangesGuardApi {
  const context = useContext(UnsavedChangesGuardContext)
  if (!context) {
    throw new Error('useUnsavedChangesGuard must be used within UnsavedChangesGuardProvider')
  }
  return context
}

export function useGuardedFormState(
  isDirty: boolean,
  discard: () => void,
  notice?: GuardNotice,
): UnsavedChangesGuardApi {
  const guard = useUnsavedChangesGuard()
  const { registerDirty, unregisterDirty } = guard
  const discardRef = useRef(discard)
  discardRef.current = discard
  const noticeRef = useRef(notice)
  noticeRef.current = notice
  const noticeKey = notice ? `${notice.label}\n${notice.lines.join('\n')}` : ''

  // Layout effects, so the guard's dirty state is updated in the same commit
  // as the DOM the user sees. With passive effects there is a window where a
  // cleared form (for example after a successful save) is still registered
  // dirty and a click in that window would show a false prompt.
  useLayoutEffect(() => {
    registerDirty(isDirty, () => discardRef.current(), noticeRef.current)
  }, [isDirty, registerDirty, noticeKey])

  useLayoutEffect(() => () => unregisterDirty(), [unregisterDirty])

  return guard
}
