import { useEffect, useRef, useState } from 'react'
import { useFeedback, errorCategory } from '../../ui/useFeedback'
import { Button } from '../../ui/Button'
import { useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import { createService, type CreateServiceInput } from './api'
import { ServiceForm } from './ServiceForm'
import { isAuthenticationRequired, safeServiceError } from './errors'

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

  useEffect(() => {
    if (error) errorMessage.current?.focus()
  }, [error])

  const submit = async (input: CreateServiceInput) => {
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
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeServiceError(caught, 'Услугата не може да бъде създадена.'),
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
          onSubmit={(input) => void submit(input as CreateServiceInput)}
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
