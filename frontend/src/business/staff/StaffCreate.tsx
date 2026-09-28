import { useEffect, useRef, useState } from 'react'
import { useFeedback, errorCategory } from '../../ui/useFeedback'
import { Button } from '../../ui/Button'
import { useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import { createStaffMember, type CreateStaffMemberInput } from './api'
import { StaffForm } from './StaffForm'
import { isAuthenticationRequired, safeStaffError } from './errors'

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

  useEffect(() => {
    if (error) errorMessage.current?.focus()
  }, [error])

  const submit = async (input: CreateStaffMemberInput) => {
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
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeStaffError(caught, 'Членът на екипа не може да бъде създаден.'),
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
        <p className="section-introduction">
          Бизнесът е временно спрян — нови членове на екипа не могат да бъдат добавяни.
        </p>
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
          onSubmit={(input) => void submit(input as CreateStaffMemberInput)}
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
