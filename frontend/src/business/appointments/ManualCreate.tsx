import { useCallback, useEffect, useLayoutEffect, useReducer, useRef, useState } from 'react'
import { Button } from '../../ui/Button'
import { useGuardedFormState, type GuardNotice } from '../../ui/UnsavedChangesGuard'
import {
  SCHEDULE_MAX_DATE,
  SCHEDULE_MIN_DATE,
  isCanonicalDate,
} from '../../navigation'
import { localDateIn } from '../schedule/exceptions/presentation'
import { formatTimeRange } from '../calendar/presentation'
import type { ServiceSummary } from '../services/api'
import {
  createAppointment,
  getCreationAvailability,
  getCreationOptions,
  loadEveryActiveService,
} from './api'
import {
  FAILURE_TEXT,
  classifyCreateFailure,
  isAuthenticationRequired,
  isUnresolved,
  readFailureKind,
  type FailureKind,
} from './errors'
import {
  INITIAL_STATE,
  STEPS,
  buildBody,
  canonicalIds,
  isDirty,
  noteError,
  normalizeNote,
  reduce,
  selectedServices,
  selectionKey,
  totalsOf,
  type Step,
} from './model'
import {
  type ReviewData,
  CustomerStep,
  DateTimeStep,
  NoteStep,
  ReviewStep,
  ServicesStep,
  StaffStep,
} from './steps'
import { dataFor, useKeyedLoad } from './useKeyedLoad'

type ManualCreateProps = {
  // Injected clock, only used to name the Business "today" (the earliest date offered).
  now?: () => Date
  // The date selected in the Calendar the owner came from (a canonical date), or null; it only proposes the date of a
  // new draft and is independent of the Calendar state the form returns to.
  initialDate?: string | null
  onCancel: () => void
  onCreated: (appointmentId: string) => void
  onAuthenticationRequired: (detail: string) => void
}

type Notice = { kind: FailureKind | 'info'; text: string }

type Errors = Partial<Record<'customer' | 'services' | 'staff' | 'date' | 'start' | 'note', string>>

const HEADINGS: Record<Step, string> = {
  customer: 'Клиент',
  services: 'Избор на услуги',
  staff: 'Избор на служител',
  datetime: 'Дата и час',
  note: 'Вътрешна бележка',
  review: 'Преглед и потвърждение',
}

const LEAVE_NOTICE: GuardNotice = {
  label: 'Несъздаден час',
  lines: ['Часът още не е създаден.', 'Ако напуснете, въведеното ще бъде загубено.'],
}

// Shown when leaving elsewhere while the outcome of the creation is unknown.
const UNRESOLVED_NOTICE: GuardNotice = {
  label: 'Неясен резултат',
  lines: ['Не е ясно дали часът е създаден.', 'Проверете графика, преди да създавате часа отново.'],
}

const defaultNow = () => new Date()

/**
 * The owner-only manual-create flow (ADR-0028 section 4): six steps, one atomic POST. The whole form
 * lives in this component's memory: nothing of it reaches the URL, history state, storage, the title,
 * or the console. The server stays the authority for duration, price, eligibility, and availability.
 */
