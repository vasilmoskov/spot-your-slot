import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import { Button } from '../../ui/Button'
import { FieldError, fieldControlProps } from '../../ui/formValidation'
import { formatDateOnlyLong, formatTimeDistinct } from '../../public/booking/dates'
import { listCustomers, type CustomerPage, type CustomerSummary } from '../customers/api'
import { SEARCH_DEBOUNCE_MS } from '../customers/CustomerList'
import { formatCustomerPhone } from '../customers/presentation'
import { normalizeSearchTerm, searchTermError } from '../customers/validation'
import type { ServiceSummary } from '../services/api'
import { isAuthenticationRequired, readFailureKind, readFailureText } from './errors'
import type { CreationAvailability, CreationOptions } from './api'
import {
  MAX_SERVICES,
  MAX_VISIT_MINUTES,
  disabledReason,
  selectedServices,
  serviceSummary,
  summaryText,
  totalsOf,
  type ChosenCustomer,
} from './model'
import { dataFor, type KeyedLoad } from './useKeyedLoad'

// The six steps of the manual-create flow. Every step is a presentational component: the state, the
// loads, the validation, and the single POST belong to ManualCreate.

type Focusable = RefObject<HTMLElement | null>

export function LoadError({
  error,
  fallback,
  onRetry,
}: {
  error: unknown
  fallback: string
  onRetry: () => void
}) {
  // A lifecycle or access refusal is stated once, by the form's own notice, and cannot be retried here.
  if (readFailureKind(error) !== null) return null
  const message = readFailureText(error, fallback)
  return (
    <div className="feedback-action-layout">
      <div className="status-message status-error" role="alert">
        <p>{message}</p>
      </div>
      <div className="feedback-action-controls">
        <Button type="button" variant="secondary" onClick={onRetry}>
          Опитай отново
        </Button>
      </div>
    </div>
  )
}

// ---- 1. Customer -----------------------------------------------------------------------------------

const CUSTOMER_LOAD_ERROR = 'Клиентите не могат да бъдат заредени.'

type CustomerStepProps = {
  customer: ChosenCustomer | null
  error: string | undefined
  focusRef: Focusable
  onChoose: (customer: ChosenCustomer) => void
  onAddCustomer: () => void
  onAuthenticationRequired: (detail: string) => void
}

type CustomerResults =
  | { kind: 'loading' }
  | { kind: 'loaded'; page: CustomerPage; term: string }
  | { kind: 'error'; error: unknown }

function contactLine(customer: CustomerSummary): string | null {
  if (customer.phone) return formatCustomerPhone(customer.phone)
  return customer.email
}

/**
 * Selecting an existing Customer of this Business through the Customer search. The term travels only
 * in the POST body of the search, never in a URL, storage, or history; nothing is created here.
 */
