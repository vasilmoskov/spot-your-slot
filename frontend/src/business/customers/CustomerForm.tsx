import { useState, type FormEvent } from 'react'
import { Button } from '../../ui/Button'
import {
  FieldError,
  fieldControlProps,
  useFieldValidation,
  type SubmitOutcome,
} from '../../ui/formValidation'
import { useGuardedFormState } from '../../ui/UnsavedChangesGuard'
import { canonicalOptional } from '../text'
import type { CustomerDetails, CustomerInput, UpdateCustomerInput } from './api'
import {
  CUSTOMER_FIELD_ORDER,
  EMAIL_INPUT_MAX_LENGTH,
  PHONE_INPUT_MAX_LENGTH,
  validateCustomer,
  type CustomerField,
  type CustomerFormValues,
} from './validation'

type CustomerFormProps = {
  customer?: CustomerDetails
  busy: boolean
  submitLabel: string
  onChange?: () => void
  onCancel?: () => void
  onSubmit: (input: CustomerInput | UpdateCustomerInput) => Promise<SubmitOutcome<CustomerField>> | void
}

const CONTACT_ERROR_ID = 'customer-contact-error'

// A confirmed discard that leaves this form mounted (for example a same-route Back or Forward)
// must really reset it, so the guard's cleared dirty flag never disagrees with the values: the
// body is remounted from the loaded values, which also drops every validation state.
export function CustomerForm(props: CustomerFormProps) {
  const [resetCount, setResetCount] = useState(0)
  return (
    <CustomerFormBody
      key={resetCount}
      {...props}
      onDiscard={() => setResetCount((current) => current + 1)}
    />
  )
}

function CustomerFormBody({
  customer,
  busy,
  submitLabel,
  onCancel,
  onChange,
  onSubmit,
  onDiscard,
}: CustomerFormProps & { onDiscard: () => void }) {
  const initialDisplayName = customer?.displayName ?? ''
  const initialPhone = customer?.phone ?? ''
  const initialEmail = customer?.email ?? ''

  const [displayName, setDisplayName] = useState(initialDisplayName)
  const [phone, setPhone] = useState(initialPhone)
  const [email, setEmail] = useState(initialEmail)

  const values: CustomerFormValues = { displayName, phone, email, contact: '' }
  const { errors, controlRef, touch, edited, validateAll, applyServerErrors } =
    useFieldValidation<CustomerField, CustomerFormValues>({
      order: CUSTOMER_FIELD_ORDER,
      values,
      validate: validateCustomer,
      // The contact error appears only after a submit attempt (or from the backend), never
      // while the user is still on their way to filling the other contact value.
      isEmpty: (field, current) =>
        field === 'contact' ? true : canonicalOptional(current[field]) === '',
    })

  const isDirty =
    displayName !== initialDisplayName || phone !== initialPhone || email !== initialEmail

  const guard = useGuardedFormState(isDirty, onDiscard)

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (busy) return
    if (!validateAll()) return

    const rawPhone = phone.trim()
    const rawEmail = email.trim()
    const common: CustomerInput = {
      displayName: displayName.trim(),
      phone: rawPhone === '' ? undefined : rawPhone,
      email: rawEmail === '' ? undefined : rawEmail,
    }

    const outcome = await onSubmit(
      customer ? { ...common, expectedVersion: customer.version } : common,
    )
    if (outcome?.fieldErrors) applyServerErrors(outcome.fieldErrors)
  }

  const describedBy = (ownId: string, own: string | undefined) => {
    const ids = [own ? `${ownId}-error` : undefined, errors.contact ? CONTACT_ERROR_ID : undefined]
    return ids.filter(Boolean).join(' ') || undefined
  }

  const phoneProps = fieldControlProps('customer-phone', errors.phone)
  const emailProps = fieldControlProps('customer-email', errors.email)

  return (
    <form onChange={onChange} className="business-form" onSubmit={submit} noValidate>
      <div className="form-field">
        <label htmlFor="customer-display-name">Име</label>
        <input
          {...fieldControlProps('customer-display-name', errors.displayName)}
          ref={controlRef('displayName')}
          name="displayName"
          type="text"
          autoComplete="off"
          value={displayName}
          required
          onBlur={() => touch('displayName')}
          onChange={(event) => {
            edited('displayName')
            setDisplayName(event.target.value)
          }}
        />
        <FieldError id="customer-display-name" error={errors.displayName} />
      </div>
      <fieldset className="contact-group">
        <legend className="visually-hidden">Данни за връзка</legend>
        <div className="form-field">
          <label htmlFor="customer-phone">Телефон</label>
          <input
            {...phoneProps}
            aria-invalid={errors.phone || errors.contact ? true : undefined}
            aria-describedby={describedBy('customer-phone', errors.phone)}
            ref={(element) => {
              controlRef('phone')(element)
              controlRef('contact')(element)
            }}
            name="phone"
            type="tel"
            autoComplete="off"
            value={phone}
            maxLength={PHONE_INPUT_MAX_LENGTH}
            onBlur={() => touch('phone')}
            onChange={(event) => {
              edited('phone')
              edited('contact')
              setPhone(event.target.value)
            }}
          />
          <FieldError id="customer-phone" error={errors.phone} />
        </div>
        <div className="form-field">
          <label htmlFor="customer-email">Имейл</label>
          <input
            {...emailProps}
            aria-invalid={errors.email || errors.contact ? true : undefined}
            aria-describedby={describedBy('customer-email', errors.email)}
            ref={controlRef('email')}
            name="email"
            type="email"
            autoComplete="off"
            value={email}
            maxLength={EMAIL_INPUT_MAX_LENGTH}
            onBlur={() => touch('email')}
            onChange={(event) => {
              edited('email')
              edited('contact')
              setEmail(event.target.value)
            }}
          />
          <FieldError id="customer-email" error={errors.email} />
        </div>
        {errors.contact ? (
          <p id={CONTACT_ERROR_ID} className="field-error">
            {errors.contact}
          </p>
        ) : (
          <p className="field-note">Попълнете телефон или имейл.</p>
        )}
      </fieldset>
      <div className="action-group">
        <Button disabled={busy}>{busy ? 'Запазване…' : submitLabel}</Button>
        {onCancel && (
          <Button
            type="button"
            variant="secondary"
            disabled={busy}
            onClick={() => guard.guard(() => onCancel())}
          >
            Отказ
          </Button>
        )}
      </div>
    </form>
  )
}
