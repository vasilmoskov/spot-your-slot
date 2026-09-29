import { useRef, useState, useLayoutEffect } from 'react'
import { useFeedback, errorCategory } from '../../ui/useFeedback'
import { Button } from '../../ui/Button'
import { useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import { createService, type CreateServiceInput } from './api'
import { ServiceForm } from './ServiceForm'
import { isAuthenticationRequired, safeServiceError } from './errors'
import { backendFieldErrors, type SubmitOutcome } from '../../ui/formValidation'
import {
  SERVICE_BACKEND_FIELDS,
  SERVICE_REJECTED_MESSAGE,
  type ServiceField,
} from './validation'

type ServiceCreateProps = {
  readOnly: boolean
  onAuthenticationRequired: (detail: string) => void
  onCreated: (serviceId: string) => void
  onCancel: () => void
}

export function ServiceCreate({
  readOnly,
  onAuthenticationRequired,
  onCreated,
  onCancel,
}: ServiceCreateProps) {
  const [busy, setBusy] = useState(false)
  const { feedback: error, setFeedback, beginFeedback } = useFeedback('service-create')
  const guard = useUnsavedChangesGuard()
  const submitting = useRef(false)
  const errorMessage = useRef<HTMLParagraphElement>(null)

  useLayoutEffect(() => {
    if (error) errorMessage.current?.focus()
  }, [error])

  const submit = async (input: CreateServiceInput): Promise<SubmitOutcome<ServiceField>> => {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    const publish = beginFeedback()
    try {
      const created = await createService(input)
      if (publish(null)) {
        // The form is still registered dirty at this point (it has not
        // unmounted yet); clear it explicitly so the guarded navigation to
        // the created Service does not show a false unsaved-changes prompt.
        guard.unregisterDirty()
        onCreated(created.id)
      }
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      const fieldErrors = backendFieldErrors(caught, SERVICE_BACKEND_FIELDS)
      if (fieldErrors) return { fieldErrors }
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeServiceError(
          caught,
          'Услугата не може да бъде създадена.',
          SERVICE_REJECTED_MESSAGE,
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
            Обратно към услугите
          </Button>
        </div>
        <p className="section-introduction">
          Бизнесът е временно спрян — нови услуги не могат да бъдат създавани.
        </p>
      </div>
    )
  }

  return (
    <div className="platform-content">
      <div className="business-page-actions">
        <Button type="button" variant="secondary" onClick={onCancel}>
          Обратно към услугите
        </Button>
      </div>
      <section className="business-section" aria-labelledby="create-service-heading">
        <h2 id="create-service-heading">Данни за услугата</h2>
        <ServiceForm
          busy={busy}
          submitLabel="Създай услуга"
          onChange={() => setFeedback(null)}
          onSubmit={(input) => submit(input as CreateServiceInput)}
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
