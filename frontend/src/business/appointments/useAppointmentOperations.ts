import { useCallback, useEffect, useRef, useState } from 'react'
import { useGuardedFormState, type GuardNotice } from '../../ui/UnsavedChangesGuard'
import type { AppointmentDetails } from '../calendar/api'
import { cancelAppointment, saveInternalNote, type LifecycleResult } from './lifecycleApi'
import {
  classifyLifecycleFailure,
  isAuthenticationRequired,
  isBlocking,
  isUnresolved,
  lifecycleText,
  reloadText,
  type LifecycleFailure,
  type Operation,
} from './lifecycleErrors'
import { canonicalNote, isNoteDirty, isReadableCancellation, isReadableNoteResult } from './lifecycleModel'
import { noteError, normalizeNote } from './model'

/** The outcome of a silent re-read of the Appointment, as the detail reports it to the operations. */
export type RefreshResult = 'ok' | 'missing' | 'failed' | 'stale'

export type FocusTarget = 'cancel-open' | 'cancel-notice' | 'cancelled' | 'note-input' | 'note-edit' | 'note-notice'

export type Notice = {
  operation: Operation
  kind: LifecycleFailure | 'reload'
  text: string
}

/** The exact operation that was sent when its outcome could not be established. */
export type Unresolved = { operation: Operation; note: string | null }

const LEAVE_NOTICE: GuardNotice = {
  label: 'Непотвърдена бележка',
  lines: ['Промяната на бележката още не е запазена.', 'Ако напуснете, написаното ще бъде загубено.'],
}

const UNRESOLVED_NOTICES: Record<Operation, GuardNotice> = {
  cancel: {
    label: 'Неясен резултат',
    lines: ['Не е ясно дали часът е отменен.', 'Проверете часа в графика, преди да го отменяте отново.'],
  },
  note: {
    label: 'Неясен резултат',
    lines: ['Не е ясно дали бележката е променена.', 'Проверете часа, преди да променяте бележката отново.'],
  },
}

type Options = {
  appointment: AppointmentDetails | null
  // A silent re-read of the authoritative Appointment; it replaces the detail's data on success.
  refresh: () => Promise<RefreshResult>
  onAuthenticationRequired: (detail: string) => void
  // The Appointment is gone: the detail shows its own not-found state.
  onMissing: () => void
}

/**
 * The two owner operations of the Appointment detail, cancellation and the private internal note
 * (Phase 6 contracts). Each is exactly one request per deliberate action, guarded by a synchronous
 * flag shared by both operations, never repeated automatically, and accepted as a success only when the 2xx
 * answer is a valid acknowledgement of exactly the request that was sent (same Appointment, expected state,
 * advanced version, the submitted note); the fresh facts are then read again from the server. A result that cannot rule a commit out freezes the submitted operation
 * until the owner reads the Appointment again; a stale answer (after a reset or an unmount) changes nothing.
 * Nothing here is stored: the edited note lives in this component's memory only.
 */
