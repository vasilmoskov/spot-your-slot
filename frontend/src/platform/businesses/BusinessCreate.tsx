import { useRef, useState, useLayoutEffect } from 'react'
import { useFeedback, errorCategory } from '../../ui/useFeedback'
import { Button } from '../../ui/Button'
import { useGuardedFormState } from '../../ui/UnsavedChangesGuard'
import { backendFieldErrors, type SubmitOutcome } from '../../ui/formValidation'
import { createBusiness, type CreateBusinessInput } from './api'
import { BusinessForm } from './BusinessForm'
import { isAuthenticationRequired, safeBusinessError } from './errors'
import { BUSINESS_BACKEND_FIELDS, type BusinessField } from './validation'

type BusinessCreateProps = {
  onAuthenticationRequired: (detail: string) => void
  onCreated: (businessId: string) => void
  onCancel: () => void
}

export function BusinessCreate({
  onAuthenticationRequired,
  onCreated,
  onCancel,
}: BusinessCreateProps) {
  const [busy, setBusy] = useState(false)
  const { feedback: error, setFeedback, beginFeedback } = useFeedback('business-create')
  const [dirty, setDirty] = useState(false)
  // Bumped when the shared guard discards the form, remounting it empty.
  const [resetCount, setResetCount] = useState(0)
  const guard = useGuardedFormState(dirty, () => {
    setDirty(false)
    setResetCount((current) => current + 1)
  })
  const submitting = useRef(false)
  const errorMessage = useRef<HTMLParagraphElement>(null)

  useLayoutEffect(() => {
    if (error) errorMessage.current?.focus()
  }, [error])

  const submit = async (input: CreateBusinessInput): Promise<SubmitOutcome<BusinessField>> => {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    const publish = beginFeedback()
    try {
      const created = await createBusiness(input)
      if (publish(null)) {
        // Still registered dirty until it unmounts; clear it so navigating to
        // the created Business shows no false unsaved-changes prompt.
        guard.unregisterDirty()
        onCreated(created.id)
      }
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      const fieldErrors = backendFieldErrors(caught, BUSINESS_BACKEND_FIELDS)
      if (fieldErrors) return { fieldErrors }
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeBusinessError(caught, 'Бизнесът не може да бъде създаден.'),
      })
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <div className="platform-content">
      <div className="business-page-actions">
        <Button type="button" variant="secondary" onClick={onCancel}>
          Обратно към бизнесите
        </Button>
      </div>
      <section
        className="business-section"
        aria-labelledby="create-business-heading"
      >
        <h2 id="create-business-heading">Данни за бизнеса</h2>
        <p className="section-introduction">
          След създаването ще можете да поканите собственик и да активирате
          бизнеса.
        </p>
        <BusinessForm
          key={resetCount}
          busy={busy}
          onDirtyChange={setDirty}
          submitLabel="Създай бизнес"
          onChange={() => setFeedback(null)}
          onSubmit={(input) => submit(input as CreateBusinessInput)}
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
