import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Button } from '../../ui/Button'
import { FieldError, fieldControlProps } from '../../ui/formValidation'
import { useFeedback, errorCategory } from '../../ui/useFeedback'
import { useGuardedFormState } from '../../ui/UnsavedChangesGuard'
import {
  getWorkingSchedule,
  replaceWorkingSchedule,
  type Weekday,
  type WorkingPeriod,
  type WorkingSchedule,
} from './api'
import { isAuthenticationRequired, isConcurrentUpdate, safeScheduleError } from './errors'
import { computeMenuPosition } from './menuPosition'
import {
  MAX_WORKING_PERIODS,
  WEEKDAY_LABELS,
  WEEKDAY_ORDER,
  WEEKDAY_SENTENCE_LABELS,
  formatPeriodRange,
  groupPeriodsByWeekday,
  periodValidationMessage,
  sortPeriodsForDisplay,
  validateDraftPeriods,
  type DraftPeriod,
} from './presentation'

type WorkingScheduleEditorProps = {
  staffMemberId: string
  staffMemberActive: boolean
  readOnly: boolean
  onAuthenticationRequired: (detail: string) => void
}

// `start`/`end` are problems with a single missing value; `range` concerns
// the pair (reversed, overlapping, duplicate, or over the period limit) and is
// tied to both fields.
type PeriodDialogErrors = {
  start?: string
  end?: string
  range?: string
}

type PeriodDialogState = {
  weekday: Weekday
  clientId: string | null
  start: string
  end: string
  initialStart: string
  initialEnd: string
  errors: PeriodDialogErrors
}

type CopyDialogState = {
  sourceWeekday: Weekday
  targets: Set<Weekday>
}

const MONDAY_TO_FRIDAY: readonly Weekday[] = WEEKDAY_ORDER.slice(0, 5)

let clientIdCounter = 0
function nextClientId(): string {
  clientIdCounter += 1
  return `schedule-period-${clientIdCounter}`
}

function toDraftPeriods(periods: readonly WorkingPeriod[]): DraftPeriod[] {
  return periods.map((period) => ({ clientId: nextClientId(), ...period }))
}

function stripClientId(period: DraftPeriod): WorkingPeriod {
  return { weekday: period.weekday, startTime: period.startTime, endTime: period.endTime }
}

function sameSchedule(draft: readonly DraftPeriod[], saved: readonly WorkingPeriod[]): boolean {
  if (draft.length !== saved.length) return false
  const normalize = (period: { weekday: Weekday; startTime: string; endTime: string }) =>
    `${period.weekday}|${period.startTime}|${period.endTime}`
  const left = draft.map(normalize).sort()
  const right = saved.map(normalize).sort()
  return left.every((value, index) => value === right[index])
}

function periodsCountLabel(count: number): string {
  return count === 1 ? `${count} период` : `${count} периода`
}

