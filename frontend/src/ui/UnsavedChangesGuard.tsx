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

type PendingNavigation = {
  proceed: () => void
  onCancel: (() => void) | undefined
}

export type UnsavedChangesGuardApi = {
  isDirty: boolean
  registerDirty: (dirty: boolean, discard: () => void) => void
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
  const [pending, setPending] = useState<PendingNavigation | null>(null)
  const continueButtonRef = useRef<HTMLButtonElement>(null)
  const invokerRef = useRef<HTMLElement | null>(null)

  const registerDirty = useCallback((dirty: boolean, discard: () => void) => {
    discardRef.current = discard
    isDirtyRef.current = dirty
    setIsDirty(dirty)
  }, [])

  const unregisterDirty = useCallback(() => {
    discardRef.current = () => undefined
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
      return { proceed, onCancel }
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
    invokerRef.current?.focus()
    invokerRef.current = null
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
            aria-label="Незапазени промени"
            aria-describedby="unsaved-changes-description-1 unsaved-changes-description-2"
          >
            <p id="unsaved-changes-description-1">Имате незапазени промени.</p>
            <p id="unsaved-changes-description-2">Ако напуснете, те ще бъдат загубени.</p>
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

export function useGuardedFormState(isDirty: boolean, discard: () => void): UnsavedChangesGuardApi {
  const guard = useUnsavedChangesGuard()
  const { registerDirty, unregisterDirty } = guard
  const discardRef = useRef(discard)
  discardRef.current = discard

  // Layout effects, so the guard's dirty state is updated in the same commit
  // as the DOM the user sees. With passive effects there is a window where a
  // cleared form (for example after a successful save) is still registered
  // dirty and a click in that window would show a false prompt.
  useLayoutEffect(() => {
    registerDirty(isDirty, () => discardRef.current())
  }, [isDirty, registerDirty])

  useLayoutEffect(() => () => unregisterDirty(), [unregisterDirty])

  return guard
}
