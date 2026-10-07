import { useEffect, useState, type FormEvent } from 'react'
import {
  EMAIL_INPUT_MAX_LENGTH,
  PHONE_INPUT_MAX_LENGTH,
} from '../../business/customers/validation'
import { canonicalOptional, codePointLength, trimApproved } from '../../business/text'
import { Button } from '../../ui/Button'
import { FieldError, fieldControlProps, useFieldValidation } from '../../ui/formValidation'
import { NOTE_MAX_CODE_POINTS, type BookingDetails } from './attempt'
import type { BookingFieldErrors } from './api'
import { DETAILS_FIELD_ORDER, validateDetails, type DetailsField } from './details'
import { StepActions } from './steps'

const CONTACT_ERROR_ID = 'booking-contact-error'

type Values = BookingDetails & { contact: string }

/**
 * The guest's own details. The values live in the journey, so they survive a move between steps;
 * the validation state (touched, errors) belongs to this step only. Validation follows the shared
 * form policy: nothing is reported for an untouched empty field, a field is judged on blur and on
 * every change after that, and submit shows every error and focuses the first invalid control.
 */
export function DetailsStep({
  details,
  serverErrors,
  frozen,
  onChange,
  onNext,
  onBack,
}: {
  details: BookingDetails
  // Field messages from a rejected submission; each disappears when its field is edited.
  serverErrors: BookingFieldErrors | null
  frozen: boolean
  onChange: (patch: Partial<BookingDetails>) => void
  onNext: () => void
  onBack: () => void
}) {
  const values: Values = { ...details, contact: '' }
  const { errors, controlRef, touch, edited, validateAll, applyServerErrors } = useFieldValidation<
    DetailsField,
    Values
  >({
    order: DETAILS_FIELD_ORDER,
    values,
    validate: validateDetails,
    // `contact` is reported only after a submit attempt, never while the other value is still pending.
    isEmpty: (field, current) =>
      field === 'contact' ? true : canonicalOptional(current[field]) === '',
  })

  // A rejected submission names the invalid fields; they are shown once, under their controls.
  const [appliedErrors, setAppliedErrors] = useState<BookingFieldErrors | null>(null)
  useEffect(() => {
    if (serverErrors && serverErrors !== appliedErrors) {
      setAppliedErrors(serverErrors)
      applyServerErrors(serverErrors)
    }
  }, [serverErrors, appliedErrors, applyServerErrors])

  const noteLength = codePointLength(trimApproved(details.note))

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (frozen || !validateAll()) return
    onNext()
  }

  const describedBy = (ownId: string, own: string | undefined) =>
    [own ? `${ownId}-error` : undefined, errors.contact ? CONTACT_ERROR_ID : undefined]
      .filter(Boolean)
      .join(' ') || undefined

  return (
    <form className="booking-step-form business-form" onSubmit={submit} noValidate>
      <fieldset className="booking-details" disabled={frozen}>
        <legend className="visually-hidden">Вашите данни</legend>
        <div className="form-field">
          <label htmlFor="booking-display-name">Име</label>
          <input
            {...fieldControlProps('booking-display-name', errors.displayName)}
            ref={controlRef('displayName')}
            name="displayName"
            type="text"
            autoComplete="name"
            value={details.displayName}
            required
            onBlur={() => touch('displayName')}
            onChange={(event) => {
              edited('displayName')
              onChange({ displayName: event.target.value })
            }}
          />
          <FieldError id="booking-display-name" error={errors.displayName} />
        </div>
        <fieldset className="contact-group">
          <legend className="visually-hidden">Данни за връзка</legend>
          <div className="form-field">
            <label htmlFor="booking-phone">Телефон</label>
            <input
              {...fieldControlProps('booking-phone', errors.phone)}
              aria-invalid={errors.phone || errors.contact ? true : undefined}
              aria-describedby={describedBy('booking-phone', errors.phone)}
              ref={(element) => {
                controlRef('phone')(element)
                controlRef('contact')(element)
              }}
              name="phone"
              type="tel"
              autoComplete="tel"
              value={details.phone}
              maxLength={PHONE_INPUT_MAX_LENGTH}
              onBlur={() => touch('phone')}
              onChange={(event) => {
                edited('phone')
                edited('contact')
                onChange({ phone: event.target.value })
              }}
            />
            <FieldError id="booking-phone" error={errors.phone} />
          </div>
          <div className="form-field">
            <label htmlFor="booking-email">Имейл</label>
            <input
              {...fieldControlProps('booking-email', errors.email)}
              aria-invalid={errors.email || errors.contact ? true : undefined}
              aria-describedby={describedBy('booking-email', errors.email)}
              ref={controlRef('email')}
              name="email"
              type="email"
              autoComplete="email"
              value={details.email}
              maxLength={EMAIL_INPUT_MAX_LENGTH}
              onBlur={() => touch('email')}
              onChange={(event) => {
                edited('email')
                edited('contact')
                onChange({ email: event.target.value })
              }}
            />
            <FieldError id="booking-email" error={errors.email} />
          </div>
          {errors.contact ? (
            <p id={CONTACT_ERROR_ID} className="field-error">
              {errors.contact}
            </p>
          ) : (
            <p className="field-note">Попълнете телефон или имейл.</p>
          )}
        </fieldset>
        <div className="form-field">
          <label htmlFor="booking-note">Бележка (по желание)</label>
          <textarea
            {...fieldControlProps('booking-note', errors.note)}
            aria-describedby={
              [errors.note ? 'booking-note-error' : undefined, 'booking-note-count']
                .filter(Boolean)
                .join(' ')
            }
            ref={controlRef('note')}
            name="note"
            autoComplete="off"
            value={details.note}
            onBlur={() => touch('note')}
            onChange={(event) => {
              edited('note')
              onChange({ note: event.target.value })
            }}
          />
          <FieldError id="booking-note" error={errors.note} />
          <p id="booking-note-count" className="field-note">
            {noteLength} / {NOTE_MAX_CODE_POINTS} знака
          </p>
        </div>
        <p className="field-note booking-privacy">
          Данните ви се предоставят на бизнеса за записване и управление на резервацията.
        </p>
      </fieldset>
      <StepActions>
        <Button type="button" variant="secondary" onClick={onBack} disabled={frozen}>
          Назад
        </Button>
        <Button type="submit" disabled={frozen}>
          Напред
        </Button>
      </StepActions>
    </form>
  )
}