export function WorkingScheduleEditor({
  staffMemberId,
  staffMemberActive,
  readOnly,
  onAuthenticationRequired,
}: WorkingScheduleEditorProps) {
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [schedule, setSchedule] = useState<WorkingSchedule | null>(null)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<DraftPeriod[]>([])
  const [saving, setSaving] = useState(false)
  const [openDayMenu, setOpenDayMenu] = useState<Weekday | null>(null)
  const [dayMenuPosition, setDayMenuPosition] = useState<{ top: number; left: number } | null>(null)
  const [periodDialog, setPeriodDialog] = useState<PeriodDialogState | null>(null)
  const [copyDialog, setCopyDialog] = useState<CopyDialogState | null>(null)
  const [clearWeekdayConfirmation, setClearWeekdayConfirmation] = useState<Weekday | null>(null)
  const [clearAllConfirmation, setClearAllConfirmation] = useState(false)
  const [clearingAll, setClearingAll] = useState(false)
  const { feedback, setFeedback, beginFeedback } = useFeedback(staffMemberId)
  const activeLoad = useRef<AbortController | null>(null)
  const savingInProgress = useRef(false)
  const errorMessage = useRef<HTMLDivElement>(null)
  const errorSummary = useRef<HTMLParagraphElement>(null)
  const dialogInvoker = useRef<HTMLElement | null>(null)
  const periodDialogStartRef = useRef<HTMLInputElement>(null)
  const periodDialogEndRef = useRef<HTMLInputElement>(null)
  const copyDialogFirstCheckboxRef = useRef<HTMLInputElement>(null)
  const clearWeekdaySafeButton = useRef<HTMLButtonElement>(null)
  const clearAllSafeButton = useRef<HTMLButtonElement>(null)
  const dayMenuButtonRefs = useRef<Map<Weekday, HTMLButtonElement>>(new Map())
  const addPeriodButtonRefs = useRef<Map<Weekday, HTMLButtonElement>>(new Map())
  const saveButtonRef = useRef<HTMLButtonElement>(null)
  const dayMenuRef = useRef<HTMLDivElement>(null)
  const clearingInProgress = useRef(false)

  const canEdit = !readOnly && staffMemberActive
  const weeklyDraftDirty =
    editing && !loading && !loadError && !!schedule && !sameSchedule(draft, schedule.periods)
  // Unsaved values typed into the open Add/Edit dialog are not yet part of
  // `draft` (they only transfer on submit), so the shared guard must treat
  // the dialog's own dirty state as equally protection-worthy: otherwise a
  // guarded navigation away from a dirty dialog-only edit (weekly draft
  // itself still clean) would silently lose the typed values.
  const periodDialogDirty =
    !!periodDialog &&
    (periodDialog.start !== periodDialog.initialStart || periodDialog.end !== periodDialog.initialEnd)
  const isDirty = weeklyDraftDirty || periodDialogDirty

  const guard = useGuardedFormState(isDirty, () => {
    if (schedule) setDraft(toDraftPeriods(schedule.periods))
    setPeriodDialog(null)
  })

  const load = useCallback(async () => {
    activeLoad.current?.abort()
    const controller = new AbortController()
    activeLoad.current = controller
    setLoading(true)
    setLoadError(null)
    setFeedback(null)
    try {
      const loaded = await getWorkingSchedule(staffMemberId, controller.signal)
      if (controller.signal.aborted || activeLoad.current !== controller) return
      setSchedule(loaded)
      setEditing(false)
    } catch (caught) {
      if (controller.signal.aborted || activeLoad.current !== controller) return
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setLoadError(safeScheduleError(caught, 'Работният график не може да бъде зареден.'))
    } finally {
      if (activeLoad.current === controller) {
        activeLoad.current = null
        setLoading(false)
      }
    }
  }, [staffMemberId, onAuthenticationRequired, setFeedback])

  useEffect(() => {
    void load()
    return () => {
      const controller = activeLoad.current
      activeLoad.current = null
      controller?.abort()
    }
  }, [load])

  // Entering edit mode always starts from the currently saved schedule, so a
  // previously discarded (or not-yet-saved) draft never carries over into a
  // new editing session. `schedule` is intentionally omitted from deps below
  // it is only read at the moment editing starts; depending on it would
  // reset in-progress edits whenever the schedule object identity changes.
  useEffect(() => {
    if (editing && schedule) {
      setDraft(toDraftPeriods(schedule.periods))
    }
  }, [editing])

  useLayoutEffect(() => {
    if (feedback?.kind === 'error') errorMessage.current?.focus()
  }, [feedback])

  useLayoutEffect(() => {
    if (clearWeekdayConfirmation) clearWeekdaySafeButton.current?.focus()
  }, [clearWeekdayConfirmation])

  useLayoutEffect(() => {
    if (clearAllConfirmation) clearAllSafeButton.current?.focus()
  }, [clearAllConfirmation])

  // Keyed on presence only (not on the dialog's own content), so typing in
  // the fields never steals focus back to the start-time input.
  useLayoutEffect(() => {
    if (periodDialog) periodDialogStartRef.current?.focus()
  }, [Boolean(periodDialog)])

  useLayoutEffect(() => {
    if (copyDialog) copyDialogFirstCheckboxRef.current?.focus()
  }, [Boolean(copyDialog)])

  // Collision-aware placement for the weekday overflow menu: measured from
  // the real trigger and the real (already-rendered, natural-width) menu,
  // so it works identically for every weekday column and at any viewport
  // width, rather than a fixed offset that only happens to work for one
  // column or one screen size. Runs before paint so there is no visible
  // jump from a default position to the corrected one.
  const repositionDayMenu = useCallback(() => {
    if (!openDayMenu) return
    const trigger = dayMenuButtonRefs.current.get(openDayMenu)
    const menu = dayMenuRef.current
    if (!trigger || !menu) return
    const triggerRect = trigger.getBoundingClientRect()
    const menuRect = menu.getBoundingClientRect()
    const placement = computeMenuPosition({
      triggerLeft: triggerRect.left,
      triggerRight: triggerRect.right,
      triggerTop: triggerRect.top,
      triggerBottom: triggerRect.bottom,
      menuWidth: menuRect.width,
      menuHeight: menuRect.height,
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight,
    })
    setDayMenuPosition({ top: placement.top, left: placement.left })
  }, [openDayMenu])

  useLayoutEffect(() => {
    if (openDayMenu) {
      repositionDayMenu()
    } else {
      setDayMenuPosition(null)
    }
  }, [openDayMenu, repositionDayMenu])

  useEffect(() => {
    if (!openDayMenu) return
    window.addEventListener('resize', repositionDayMenu)
    return () => window.removeEventListener('resize', repositionDayMenu)
  }, [openDayMenu, repositionDayMenu])

  // Escape restores focus to the "⋯" trigger that opened the menu; an
  // outside click or any scroll (captured, so a scroll inside a nested
  // container is caught too) only closes it, since focus has already moved
  // to whatever was clicked, or nothing was clicked at all. `position: fixed`
  // does not follow its trigger during scroll, so leaving the menu open
  // across a scroll would visibly detach it from the "⋯" button. All three
  // listeners are registered only while a menu is open and torn down on
  // close or unmount, per weekday menu instance.
  const closeDayMenu = useCallback(() => {
    if (!openDayMenu) return
    const trigger = dayMenuButtonRefs.current.get(openDayMenu)
    setOpenDayMenu(null)
    trigger?.focus()
  }, [openDayMenu])

  useEffect(() => {
    if (!openDayMenu) return

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation()
        closeDayMenu()
      }
    }
    const handlePointerDown = (event: MouseEvent) => {
      const target = event.target as Node
      const menu = dayMenuRef.current
      const trigger = dayMenuButtonRefs.current.get(openDayMenu)
      if (menu?.contains(target) || trigger?.contains(target)) return
      setOpenDayMenu(null)
    }
    const handleScroll = () => setOpenDayMenu(null)

    document.addEventListener('keydown', handleKeyDown)
    document.addEventListener('mousedown', handlePointerDown)
    window.addEventListener('scroll', handleScroll, true)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      document.removeEventListener('mousedown', handlePointerDown)
      window.removeEventListener('scroll', handleScroll, true)
    }
  }, [openDayMenu, closeDayMenu])

  const validation = useMemo(() => validateDraftPeriods(draft), [draft])
  // Sorted into the single deterministic schedule order (weekday, then start
  // time, then end time) before grouping, so every local draft operation —
  // add, edit, copy, remove, clear, or discard — is immediately reflected in
  // chronological order without waiting for a round-trip to the backend.
  const draftGroups = useMemo(() => groupPeriodsByWeekday(sortPeriodsForDisplay(draft)), [draft])

  const captureInvoker = () => {
    dialogInvoker.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
  }

  const openAddDialog = (weekday: Weekday) => {
    captureInvoker()
    setPeriodDialog({
      weekday,
      clientId: null,
      start: '',
      end: '',
      initialStart: '',
      initialEnd: '',
      errors: {},
    })
  }

  const openEditDialog = (weekday: Weekday, period: DraftPeriod) => {
    captureInvoker()
    setPeriodDialog({
      weekday,
      clientId: period.clientId,
      start: period.startTime,
      end: period.endTime,
      initialStart: period.startTime,
      initialEnd: period.endTime,
      errors: {},
    })
  }

  const closePeriodDialog = () => {
    setPeriodDialog(null)
    dialogInvoker.current?.focus()
  }

  const submitPeriodDialog = () => {
    if (!periodDialog) return
    const errors: PeriodDialogErrors = {}
    if (!periodDialog.start) errors.start = 'Въведете начален час.'
    if (!periodDialog.end) errors.end = 'Въведете краен час.'
    const clientId = periodDialog.clientId ?? nextClientId()
    if (!errors.start && !errors.end) {
      const candidate: DraftPeriod = {
        clientId,
        weekday: periodDialog.weekday,
        startTime: periodDialog.start,
        endTime: periodDialog.end,
      }
      const nextDraft = periodDialog.clientId
        ? draft.map((period) => (period.clientId === clientId ? candidate : period))
        : [...draft, candidate]
      const result = validateDraftPeriods(nextDraft)
      if (result.errorsByPeriod.has(clientId)) {
        errors.range = periodValidationMessage(result.errorsByPeriod.get(clientId)!)
      } else if (result.tooManyPeriods) {
        errors.range = `Достигнат е максималният брой от ${MAX_WORKING_PERIODS} периода за седмицата.`
      } else {
        setDraft(nextDraft)
        closePeriodDialog()
        return
      }
    }
    setPeriodDialog({ ...periodDialog, errors })
    // Focus the first invalid control: a missing end time alone points at the
    // end field; every other failure starts at the start field.
    const target = errors.start || errors.range ? periodDialogStartRef : periodDialogEndRef
    target.current?.focus()
  }

  const removePeriod = (clientId: string) => {
    setDraft((current) => current.filter((period) => period.clientId !== clientId))
  }

  const openClearWeekdayFromMenu = (weekday: Weekday) => {
    dialogInvoker.current = dayMenuButtonRefs.current.get(weekday) ?? null
    setOpenDayMenu(null)
    setClearWeekdayConfirmation(weekday)
  }

  const closeClearWeekdayDialog = () => {
    setClearWeekdayConfirmation(null)
    dialogInvoker.current?.focus()
  }

  const confirmClearWeekday = () => {
    const weekday = clearWeekdayConfirmation
    if (!weekday) return
    setDraft((current) => current.filter((period) => period.weekday !== weekday))
    setClearWeekdayConfirmation(null)
    // The "⋯" trigger that opened this dialog only exists while the weekday
    // has periods, so it no longer renders once cleared; the weekday's
    // always-present "+ Добави" control is the next meaningful focus target.
    addPeriodButtonRefs.current.get(weekday)?.focus()
  }

  const openClearAllDialog = () => {
    captureInvoker()
    setClearAllConfirmation(true)
  }

  const closeClearAllDialog = () => {
    // Refuses to close while the request is in flight, so neither Escape
    // nor a stray call can dismiss the confirmation out from under a
    // pending clear — the dialog only closes from within `confirmClearAll`
    // itself, once the request has actually finished.
    if (clearingAll) return
    setClearAllConfirmation(false)
    dialogInvoker.current?.focus()
  }

  // Clearing the complete schedule is a standalone destructive operation,
  // separate from ordinary draft editing: it never enters edit mode or
  // builds a temporary editable draft, calls the same atomic replace
  // endpoint directly with the currently loaded `schedule.version` and an
  // empty period list, and tracks its own in-flight/duplicate-submission
  // state independently of the edit-mode `saving`/`savingInProgress` used
  // by `submit`.
  const confirmClearAll = async () => {
    if (!schedule || clearingInProgress.current) return
    clearingInProgress.current = true
    setClearingAll(true)
    const publish = beginFeedback()
    try {
      const updated = await replaceWorkingSchedule(staffMemberId, {
        expectedVersion: schedule.version,
        periods: [],
      })
      if (!publish(null)) return
      setClearAllConfirmation(false)
      setSchedule(updated)
      setDraft(toDraftPeriods(updated.periods))
      setFeedback({ kind: 'success', text: 'Работният график е изчистен.' })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setClearAllConfirmation(false)
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeScheduleError(caught, 'Работният график не може да бъде изчистен.'),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      clearingInProgress.current = false
      setClearingAll(false)
    }
  }

  const openCopyDialogFromMenu = (weekday: Weekday) => {
    dialogInvoker.current = dayMenuButtonRefs.current.get(weekday) ?? null
    setOpenDayMenu(null)
    setCopyDialog({ sourceWeekday: weekday, targets: new Set() })
  }

  const closeCopyDialog = () => {
    setCopyDialog(null)
    dialogInvoker.current?.focus()
  }

  const toggleCopyTarget = (weekday: Weekday) => {
    setCopyDialog((current) => {
      if (!current) return current
      const targets = new Set(current.targets)
      if (targets.has(weekday)) {
        targets.delete(weekday)
      } else {
        targets.add(weekday)
      }
      return { ...current, targets }
    })
  }

  // Shortcuts only adjust the pending selection — they never copy by
  // themselves. The source weekday is always excluded, since it is filtered
  // out of the candidate list before the selection is built.
  const selectCopyTargets = (weekdays: readonly Weekday[]) => {
    setCopyDialog((current) =>
      current
        ? { ...current, targets: new Set(weekdays.filter((weekday) => weekday !== current.sourceWeekday)) }
        : current,
    )
  }

  const clearCopyTargets = () => {
    setCopyDialog((current) => (current ? { ...current, targets: new Set() } : current))
  }

  const confirmCopy = () => {
    if (!copyDialog || copyDialog.targets.size === 0) return
    const sourcePeriods = draftGroups[copyDialog.sourceWeekday]
    setDraft((current) => {
      const withoutTargets = current.filter((period) => !copyDialog.targets.has(period.weekday))
      const copied = Array.from(copyDialog.targets).flatMap((weekday) =>
        sourcePeriods.map((period) => ({
          clientId: nextClientId(),
          weekday,
          startTime: period.startTime,
          endTime: period.endTime,
        })),
      )
      return [...withoutTargets, ...copied]
    })
    closeCopyDialog()
  }

  const cancel = () => {
    guard.guard(() => {
      if (schedule) setDraft(toDraftPeriods(schedule.periods))
      setEditing(false)
      setOpenDayMenu(null)
      setPeriodDialog(null)
      setCopyDialog(null)
      setClearWeekdayConfirmation(null)
      setClearAllConfirmation(false)
      setFeedback(null)
    })
  }

  const submit = async () => {
    if (savingInProgress.current || !schedule) return
    if (!validation.valid) {
      errorSummary.current?.focus()
      return
    }
    savingInProgress.current = true
    setSaving(true)
    const publish = beginFeedback()
    try {
      const updated = await replaceWorkingSchedule(staffMemberId, {
        expectedVersion: schedule.version,
        periods: sortPeriodsForDisplay(draft).map(stripClientId),
      })
      if (!publish(null)) return
      guard.unregisterDirty()
      setSchedule(updated)
      setDraft(toDraftPeriods(updated.periods))
      setEditing(false)
      setFeedback({ kind: 'success', text: 'Работният график е запазен.' })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeScheduleError(caught, 'Работният график не може да бъде запазен.'),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      savingInProgress.current = false
      setSaving(false)
    }
  }

  if (loading) {
    return (
      <p className="business-list-state" aria-live="polite">
        Зареждане на работния график…
      </p>
    )
  }

  if (loadError || !schedule) {
    return (
      <div className="feedback-action-layout">
        <div className="status-message status-error" role="alert">
          <p>{loadError ?? 'Работният график не може да бъде зареден.'}</p>
        </div>
        <Button type="button" variant="secondary" onClick={() => void load()}>
          Зареди отново
        </Button>
      </div>
    )
  }

  const viewGroups = groupPeriodsByWeekday(sortPeriodsForDisplay(schedule.periods))

  return (
    <div className="feedback-action-layout schedule-editor">
      {!canEdit && !readOnly && (
        <p className="section-introduction">
          Работният график на неактивен член на екипа може само да бъде преглеждан.
        </p>
      )}

      {editing && validation.tooManyPeriods && (
        <p ref={errorSummary} className="status-message status-error" role="alert" tabIndex={-1}>
          Достигнат е максималният брой от {MAX_WORKING_PERIODS} периода за седмицата.
        </p>
      )}

      <div className="schedule-grid">
        {WEEKDAY_ORDER.map((weekday) => {
          const label = WEEKDAY_LABELS[weekday]
          const sentenceLabel = WEEKDAY_SENTENCE_LABELS[weekday]
          const draftDayPeriods = draftGroups[weekday]
          const viewDayPeriods = viewGroups[weekday]
          const dayPeriods = editing ? draftDayPeriods : viewDayPeriods

          return (
            <section
              key={weekday}
              className="schedule-day"
              aria-labelledby={`schedule-day-heading-${weekday}`}
            >
              <div className="schedule-day-header">
                <h3 id={`schedule-day-heading-${weekday}`}>{label}</h3>
                {editing && draftDayPeriods.length > 0 && (
                  <div className="schedule-day-menu-wrapper">
                    <button
                      type="button"
                      className="schedule-day-menu-trigger"
                      aria-haspopup="menu"
                      aria-expanded={openDayMenu === weekday}
                      ref={(element) => {
                        if (element) dayMenuButtonRefs.current.set(weekday, element)
                        else dayMenuButtonRefs.current.delete(weekday)
                      }}
                      onClick={() => setOpenDayMenu((current) => (current === weekday ? null : weekday))}
                    >
                      <span aria-hidden="true">⋯</span>
                      <span className="visually-hidden">Още действия за {sentenceLabel}</span>
                    </button>
                    {openDayMenu === weekday && (
                      <div
                        ref={dayMenuRef}
                        role="menu"
                        className="schedule-day-menu"
                        aria-label={`Действия за ${sentenceLabel}`}
                        style={
                          dayMenuPosition
                            ? { top: dayMenuPosition.top, left: dayMenuPosition.left, visibility: 'visible' }
                            : { top: 0, left: 0, visibility: 'hidden' }
                        }
                      >
                        <button
                          type="button"
                          role="menuitem"
                          className="schedule-day-menu-item"
                          onClick={() => openClearWeekdayFromMenu(weekday)}
                        >
                          Изчисти деня
                        </button>
                        <button
                          type="button"
                          role="menuitem"
                          className="schedule-day-menu-item"
                          onClick={() => openCopyDialogFromMenu(weekday)}
                        >
                          Копирай графика
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </div>

              <div className="schedule-day-body">
                {dayPeriods.length === 0 && <p className="schedule-day-empty">Почивен ден</p>}
                {!editing
                  ? viewDayPeriods.map((period, index) => (
                      <span
                        key={`${period.weekday}-${period.startTime}-${period.endTime}-${index}`}
                        className="schedule-period-chip"
                      >
                        {formatPeriodRange(period.startTime, period.endTime)}
                      </span>
                    ))
                  : draftDayPeriods.map((period) => (
                      <span key={period.clientId} className="schedule-period-chip schedule-period-chip--editable">
                        <button
                          type="button"
                          className="schedule-period-chip-label"
                          onClick={() => openEditDialog(weekday, period)}
                        >
                          {formatPeriodRange(period.startTime, period.endTime)}
                          <span aria-hidden="true">✎</span>
                          <span className="visually-hidden">
                            Редактирай периода {formatPeriodRange(period.startTime, period.endTime)} за{' '}
                            {sentenceLabel}
                          </span>
                        </button>
                        <button
                          type="button"
                          className="schedule-period-chip-remove"
                          aria-label={`Премахни периода ${formatPeriodRange(period.startTime, period.endTime)} за ${sentenceLabel}`}
                          onClick={() => removePeriod(period.clientId)}
                        >
                          <span aria-hidden="true">×</span>
                        </button>
                      </span>
                    ))}
                {editing && (
                  <button
                    type="button"
                    className="schedule-add-period"
                    ref={(element) => {
                      if (element) addPeriodButtonRefs.current.set(weekday, element)
                      else addPeriodButtonRefs.current.delete(weekday)
                    }}
                    onClick={() => openAddDialog(weekday)}
                  >
                    + Добави
                  </button>
                )}
              </div>
            </section>
          )
        })}
      </div>

      {feedback && (
        <div
          ref={feedback.kind === 'error' ? errorMessage : undefined}
          className={`status-message status-${feedback.kind}`}
          role={feedback.kind === 'error' ? 'alert' : 'status'}
          aria-live={feedback.kind === 'success' ? 'polite' : undefined}
          tabIndex={feedback.kind === 'error' ? -1 : undefined}
        >
          <p>{feedback.text}</p>
        </div>
      )}
      {feedback?.reload && (
        <Button type="button" variant="secondary" onClick={() => guard.guard(() => void load())}>
          Зареди актуалните данни
        </Button>
      )}

      {canEdit && (
        <div className="schedule-page-actions">
          {editing ? (
            // Editing is responsible only for draft changes and
            // cancellation; clearing the complete schedule is a separate
            // destructive operation, available only from read-only mode
            // below.
            <div className="action-group">
              <Button ref={saveButtonRef} type="button" disabled={saving} onClick={() => void submit()}>
                {saving ? 'Запазване…' : 'Запази промените'}
              </Button>
              <Button type="button" variant="secondary" disabled={saving} onClick={cancel}>
                Отказ
              </Button>
            </div>
          ) : (
            <div className="action-group">
              <Button
                type="button"
                disabled={clearingAll}
                onClick={() => {
                  setFeedback(null)
                  setDraft(toDraftPeriods(schedule.periods))
                  setEditing(true)
                }}
              >
                Редактирай графика
              </Button>
              <Button
                type="button"
                variant="destructive"
                disabled={clearingAll || schedule.periods.length === 0}
                onClick={openClearAllDialog}
              >
                {clearingAll ? 'Изчистване…' : 'Изчисти графика'}
              </Button>
            </div>
          )}
        </div>
      )}

      {periodDialog && (
        <div
          className="schedule-dialog-overlay"
          onKeyDown={(event) => {
            if (event.key === 'Escape' && !periodDialogDirty) closePeriodDialog()
          }}
        >
          <div
            className="confirmation-panel schedule-dialog-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="period-dialog-heading"
          >
            <h4 id="period-dialog-heading">
              {periodDialog.clientId
                ? `Редактиране на работно време за ${WEEKDAY_SENTENCE_LABELS[periodDialog.weekday]}`
                : `Добавяне на работно време за ${WEEKDAY_SENTENCE_LABELS[periodDialog.weekday]}`}
            </h4>
            <div className="schedule-dialog-fields">
              <div className="form-field">
                <label htmlFor="period-dialog-start">Начален час</label>
                <input
                  {...fieldControlProps(
                    'period-dialog-start',
                    periodDialog.errors.start ?? periodDialog.errors.range,
                  )}
                  aria-describedby={
                    periodDialog.errors.start
                      ? 'period-dialog-start-error'
                      : periodDialog.errors.range
                        ? 'period-dialog-range-error'
                        : undefined
                  }
                  ref={periodDialogStartRef}
                  type="time"
                  value={periodDialog.start}
                  onChange={(event) =>
                    setPeriodDialog((current) =>
                      current
                        ? {
                            ...current,
                            start: event.target.value,
                            errors: current.errors.end ? { end: current.errors.end } : {},
                          }
                        : current,
                    )
                  }
                />
                <FieldError id="period-dialog-start" error={periodDialog.errors.start} />
              </div>
              <div className="form-field">
                <label htmlFor="period-dialog-end">Краен час</label>
                <input
                  {...fieldControlProps(
                    'period-dialog-end',
                    periodDialog.errors.end ?? periodDialog.errors.range,
                  )}
                  aria-describedby={
                    periodDialog.errors.end
                      ? 'period-dialog-end-error'
                      : periodDialog.errors.range
                        ? 'period-dialog-range-error'
                        : undefined
                  }
                  ref={periodDialogEndRef}
                  type="time"
                  value={periodDialog.end}
                  onChange={(event) =>
                    setPeriodDialog((current) =>
                      current
                        ? {
                            ...current,
                            end: event.target.value,
                            errors: current.errors.start ? { start: current.errors.start } : {},
                          }
                        : current,
                    )
                  }
                />
                <FieldError id="period-dialog-end" error={periodDialog.errors.end} />
              </div>
            </div>
            <FieldError id="period-dialog-range" error={periodDialog.errors.range} />
            <div className="action-group">
              <Button type="button" onClick={submitPeriodDialog}>
                {periodDialog.clientId ? 'Запази' : 'Добави'}
              </Button>
              <Button type="button" variant="secondary" onClick={closePeriodDialog}>
                Отказ
              </Button>
            </div>
          </div>
        </div>
      )}

      {copyDialog && (
        <div
          className="schedule-dialog-overlay"
          onKeyDown={(event) => {
            if (event.key === 'Escape') closeCopyDialog()
          }}
        >
          <div
            className="confirmation-panel schedule-dialog-panel schedule-copy-dialog-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="copy-dialog-heading"
            aria-describedby="copy-dialog-description"
          >
            <h4 id="copy-dialog-heading">
              Копиране на график от {WEEKDAY_SENTENCE_LABELS[copyDialog.sourceWeekday]}
            </h4>
            <p id="copy-dialog-description">
              Изберете дните, в които да се копира графикът за{' '}
              {WEEKDAY_SENTENCE_LABELS[copyDialog.sourceWeekday]}.
            </p>
            <div className="schedule-copy-shortcuts">
              <button
                type="button"
                className="schedule-copy-shortcut"
                onClick={() => selectCopyTargets(MONDAY_TO_FRIDAY)}
              >
                Понеделник–петък
              </button>
              <button
                type="button"
                className="schedule-copy-shortcut"
                onClick={() => selectCopyTargets(WEEKDAY_ORDER)}
              >
                Всички останали дни
              </button>
              <button
                type="button"
                className="schedule-copy-shortcut"
                disabled={copyDialog.targets.size === 0}
                onClick={clearCopyTargets}
              >
                Изчисти избора
              </button>
            </div>
            <div className="schedule-copy-targets">
              {WEEKDAY_ORDER.filter((weekday) => weekday !== copyDialog.sourceWeekday).map(
                (weekday, index) => {
                  const targetPeriods = draftGroups[weekday]
                  return (
                    <label key={weekday} className="schedule-copy-target">
                      <input
                        type="checkbox"
                        ref={index === 0 ? copyDialogFirstCheckboxRef : undefined}
                        checked={copyDialog.targets.has(weekday)}
                        onChange={() => toggleCopyTarget(weekday)}
                      />
                      {WEEKDAY_LABELS[weekday]}
                      {targetPeriods.length > 0 && (
                        <span className="schedule-copy-target-warning">
                          (ще замени {periodsCountLabel(targetPeriods.length)})
                        </span>
                      )}
                    </label>
                  )
                },
              )}
            </div>
            <div className="action-group">
              <Button type="button" disabled={copyDialog.targets.size === 0} onClick={confirmCopy}>
                {Array.from(copyDialog.targets).some((weekday) => draftGroups[weekday].length > 0)
                  ? 'Копирай и замени'
                  : 'Копирай'}
              </Button>
              <Button type="button" variant="secondary" onClick={closeCopyDialog}>
                Отказ
              </Button>
            </div>
          </div>
        </div>
      )}

      {clearWeekdayConfirmation && (
        <div
          className="schedule-dialog-overlay"
          onKeyDown={(event) => {
            if (event.key === 'Escape') closeClearWeekdayDialog()
          }}
        >
          <div
            className="confirmation-panel schedule-dialog-panel"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="schedule-clear-weekday-heading"
            aria-describedby="schedule-clear-weekday-description-1 schedule-clear-weekday-description-2"
          >
            <h4 id="schedule-clear-weekday-heading">
              Изчистване на графика за {WEEKDAY_SENTENCE_LABELS[clearWeekdayConfirmation]}
            </h4>
            <p id="schedule-clear-weekday-description-1">
              Всички работни часове за {WEEKDAY_SENTENCE_LABELS[clearWeekdayConfirmation]} ще бъдат
              премахнати.
            </p>
            <p id="schedule-clear-weekday-description-2">Сигурни ли сте, че искате да продължите?</p>
            <div className="action-group">
              <Button type="button" variant="destructive" onClick={confirmClearWeekday}>
                Изчисти графика за деня
              </Button>
              <Button
                ref={clearWeekdaySafeButton}
                type="button"
                variant="secondary"
                onClick={closeClearWeekdayDialog}
              >
                Отказ
              </Button>
            </div>
          </div>
        </div>
      )}

      {clearAllConfirmation && (
        <div
          className="schedule-dialog-overlay"
          onKeyDown={(event) => {
            if (event.key === 'Escape') closeClearAllDialog()
          }}
        >
          <div
            className="confirmation-panel schedule-dialog-panel"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="schedule-clear-all-heading"
            aria-describedby="schedule-clear-all-description-1 schedule-clear-all-description-2"
          >
            <h4 id="schedule-clear-all-heading">Изчистване на графика</h4>
            <p id="schedule-clear-all-description-1">
              Всички работни часове от седмичния график ще бъдат премахнати.
            </p>
            <p id="schedule-clear-all-description-2">Сигурни ли сте, че искате да продължите?</p>
            <div className="action-group">
              <Button
                type="button"
                variant="destructive"
                disabled={clearingAll}
                onClick={() => void confirmClearAll()}
              >
                {clearingAll ? 'Изчистване…' : 'Изчисти графика'}
              </Button>
              <Button
                ref={clearAllSafeButton}
                type="button"
                variant="secondary"
                disabled={clearingAll}
                onClick={closeClearAllDialog}
              >
                Отказ
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
