import { useEffect, useLayoutEffect, useReducer, useRef, useState, type ReactNode } from 'react'
import { Button } from '../../ui/Button'
import { useGuardedFormState, type GuardNotice } from '../../ui/UnsavedChangesGuard'
import type { PublicService } from '../api'
import {
  fetchAvailability,
  fetchBookingOptions,
  postBooking,
  type BookingFieldErrors,
  type BookingOptions,
  type Slot,
} from './api'
import {
  INITIAL_SUBMISSION,
  afterEdit,
  applyOutcome,
  attemptForSubmit,
  isFrozen,
  mayHaveBooking,
  newAttemptId,
  type BookingDetails,
  type Submission,
} from './attempt'
import { ConfirmationView } from './ConfirmationView'
import { DetailsStep } from './DetailsStep'
import { EMPTY_DETAILS, detailsAreValid, hasEnteredDetails } from './details'
import { useRead } from './hooks'
import { businessLocalDate } from './dates'
import { NO_PREFERENCE_LABEL } from './messages'
import { ReviewStep } from './ReviewStep'
import {
  DateTimeStep,
  Loading,
  ReadProblem,
  ServiceContext,
  ServiceStep,
  StaffStep,
  UnavailableProblem,
} from './steps'
import { useJourneyHistory, type PopVerdict } from './useJourneyHistory'

// Step ordinals; they equal the browser history entry index (the profile is entry 0).
const STEP_SERVICE = 1
const STEP_STAFF = 2
const STEP_DATE_TIME = 3
const STEP_DETAILS = 4
const STEP_REVIEW = 5
const STEP_RESULT = 6
const FORM_STEPS = 5

const HEADINGS: Record<number, string> = {
  [STEP_SERVICE]: 'Избор на услуга',
  [STEP_STAFF]: 'Избор на служител',
  [STEP_DATE_TIME]: 'Дата и час',
  [STEP_DETAILS]: 'Вашите данни',
  [STEP_REVIEW]: 'Преглед и потвърждение',
}

type State = {
  step: number
  serviceId: string | null
  // null is "no preference".
  staffId: string | null
  date: string | null
  slot: Slot | null
  details: BookingDetails
  submission: Submission
  detailsErrors: BookingFieldErrors | null
  // True while the guest edits a step they reached from the review: the steps offer «Към прегледа».
  editing: boolean
}

type Action =
  | { type: 'step'; step: number }
  | { type: 'service'; id: string | null }
  | { type: 'staff'; id: string | null }
  | { type: 'date'; date: string | null }
  | { type: 'slot'; slot: Slot | null }
  | { type: 'details'; patch: Partial<BookingDetails> }
  | { type: 'submission'; submission: Submission }
  | { type: 'editing'; value: boolean }
  | { type: 'reset' }

function initialState(): State {
  return {
    step: STEP_SERVICE,
    serviceId: null,
    staffId: null,
    date: null,
    slot: null,
    details: EMPTY_DETAILS,
    submission: INITIAL_SUBMISSION,
    detailsErrors: null,
    editing: false,
  }
}

// A frozen review (sending, uncertain, confirmed) ignores every edit, so no choice can change while a
// booking may exist. A change to a Service or a StaffMember invalidates every dependent choice.
function reducer(state: State, action: Action): State {
  if (action.type === 'reset') return initialState()
  if (action.type === 'editing') return { ...state, editing: action.value }
  if (action.type === 'step') {
    return {
      ...state,
      step: action.step,
      // Reaching the review ends an edit that began there.
      editing: action.step === STEP_REVIEW ? false : state.editing,
      detailsErrors: action.step === STEP_DETAILS ? state.detailsErrors : null,
    }
  }
  if (action.type === 'submission') {
    const submission = action.submission
    if (submission.phase === 'confirmed') {
      // Personal data leaves memory as soon as the server has answered.
      return { ...state, submission, details: EMPTY_DETAILS, slot: null, detailsErrors: null }
    }
    const fieldErrors = submission.phase === 'idle' ? submission.notice?.fieldErrors : undefined
    return { ...state, submission, detailsErrors: fieldErrors ?? state.detailsErrors }
  }
  if (isFrozen(state.submission)) return state
  const submission = afterEdit(state.submission)
  switch (action.type) {
    case 'service':
      if (action.id === state.serviceId) return state
      return { ...state, serviceId: action.id, staffId: null, date: null, slot: null, submission }
    case 'staff':
      if (action.id === state.staffId) return state
      return { ...state, staffId: action.id, date: null, slot: null, submission }
    case 'date':
      if (action.date === state.date) return state
      return { ...state, date: action.date, slot: null, submission }
    case 'slot':
      if (action.slot?.start === state.slot?.start) return state
      return { ...state, slot: action.slot, submission }
    case 'details':
      return {
        ...state,
        details: { ...state.details, ...action.patch },
        detailsErrors: null,
        submission,
      }
  }
}

