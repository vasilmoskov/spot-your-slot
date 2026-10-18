import { useCallback, useEffect, useLayoutEffect, useReducer, useRef, useState } from 'react'
import { Button } from '../../ui/Button'
import { useGuardedFormState, type GuardNotice } from '../../ui/UnsavedChangesGuard'
import { SCHEDULE_MAX_DATE, SCHEDULE_MIN_DATE, isCanonicalDate, type AppointmentOrigin } from '../../navigation'
import { formatDateOnlyLong, parseInstant } from '../../public/booking/dates'
import { localDateIn } from '../schedule/exceptions/presentation'
import { getAppointment, type AppointmentDetails } from '../calendar/api'
import { businessDateOf, formatTimeRange } from '../calendar/presentation'
import {
  getRescheduleAvailability,
  getRescheduleOptions,
  rescheduleAppointment,
  type RescheduleOptions,
  type RescheduledAppointment,
} from './rescheduleApi'
import {
  RESCHEDULE_TEXT,
  classifyRescheduleFailure,
  isAuthenticationRequired,
  isUnresolved,
  readFailureOf,
  type RescheduleFailure,
} from './rescheduleErrors'
import {
  STEPS,
  buildBody,
  initialProposal,
  isDirty,
  isEffective,
  reduce,
  staffChanged,
  type Original,
  type Step,
} from './rescheduleModel'
import { AppointmentContext, ReviewStep, StaffStep, type ReviewData } from './rescheduleSteps'
import { DateTimeStep, LoadError } from './steps'
import { dataFor, useKeyedLoad, type KeyedLoad } from './useKeyedLoad'

type RescheduleProps = {
  appointmentId: string
  origin: AppointmentOrigin
  // Injected clock, only used to name the Business "today" and to tell whether the visit has started.
  now?: () => Date
  onBackToDetail: () => void
  onLeaveToOrigin: () => void
  onRescheduled: () => void
  onAuthenticationRequired: (detail: string) => void
}

type Notice = { kind: RescheduleFailure | 'info'; text: string }

type Errors = Partial<Record<'staff' | 'date' | 'start', string>>

const HEADINGS: Record<Step, string> = {
  staff: 'Избор на служител',
  datetime: 'Дата и час',
  review: 'Преглед и потвърждение',
}

const LEAVE_NOTICE: GuardNotice = {
  label: 'Непотвърдена промяна',
  lines: ['Промяната на часа още не е направена.', 'Ако напуснете, избраното ще бъде загубено.'],
}

// Shown when leaving elsewhere while the outcome of the change is unknown.
const UNRESOLVED_NOTICE: GuardNotice = {
  label: 'Неясен резултат',
  lines: ['Не е ясно дали промяната е направена.', 'Проверете часа в графика, преди да я правите отново.'],
}

const defaultNow = () => new Date()

/** Why the Appointment cannot be changed at all, judged from the authoritative detail. */
function blockOf(detail: AppointmentDetails, now: Date): RescheduleFailure | null {
  if (detail.businessStatus === 'DRAFT') return 'business-not-active'
  if (detail.businessStatus === 'SUSPENDED') return 'business-suspended'
  const start = parseInstant(detail.start)
  if (detail.status !== 'CONFIRMED' || start === null || start <= now.getTime()) return 'state-conflict'
  return null
}

function readable(result: unknown, appointmentId: string): result is RescheduledAppointment {
  const value = result as Partial<RescheduledAppointment> | null | undefined
  return (
    typeof value === 'object' &&
    value !== null &&
    value.id === appointmentId &&
    typeof value.start === 'string' &&
    typeof value.version === 'number'
  )
}

/**
 * The owner-only reschedule and StaffMember reassignment flow (ADR-0028 sections 3 and 6): three steps,
 * one atomic POST. The Appointment and its options are read from the server; the Customer and the
 * Services are shown and never editable. The whole proposal lives in this component's memory: nothing of
 * it reaches the URL, history state, storage, the title, or the console. The version of the loaded
 * Appointment is used only as `expectedVersion` of the single request and is never displayed.
 */
