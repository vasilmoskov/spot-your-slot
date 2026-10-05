import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { useFeedback, errorCategory, type Feedback } from '../../ui/useFeedback'
import { Button } from '../../ui/Button'
import { useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import type { SubmitOutcome } from '../../ui/formValidation'
import {
  getCustomer,
  updateCustomer,
  type CustomerDetails as CustomerDetailsResponse,
  type UpdateCustomerInput,
} from './api'
import { CustomerForm } from './CustomerForm'
import {
  customerErrorMessage,
  customerFieldErrors,
  customerLoadFailure,
  isAuthenticationRequired,
  isConcurrentUpdate,
} from './errors'
import { formatCustomerPhone } from './presentation'
import type { CustomerField } from './validation'

type CustomerDetailProps = {
  customerId: string
  // A success message to show once the Customer has finished loading (for example after create).
  initialSuccess?: string | undefined
  onInitialSuccessShown?: (() => void) | undefined
  onAuthenticationRequired: (detail: string) => void
  onBack: () => void
}

type LoadFailure = { message: string; retryable: boolean }

export function CustomerDetail({
  customerId,
  initialSuccess,
  onInitialSuccessShown,
  onAuthenticationRequired,
  onBack,
}: CustomerDetailProps) {
  const [customer, setCustomer] = useState<CustomerDetailsResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadFailure, setLoadFailure] = useState<LoadFailure | null>(null)
  const { feedback, setFeedback, beginFeedback } = useFeedback(customerId)
  const [editing, setEditing] = useState(false)
  const [updating, setUpdating] = useState(false)
  const guard = useUnsavedChangesGuard()
  const activeLoad = useRef<AbortController | null>(null)
  const updateInProgress = useRef(false)
  const errorBox = useRef<HTMLDivElement>(null)
  // Read through refs so the load effect depends on the Customer ID alone.
  const handlers = useRef({ onAuthenticationRequired, onInitialSuccessShown, initialSuccess })
  handlers.current = { onAuthenticationRequired, onInitialSuccessShown, initialSuccess }

  const load = useCallback(async () => {
    activeLoad.current?.abort()
    const controller = new AbortController()
    activeLoad.current = controller
    setLoading(true)
    setLoadFailure(null)
    setFeedback(null)
    try {
      const loaded = await getCustomer(customerId, controller.signal)
      if (!controller.signal.aborted && activeLoad.current === controller) {
        setCustomer(loaded)
        setEditing(false)
        const success = handlers.current.initialSuccess
        if (success) {
          setFeedback({ kind: 'success', text: success })
          handlers.current.onInitialSuccessShown?.()
        }
      }
    } catch (caught) {
      if (controller.signal.aborted || activeLoad.current !== controller) return
      if (isAuthenticationRequired(caught)) {
        handlers.current.onAuthenticationRequired(caught.detail)
        return
      }
      setCustomer(null)
      setLoadFailure(customerLoadFailure(caught, 'Данните за клиента не могат да бъдат заредени.'))
    } finally {
      if (activeLoad.current === controller) {
        activeLoad.current = null
        setLoading(false)
      }
    }
  }, [customerId, setFeedback])

  useEffect(() => {
    void load()
    return () => {
      const controller = activeLoad.current
      activeLoad.current = null
      controller?.abort()
    }
  }, [load])

  useLayoutEffect(() => {
    if (feedback?.kind === 'error') errorBox.current?.focus()
  }, [feedback])

  const update = async (input: UpdateCustomerInput): Promise<SubmitOutcome<CustomerField>> => {
    if (updateInProgress.current) return
    updateInProgress.current = true
    setUpdating(true)
    const publish = beginFeedback()
    try {
      const updated = await updateCustomer(customerId, input)
      if (!publish(null)) return
      guard.unregisterDirty()
      setCustomer(updated)
      setEditing(false)
      setFeedback({ kind: 'success', text: 'Промените са запазени.' })
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        handlers.current.onAuthenticationRequired(caught.detail)
        return
      }
      const fieldErrors = customerFieldErrors(caught)
      if (fieldErrors) return { fieldErrors }
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: customerErrorMessage(caught, 'Промените не могат да бъдат запазени.'),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      updateInProgress.current = false
      setUpdating(false)
    }
  }

  // Reloading discards an open, possibly dirty editor, so it goes through the shared
  // unsaved-changes guard like every other discarding transition.
  const reloadGuarded = () => guard.guard(() => void load())

  if (loading) {
    return (
      <div className="platform-content" aria-live="polite" aria-busy="true">
        <p className="business-list-state">Зареждане на клиента…</p>
      </div>
    )
  }

  if (loadFailure || !customer) {
    return (
      <div className="platform-content">
        <div className="feedback-action-layout">
          <div ref={errorBox} className="status-message status-error" role="alert" tabIndex={-1}>
            <p>{loadFailure?.message ?? 'Клиентът не е намерен.'}</p>
          </div>
          <div className="action-group">
            {loadFailure?.retryable !== false && (
              <Button type="button" variant="secondary" onClick={() => void load()}>
                Зареди отново
              </Button>
            )}
            <Button type="button" variant="secondary" onClick={onBack}>
              Обратно към клиентите
            </Button>
          </div>
        </div>
      </div>
    )
  }

  // Editing an existing Customer is permitted in every Business state, SUSPENDED included (an
  // approved exception; creation is the part that SUSPENDED forbids).
  return (
    <div className="platform-content business-detail">
      <div className="business-page-actions">
        <Button type="button" variant="secondary" disabled={updating} onClick={onBack}>
          Обратно към клиентите
        </Button>
      </div>

      <header className="business-detail-header customer-detail-header">
        <h2>{customer.displayName}</h2>
      </header>

      <section className="business-section customer-detail-section" aria-label="Данни за клиента">
        {editing ? (
          <CustomerForm
            key={`${customer.id}-${customer.version}`}
            customer={customer}
            busy={updating}
            submitLabel="Запази промените"
            onChange={() => {
              if (!feedback?.reload) setFeedback(null)
            }}
            onCancel={() => {
              setEditing(false)
              setFeedback(null)
            }}
            onSubmit={(input) => update(input as UpdateCustomerInput)}
          />
        ) : (
          <>
            <dl className="business-details-list">
              {customer.phone && (
                <div>
                  <dt>Телефон</dt>
                  <dd>{formatCustomerPhone(customer.phone)}</dd>
                </div>
              )}
              {customer.email && (
                <div>
                  <dt>Имейл</dt>
                  <dd>{customer.email}</dd>
                </div>
              )}
            </dl>
            <Button
              type="button"
              onClick={() => {
                guard.guard(() => {
                  setFeedback(null)
                  setEditing(true)
                })
              }}
            >
              Редактирай
            </Button>
          </>
        )}
        <LocalFeedback feedback={feedback} errorRef={errorBox} />
        {feedback?.reload && (
          <div className="action-group">
            <Button type="button" variant="secondary" onClick={reloadGuarded}>
              Зареди актуалните данни
            </Button>
          </div>
        )}
      </section>
    </div>
  )
}

function LocalFeedback({
  feedback,
  errorRef,
}: {
  feedback: Feedback | null
  errorRef: RefObject<HTMLDivElement | null>
}) {
  if (!feedback) return null

  return (
    <div
      ref={feedback.kind === 'error' ? errorRef : undefined}
      className={`status-message status-${feedback.kind}`}
      role={feedback.kind === 'error' ? 'alert' : 'status'}
      aria-live={feedback.kind === 'success' ? 'polite' : undefined}
      tabIndex={feedback.kind === 'error' ? -1 : undefined}
    >
      <p>{feedback.text}</p>
    </div>
  )
}