export function CustomerStep({
  customer,
  error,
  focusRef,
  onChoose,
  onAddCustomer,
  onAuthenticationRequired,
}: CustomerStepProps) {
  const [draft, setDraft] = useState('')
  const [term, setTerm] = useState('')
  const [reload, setReload] = useState(0)
  const [results, setResults] = useState<CustomerResults>({ kind: 'loading' })
  const auth = useRef(onAuthenticationRequired)
  auth.current = onAuthenticationRequired

  const normalized = normalizeSearchTerm(draft)
  const invalid = searchTermError(normalized)

  useEffect(() => {
    if (invalid) return
    const timer = setTimeout(() => setTerm(normalized), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [normalized, invalid])

  useEffect(() => {
    const controller = new AbortController()
    setResults((current) => (current.kind === 'loaded' ? current : { kind: 'loading' }))
    listCustomers({ page: 0, size: 10, sort: 'name', direction: 'asc' }, term, controller.signal)
      .then((page) => {
        if (!controller.signal.aborted) setResults({ kind: 'loaded', page, term })
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted) return
        if (isAuthenticationRequired(failure)) {
          auth.current(failure.detail)
          return
        }
        setResults({ kind: 'error', error: failure })
      })
    return () => controller.abort()
  }, [term, reload])

  return (
    <div className="manual-step-body">
      <form
        role="search"
        aria-label="Търсене на клиенти"
        className="customer-search"
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          if (!invalid) setTerm(normalized)
        }}
      >
        <div className="form-field customer-search-field">
          <label htmlFor="manual-customer-search">Търсене на клиент</label>
          <input
            {...fieldControlProps('manual-customer-search', invalid)}
            ref={focusRef as RefObject<HTMLInputElement>}
            name="search"
            type="text"
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            enterKeyHint="search"
            placeholder="Име, телефон или имейл"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
          />
          <FieldError id="manual-customer-search" error={invalid} />
        </div>
      </form>

      {customer && (
        <p className="manual-chosen" role="status">
          Избран клиент: <strong>{customer.name}</strong>
        </p>
      )}

      {results.kind === 'loading' && (
        <p className="business-list-state" aria-live="polite" aria-busy="true">
          Зареждане на клиентите…
        </p>
      )}
      {results.kind === 'error' && (
        <LoadError error={results.error} fallback={CUSTOMER_LOAD_ERROR} onRetry={() => setReload((token) => token + 1)} />
      )}
      {results.kind === 'loaded' &&
        (results.page.items.length === 0 ? (
          <p className="business-list-state" aria-live="polite">
            {results.term !== ''
              ? 'Не са намерени клиенти по това търсене.'
              : 'Все още няма добавени клиенти.'}
          </p>
        ) : (
          <fieldset className="manual-choices" aria-describedby={error ? 'manual-customer-error' : undefined}>
            <legend className="visually-hidden">Клиенти</legend>
            {results.page.items.map((item) => {
              const contact = contactLine(item)
              return (
                <label key={item.id} className="manual-choice">
                  <input
                    type="radio"
                    name="manual-customer"
                    checked={customer?.id === item.id}
                    onChange={() => onChoose({ id: item.id, name: item.displayName })}
                  />
                  <span className="manual-choice-body">
                    <span className="manual-choice-title">{item.displayName}</span>
                    {contact && <span className="manual-choice-facts">{contact}</span>}
                  </span>
                </label>
              )
            })}
            {results.page.total > results.page.items.length && (
              <p className="manual-hint">
                Показани са първите {results.page.items.length} от {results.page.total}. Уточнете
                търсенето.
              </p>
            )}
          </fieldset>
        ))}

      {error && (
        <p id="manual-customer-error" className="field-error" role="alert">
          {error}
        </p>
      )}

      <div className="manual-side-action">
        <Button type="button" variant="secondary" onClick={onAddCustomer}>
          Добави клиент
        </Button>
      </div>
    </div>
  )
}

// ---- 2. Services -------------------------------------------------------------------------------------

type ServicesStepProps = {
  catalog: KeyedLoad<ServiceSummary[]>
  serviceIds: readonly string[]
  error: string | undefined
  focusRef: Focusable
  onToggle: (serviceId: string) => void
  onRetry: () => void
}

const REASON_TEXT = {
  count: `Най-много ${MAX_SERVICES} услуги в един час.`,
  duration: 'Общата продължителност не може да надвишава 8 часа.',
} as const

export function ServicesStep({ catalog, serviceIds, error, focusRef, onToggle, onRetry }: ServicesStepProps) {
  const services = dataFor(catalog, 'catalog')
  if (catalog.kind === 'error') {
    return <LoadError error={catalog.error} fallback="Услугите не могат да бъдат заредени." onRetry={onRetry} />
  }
  if (!services) {
    return (
      <p className="business-list-state" aria-live="polite" aria-busy="true">
        Зареждане на услугите…
      </p>
    )
  }
  if (services.length === 0) {
    return <p className="business-list-state">Няма активни услуги. Добавете услуга, за да създадете час.</p>
  }
  const selected = selectedServices(services, serviceIds)
  const totals = totalsOf(selected)
  let firstFocusable = true
  return (
    <div className="manual-step-body">
      <fieldset className="manual-choices" aria-describedby={error ? 'manual-services-error' : undefined}>
        <legend className="visually-hidden">Услуги</legend>
        {services.map((service) => {
          const reason = disabledReason(selected, service)
          const checked = serviceIds.includes(service.id)
          const attach = firstFocusable && reason === null
          if (attach) firstFocusable = false
          return (
            <label
              key={service.id}
              className={reason === null ? 'manual-choice' : 'manual-choice manual-choice--unavailable'}
            >
              <input
                ref={attach ? (focusRef as RefObject<HTMLInputElement>) : undefined}
                type="checkbox"
                checked={checked}
                disabled={reason !== null}
                onChange={() => onToggle(service.id)}
              />
              <span className="manual-choice-body">
                <span className="manual-choice-title">{service.name}</span>
                <span className="manual-choice-facts">{serviceSummary(service)}</span>
                {reason && <span className="manual-choice-reason">{REASON_TEXT[reason]}</span>}
              </span>
            </label>
          )
        })}
      </fieldset>
      {error && (
        <p id="manual-services-error" className="field-error" role="alert">
          {error}
        </p>
      )}
      {selected.length === 0 ? (
        <p className="manual-selection-empty" role="status">
          Не са избрани услуги.
        </p>
      ) : (
        <div className="manual-selection" role="status" aria-label="Избрани услуги">
          <p className="manual-selection-title">Избрани услуги: {selected.length}</p>
          <ul>
            {selected.map((service) => (
              <li key={service.id}>
                <span className="manual-choice-title">{service.name}</span> {serviceSummary(service)}
              </li>
            ))}
          </ul>
          <p className="manual-selection-total">Общо: {summaryText(totals.cents, totals.minutes)}</p>
          {totals.minutes === MAX_VISIT_MINUTES && (
            <p className="manual-hint">Достигнахте максималната продължителност.</p>
          )}
        </div>
      )}
    </div>
  )
}