export function ManualCreate({
  now = defaultNow,
  initialDate = null,
  onCancel,
  onCreated,
  onAuthenticationRequired,
}: ManualCreateProps) {
  const [state, dispatch] = useReducer(reduce, INITIAL_STATE)
  const [errors, setErrors] = useState<Errors>({})
  const [notice, setNotice] = useState<Notice | null>(null)
  const [blocked, setBlocked] = useState(false)
  // Sticky: once the outcome of the POST cannot be ruled out, this form can never send another one.
  const [unresolved, setUnresolved] = useState(false)
  const unresolvedRef = useRef(false)
  const snapshotRef = useRef<ReviewData | null>(null)
  // The facts that were submitted, kept as they were shown while the outcome is unknown.
  const [submitted, setSubmitted] = useState<ReviewData | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [catalogReload, setCatalogReload] = useState(0)
  const [optionsReload, setOptionsReload] = useState(0)
  const [slotsReload, setSlotsReload] = useState(0)
  const [focusTick, setFocusTick] = useState(0)

  // True once the owner has set the date field to nothing: the empty date is then theirs, not an unset default.
  const dateClearedByOwner = useRef(false)
  const submitting_ = useRef(false)
  const closed = useRef(false)
  const pendingCatalog = useRef<((catalog: ServiceSummary[]) => void) | null>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const noticeRef = useRef<HTMLDivElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const focusRef = useRef<HTMLElement | null>(null)
  const firstStep = useRef(true)
  const handlers = useRef({ onAuthenticationRequired })
  handlers.current = { onAuthenticationRequired }

  const guard = useGuardedFormState(
    isDirty(state),
    () => setErrors({}),
    unresolved ? UNRESOLVED_NOTICE : LEAVE_NOTICE,
  )

  // Setup resets the flag: React StrictMode (the development server) runs every effect twice, and a flag that
  // is only ever set to true made the form ignore every answer of the server (Phase 11 review).
  useEffect(() => {
    closed.current = false
    return () => {
      closed.current = true
    }
  }, [])

  // ---- reads ---------------------------------------------------------------------------------

  const onReadFailure = useCallback((error: unknown) => {
    if (isAuthenticationRequired(error)) {
      handlers.current.onAuthenticationRequired(error.detail)
      return
    }
    const kind = readFailureKind(error)
    if (kind !== null) {
      setBlocked(true)
      setNotice({ kind, text: FAILURE_TEXT[kind] })
    }
  }, [])

  const catalog = useKeyedLoad('catalog', (signal) => loadEveryActiveService(signal), catalogReload, onReadFailure)
  const services = dataFor(catalog, 'catalog')
  const selected = services ? selectedServices(services, state.serviceIds) : []
  const totals = totalsOf(selected)

  // The options belong to the committed Service set: they are read from the StaffMember step on, and
  // kept while an earlier, unrelated step (the Customer) is edited from the review.
  const committed = (state.step !== 'customer' && state.step !== 'services') || state.staffMemberId !== null
  const optionsKey = state.serviceIds.length > 0 && committed ? selectionKey(state.serviceIds) : null
  const options = useKeyedLoad(
    optionsKey,
    (signal) => getCreationOptions(canonicalIds(state.serviceIds), signal),
    optionsReload,
    onReadFailure,
  )
  const optionsData = dataFor(options, optionsKey)

  const slotsKey =
    state.step === 'datetime' && state.staffMemberId !== null && state.date !== '' && optionsKey !== null
      ? `${optionsKey}|${state.staffMemberId}|${state.date}`
      : null
  const slots = useKeyedLoad(
    slotsKey,
    (signal) =>
      getCreationAvailability(canonicalIds(state.serviceIds), state.staffMemberId as string, state.date, signal),
    slotsReload,
    onReadFailure,
  )
  const slotsData = dataFor(slots, slotsKey)

  // The Services the server no longer offers leave the selection (and, through the model, everything
  // that depended on it); a pending recovery step runs once on every fresh catalog.
  useEffect(() => {
    if (!services || unresolvedRef.current) return
    const present = state.serviceIds.filter((id) => services.some((service) => service.id === id))
    if (present.length !== state.serviceIds.length) {
      dispatch({ type: 'services', ids: present })
      dispatch({ type: 'go', step: 'services', fromReview: true })
      setNotice({ kind: 'service-unavailable', text: FAILURE_TEXT['service-unavailable'] })
    }
    const pending = pendingCatalog.current
    pendingCatalog.current = null
    pending?.(services)
    // Only a new catalog may trigger this; the current selection is read as of that moment.
  }, [services])

  // A StaffMember the server no longer offers for the selection, or terms that no longer add up to the
  // authoritative duration, are stale choices: they are dropped or the catalog is read again.
  useEffect(() => {
    if (!optionsData || unresolvedRef.current) return
    if (
      state.staffMemberId !== null &&
      !optionsData.staffMembers.some((member) => member.id === state.staffMemberId)
    ) {
      dispatch({ type: 'staff', staffMemberId: null })
      dispatch({ type: 'go', step: 'staff', fromReview: true })
      setNotice({ kind: 'staff-unavailable', text: FAILURE_TEXT['staff-unavailable'] })
    }
    if (services && totals.minutes !== optionsData.durationMinutes) {
      refreshTerms()
    }
    // Only fresh options may trigger this.
  }, [optionsData])

  // The date of a new draft: the Calendar's date when it is today or later in the Business timezone, otherwise today
  // there. It is set once the authoritative timezone is known (never from the browser's), only while the date is
  // empty and the owner has not emptied it, so a later choice is never overwritten.
  const businessTimezone = optionsData?.timezone
  useEffect(() => {
    if (!businessTimezone || state.date !== '' || dateClearedByOwner.current || unresolvedRef.current) return
    const today = localDateIn(now(), businessTimezone)
    if (!today) return
    const proposed =
      initialDate !== null && isCanonicalDate(initialDate) && initialDate >= today ? initialDate : today
    if (proposed >= SCHEDULE_MIN_DATE && proposed <= SCHEDULE_MAX_DATE) dispatch({ type: 'date', date: proposed })
  }, [businessTimezone, state.date])

  // An offered start the server no longer lists is not kept.
  useEffect(() => {
    if (!unresolvedRef.current && slotsData && state.start && !slotsData.slots.some((slot) => slot.start === state.start?.start)) {
      dispatch({ type: 'start', start: null })
    }
  }, [slotsData])

  /** Reads the Service terms again; a changed total duration invalidates the chosen start. */
  function refreshTerms() {
    const minutesBefore = totals.minutes
    const hadStart = state.start !== null
    pendingCatalog.current = (fresh) => {
      const after = totalsOf(selectedServices(fresh, state.serviceIds)).minutes
      if (after !== minutesBefore && hadStart) {
        dispatch({ type: 'start', start: null })
        dispatch({ type: 'go', step: 'datetime', fromReview: true })
      }
    }
    setCatalogReload((token) => token + 1)
  }

  // ---- focus ---------------------------------------------------------------------------------

  useLayoutEffect(() => {
    if (firstStep.current) {
      firstStep.current = false
      return
    }
    headingRef.current?.focus()
  }, [state.step])

  useLayoutEffect(() => {
    if (notice) noticeRef.current?.focus()
  }, [notice])

  useLayoutEffect(() => {
    if (focusTick === 0) return
    if (state.step === 'datetime' && errors.start && !errors.date) {
      bodyRef.current?.querySelector<HTMLElement>('[data-first-slot]')?.focus()
      return
    }
    focusRef.current?.focus()
    // Focus is requested by the tick only.
  }, [focusTick])

  // ---- navigation between steps ---------------------------------------------------------------------

  function validate(): Errors {
    const found: Errors = {}
    switch (state.step) {
      case 'customer':
        if (!state.customer) found.customer = 'Изберете клиент.'
        break
      case 'services':
        if (state.serviceIds.length === 0) found.services = 'Изберете поне една услуга.'
        break
      case 'staff':
        if (state.staffMemberId === null) found.staff = 'Изберете член на екипа.'
        break
      case 'datetime':
        if (state.date === '') found.date = 'Изберете дата.'
        else if (state.start === null) found.start = 'Изберете час.'
        break
      case 'note': {
        const problem = noteError(state.note)
        if (problem) found.note = problem
        break
      }
      default:
        break
    }
    return found
  }

  function next() {
    if (unresolvedRef.current) return
    const found = validate()
    if (Object.keys(found).length > 0) {
      setErrors(found)
      setFocusTick((tick) => tick + 1)
      return
    }
    setErrors({})
    setNotice(null)
    dispatch({ type: 'advance' })
  }

  function back() {
    if (unresolvedRef.current) return
    setErrors({})
    setNotice(null)
    dispatch({ type: 'back' })
  }

  function edit(step: Step) {
    if (unresolvedRef.current) return
    setErrors({})
    setNotice(null)
    dispatch({ type: 'go', step, fromReview: true })
  }

  // ---- submit ---------------------------------------------------------------------------------------

  const staffName = optionsData?.staffMembers.find((member) => member.id === state.staffMemberId)?.displayName
  const ready =
    services !== null &&
    optionsData !== null &&
    state.customer !== null &&
    selected.length === state.serviceIds.length &&
    state.staffMemberId !== null &&
    staffName !== undefined &&
    state.start !== null &&
    !blocked

  async function submit() {
    if (unresolvedRef.current || submitting_.current || !ready || !state.customer || !state.staffMemberId || !state.start) return
    submitting_.current = true
    setSubmitting(true)
    setNotice(null)
    const snapshot: ReviewData | null =
      staffName !== undefined && timezone
        ? {
            customer: state.customer,
            services: selected,
            staffName,
            date: state.date,
            time: formatTimeRange(state.start.start, state.start.end, timezone),
            note: normalizeNote(state.note),
          }
        : null
    snapshotRef.current = snapshot
    try {
      const created = await createAppointment(
        buildBody(state.customer.id, state.staffMemberId, state.start.start, selected, state.note),
      )
      // A response that arrives after the form was left must not navigate.
      if (closed.current) return
      closed.current = true
      guard.unregisterDirty()
      onCreated(created.id)
    } catch (error) {
      if (closed.current) return
      if (isAuthenticationRequired(error)) {
        handlers.current.onAuthenticationRequired(error.detail)
        return
      }
      recover(classifyCreateFailure(error))
    } finally {
      submitting_.current = false
      if (!closed.current) setSubmitting(false)
    }
  }

  /** Every documented result of the create, kept distinct; nothing here repeats the POST. */
  function recover(kind: FailureKind) {
    const text = FAILURE_TEXT[kind]
    if (isUnresolved(kind)) {
      // Freeze the submitted form: nothing below runs, nothing is reset, nothing can be sent again.
      unresolvedRef.current = true
      setUnresolved(true)
      setSubmitted(snapshotRef.current)
      setNotice({ kind, text })
      return
    }
    switch (kind) {
      case 'slot-unavailable':
        dispatch({ type: 'start', start: null })
        dispatch({ type: 'go', step: 'datetime', fromReview: true })
        setSlotsReload((token) => token + 1)
        break
      case 'staff-unavailable':
        dispatch({ type: 'staff', staffMemberId: null })
        dispatch({ type: 'go', step: 'staff', fromReview: true })
        setOptionsReload((token) => token + 1)
        break
      case 'service-unavailable':
        dispatch({ type: 'go', step: 'services', fromReview: true })
        setCatalogReload((token) => token + 1)
        setOptionsReload((token) => token + 1)
        break
      case 'review-changed':
        // The authoritative terms are read again and shown on the review; the owner confirms again.
        refreshTerms()
        setOptionsReload((token) => token + 1)
        break
      case 'customer-missing':
        dispatch({ type: 'customer', customer: null })
        dispatch({ type: 'go', step: 'customer', fromReview: true })
        break
      case 'access':
      case 'business-not-active':
      case 'business-suspended':
        setBlocked(true)
        break
      default:
        break
    }
    setNotice({ kind, text })
  }

  /** The recovery of an unresolved outcome: the authoritative calendar, without the ordinary prompt. */
  function leaveToCalendar() {
    guard.unregisterDirty()
    onCancel()
  }

  // ---- rendering -----------------------------------------------------------------------------------------

  const stepIndex = STEPS.indexOf(state.step)
  const timezone = optionsData?.timezone
  const businessToday = timezone ? (localDateIn(now(), timezone) ?? undefined) : undefined
  const isReview = state.step === 'review'

  let body
  switch (state.step) {
    case 'customer':
      body = (
        <CustomerStep
          customer={state.customer}
          error={errors.customer}
          focusRef={focusRef}
          onChoose={(customer) => {
            setErrors({})
            dispatch({ type: 'customer', customer })
          }}
          onAuthenticationRequired={onAuthenticationRequired}
        />
      )
      break
    case 'services':
      body = (
        <ServicesStep
          catalog={catalog}
          serviceIds={state.serviceIds}
          error={errors.services}
          focusRef={focusRef}
          onToggle={(id) => {
            setErrors({})
            dispatch({
              type: 'services',
              ids: state.serviceIds.includes(id)
                ? state.serviceIds.filter((existing) => existing !== id)
                : [...state.serviceIds, id],
            })
          }}
          onRetry={() => setCatalogReload((token) => token + 1)}
        />
      )
      break
    case 'staff':
      body = (
        <StaffStep
          options={options}
          optionsKey={optionsKey}
          staffMemberId={state.staffMemberId}
          error={errors.staff}
          focusRef={focusRef}
          onChoose={(staffMemberId) => {
            setErrors({})
            dispatch({ type: 'staff', staffMemberId })
          }}
          onChangeServices={() => edit('services')}
          onRetry={() => setOptionsReload((token) => token + 1)}
        />
      )
      break
    case 'datetime':
      body = (
        <DateTimeStep
          date={state.date}
          min={businessToday}
          start={state.start?.start ?? null}
          slots={slots}
          slotsKey={slotsKey}
          timezone={timezone}
          dateError={errors.date}
          startError={errors.start}
          focusRef={focusRef}
          onDate={(value) => {
            setErrors({})
            const valid = isCanonicalDate(value) && value >= SCHEDULE_MIN_DATE && value <= SCHEDULE_MAX_DATE
            dateClearedByOwner.current = !valid
            dispatch({ type: 'date', date: valid ? value : '' })
          }}
          onStart={(start) => {
            setErrors({})
            dispatch({ type: 'start', start })
          }}
          onRetry={() => setSlotsReload((token) => token + 1)}
        />
      )
      break
    case 'note':
      body = (
        <NoteStep
          note={state.note}
          error={errors.note}
          focusRef={focusRef}
          onChange={(note) => {
            setErrors({})
            dispatch({ type: 'note', note })
          }}
        />
      )
      break
    case 'review':
      if (unresolved) {
        // The submitted facts, exactly as shown, without any control that could change or send them.
        body = submitted ? <ReviewStep data={submitted} /> : null
      } else if (ready && state.customer && state.start && staffName !== undefined && timezone) {
        body = (
          <ReviewStep
            data={{
              customer: state.customer,
              services: selected,
              staffName,
              date: state.date,
              time: formatTimeRange(state.start.start, state.start.end, timezone),
              note: normalizeNote(state.note),
            }}
            onEdit={edit}
          />
        )
      } else {
        body = (
          <p className="business-list-state" aria-live="polite" aria-busy={!blocked}>
            {blocked ? 'Прегледът не е наличен.' : 'Зареждане на прегледа…'}
          </p>
        )
      }
      break
  }

  return (
    <div className="platform-content manual-create">
      <div className="business-page-actions">
        <Button
          type="button"
          variant="secondary"
          disabled={submitting}
          onClick={unresolved ? leaveToCalendar : onCancel}
        >
          Обратно към графика
        </Button>
      </div>

      <section className="manual-step" aria-labelledby="manual-step-heading">
        <p className="manual-progress">
          Стъпка {stepIndex + 1} от {STEPS.length}
        </p>
        <h2 id="manual-step-heading" ref={headingRef} tabIndex={-1}>
          {HEADINGS[state.step]}
        </h2>
        <div ref={bodyRef}>{body}</div>

        {notice && (
          <div className="feedback-action-layout">
            <div
              ref={noticeRef}
              className={notice.kind === 'info' ? 'status-message status-info' : 'status-message status-error'}
              role="alert"
              tabIndex={-1}
            >
              <p>{notice.text}</p>
            </div>
            {(blocked || unresolved) && (
              <div className="feedback-action-controls">
                <Button
                  type="button"
                  variant={unresolved ? 'primary' : 'secondary'}
                  disabled={submitting}
                  onClick={unresolved ? leaveToCalendar : onCancel}
                >
                  Към графика
                </Button>
              </div>
            )}
          </div>
        )}

        {!unresolved && (
        <div className="manual-actions action-group">
          {stepIndex > 0 && (
            <Button type="button" variant="secondary" disabled={submitting} onClick={back}>
              Назад
            </Button>
          )}
          {isReview ? (
            <Button type="button" disabled={!ready || submitting} onClick={() => void submit()}>
              Създай час
            </Button>
          ) : (
            <Button type="button" disabled={blocked} onClick={next}>
              Напред
            </Button>
          )}
        </div>
        )}
        {submitting && (
          <p className="visually-hidden" role="status">
            Създаване на часа…
          </p>
        )}
      </section>
    </div>
  )
}
