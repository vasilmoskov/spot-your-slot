import { useState, type FormEvent } from 'react'
import { Button } from '../../ui/Button'
import {
  FieldError,
  fieldControlProps,
  useFieldValidation,
  type SubmitOutcome,
} from '../../ui/formValidation'
import { useGuardedFormState } from '../../ui/UnsavedChangesGuard'
import { type CreateStaffMemberInput, type StaffMemberDetails, type UpdateStaffMemberInput } from './api'
import { canonicalOptional } from '../text'
import {
  CONTACT_EMAIL_MAX_LENGTH,
  CONTACT_PHONE_MAX_LENGTH,
  DISPLAY_NAME_MAX_LENGTH,
  STAFF_FIELD_ORDER,
  validateStaff,
  type StaffField,
  type StaffFormValues,
} from './validation'

type StaffFormProps = {
  staffMember?: StaffMemberDetails
  busy: boolean
  submitLabel: string
  onChange?: () => void
  onCancel?: () => void
  onSubmit: (
    input: CreateStaffMemberInput | UpdateStaffMemberInput,
  ) => Promise<SubmitOutcome<StaffField>> | void
}

export function StaffForm({
  staffMember,
  busy,
  submitLabel,
  onCancel,
  onChange,
  onSubmit,
}: StaffFormProps) {
  const initialDisplayName = staffMember?.displayName ?? ''
  const initialContactEmail = staffMember?.contactEmail ?? ''
  const initialContactPhone = staffMember?.contactPhone ?? ''

  const [displayName, setDisplayName] = useState(initialDisplayName)
  const [contactEmail, setContactEmail] = useState(initialContactEmail)
  const [contactPhone, setContactPhone] = useState(initialContactPhone)

  const values: StaffFormValues = { displayName, contactEmail, contactPhone }
  const { errors, controlRef, touch, edited, validateAll, applyServerErrors } =
    useFieldValidation<StaffField, StaffFormValues>({
      order: STAFF_FIELD_ORDER,
      values,
      validate: validateStaff,
      isEmpty: (field, current) => canonicalOptional(current[field]) === '',
    })

  const isDirty =
    displayName !== initialDisplayName ||
    contactEmail !== initialContactEmail ||
    contactPhone !== initialContactPhone

  const guard = useGuardedFormState(isDirty, () => undefined)

  const requestCancel = () => {
    guard.guard(() => onCancel?.())
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (busy) return
    if (!validateAll()) return

    const rawContactEmail = contactEmail.trim()
    const rawContactPhone = contactPhone.trim()
    const common = {
      displayName: displayName.trim(),
      contactEmail: rawContactEmail === '' ? undefined : rawContactEmail,
      contactPhone: rawContactPhone === '' ? undefined : rawContactPhone,
    }

    const outcome = await onSubmit(
      staffMember ? { ...common, expectedVersion: staffMember.version } : common,
    )
    if (outcome?.fieldErrors) applyServerErrors(outcome.fieldErrors)
  }

  return (
    <form onChange={onChange} className="business-form" onSubmit={submit} noValidate>
      <div className="form-field">
        <label htmlFor="staff-display-name">Име на члена на екипа</label>
        <input
          {...fieldControlProps('staff-display-name', errors.displayName)}
          ref={controlRef('displayName')}
          name="displayName"
          type="text"
          value={displayName}
          required
          maxLength={DISPLAY_NAME_MAX_LENGTH}
          onBlur={() => touch('displayName')}
          onChange={(event) => {
            edited('displayName')
            setDisplayName(event.target.value)
          }}
        />
        <FieldError id="staff-display-name" error={errors.displayName} />
      </div>
      <div className="form-field">
        <label htmlFor="staff-contact-email">Имейл за връзка (по избор)</label>
        <input
          {...fieldControlProps('staff-contact-email', errors.contactEmail)}
          ref={controlRef('contactEmail')}
          name="contactEmail"
          type="email"
          value={contactEmail}
          maxLength={CONTACT_EMAIL_MAX_LENGTH}
          onBlur={() => touch('contactEmail')}
          onChange={(event) => {
            edited('contactEmail')
            setContactEmail(event.target.value)
          }}
        />
        <FieldError id="staff-contact-email" error={errors.contactEmail} />
      </div>
      <div className="form-field">
        <label htmlFor="staff-contact-phone">Телефон за връзка (по избор)</label>
        <input
          {...fieldControlProps('staff-contact-phone', errors.contactPhone)}
          ref={controlRef('contactPhone')}
          name="contactPhone"
          type="tel"
          value={contactPhone}
          maxLength={CONTACT_PHONE_MAX_LENGTH}
          onBlur={() => touch('contactPhone')}
          onChange={(event) => {
            edited('contactPhone')
            setContactPhone(event.target.value)
          }}
        />
        <FieldError id="staff-contact-phone" error={errors.contactPhone} />
      </div>
      <div className="action-group">
        {onCancel && (
          <Button type="button" variant="secondary" disabled={busy} onClick={requestCancel}>
            Отказ
          </Button>
        )}
        <Button disabled={busy}>{busy ? 'Запазване…' : submitLabel}</Button>
      </div>
    </form>
  )
}
