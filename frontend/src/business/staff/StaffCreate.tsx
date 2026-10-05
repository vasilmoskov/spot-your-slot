import { useRef, useState, useLayoutEffect } from 'react'
import { useFeedback, errorCategory } from '../../ui/useFeedback'
import { Button } from '../../ui/Button'
import { useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import { createStaffMember, type CreateStaffMemberInput } from './api'
import { StaffForm } from './StaffForm'
import { isAuthenticationRequired, safeStaffError } from './errors'
import { backendFieldErrors, type SubmitOutcome } from '../../ui/formValidation'
import { STAFF_BACKEND_FIELDS, STAFF_REJECTED_MESSAGE, type StaffField } from './validation'

type StaffCreateProps = {
  readOnly: boolean
  onAuthenticationRequired: (detail: string) => void
  onCreated: (staffMemberId: string) => void
  onCancel: () => void
}

export function StaffCreate({
  readOnly,
  onAuthenticationRequired,
  onCreated,
  onCancel,
}: StaffCreateProps) {
  const [busy, setBusy] = useState(false)
  const { feedback: error, setFeedback, beginFeedback } = useFeedback('staff-create')
  const guard = useUnsavedChangesGuard()
  const submitting = useRef(false)
  const errorMessage = useRef<HTMLParagraphElement>(null)

  useLayoutEffect(() => {
    if (error) errorMessage.current?.focus()
  }, [error])

  const submit = async (input: CreateStaffMemberInput): Promise<SubmitOutcome<StaffField>> => {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    const publish = beginFeedback()
    try {
      const created = await createStaffMember(input)
      if (publish(null)) {
        // The form is still registered dirty at this point (it has not
        // unmounted yet); clear it explicitly so the guarded navigation to
        // the created StaffMember does not show a false unsaved-changes prompt.
        guard.unregisterDirty()
        onCreated(created.id)
      }
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      const fieldErrors = backendFieldErrors(caught, STAFF_BACKEND_FIELDS)
      if (fieldErrors) return { fieldErrors }
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeStaffError(
          caught,
          'Членът на екипа не може да бъде създаден.',
          STAFF_REJECTED_MESSAGE,
        ),
      })
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  if (readOnly) {
    return (
      <div className="platform-content">
        <div className="business-page-actions">
          <Button type="button" variant="secondary" onClick={onCancel}>
            Обратно към екипа
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="platform-content">
      <div className="business-page-actions">
        <Button type="button" variant="secondary" onClick={onCancel}>
          Обратно към екипа
        </Button>
      </div>
      <section className="business-section" aria-labelledby="create-staff-heading">
        <h2 id="create-staff-heading">Данни за члена на екипа</h2>
        <StaffForm
          busy={busy}
          submitLabel="Добави член на екипа"
          onChange={() => setFeedback(null)}
          onSubmit={(input) => submit(input as CreateStaffMemberInput)}
        />
        {error && (
          <p
            ref={errorMessage}
            className="status-message status-error"
            role="alert"
            tabIndex={-1}
          >
            {error.text}
          </p>
        )}
      </section>
    </div>
  )
}