export function useAppointmentOperations({ appointment, refresh, onAuthenticationRequired, onMissing }: Options) {
  const [pending, setPending] = useState<Operation | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [notice, setNotice] = useState<Notice | null>(null)
  const [noteStatus, setNoteStatus] = useState<string | null>(null)
  const [unresolved, setUnresolved] = useState<Unresolved | null>(null)
  const [blocked, setBlocked] = useState(false)
  const [cancelRefused, setCancelRefused] = useState(false)
  const [reloadNeeded, setReloadNeeded] = useState(false)
  const [focus, setFocus] = useState<{ target: FocusTarget; tick: number } | null>(null)

  const busyRef = useRef(false)
  const lockedRef = useRef(false)
  const generation = useRef(0)
  const alive = useRef(true)
  const latest = useRef({ appointment, refresh, onAuthenticationRequired, onMissing })
  latest.current = { appointment, refresh, onAuthenticationRequired, onMissing }

  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  const requestFocus = useCallback((target: FocusTarget) => {
    setFocus((current) => ({ target, tick: (current?.tick ?? 0) + 1 }))
  }, [])

  const closeEdit = useCallback(() => {
    setEditing(false)
    setDraft('')
  }, [])

  const dirty = editing && unresolved === null && isNoteDirty(draft, appointment?.internalNote ?? null)
  const guard = useGuardedFormState(
    unresolved !== null || dirty,
    closeEdit,
    unresolved ? UNRESOLVED_NOTICES[unresolved.operation] : LEAVE_NOTICE,
  )

  /** Forget every result and invalidate every request still in flight (a full re-read of the Appointment). */
  const reset = useCallback(() => {
    generation.current += 1
    busyRef.current = false
    lockedRef.current = false
    setPending(null)
    setConfirming(false)
    setEditing(false)
    setDraft('')
    setNotice(null)
    setNoteStatus(null)
    setUnresolved(null)
    setBlocked(false)
    setCancelRefused(false)
    setReloadNeeded(false)
  }, [])

  function lock() {
    lockedRef.current = true
  }

  function show(operation: Operation, kind: LifecycleFailure | 'reload', text: string, target: FocusTarget) {
    setNotice({ operation, kind, text })
    requestFocus(target)
  }

  const noticeTarget = (operation: Operation): FocusTarget => (operation === 'cancel' ? 'cancel-notice' : 'note-notice')

  /** Every documented result, kept distinct; nothing here sends the request again. */
  async function fail(
    operation: Operation,
    kind: LifecycleFailure,
    submittedNote: string | null,
    stale: () => boolean,
  ) {
    setConfirming(false)
    const text = lifecycleText(operation, kind)
    if (isUnresolved(kind)) {
      lock()
      closeEdit()
      setUnresolved({ operation, note: submittedNote })
      show(operation, kind, text, noticeTarget(operation))
      return
    }
    if (kind === 'not-found') {
      latest.current.onMissing()
      return
    }
    if (isBlocking(kind) || kind === 'concurrent-update' || kind === 'state-conflict') {
      if (isBlocking(kind)) {
        lock()
        setBlocked(true)
        closeEdit()
      }
      // The authoritative data is read again whatever the refusal: it is the new baseline.
      const read = await latest.current.refresh()
      if (stale() || read === 'stale') return
      if (read === 'missing') {
        latest.current.onMissing()
        return
      }
      if (read === 'failed') {
        lock()
        setReloadNeeded(true)
        closeEdit()
        show(operation, 'reload', `${text} Актуалните данни не можаха да бъдат заредени.`, noticeTarget(operation))
        return
      }
      if (kind === 'state-conflict' && operation === 'cancel') setCancelRefused(true)
      // A reloaded Appointment is the new clean baseline: the edit is never compared with the obsolete value.
      if (kind === 'concurrent-update' || kind === 'state-conflict') closeEdit()
      show(operation, kind, text, noticeTarget(operation))
      return
    }
    // Validation and a temporary refusal are known rollbacks: the edit is kept and only an explicit retry exists.
    show(operation, kind, text, noticeTarget(operation))
  }

  async function run(
    operation: Operation,
    send: (current: AppointmentDetails) => Promise<LifecycleResult>,
    accept: (result: unknown, current: AppointmentDetails) => boolean,
    submittedNote: string | null,
    afterSuccess: () => void,
  ) {
    const current = latest.current.appointment
    if (current === null || busyRef.current || lockedRef.current) return
    busyRef.current = true
    const started = generation.current
    const stale = () => !alive.current || started !== generation.current
    setPending(operation)
    setNotice(null)
    setNoteStatus(null)
    try {
      let result: unknown
      try {
        result = await send(current)
      } catch (error) {
        if (stale()) return
        if (isAuthenticationRequired(error)) {
          latest.current.onAuthenticationRequired(error.detail)
          return
        }
        await fail(operation, classifyLifecycleFailure(error), submittedNote, stale)
        return
      }
      if (stale()) return
      if (!accept(result, current)) {
        await fail(operation, 'uncertain', submittedNote, stale)
        return
      }
      // The validated acknowledgement is definitive evidence of success but is never used as the displayed
      // state: fresh facts come only from the authoritative re-read. If that fails, success is stated from
      // the acknowledgement and no post-mutation fact is shown.
      const read = await latest.current.refresh()
      if (stale() || read === 'stale') return
      if (read === 'missing') {
        latest.current.onMissing()
        return
      }
      setConfirming(false)
      if (read === 'failed') {
        lock()
        setReloadNeeded(true)
        closeEdit()
        show(operation, 'reload', reloadText(operation), noticeTarget(operation))
        return
      }
      afterSuccess()
    } finally {
      if (!stale()) {
        busyRef.current = false
        setPending(null)
      }
    }
  }

  // ---- cancellation ----------------------------------------------------------------------------------

  function openCancel() {
    if (busyRef.current || lockedRef.current) return
    setNotice(null)
    setConfirming(true)
  }

  function closeCancel() {
    // While the request is in flight the dialog stays open and ignores cancellation.
    if (busyRef.current) return
    setConfirming(false)
    requestFocus('cancel-open')
  }

  function confirmCancel() {
    if (!confirming) return
    void run(
      'cancel',
      (current) => cancelAppointment(current.id, current.version),
      (result, current) => isReadableCancellation(result, current.id, current.version),
      null,
      () => requestFocus('cancelled'),
    )
  }

  // ---- internal note ---------------------------------------------------------------------------------

  function startEdit() {
    const current = latest.current.appointment
    if (current === null || busyRef.current || lockedRef.current) return
    setNotice(null)
    setNoteStatus(null)
    setDraft(current.internalNote ?? '')
    setEditing(true)
    requestFocus('note-input')
  }

  function cancelEdit() {
    if (busyRef.current) return
    guard.guard(() => {
      closeEdit()
      requestFocus('note-edit')
    })
  }

  function changeDraft(value: string) {
    if (busyRef.current || lockedRef.current) return
    setDraft(value)
  }

  function saveNote() {
    const current = latest.current.appointment
    if (current === null || busyRef.current || lockedRef.current || !editing) return
    if (noteError(draft) !== undefined) {
      requestFocus('note-input')
      return
    }
    const normalized = normalizeNote(draft)
    if (normalized === canonicalNote(current.internalNote)) {
      // Nothing changed: no request, and the version does not advance.
      closeEdit()
      setNoteStatus('Бележката не е променена.')
      requestFocus('note-edit')
      return
    }
    void run(
      'note',
      (appointmentNow) => saveInternalNote(appointmentNow.id, appointmentNow.version, normalized ?? ''),
      (result, current) => isReadableNoteResult(result, current.id, current.version, normalized),
      normalized,
      () => {
        closeEdit()
        setNoteStatus('Бележката е запазена.')
        requestFocus('note-edit')
      },
    )
  }

  const locked = pending !== null || unresolved !== null || blocked || reloadNeeded
  return {
    pending,
    confirming,
    editing,
    draft,
    notice,
    noteStatus,
    unresolved,
    blocked,
    cancelRefused,
    reloadNeeded,
    locked,
    focus,
    guard,
    reset,
    openCancel,
    closeCancel,
    confirmCancel,
    startEdit,
    cancelEdit,
    changeDraft,
    saveNote,
  }
}

export type AppointmentOperations = ReturnType<typeof useAppointmentOperations>
