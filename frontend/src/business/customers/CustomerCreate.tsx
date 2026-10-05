import { useLayoutEffect, useRef, useState } from 'react'
import { useFeedback, errorCategory } from '../../ui/useFeedback'
import { Button } from '../../ui/Button'
import { useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import type { SubmitOutcome } from '../../ui/formValidation'
import { createCustomer, type CustomerInput } from './api'
import { CustomerForm } from './CustomerForm'
import {
  customerErrorMessage,
  customerFieldErrors,
  isAuthenticationRequired,
  isBusinessSuspended,
} from './errors'
import type { CustomerField } from './validation'

type CustomerCreateProps = {
  readOnly: boolean
  onAuthenticationRequired: (detail: string) => void
  onBusinessSuspended: () => void
  onCreated: (customerId: string) => void
  onCancel: () => void
}

export function CustomerCreate({
  readOnly,
  onAuthenticationRequired,
  onBusinessSuspended,
  onCreated,
  onCancel,
}: CustomerCreateProps) {
  const [busy, setBusy] = useState(false)
  const { feedback: error, setFeedback, beginFeedback } = useFeedback('customer-create')
  const guard = useUnsavedChangesGuard()
  const submitting = useRef(false)
  const errorMessage = useRef<HTMLParagraphElement>(null)

  useLayoutEffect(() => {
    if (error) errorMessage.current?.focus()
  }, [error])

  const submit = async (input: CustomerInput): Promise<SubmitOutcome<CustomerField>> => {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    const publish = beginFeedback()
    try {
      const created = await createCustomer(input)
      if (publish(null)) {
        // The form is still registered dirty (it has not unmounted yet); clear it explicitly so
        // the navigation to the created Customer shows no false unsaved-changes prompt.
        guard.unregisterDirty()
        onCreated(created.id)
      }
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      const fieldErrors = customerFieldErrors(caught)
      if (fieldErrors) return { fieldErrors }
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: customerErrorMessage(caught, 'Клиентът не може да бъде добавен.'),
      })
      if (isBusinessSuspended(caught)) onBusinessSuspended()
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <div className="platform-content">
      <div className="business-page-actions">
        <Button type="button" variant="secondary" disabled={busy} onClick={onCancel}>
          Обратно към клиентите
        </Button>
      </div>
      {readOnly ? (
        error ? (
          <p
            ref={errorMessage}
            className="status-message status-error"
            role="alert"
            tabIndex={-1}
          >
            {error.text}
          </p>
        ) : (
          // The shell's suspended notice already says why; the page keeps only the way back.
          null
        )
      ) : (
        <section className="business-section customer-form-section" aria-label="Данни за клиента">
          <CustomerForm
            busy={busy}
            submitLabel="Добави"
            onChange={() => setFeedback(null)}
            onSubmit={(input) => submit(input as CustomerInput)}
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
      )}
    </div>
  )
}
