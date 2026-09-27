import { useState, type FormEvent, type InvalidEvent } from 'react'
import { Button } from '../../ui/Button'
import { useGuardedFormState } from '../../ui/UnsavedChangesGuard'
import { type CreateServiceInput, type ServiceDetails, type UpdateServiceInput } from './api'

const NAME_MAX_LENGTH = 200
const DESCRIPTION_MAX_LENGTH = 2_000
const MIN_DURATION_MINUTES = 1
const MAX_DURATION_MINUTES = 480
const PRICE_PATTERN = /^\d{1,10}(\.\d{1,2})?$/

type ServiceFormProps = {
  service?: ServiceDetails
  busy: boolean
  submitLabel: string
  onChange?: () => void
  onCancel?: () => void
  onSubmit: (input: CreateServiceInput | UpdateServiceInput) => void
}

function priceValidationMessage(field: HTMLInputElement): string {
  if (field.validity.valueMissing) return 'Моля, въведете цена.'
  if (field.validity.patternMismatch) {
    return 'Въведете цена като число с най-много 2 знака след десетичната точка.'
  }
  return ''
}

function nameValidationMessage(field: HTMLInputElement | HTMLTextAreaElement): string {
  if (field.validity.valueMissing) return 'Моля, попълнете това поле.'
  if (field.validity.tooLong) {
    return `Полето може да съдържа най-много ${field.maxLength} знака.`
  }
  return ''
}

function durationValidationMessage(field: HTMLInputElement): string {
  if (field.validity.valueMissing) return 'Моля, въведете продължителност.'
  if (field.validity.rangeUnderflow || field.validity.rangeOverflow) {
    return `Продължителността трябва да е между ${MIN_DURATION_MINUTES} и ${MAX_DURATION_MINUTES} минути.`
  }
  return 'Моля, въведете валидна продължителност в минути.'
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
  const [price, setPrice] = useState(initialPrice)

  const isDirty =
    name !== initialName ||
    description !== initialDescription ||
    durationMinutes !== initialDurationMinutes ||
    price !== initialPrice

  const guard = useGuardedFormState(isDirty, () => undefined)

  const requestCancel = () => {
    guard.guard(() => onCancel?.())
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (busy) return

    const data = new FormData(event.currentTarget)
    const rawDescription = String(data.get('description') ?? '').trim()
    const common = {
      name: String(data.get('name') ?? '').trim(),
      description: rawDescription === '' ? undefined : rawDescription,
      durationMinutes: Number(data.get('durationMinutes')),
      price: String(data.get('price') ?? '').trim(),
    }

    if (service) {
      onSubmit({ ...common, expectedVersion: service.version })
      return
    }

    onSubmit(common)
  }

  return (
    <form onChange={onChange} className="business-form" onSubmit={submit}>
      <label>
        Име на услугата
        <input
          name="name"
          type="text"
          value={name}
          required
          maxLength={NAME_MAX_LENGTH}
          onChange={(event) => setName(event.target.value)}
          onInvalid={(event: InvalidEvent<HTMLInputElement>) => {
            event.currentTarget.setCustomValidity('')
            event.currentTarget.setCustomValidity(nameValidationMessage(event.currentTarget))
          }}
          onInput={(event) => event.currentTarget.setCustomValidity('')}
        />
      </label>
      <label>
        Описание (по избор)
        <textarea
          name="description"
          value={description}
          maxLength={DESCRIPTION_MAX_LENGTH}
          rows={4}
          onChange={(event) => setDescription(event.target.value)}
          onInvalid={(event: InvalidEvent<HTMLTextAreaElement>) => {
            event.currentTarget.setCustomValidity('')
            event.currentTarget.setCustomValidity(nameValidationMessage(event.currentTarget))
          }}
          onInput={(event) => event.currentTarget.setCustomValidity('')}
        />
      </label>
      <label>
        Продължителност (минути)
        <input
          name="durationMinutes"
          type="number"
          inputMode="numeric"
          value={durationMinutes}
          required
          min={MIN_DURATION_MINUTES}
          max={MAX_DURATION_MINUTES}
          step={1}
          onChange={(event) => setDurationMinutes(event.target.value)}
          onInvalid={(event: InvalidEvent<HTMLInputElement>) => {
            event.currentTarget.setCustomValidity('')
            event.currentTarget.setCustomValidity(durationValidationMessage(event.currentTarget))
          }}
          onInput={(event) => event.currentTarget.setCustomValidity('')}
        />
      </label>
      <label>
        Цена (EUR)
        <input
          name="price"
          type="text"
          inputMode="decimal"
          value={price}
          required
          pattern={PRICE_PATTERN.source}
          onChange={(event) => setPrice(event.target.value)}
          onInvalid={(event: InvalidEvent<HTMLInputElement>) => {
            event.currentTarget.setCustomValidity('')
            event.currentTarget.setCustomValidity(priceValidationMessage(event.currentTarget))
          }}
          onInput={(event) => event.currentTarget.setCustomValidity('')}
        />
      </label>
      <div className="action-group">
        <Button disabled={busy}>{busy ? 'Запазване…' : submitLabel}</Button>
        {onCancel && (
          <Button type="button" variant="secondary" disabled={busy} onClick={requestCancel}>
            Отказ
          </Button>
        )}
      </div>
    </form>
  )
}
