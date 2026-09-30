import { useEffect, useRef, useState, type FormEvent, type InvalidEvent } from 'react'
import { Button } from '../../ui/Button'
import {
  FieldError,
  fieldControlProps,
  useFieldValidation,
  type SubmitOutcome,
} from '../../ui/formValidation'
import {
  type BusinessDetails,
  type BusinessType,
  type CreateBusinessInput,
  type UpdateBusinessInput,
} from './api'
import { BUSINESS_TYPE_OPTIONS } from './presentation'
import {
  BUSINESS_FIELD_ORDER,
  IMMUTABLE_SLUG_NOTE,
  validateBusinessSlug,
  type BusinessField,
} from './validation'

type BusinessFormProps = {
  business?: BusinessDetails
  busy: boolean
  submitLabel: string
  onChange?: () => void
  // Reports whether the current values differ from the loaded/empty ones, so the
  // owning page can register one shared unsaved-changes guard for it.
  onDirtyChange?: (dirty: boolean) => void
  onCancel?: () => void
  onSubmit: (
    input: CreateBusinessInput | UpdateBusinessInput,
  ) => Promise<SubmitOutcome<BusinessField>> | void
}

type FieldProps = {
  label: string
  name: string
  defaultValue?: string | undefined
  required?: boolean
  maxLength: number
  type?: 'text' | 'email' | 'tel'
}

function localizedValidationMessage(
  field: HTMLInputElement | HTMLTextAreaElement,
): string {
  if (field.validity.valueMissing) {
    return field.type === 'email'
      ? 'Моля, въведете имейл адрес.'
      : 'Моля, попълнете това поле.'
  }
  if (field.validity.typeMismatch && field.type === 'email') {
    return 'Моля, въведете валиден имейл адрес.'
  }
  if (field.validity.tooLong) {
    return `Полето може да съдържа най-много ${field.maxLength} знака.`
  }
  return ''
}

function validateField(
  event: InvalidEvent<HTMLInputElement | HTMLTextAreaElement>,
) {
  event.currentTarget.setCustomValidity('')
  event.currentTarget.setCustomValidity(localizedValidationMessage(event.currentTarget))
}

function clearValidation(
  event: FormEvent<HTMLInputElement | HTMLTextAreaElement>,
) {
  event.currentTarget.setCustomValidity('')
}

function TextField({
  label,
  name,
  defaultValue = '',
  required = false,
  maxLength,
  type = 'text',
}: FieldProps) {
  return (
    <label>
      {label}
      <input
        name={name}
        type={type}
        defaultValue={defaultValue}
        required={required}
        maxLength={maxLength}
        onInvalid={validateField}
        onInput={clearValidation}
      />
    </label>
  )
}

const TEXT_FIELD_NAMES = [
  'displayName',
  'slug',
  'phone',
  'contactEmail',
  'street',
  'streetNumber',
  'postalCode',
  'city',
  'addressDetails',
  'description',
] as const

function initialText(business: BusinessDetails | undefined, name: (typeof TEXT_FIELD_NAMES)[number]): string {
  return business?.[name] ?? ''
}

function value(data: FormData, name: string): string {
  return String(data.get(name) ?? '')
}

function optionalValue(data: FormData, name: string): string | undefined {
  const fieldValue = value(data, name)
  return fieldValue === '' ? undefined : fieldValue
}

