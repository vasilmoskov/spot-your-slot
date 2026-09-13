import { useState, type FormEvent, type InvalidEvent } from 'react'
import { Button } from '../../ui/Button'
import {
  type BusinessDetails,
  type BusinessType,
  type CreateBusinessInput,
  type UpdateBusinessInput,
} from './api'
import { BUSINESS_TYPE_OPTIONS } from './presentation'

type BusinessFormProps = {
  business?: BusinessDetails
  busy: boolean
  submitLabel: string
  onChange?: () => void
  onCancel?: () => void
  onSubmit: (input: CreateBusinessInput | UpdateBusinessInput) => void
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
  onSubmit,
}: BusinessFormProps) {
  const [businessType, setBusinessType] = useState<BusinessType>(
    business?.businessType ?? 'OTHER',
  )

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (busy) return

    const data = new FormData(event.currentTarget)
    const common = {
      slug: value(data, 'slug'),
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

    if (business) {
      onSubmit({
        ...common,
        timezone: business.timezone,
        expectedVersion: business.version,
      })
      return
    }

    onSubmit({
      ...common,
    })
  }

  return (
    <form onChange={onChange} className="business-form" onSubmit={submit}>
      <div className="business-information-columns">
        <div className="business-information-column">
          <TextField
            name="displayName"
            label="Име на бизнеса"
            defaultValue={business?.displayName}
            required
            maxLength={200}
          />
          <TextField
            name="slug"
            label="Идентификатор в уеб адреса"
            defaultValue={business?.slug}
            required
            maxLength={100}
          />
          <label>
            Дейност
            <select
              name="businessType"
              value={businessType}
              onChange={(event) =>
                setBusinessType(event.target.value as BusinessType)
              }
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