// ---- 3. StaffMember ---------------------------------------------------------------------------------------

type StaffStepProps = {
  options: KeyedLoad<CreationOptions>
  optionsKey: string | null
  staffMemberId: string | null
  error: string | undefined
  focusRef: Focusable
  onChoose: (staffMemberId: string) => void
  onChangeServices: () => void
  onRetry: () => void
}

export function StaffStep({
  options,
  optionsKey,
  staffMemberId,
  error,
  focusRef,
  onChoose,
  onChangeServices,
  onRetry,
}: StaffStepProps) {
  if (options.kind === 'error') {
    return <LoadError error={options.error} fallback="Екипът не може да бъде зареден." onRetry={onRetry} />
  }
  const loaded = dataFor(options, optionsKey)
  if (!loaded) {
    return (
      <p className="business-list-state" aria-live="polite" aria-busy="true">
        Зареждане на екипа…
      </p>
    )
  }
  if (loaded.staffMembers.length === 0) {
    return (
      <div className="manual-step-body">
        <p className="status-message status-info" role="status">
          Няма член на екипа, който извършва всички избрани услуги.
        </p>
        {error && (
          <p id="manual-staff-error" className="field-error" role="alert">
            {error}
          </p>
        )}
        <div className="manual-side-action">
          <Button type="button" variant="secondary" onClick={onChangeServices}>
            Промени услугите
          </Button>
        </div>
      </div>
    )
  }
  return (
    <div className="manual-step-body">
      <fieldset className="manual-choices" aria-describedby={error ? 'manual-staff-error' : undefined}>
        <legend className="visually-hidden">Избор на служител</legend>
        {loaded.staffMembers.map((member, index) => (
          <label key={member.id} className="manual-choice">
            <input
              ref={index === 0 ? (focusRef as RefObject<HTMLInputElement>) : undefined}
              type="radio"
              name="manual-staff"
              checked={staffMemberId === member.id}
              onChange={() => onChoose(member.id)}
            />
            <span className="manual-choice-body">
              <span className="manual-choice-title">{member.displayName}</span>
            </span>
          </label>
        ))}
      </fieldset>
      {error && (
        <p id="manual-staff-error" className="field-error" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

// ---- 4. Date and time -----------------------------------------------------------------------------------------

type DateTimeStepProps = {
  date: string
  min: string | undefined
  start: string | null
  slots: KeyedLoad<CreationAvailability>
  slotsKey: string | null
  timezone: string | undefined
  dateError: string | undefined
  startError: string | undefined
  focusRef: Focusable
  onDate: (date: string) => void
  onStart: (start: { start: string; end: string }) => void
  onRetry: () => void
}

export function DateTimeStep({
  date,
  min,
  start,
  slots,
  slotsKey,
  timezone,
  dateError,
  startError,
  focusRef,
  onDate,
  onStart,
  onRetry,
}: DateTimeStepProps) {
  const loaded = dataFor(slots, slotsKey)
  return (
    <div className="manual-step-body">
      <div className="form-field manual-date-field">
        <label htmlFor="manual-date">Дата</label>
        <input
          {...fieldControlProps('manual-date', dateError)}
          ref={focusRef as RefObject<HTMLInputElement>}
          type="date"
          min={min}
          max="2100-12-31"
          value={date}
          onChange={(event) => onDate(event.target.value)}
        />
        <FieldError id="manual-date" error={dateError} />
      </div>

      {date !== '' && <p className="manual-date-text">{formatDateOnlyLong(date)}</p>}

      {slotsKey !== null && slots.kind === 'error' && (
        <LoadError error={slots.error} fallback="Свободните часове не могат да бъдат заредени." onRetry={onRetry} />
      )}
      {slotsKey !== null && slots.kind !== 'error' && !loaded && (
        <p className="business-list-state" aria-live="polite" aria-busy="true">
          Зареждане на свободните часове…
        </p>
      )}
      {loaded && loaded.slots.length === 0 && (
        <p className="business-list-state" role="status">
          Няма свободни часове за тази дата. Изберете друга дата.
        </p>
      )}
      {loaded && loaded.slots.length > 0 && (
        <fieldset className="manual-slots" aria-describedby={startError ? 'manual-start-error' : undefined}>
          <legend>Свободни часове</legend>
          {loaded.slots.map((slot, index) => (
            <label key={slot.start} className="manual-slot">
              <input
                type="radio"
                name="manual-start"
                data-first-slot={index === 0 ? '' : undefined}
                checked={start === slot.start}
                onChange={() => onStart(slot)}
              />
              <span>{formatTimeDistinct(slot.start, timezone ?? loaded.timezone)}</span>
            </label>
          ))}
        </fieldset>
      )}
      {startError && (
        <p id="manual-start-error" className="field-error" role="alert">
          {startError}
        </p>
      )}
    </div>
  )
}

// ---- 5. Internal note ---------------------------------------------------------------------------------------------

type NoteStepProps = {
  note: string
  error: string | undefined
  focusRef: Focusable
  onChange: (note: string) => void
}

export function NoteStep({ note, error, focusRef, onChange }: NoteStepProps) {
  return (
    <div className="manual-step-body">
      <div className="form-field">
        <label htmlFor="manual-note">Бележка (по желание)</label>
        <textarea
          {...fieldControlProps('manual-note', error)}
          ref={focusRef as RefObject<HTMLTextAreaElement>}
          rows={4}
          value={note}
          onChange={(event) => onChange(event.target.value)}
        />
        <p className="manual-hint">Вижда се само от вас и не се показва на клиента.</p>
        <FieldError id="manual-note" error={error} />
      </div>
    </div>
  )
}

// ---- 6. Review ---------------------------------------------------------------------------------------------------

export type ReviewData = {
  customer: ChosenCustomer
  services: readonly ServiceSummary[]
  staffName: string
  date: string
  time: string
  note: string | null
}

type ReviewStepProps = {
  data: ReviewData
  // Absent when the review is read-only (an unresolved outcome): then no edit control is rendered.
  onEdit?: (section: 'customer' | 'services' | 'staff' | 'datetime' | 'note') => void
}

/** The whole visit as the owner is about to create it; every section can be edited directly. */
export function ReviewStep({ data, onEdit }: ReviewStepProps) {
  const totals = totalsOf(data.services)
  const row = (
    section: 'customer' | 'services' | 'staff' | 'datetime' | 'note',
    label: string,
    editLabel: string,
    body: ReactNode,
  ) => (
    <div className="manual-review-row">
      <dt>{label}</dt>
      <dd>{body}</dd>
      {onEdit && (
        <div className="manual-review-edit">
          <Button type="button" variant="secondary" aria-label={editLabel} onClick={() => onEdit(section)}>
            Промени
          </Button>
        </div>
      )}
    </div>
  )
  return (
    <div className="manual-step-body">
      {onEdit && (
        <p className="manual-hint">
          Клиентът и услугите на създаден час не могат да се променят. При грешка отменете часа и
          създайте нов.
        </p>
      )}
      <dl className="manual-review">
        {row('customer', 'Клиент', 'Промени клиента', data.customer.name)}
        {row(
          'services',
          'Услуги',
          'Промени услугите',
          <>
            <ol className="manual-review-services">
              {data.services.map((service) => (
                <li key={service.id}>
                  <span className="manual-choice-title">{service.name}</span> {serviceSummary(service)}
                </li>
              ))}
            </ol>
            <p className="manual-selection-total">Общо: {summaryText(totals.cents, totals.minutes)}</p>
          </>,
        )}
        {row('staff', 'Член на екипа', 'Промени члена на екипа', data.staffName)}
        {row(
          'datetime',
          'Дата и час',
          'Промени датата и часа',
          <>
            {formatDateOnlyLong(data.date)}, {data.time}
          </>,
        )}
        {row('note', 'Вътрешна бележка', 'Промени бележката', data.note ?? 'Няма бележка.')}
      </dl>
    </div>
  )
}