export function Reschedule({
  appointmentId,
  origin,
  now = defaultNow,
  onBackToDetail,
  onLeaveToOrigin,
  onRescheduled,
  onAuthenticationRequired,
}: RescheduleProps) {
  const [detailReload, setDetailReload] = useState(0)
  const [optionsReload, setOptionsReload] = useState(0)
  // A new epoch discards the whole form and every option read for the previous version.
  const [epoch, setEpoch] = useState(0)
  const [banner, setBanner] = useState<string | null>(null)
  const [terminal, setTerminal] = useState<RescheduleFailure | null>(null)
  // The session ended: the shared recovery takes over, so no failure of this screen is shown meanwhile.
  const [signedOut, setSignedOut] = useState(false)
  const [first, setFirst] = useState<{ epoch: number; options: RescheduleOptions } | null>(null)
  const handlers = useRef({ onAuthenticationRequired })
  handlers.current = { onAuthenticationRequired }

  const onReadFailure = useCallback((error: unknown) => {
    if (isAuthenticationRequired(error)) {
      setSignedOut(true)
      handlers.current.onAuthenticationRequired(error.detail)
      return
    }
    const kind = readFailureOf(error)
    if (
      kind === 'access' ||
      kind === 'business-not-active' ||
      kind === 'business-suspended' ||
      kind === 'not-found' ||
      kind === 'state-conflict'
    ) {
      setTerminal(kind)
    }
  }, [])

  // The key carries the reload token, so the Appointment of the previous read is never shown, and the
  // options of an older version are never read, while the new one is on its way.
  const detailKey = `detail:${detailReload}`
  const detailLoad = useKeyedLoad(detailKey, (signal) => getAppointment(appointmentId, signal), detailReload, onReadFailure)
  const detail = dataFor(detailLoad, detailKey)
  const block = detail ? blockOf(detail, now()) : null
  const stopped = terminal ?? block

  const optionsKey = detail && stopped === null ? `options:${detail.version}:${epoch}` : null
  const optionsLoad = useKeyedLoad(
    optionsKey,
    (signal) => getRescheduleOptions(appointmentId, signal),
    optionsReload,
    onReadFailure,
  )
  const live = dataFor(optionsLoad, optionsKey)
  useEffect(() => {
    if (live) setFirst((current) => (current && current.epoch === epoch ? current : { epoch, options: live }))
  }, [live, epoch])

  const leaveBack = origin.kind === 'customer' ? 'Към клиента' : 'Към графика'
  const noticeRef = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    if (stopped) noticeRef.current?.focus()
  }, [stopped])

  const topBack = (
    <div className="business-page-actions">
      <Button type="button" variant="secondary" onClick={onBackToDetail}>
        Обратно към часа
      </Button>
    </div>
  )

  if (stopped !== null) {
    return (
      <div className="platform-content manual-create">
        {topBack}
        {detail && stopped !== 'not-found' && stopped !== 'access' && <AppointmentContext appointment={detail} />}
        <div className="feedback-action-layout">
          <div ref={noticeRef} className="status-message status-error" role="alert" tabIndex={-1}>
            <p>{RESCHEDULE_TEXT[stopped]}</p>
          </div>
          <div className="feedback-action-controls action-group">
            {(stopped === 'state-conflict' ||
              stopped === 'business-not-active' ||
              stopped === 'business-suspended') && (
              <Button type="button" variant="secondary" onClick={onBackToDetail}>
                Към часа
              </Button>
            )}
            <Button type="button" variant="secondary" onClick={onLeaveToOrigin}>
              {leaveBack}
            </Button>
          </div>
        </div>
      </div>
    )
  }

  if (detailLoad.kind === 'error' && !signedOut) {
    return (
      <div className="platform-content manual-create">
        {topBack}
        <LoadError
          error={detailLoad.error}
          fallback="Часът не може да бъде зареден."
          onRetry={() => setDetailReload((token) => token + 1)}
        />
      </div>
    )
  }
  if (optionsLoad.kind === 'error' && !signedOut && !(first && first.epoch === epoch)) {
    return (
      <div className="platform-content manual-create">
        {topBack}
        <LoadError
          error={optionsLoad.error}
          fallback="Възможностите за промяна не могат да бъдат заредени."
          onRetry={() => setOptionsReload((token) => token + 1)}
        />
      </div>
    )
  }
  if (!detail || !first || first.epoch !== epoch) {
    return (
      <div className="platform-content manual-create" aria-live="polite" aria-busy="true">
        {topBack}
        <p className="business-list-state">Зареждане на часа…</p>
      </div>
    )
  }

  return (
    <RescheduleForm
      key={epoch}
      detail={detail}
      initial={first.options}
      optionsLoad={optionsLoad}
      optionsKey={optionsKey}
      banner={banner}
      now={now}
      leaveLabel={leaveBack}
      onRefreshOptions={() => setOptionsReload((token) => token + 1)}
      onConcurrentUpdate={() => {
        // The version the options belong to is stale: the Appointment and the options are read again and
        // the owner reviews the change from the start.
        setBanner(RESCHEDULE_TEXT['concurrent-update'])
        setFirst(null)
        setEpoch((value) => value + 1)
        setDetailReload((token) => token + 1)
      }}
      onStateConflict={() => {
        setTerminal('state-conflict')
        setDetailReload((token) => token + 1)
      }}
      onBackToDetail={onBackToDetail}
      onLeaveToOrigin={onLeaveToOrigin}
      onRescheduled={onRescheduled}
      onAuthenticationRequired={onAuthenticationRequired}
    />
  )
}

