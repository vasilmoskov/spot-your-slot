import { useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import { ApiError } from '../../identity/api'
import type { SubmitOutcome } from '../../ui/formValidation'
import { createCustomer, type CustomerDetails, type CustomerInput } from '../customers/api'
import { CustomerForm } from '../customers/CustomerForm'
import {
  customerErrorMessage,
  customerFieldErrors,
  isAuthenticationRequired,
} from '../customers/errors'
import type { CustomerField } from '../customers/validation'

type NewCustomerDialogProps = {
  onCreated: (customer: CustomerDetails) => void
  onClose: () => void
  onAuthenticationRequired: (detail: string) => void
}

const UNCERTAIN_TEXT =
  'Не е ясно дали клиентът е добавен. Затворете прозореца и потърсете клиента в списъка, преди да опитате пак.'

// The outcome of a create that cannot be ruled out: a response that never arrived or an unexpected server error.
function isUncertain(error: unknown): boolean {
  if (!(error instanceof ApiError)) return true
  return error.status >= 500 || (error.status >= 200 && error.status < 300)
}

/**
 * Creates a Customer from inside the manual-create flow with the very form of the Customers screen (fields, rules,
 * wording and errors). The form runs unguarded: the Appointment draft behind the dialog keeps its own unsaved-changes
 * state, and cancelling closes only this dialog. Nothing typed here reaches the URL or any storage. Creating the
 * Customer is a separate persisted operation: it stays even if the Appointment is never created.
 */
export function NewCustomerDialog({ onCreated, onClose, onAuthenticationRequired }: NewCustomerDialogProps) {
  const [busy, setBusy] = useState(false)
  // Sticky after an uncertain result: the same Customer is never submitted again from this dialog.
  const [uncertain, setUncertain] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [typed, setTyped] = useState(false)
  const submitting = useRef(false)
  const closed = useRef(false)
  const panelRef = useRef<HTMLDivElement>(null)
  const failureRef = useRef<HTMLParagraphElement>(null)
  const handlers = useRef({ onCreated, onAuthenticationRequired })
  handlers.current = { onCreated, onAuthenticationRequired }

  useLayoutEffect(() => {
    closed.current = false
    panelRef.current?.querySelector<HTMLElement>('input')?.focus()
    return () => {
      closed.current = true
    }
  }, [])

  useLayoutEffect(() => {
    if (failure) failureRef.current?.focus()
  }, [failure])

  async function submit(input: CustomerInput): Promise<SubmitOutcome<CustomerField>> {
    if (submitting.current || uncertain) return
    submitting.current = true
    setBusy(true)
    setFailure(null)
    try {
      const created = await createCustomer(input)
      if (!closed.current) handlers.current.onCreated(created)
    } catch (caught) {
      if (closed.current) return
      if (isAuthenticationRequired(caught)) {
        handlers.current.onAuthenticationRequired(caught.detail)
        return
      }
      const fieldErrors = customerFieldErrors(caught)
      if (fieldErrors) return { fieldErrors }
      if (isUncertain(caught)) {
        setUncertain(true)
        setFailure(UNCERTAIN_TEXT)
      } else {
        setFailure(customerErrorMessage(caught, 'Клиентът не може да бъде добавен.'))
      }
    } finally {
      submitting.current = false
      if (!closed.current) setBusy(false)
    }
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.stopPropagation()
      // A typed form or a running request is not discarded by a stray key.
      if (!busy && !typed) onClose()
      return
    }
    if (event.key !== 'Tab') return
    const focusable = Array.from(
      panelRef.current?.querySelectorAll<HTMLElement>('input:not(:disabled), button:not(:disabled), [tabindex="0"]') ?? [],
    )
    if (focusable.length === 0) return
    const first = focusable[0]!
    const last = focusable[focusable.length - 1]!
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <div className="unsaved-changes-overlay" onKeyDown={onKeyDown}>
      <div
        ref={panelRef}
        className="confirmation-panel unsaved-changes-panel new-customer-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="new-customer-title"
      >
        <h4 id="new-customer-title">Нов клиент</h4>
        <CustomerForm
          guarded={false}
          busy={busy}
          frozen={uncertain}
          submitLabel="Добави"
          onChange={() => {
            setTyped(true)
            setFailure(null)
          }}
          onCancel={onClose}
          onSubmit={(input) => submit(input as CustomerInput)}
        />
        {failure && (
          <p ref={failureRef} className="status-message status-error" role="alert" tabIndex={-1}>
            {failure}
          </p>
        )}
      </div>
    </div>
  )
}
