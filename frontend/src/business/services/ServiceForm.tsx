import { useState, type FormEvent } from 'react'
import { Button } from '../../ui/Button'
import {
  FieldError,
  fieldControlProps,
  useFieldValidation,
  type SubmitOutcome,
} from '../../ui/formValidation'
import { useGuardedFormState } from '../../ui/UnsavedChangesGuard'
import { type CreateServiceInput, type ServiceDetails, type UpdateServiceInput } from './api'
import {
  DESCRIPTION_MAX_LENGTH,
  MAX_DURATION_MINUTES,
  MIN_DURATION_MINUTES,
  NAME_MAX_LENGTH,
  SERVICE_FIELD_ORDER,
  validateService,
  type ServiceField,
  type ServiceFormValues,
} from './validation'

type ServiceFormProps = {
  service?: ServiceDetails
  busy: boolean
  submitLabel: string
  onChange?: () => void
  onCancel?: () => void
  onSubmit: (
    input: CreateServiceInput | UpdateServiceInput,
  ) => Promise<SubmitOutcome<ServiceField>> | void
}

export function ServiceForm({
  service,
  busy,
  submitLabel,
  onCancel,
  onChange,
  onSubmit,
}: ServiceFormProps) {
  const initialName = service?.name ?? ''
  const initialDescription = service?.description ?? ''
  const initialDurationMinutes = service ? String(service.durationMinutes) : ''
  const initialPrice = service ? service.price.toFixed(2) : ''

  const [name, setName] = useState(initialName)
  const [description, setDescription] = useState(initialDescription)
  const [durationMinutes, setDurationMinutes] = useState(initialDurationMinutes)
  const [durationBadInput, setDurationBadInput] = useState(false)
  const [price, setPrice] = useState(initialPrice)

  const values: ServiceFormValues = { name, description, durationMinutes, price, durationBadInput }
  const { errors, controlRef, touch, edited, validateAll, applyServerErrors } =
    useFieldValidation<ServiceField, ServiceFormValues>({
      order: SERVICE_FIELD_ORDER,
      values,
      validate: validateService,
      isEmpty: (field, current) =>
        field === 'durationMinutes'
          ? current.durationMinutes.trim() === '' && !current.durationBadInput
          : current[field].trim() === '',
    })

  const isDirty =
    name !== initialName ||
    description !== initialDescription ||
    durationMinutes !== initialDurationMinutes ||
    price !== initialPrice

  const guard = useGuardedFormState(isDirty, () => undefined)

  const requestCancel = () => {
    guard.guard(() => onCancel?.())
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (busy) return
    if (!validateAll()) return

    const rawDescription = description.trim()
    const common = {
      name: name.trim(),
      description: rawDescription === '' ? undefined : rawDescription,
      durationMinutes: Number(durationMinutes),
      price: price.trim(),
    }

    const outcome = await onSubmit(
      service ? { ...common, expectedVersion: service.version } : common,
    )
    if (outcome?.fieldErrors) applyServerErrors(outcome.fieldErrors)
  }

  return (
    <form onChange={onChange} className="business-form" onSubmit={submit} noValidate>
      <div className="form-field">
        <label htmlFor="service-name">Име на услугата</label>
        <input
          {...fieldControlProps('service-name', errors.name)}
          ref={controlRef('name')}
          name="name"
          type="text"
          value={name}
          required
          maxLength={NAME_MAX_LENGTH}
          onBlur={() => touch('name')}
          onChange={(event) => {
            edited('name')
            setName(event.target.value)
          }}
        />
        <FieldError id="service-name" error={errors.name} />
      </div>
      <div className="form-field">
        <label htmlFor="service-description">Описание (по избор)</label>
        <textarea
          {...fieldControlProps('service-description', errors.description)}
          ref={controlRef('description')}
          name="description"
          value={description}
          maxLength={DESCRIPTION_MAX_LENGTH}
          rows={4}
          onBlur={() => touch('description')}
          onChange={(event) => {
            edited('description')
            setDescription(event.target.value)
          }}
        />
        <FieldError id="service-description" error={errors.description} />
      </div>
      <div className="form-field">
        <label htmlFor="service-duration">Продължителност (минути)</label>
        <input
          {...fieldControlProps('service-duration', errors.durationMinutes)}
          ref={controlRef('durationMinutes')}
          name="durationMinutes"
          type="number"
          inputMode="numeric"
          value={durationMinutes}
          required
          min={MIN_DURATION_MINUTES}
          max={MAX_DURATION_MINUTES}
          step={1}
          onBlur={() => touch('durationMinutes')}
          onChange={(event) => {
            edited('durationMinutes')
            setDurationBadInput(event.target.validity.badInput)
            setDurationMinutes(event.target.value)
          }}
        />
        <FieldError id="service-duration" error={errors.durationMinutes} />
      </div>
      <div className="form-field">
        <label htmlFor="service-price">Цена (EUR)</label>
        <input
          {...fieldControlProps('service-price', errors.price)}
          ref={controlRef('price')}
          name="price"
          type="text"
          inputMode="decimal"
          value={price}
          required
          onBlur={() => touch('price')}
          onChange={(event) => {
            edited('price')
            setPrice(event.target.value)
          }}
        />
        <FieldError id="service-price" error={errors.price} />
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