type FormProps = {
  detail: AppointmentDetails
  initial: RescheduleOptions
  optionsLoad: KeyedLoad<RescheduleOptions>
  optionsKey: string | null
  banner: string | null
  now: () => Date
  leaveLabel: string
  onRefreshOptions: () => void
  onConcurrentUpdate: () => void
  onStateConflict: () => void
  onBackToDetail: () => void
  onLeaveToOrigin: () => void
  onRescheduled: () => void
  onAuthenticationRequired: (detail: string) => void
}

function RescheduleForm({
  detail,
  initial,
  optionsLoad,
  optionsKey,
  banner,
  now,
  leaveLabel,
  onRefreshOptions,
  onConcurrentUpdate,
  onStateConflict,
  onBackToDetail,
  onLeaveToOrigin,
  onRescheduled,
  onAuthenticationRequired,
}: FormProps) {
  const timezone = initial.timezone
  const original: Original = {
    staffMemberId: detail.staffMemberId,
    start: detail.start,
    date: businessDateOf(detail.start, timezone),
    version: detail.version,
  }
  const [baseline] = useState(() => initialProposal(original, initial.currentStaffMember.available))
  const [state, dispatch] = useReducer(reduce, baseline)
  const [errors, setErrors] = useState<Errors>({})
  const [notice, setNotice] = useState<Notice | null>(banner ? { kind: 'concurrent-update', text: banner } : null)
  const [blocked, setBlocked] = useState(false)
  // Sticky: once the outcome of the POST cannot be ruled out, this form can never send another one.
  const [unresolved, setUnresolved] = useState(false)
  const unresolvedRef = useRef(false)
  const snapshotRef = useRef<ReviewData | null>(null)
  const [submitted, setSubmitted] = useState<ReviewData | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [slotsReload, setSlotsReload] = useState(0)
  const [focusTick, setFocusTick] = useState(0)

  const submittingRef = useRef(false)
  const closed = useRef(false)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const noticeRef = useRef<HTMLDivElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const focusRef = useRef<HTMLElement | null>(null)
  const firstStep = useRef(true)
  const handlers = useRef({ onAuthenticationRequired, onConcurrentUpdate, onStateConflict })
  handlers.current = { onAuthenticationRequired, onConcurrentUpdate, onStateConflict }

  const guard = useGuardedFormState(
    unresolved || isDirty(state, baseline),
    () => setErrors({}),
    unresolved ? UNRESOLVED_NOTICE : LEAVE_NOTICE,
  )

  useEffect(() => {
    closed.current = false
    return () => {
      closed.current = true
    }
  }, [])

  // ---- reads ---------------------------------------------------------------------------------

  const live = dataFor(optionsLoad, optionsKey)
  const options = live ?? initial

  /** Every documented result, kept distinct; nothing here repeats the POST. */
  const recover = useCallback(
    (kind: RescheduleFailure) => {
      const text = RESCHEDULE_TEXT[kind]
      if (isUnresolved(kind)) {
        // Freeze the submitted review: nothing below runs, nothing is reset, nothing can be sent again.
        unresolvedRef.current = true
        setUnresolved(true)
        setSubmitted(snapshotRef.current)
        setNotice({ kind, text })
        return
      }
      switch (kind) {
        case 'concurrent-update':
          handlers.current.onConcurrentUpdate()
          return
        case 'state-conflict':
          handlers.current.onStateConflict()
          return
        case 'slot-unavailable':
          dispatch({ type: 'start', start: null })
          dispatch({ type: 'go', step: 'datetime', fromReview: true })
          setSlotsReload((token) => token + 1)
          break
        case 'staff-unavailable':
          dispatch({ type: 'staff', staffMemberId: null })
          dispatch({ type: 'go', step: 'staff', fromReview: true })
          onRefreshOptions()
          break
        case 'access':
        case 'business-not-active':
        case 'business-suspended':
        case 'not-found':
          setBlocked(true)
          break
        default:
          break
      }
      setNotice({ kind, text })
    },
    [onRefreshOptions],
  )

  const onSlotsFailure = useCallback(
    (error: unknown) => {
      if (isAuthenticationRequired(error)) {
        handlers.current.onAuthenticationRequired(error.detail)
        return
      }
      if (unresolvedRef.current) return
      const kind = readFailureOf(error)
      if (
        kind === 'staff-unavailable' ||
        kind === 'state-conflict' ||
        kind === 'access' ||
        kind === 'business-not-active' ||
        kind === 'business-suspended' ||
        kind === 'not-found'
      ) {
        recover(kind)
      }
    },
    [recover],
  )

  const slotsKey =
    state.step === 'datetime' && state.staffMemberId !== null && state.date !== ''
      ? `${state.staffMemberId}|${state.date}`
      : null
  const slots = useKeyedLoad(
    slotsKey,
    (signal) => getRescheduleAvailability(detail.id, state.staffMemberId as string, state.date, signal),
    slotsReload,
    onSlotsFailure,
  )
  const slotsData = dataFor(slots, slotsKey)

  // An offered start the server no longer lists is not kept.
  useEffect(() => {
    if (
      !unresolvedRef.current &&
      slotsData &&
      state.start &&
      !slotsData.slots.some((slot) => slot.start === state.start?.start)
    ) {
      dispatch({ type: 'start', start: null })
    }
  }, [slotsData])

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

  // ---- navigation between steps ----------------------------------------------------------------

  function validate(): Errors {
    const found: Errors = {}
    if (state.step === 'staff') {
      if (state.staffMemberId === null) found.staff = 'Изберете член на екипа.'
    } else if (state.step === 'datetime') {
      if (state.date === '') found.date = 'Изберете дата.'
      else if (state.start === null) found.start = 'Изберете час.'
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

  // ---- submit ----------------------------------------------------------------------------------

  const staffName = (id: string | null): string | undefined => {
    if (id === null) return undefined
    if (id === original.staffMemberId) return detail.staffDisplayName
    return options.staffMembers.find((member) => member.id === id)?.displayName
  }
  const newStaffName = staffName(state.staffMemberId)
  const effective = isEffective(state, original)
  const ready =
    !blocked &&
    !unresolved &&
    newStaffName !== undefined &&
    state.start !== null &&
    state.staffMemberId !== null &&
    effective

  const currentWhen = `${formatDateOnlyLong(original.date)}, ${formatTimeRange(detail.start, detail.end, timezone)}`

  function reviewData(): ReviewData | null {
    if (newStaffName === undefined || state.start === null) return null
    return {
      appointment: detail,
      currentStaffName: detail.staffDisplayName,
      newStaffName,
      staffChanged: staffChanged(state, original),
      currentWhen,
      newDate: state.date,
      newTime: formatTimeRange(state.start.start, state.start.end, timezone),
    }
  }

  async function submit() {
    if (unresolvedRef.current || submittingRef.current || !ready) return
    submittingRef.current = true
    setSubmitting(true)
    setNotice(null)
    snapshotRef.current = reviewData()
    try {
      const result = await rescheduleAppointment(detail.id, buildBody(state, original))
      // A response that arrives after the form was left must not navigate.
      if (closed.current) return
      if (!readable(result, detail.id)) {
        recover('uncertain')
        return
      }
      closed.current = true
      guard.unregisterDirty()
      onRescheduled()
    } catch (error) {
      if (closed.current) return
      if (isAuthenticationRequired(error)) {
        handlers.current.onAuthenticationRequired(error.detail)
        return
      }
      recover(classifyRescheduleFailure(error))
    } finally {
      submittingRef.current = false
      if (!closed.current) setSubmitting(false)
    }
  }

  /** The recovery of an unresolved outcome: the authoritative detail or calendar, without the ordinary prompt. */
  function leave(target: () => void) {
    guard.unregisterDirty()
    target()
  }

  // ---- rendering -------------------------------------------------------------------------------

  const stepIndex = STEPS.indexOf(state.step)
  const businessToday = localDateIn(now(), timezone) ?? undefined
  const isReview = state.step === 'review'
  const fresh = reviewData()

  let body
  switch (state.step) {
    case 'staff':
      body = (
        <StaffStep
          options={optionsLoad}
          optionsKey={optionsKey}
          staffMemberId={state.staffMemberId}
          error={errors.staff}
          focusRef={focusRef}
          onChoose={(staffMemberId) => {
            setErrors({})
            dispatch({ type: 'staff', staffMemberId })
          }}
          onRetry={onRefreshOptions}
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
            dispatch({
              type: 'date',
              date:
                isCanonicalDate(value) && value >= SCHEDULE_MIN_DATE && value <= SCHEDULE_MAX_DATE ? value : '',
            })
          }}
          onStart={(start) => {
            setErrors({})
            dispatch({ type: 'start', start })
          }}
          onRetry={() => setSlotsReload((token) => token + 1)}
        />
      )
      break
    case 'review':
      if (unresolved) {
        // The submitted facts, exactly as shown, without any control that could change or send them.
        body = submitted ? <ReviewStep data={submitted} effective /> : null
      } else if (fresh) {
        body = <ReviewStep data={fresh} effective={effective} onEdit={edit} />
      } else {
        body = (
          <p className="business-list-state" aria-live="polite">
            Прегледът не е наличен.
          </p>
        )
      }
      break
  }

  return (
    <div className="platform-content manual-create reschedule">
      <div className="business-page-actions">
        <Button
          type="button"
          variant="secondary"
          disabled={submitting}
          onClick={unresolved ? () => leave(onBackToDetail) : onBackToDetail}
        >
          Обратно към часа
        </Button>
      </div>

      {/* The review states the unchanged Customer, Services, and the original itself. */}
      {!isReview && <AppointmentContext appointment={detail} />}

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
              <div className="feedback-action-controls action-group">
                {unresolved && (
                  <Button type="button" variant="primary" disabled={submitting} onClick={() => leave(onBackToDetail)}>
                    Към часа
                  </Button>
                )}
                <Button
                  type="button"
                  variant="secondary"
                  disabled={submitting}
                  onClick={() => leave(onLeaveToOrigin)}
                >
                  {leaveLabel}
                </Button>
              </div>
            )}
          </div>
        )}

        {!unresolved && (
          <div className="manual-actions action-group">
            {stepIndex > 0 ? (
              <Button type="button" variant="secondary" disabled={submitting} onClick={back}>
                Назад
              </Button>
            ) : (
              <Button type="button" variant="secondary" disabled={submitting} onClick={onBackToDetail}>
                Отказ
              </Button>
            )}
            {isReview ? (
              <Button type="button" disabled={!ready || submitting} onClick={() => void submit()}>
                Промени часа
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
            Промяна на часа…
          </p>
        )}
      </section>
    </div>
  )
}