export function BusinessForm({
  business,
  busy,
  submitLabel,
  onCancel,
  onChange,
  onDirtyChange,
  onSubmit,
}: BusinessFormProps) {
  const initialType: BusinessType = business?.businessType ?? 'OTHER'
  const [businessType, setBusinessType] = useState<BusinessType>(initialType)
  const formRef = useRef<HTMLFormElement>(null)
  const dirtyReporter = useRef(onDirtyChange)
  dirtyReporter.current = onDirtyChange
  // After first activation (ACTIVE or SUSPENDED) the public address is fixed:
  // it is shown, never offered for editing, and never submitted as a change.
  const slugEditable = !business || business.status === 'DRAFT'
  const storedSlug = business?.slug
  const [slug, setSlug] = useState(storedSlug ?? '')
  const { errors, controlRef, touch, edited, validateAll, applyServerErrors } =
    useFieldValidation<BusinessField, { slug: string }>({
      order: BUSINESS_FIELD_ORDER,
      values: { slug },
      validate: (current) => {
        const message = slugEditable
          ? validateBusinessSlug(current.slug, storedSlug)
          : undefined
        return message ? { slug: message } : {}
      },
      isEmpty: (_field, current) => current.slug.trim() === '',
    })

  // The inputs are uncontrolled, so dirtiness is measured from the live form
  // values against the loaded (or empty) ones after every edit.
  const reportDirty = (type: BusinessType) => {
    const form = formRef.current
    if (!form) return
    const data = new FormData(form)
    const changed =
      type !== initialType ||
      TEXT_FIELD_NAMES.some(
        (name) =>
          (name !== 'slug' || slugEditable) &&
          value(data, name) !== initialText(business, name),
      )
    dirtyReporter.current?.(changed)
  }

  // An unmounted form has nothing left to lose.
  useEffect(() => () => dirtyReporter.current?.(false), [])

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (busy) return
    if (!validateAll()) return

    const data = new FormData(event.currentTarget)
    const common = {
      slug: slugEditable ? value(data, 'slug') : (storedSlug ?? ''),
      displayName: value(data, 'displayName'),
      businessType,
      description: optionalValue(data, 'description'),
      city: optionalValue(data, 'city'),
      postalCode: optionalValue(data, 'postalCode'),
      street: optionalValue(data, 'street'),
      streetNumber: optionalValue(data, 'streetNumber'),
      addressDetails: optionalValue(data, 'addressDetails'),
      phone: optionalValue(data, 'phone'),
      contactEmail: optionalValue(data, 'contactEmail'),
    }

    const outcome = await onSubmit(
      business
        ? {
            ...common,
            timezone: business.timezone,
            expectedVersion: business.version,
          }
        : { ...common },
    )
    if (outcome?.fieldErrors) applyServerErrors(outcome.fieldErrors)
  }

  return (
    <form
      ref={formRef}
      onChange={() => {
        reportDirty(businessType)
        onChange?.()
      }}
      onInput={() => reportDirty(businessType)}
      className="business-form"
      onSubmit={submit}
    >
      <div className="business-information-columns">
        <div className="business-information-column">
          <TextField
            name="displayName"
            label="Име на бизнеса"
            defaultValue={business?.displayName}
            required
            maxLength={200}
          />
          {slugEditable ? (
            <div className="form-field">
              <label htmlFor="business-slug">Идентификатор в уеб адреса</label>
              <input
                {...fieldControlProps('business-slug', errors.slug)}
                ref={controlRef('slug')}
                name="slug"
                type="text"
                value={slug}
                required
                maxLength={100}
                onBlur={() => touch('slug')}
                onInvalid={validateField}
                onInput={clearValidation}
                onChange={(event) => {
                  edited('slug')
                  setSlug(event.target.value)
                }}
              />
              <FieldError id="business-slug" error={errors.slug} />
            </div>
          ) : (
            <div className="form-field">
              <dl className="business-details-list">
                <div>
                  <dt>Идентификатор в уеб адреса</dt>
                  <dd>{storedSlug}</dd>
                </div>
              </dl>
              <p id="business-slug-note" className="field-note">
                {IMMUTABLE_SLUG_NOTE}
              </p>
            </div>
          )}
          <label>
            Дейност
            <select
              name="businessType"
              value={businessType}
              onChange={(event) => {
                const next = event.target.value as BusinessType
                setBusinessType(next)
                reportDirty(next)
              }}
            >
              {BUSINESS_TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <TextField
            name="phone"
            label="Телефон (по избор)"
            type="tel"
            defaultValue={business?.phone ?? ''}
            maxLength={50}
          />
          <TextField
            name="contactEmail"
            label="Имейл за контакт (по избор)"
            type="email"
            defaultValue={business?.contactEmail ?? ''}
            maxLength={320}
          />
        </div>
        <div className="business-information-column">
          <TextField
            name="street"
            label="Улица (по избор)"
            defaultValue={business?.street ?? ''}
            maxLength={200}
          />
          <TextField
            name="streetNumber"
            label="Номер (по избор)"
            defaultValue={business?.streetNumber ?? ''}
            maxLength={50}
          />
          <TextField
            name="postalCode"
            label="Пощенски код (по избор)"
            defaultValue={business?.postalCode ?? ''}
            maxLength={20}
          />
          <TextField
            name="city"
            label="Град (по избор)"
            defaultValue={business?.city ?? ''}
            maxLength={100}
          />
          <TextField
            name="addressDetails"
            label="Допълнителни указания (по избор)"
            defaultValue={business?.addressDetails ?? ''}
            maxLength={500}
          />
        </div>
      </div>
      <label>
        Описание (по избор)
        <textarea
          name="description"
          defaultValue={business?.description ?? ''}
          maxLength={2000}
          rows={5}
          onInvalid={validateField}
          onInput={clearValidation}
        />
      </label>
      <div className="action-group">
        <Button disabled={busy}>
          {busy ? 'Запазване…' : submitLabel}
        </Button>
        {onCancel && (
          <Button
            type="button"
            variant="secondary"
            disabled={busy}
            onClick={onCancel}
          >
            Отказ
          </Button>
        )}
      </div>
    </form>
  )
}