// The furthest step the current choices allow; a browser traversal beyond it is refused.
function reachableStep(state: State): number {
  if (state.serviceId === null) return STEP_SERVICE
  if (state.slot === null) return STEP_DATE_TIME
  return detailsAreValid(state.details) ? STEP_REVIEW : STEP_DETAILS
}

export type BookingJourneyProps = {
  slug: string
  businessName: string
  businessPhone: string | null
  services: PublicService[]
  journey: string
  onClose: () => void
  onBusinessUnavailable: () => void
  // Injected so that tests control time and identity; the defaults are the real clock and a
  // cryptographically secure UUID.
  now?: () => number
  makeAttemptId?: () => string
}

/**
 * The in-memory guest booking journey inside the public `/{slug}` page (ADR-0026). Every choice, every
 * detail and the attempt identity live only in this component's state: nothing is written to the URL,
 * `history.state`, a cookie, storage or the page title. The component is keyed by Business, so its state
 * and its pending reads end with the Business.
 */
export function BookingJourney({
  slug,
  businessName,
  businessPhone,
  services,
  journey,
  onClose,
  onBusinessUnavailable,
  now = Date.now,
  makeAttemptId = newAttemptId,
}: BookingJourneyProps) {
  const [state, dispatch] = useReducer(reducer, undefined, initialState)
  const latest = useRef(state)
  latest.current = state
  const headingRef = useRef<HTMLHeadingElement>(null)
  const inFlight = useRef(false)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  const { step, serviceId, staffId, date, slot, details, submission } = state
  const service = services.find((candidate) => candidate.id === serviceId)

  // ---- reads ----

  // Booking options belong to the Service alone, so changing only the date never reloads them.
  const optionsRead = useRead(
    serviceId !== null && step >= STEP_STAFF && step < STEP_RESULT
      ? `options|${slug}|${serviceId}`
      : null,
    (signal) => fetchBookingOptions(slug, serviceId as string, signal),
  )
  const options: BookingOptions | null =
    optionsRead.view.status === 'ready' && optionsRead.view.data.kind === 'options'
      ? optionsRead.view.data.options
      : null

  const requestDate = date ?? options?.firstDate ?? null
  const availabilityRead = useRead(
    serviceId !== null && step === STEP_DATE_TIME && options !== null && requestDate !== null
      ? `availability|${slug}|${serviceId}|${staffId ?? 'any'}|${requestDate}`
      : null,
    (signal) => fetchAvailability(slug, serviceId as string, requestDate as string, staffId, signal),
  )

  // The dates of the last answer for this Service and preference stay visible while one date's slots load.
  const group = `${serviceId}|${staffId ?? 'any'}`
  const [knownDates, setKnownDates] = useState<{ group: string; dates: string[] } | null>(null)
  const availableDates = knownDates?.group === group ? knownDates.dates : null

  const availabilityView = availabilityRead.view
  useEffect(() => {
    if (availabilityView.status !== 'ready' || availabilityView.data.kind !== 'availability') return
    const answer = availabilityView.data.availability
    setKnownDates({ group, dates: answer.availableDates })
    const current = latest.current
    if (current.date === null) {
      // The first available date is selected, so the guest sees slots at once and can change it.
      const first = answer.availableDates[0]
      if (first !== undefined) dispatch({ type: 'date', date: first })
    } else if (!answer.availableDates.includes(current.date)) {
      dispatch({ type: 'date', date: null })
    } else if (
      current.slot !== null &&
      answer.date === current.date &&
      !answer.slots.some((candidate) => candidate.start === current.slot?.start)
    ) {
      // A refreshed list no longer offers the slot the guest had chosen.
      dispatch({ type: 'slot', slot: null })
    }
  }, [availabilityView, group])

  const businessGone =
    (optionsRead.view.status === 'ready' && optionsRead.view.data.kind === 'business') ||
    (availabilityView.status === 'ready' && availabilityView.data.kind === 'business')
  // An unresolved attempt is never discarded by a page-level callback: unmounting the journey would
  // silently drop it together with its leave warning.
  const unresolved = mayHaveBooking(submission)
  useEffect(() => {
    if (businessGone && !unresolved) onBusinessUnavailable()
  }, [businessGone, unresolved, onBusinessUnavailable])

  // ---- leaving, and history ----

  const entered = state.slot !== null || hasEnteredDetails(details)
  const mayBeBooked = mayHaveBooking(submission)
  const dirty = mayBeBooked || (submission.phase !== 'confirmed' && entered)
  const phoneText = businessPhone?.trim() ?? ''
  const notice: GuardNotice = mayBeBooked
    ? {
        label: 'Резервацията може вече да е направена',
        lines: [
          'Резервацията може вече да е направена.',
          'Ако напуснете, няма да видите резултата.',
          ...(phoneText === '' ? [] : [`Телефон на бизнеса: ${phoneText}`]),
        ],
      }
    : {
        label: 'Незавършена резервация',
        lines: ['Резервацията не е завършена.', 'Ако напуснете, въведените данни ще бъдат загубени.'],
      }
  const guard = useGuardedFormState(dirty, () => undefined, notice)

  const verdict = (target: number): PopVerdict => {
    const current = latest.current
    if (current.submission.phase === 'confirmed') return target === STEP_RESULT ? 'accept' : 'restore'
    if (isFrozen(current.submission)) return target === current.step ? 'accept' : 'restore'
    return target < STEP_RESULT && target <= reachableStep(current) ? 'accept' : 'restore'
  }

  const dirtyRef = useRef(dirty)
  dirtyRef.current = dirty
  const nav = useJourneyHistory({
    journey,
    step,
    verdict,
    onStep: (next) => dispatch({ type: 'step', step: next }),
    onLeft: (restore) => {
      // History already moved to the profile entry. Without data to lose that is the end of the
      // journey; otherwise the shown entry is put back and the guest decides.
      if (!dirtyRef.current) {
        onClose()
        return
      }
      restore()
      guard.guard(() => {
        nav.rewindToProfile()
        onClose()
      })
    },
  })

  const leave = () =>
    guard.guard(() => {
      nav.rewindToProfile()
      onClose()
    })

  const jumpTo = (target: number) => nav.goTo(target)

  // Editing a step from the review: the guest returns directly once everything after the edit is valid.
  const editFromReview = (target: number) => {
    dispatch({ type: 'editing', value: true })
    jumpTo(target)
  }

  const restart = () =>
    guard.guard(() => {
      dispatch({ type: 'reset' })
      jumpTo(STEP_SERVICE)
    })

  useLayoutEffect(() => {
    headingRef.current?.focus()
  }, [step])

  // ---- submission ----

  const send = async () => {
    if (inFlight.current) return
    const current = latest.current
    if (current.serviceId === null || current.slot === null) return
    const attempt = attemptForSubmit(
      current.submission,
      { serviceId: current.serviceId, staffId: current.staffId, slot: current.slot },
      current.details,
      makeAttemptId,
    )
    if (!attempt) return
    inFlight.current = true
    dispatch({ type: 'submission', submission: { phase: 'sending', attempt } })
    const outcome = await postBooking(slug, attempt.body, now())
    inFlight.current = false
    // The Business page may have been left meanwhile; a late answer is not shown anywhere.
    if (!alive.current) return
    const next = applyOutcome(attempt, outcome, now())
    // Only a first send that was proven rejected may end the journey; an unresolved attempt stays
    // on screen, frozen, with its leave warning.
    if (outcome.kind === 'rejected' && outcome.reason === 'business-unavailable' && next.phase === 'idle') {
      onBusinessUnavailable()
      return
    }
    dispatch({ type: 'submission', submission: next })
    if (next.phase === 'confirmed') nav.advance(STEP_RESULT)
  }

  // ---- rendering ----

  // After an edit that left a chosen time standing, every later step is still valid (details are only
  // changed on their own step, and a validated form is required to leave it), so the way back is direct.
  const returning = state.editing && slot !== null
  const nextLabel = returning ? 'Към прегледа' : 'Напред'
  const forward = (from: number) => {
    if (returning) nav.goTo(STEP_REVIEW)
    else nav.advance(from + 1)
  }
  const today = options ? businessLocalDate(now(), options.timezone) : null

  const confirmed = submission.phase === 'confirmed' ? submission : null
  const frozen = isFrozen(submission)
  const staffLabel =
    staffId === null
      ? NO_PREFERENCE_LABEL
      : (options?.staff.find((member) => member.id === staffId)?.displayName ?? 'Избран служител')
  const heading = confirmed
    ? confirmed.booking.status === 'CANCELLED'
      ? 'Резервацията е отменена'
      : 'Резервацията е потвърдена'
    : (HEADINGS[step] ?? HEADINGS[STEP_SERVICE]!)

  const gate = (content: () => ReactNode) => {
    const view = optionsRead.view
    if (view.status === 'failed') {
      return <ReadProblem error={view.error} onRetry={optionsRead.reload} />
    }
    if (view.status === 'ready' && view.data.kind !== 'options') {
      return (
        <UnavailableProblem
          kind={view.data.kind}
          onChooseService={() => {
            dispatch({ type: 'service', id: null })
            jumpTo(STEP_SERVICE)
          }}
          onChooseStaff={optionsRead.reload}
        />
      )
    }
    if (options === null) return <Loading>Зареждане…</Loading>
    return content()
  }

  const chooseAnotherService = () => {
    dispatch({ type: 'service', id: null })
    jumpTo(STEP_SERVICE)
  }

  let body: ReactNode = null
  if (confirmed) {
    body = (
      <ConfirmationView
        booking={confirmed.booking}
        businessPhone={businessPhone}
        onClose={leave}
      />
    )
  } else if (step === STEP_SERVICE) {
    body = (
      <ServiceStep
        services={services}
        serviceId={serviceId}
        frozen={frozen}
        nextLabel={nextLabel}
        onSelect={(id) => dispatch({ type: 'service', id })}
        onNext={() => forward(STEP_SERVICE)}
      />
    )
  } else if (step === STEP_STAFF) {
    body = (
      <StaffStep
        view={optionsRead.view}
        staffId={staffId}
        frozen={frozen}
        nextLabel={nextLabel}
        onSelect={(id) => dispatch({ type: 'staff', id })}
        onReload={optionsRead.reload}
        onChooseService={chooseAnotherService}
        onNext={() => forward(STEP_STAFF)}
        onBack={nav.back}
      />
    )
  } else if (step === STEP_DATE_TIME) {
    body = gate(() => (
      <DateTimeStep
        view={availabilityRead.view}
        availableDates={availableDates}
        firstDate={options!.firstDate}
        lastDate={options!.lastDate}
        timezone={options!.timezone}
        today={today}
        date={date}
        slot={slot}
        frozen={frozen}
        nextLabel={nextLabel}
        onSelectDate={(value) => dispatch({ type: 'date', date: value })}
        onSelectSlot={(value) => dispatch({ type: 'slot', slot: value })}
        onReload={availabilityRead.reload}
        onChooseService={chooseAnotherService}
        onChooseStaff={() => {
          dispatch({ type: 'staff', id: null })
          optionsRead.reload()
          jumpTo(STEP_STAFF)
        }}
        onNext={() => forward(STEP_DATE_TIME)}
        onBack={nav.back}
      />
    ))
  } else if (step === STEP_DETAILS) {
    body = (
      <DetailsStep
        details={details}
        serverErrors={state.detailsErrors}
        frozen={frozen}
        nextLabel={nextLabel}
        onChange={(patch) => dispatch({ type: 'details', patch })}
        onNext={() => forward(STEP_DETAILS)}
        onBack={nav.back}
      />
    )
  } else if (step === STEP_REVIEW && service && slot && date) {
    body = gate(() => (
      <ReviewStep
        service={service}
        staffLabel={staffLabel}
        date={date}
        slot={slot}
        timezone={options!.timezone}
        details={details}
        submission={submission}
        businessPhone={businessPhone}
        now={now}
        recovery={{
          chooseSlot: () => {
            dispatch({ type: 'slot', slot: null })
            editFromReview(STEP_DATE_TIME)
          },
          chooseService: () => {
            dispatch({ type: 'service', id: null })
            editFromReview(STEP_SERVICE)
          },
          chooseStaff: () => {
            dispatch({ type: 'staff', id: null })
            optionsRead.reload()
            editFromReview(STEP_STAFF)
          },
          editDetails: () => editFromReview(STEP_DETAILS),
          restart,
        }}
        onSubmit={() => void send()}
        onBack={nav.back}
        onEdit={editFromReview}
      />
    ))
  }

  return (
    <div className="booking-journey">
      <div className="booking-header">
        <p className="booking-business">{businessName}</p>
        {!confirmed && (
          <Button type="button" variant="secondary" onClick={leave}>
            Към страницата на бизнеса
          </Button>
        )}
      </div>
      <section className="booking-step" aria-labelledby="booking-step-heading">
        {!confirmed && (
          <p className="booking-progress">
            Стъпка {step} от {FORM_STEPS}
          </p>
        )}
        {!confirmed && step > STEP_SERVICE && step < STEP_REVIEW && (
          <ServiceContext service={service} />
        )}
        <h1 id="booking-step-heading" ref={headingRef} tabIndex={-1}>
          {heading}
        </h1>
        {body}
      </section>
    </div>
  )
}
