import { useState, type FormEvent, type InvalidEvent } from 'react'
import { Button } from '../../ui/Button'
import { useGuardedFormState } from '../../ui/UnsavedChangesGuard'
import { type CreateStaffMemberInput, type StaffMemberDetails, type UpdateStaffMemberInput } from './api'

const DISPLAY_NAME_MAX_LENGTH = 200
const CONTACT_EMAIL_MAX_LENGTH = 320
const CONTACT_PHONE_MAX_LENGTH = 50

type StaffFormProps = {
  staffMember?: StaffMemberDetails
  busy: boolean
  submitLabel: string
  onChange?: () => void
  onCancel?: () => void
  onSubmit: (input: CreateStaffMemberInput | UpdateStaffMemberInput) => void
}

function nameValidationMessage(field: HTMLInputElement): string {
  if (field.validity.valueMissing) return 'Моля, попълнете това поле.'
  if (field.validity.tooLong) {
    return `Полето може да съдържа най-много ${field.maxLength} знака.`
  }
  return ''
}

function emailValidationMessage(field: HTMLInputElement): string {
  if (field.validity.tooLong) {
    return `Полето може да съдържа най-много ${field.maxLength} знака.`
  }
  if (field.validity.typeMismatch) {
    return 'Въведете валиден имейл адрес.'
  }
  return ''
}

function phoneValidationMessage(field: HTMLInputElement): string {
  if (field.validity.tooLong) {
    return `Полето може да съдържа най-много ${field.maxLength} знака.`
  }
  return ''
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

  const isDirty =
    displayName !== initialDisplayName ||
    contactEmail !== initialContactEmail ||
    contactPhone !== initialContactPhone

  const guard = useGuardedFormState(isDirty, () => undefined)

  const requestCancel = () => {
    guard.guard(() => onCancel?.())
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (busy) return

    const data = new FormData(event.currentTarget)
    const rawContactEmail = String(data.get('contactEmail') ?? '').trim()
    const rawContactPhone = String(data.get('contactPhone') ?? '').trim()
    const common = {
      displayName: String(data.get('displayName') ?? '').trim(),
      contactEmail: rawContactEmail === '' ? undefined : rawContactEmail,
      contactPhone: rawContactPhone === '' ? undefined : rawContactPhone,
    }

    if (staffMember) {
      onSubmit({ ...common, expectedVersion: staffMember.version })
      return
    }

    onSubmit(common)
  }

  return (
    <form onChange={onChange} className="business-form" onSubmit={submit}>
      <label>
        Име на члена на екипа
        <input
          name="displayName"
          type="text"
          value={displayName}
          required
          maxLength={DISPLAY_NAME_MAX_LENGTH}
          onChange={(event) => setDisplayName(event.target.value)}
          onInvalid={(event: InvalidEvent<HTMLInputElement>) => {
            event.currentTarget.setCustomValidity('')
            event.currentTarget.setCustomValidity(nameValidationMessage(event.currentTarget))
          }}
          onInput={(event) => event.currentTarget.setCustomValidity('')}
        />
      </label>
      <label>
        Имейл за връзка (по избор)
        <input
          name="contactEmail"
          type="email"
          value={contactEmail}
          maxLength={CONTACT_EMAIL_MAX_LENGTH}
          onChange={(event) => setContactEmail(event.target.value)}
          onInvalid={(event: InvalidEvent<HTMLInputElement>) => {
            event.currentTarget.setCustomValidity('')
            event.currentTarget.setCustomValidity(emailValidationMessage(event.currentTarget))
          }}
          onInput={(event) => event.currentTarget.setCustomValidity('')}
        />
      </label>
      <label>
        Телефон за връзка (по избор)
        <input
          name="contactPhone"
          type="tel"
          value={contactPhone}
          maxLength={CONTACT_PHONE_MAX_LENGTH}
          onChange={(event) => setContactPhone(event.target.value)}
          onInvalid={(event: InvalidEvent<HTMLInputElement>) => {
            event.currentTarget.setCustomValidity('')
            event.currentTarget.setCustomValidity(phoneValidationMessage(event.currentTarget))
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
