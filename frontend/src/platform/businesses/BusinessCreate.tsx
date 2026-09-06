import { useEffect, useRef, useState } from 'react'
import { createBusiness, type CreateBusinessInput } from './api'
import { BusinessForm } from './BusinessForm'
import { isAuthenticationRequired, safeBusinessError } from './errors'

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
  const [error, setError] = useState<string | null>(null)
  const submitting = useRef(false)
  const errorMessage = useRef<HTMLParagraphElement>(null)

  useEffect(() => {
    if (error) errorMessage.current?.focus()
  }, [error])

  const submit = async (input: CreateBusinessInput) => {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    setError(null)
    try {
      const created = await createBusiness(input)
      onCreated(created.id)
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setError(
        safeBusinessError(caught, 'Бизнесът не може да бъде създаден.'),
      )
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <div className="platform-content">
      <div className="business-page-actions">
        <button type="button" className="secondary-button" onClick={onCancel}>
          Обратно към бизнесите
        </button>
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
          busy={busy}
          submitLabel="Създай бизнес"
          onSubmit={(input) => void submit(input as CreateBusinessInput)}
        />
        {error && (
          <p
            ref={errorMessage}
            className="status-message status-error"
            role="alert"
            tabIndex={-1}
          >
            {error}
          </p>
        )}
      </section>
    </div>
  )
}
