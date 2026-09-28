import { useCallback, useEffect, useRef, useState } from 'react'
import { Button } from '../../ui/Button'
import { useFeedback, errorCategory } from '../../ui/useFeedback'
import { useGuardedFormState } from '../../ui/UnsavedChangesGuard'
import {
  listStaffMemberAssignments,
  replaceStaffMemberAssignments,
  type AssignedService,
  type StaffMemberAssignments,
} from './api'
import { listServices } from '../services/api'
import { isAuthenticationRequired, isConcurrentUpdate, safeStaffError } from './errors'

// One Service page is requested at a time; every remaining page is then
// loaded with the same page size so that assignment management is never
// silently limited to the first page of Services.
const SERVICES_PAGE_SIZE = 50

type StaffServiceAssignmentsProps = {
  staffMemberId: string
  staffMemberVersion: number
  readOnly: boolean
  editing: boolean
  onRequestEdit: () => void
  onEditingDone: () => void
  onAuthenticationRequired: (detail: string) => void
  onVersionChange: (version: number) => void
}

// Only the fields the assignment editor renders. Both the paginated Service
// catalog and the assignment endpoint's own AssignedService shape satisfy it,
// which keeps the defensive merge below trivial.
type AssignableService = {
  id: string
  name: string
  active: boolean
}

function sameSelection(a: Set<string>, b: Set<string>): boolean {
  if (a.size !== b.size) return false
  for (const id of a) {
    if (!b.has(id)) return false
  }
  return true
}

function mergeAssignedIntoCatalog(
  catalog: AssignableService[],
  assigned: AssignedService[],
): AssignableService[] {
  const seen = new Set(catalog.map((service) => service.id))
  const merged = [...catalog]
  for (const service of assigned) {
    if (!seen.has(service.id)) {
      merged.push({ id: service.id, name: service.name, active: service.active })
      seen.add(service.id)
    }
  }
  return merged
}

// Loads every Service page (not only the first) using one AbortSignal for
// every request, so an unmount or a mode change aborts every outstanding
// request together. Callers only see the full list once every page has
// resolved; a partial result is never returned.
async function loadEveryService(signal: AbortSignal): Promise<AssignableService[]> {
  const first = await listServices(0, SERVICES_PAGE_SIZE, 'name', 'asc', signal)
  const collected: AssignableService[] = first.services.map((service) => ({
    id: service.id,
    name: service.name,
    active: service.active,
  }))
  const totalPages = Math.ceil(first.totalElements / SERVICES_PAGE_SIZE)
  for (let page = 1; page < totalPages; page += 1) {
    const next = await listServices(page, SERVICES_PAGE_SIZE, 'name', 'asc', signal)
    collected.push(
      ...next.services.map((service) => ({
        id: service.id,
        name: service.name,
        active: service.active,
      })),
    )
  }
  return collected
}

export function StaffServiceAssignments({
  staffMemberId,
  staffMemberVersion,
  readOnly,
  editing,
  onRequestEdit,
  onEditingDone,
  onAuthenticationRequired,
  onVersionChange,
}: StaffServiceAssignmentsProps) {
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [assignments, setAssignments] = useState<StaffMemberAssignments | null>(null)
  const [selection, setSelection] = useState<Set<string>>(new Set())
  const [catalog, setCatalog] = useState<AssignableService[] | null>(null)
  const [catalogLoading, setCatalogLoading] = useState(false)
  const [catalogError, setCatalogError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const { feedback, setFeedback, beginFeedback } = useFeedback(staffMemberId)
  const activeAssignmentsLoad = useRef<AbortController | null>(null)
  const activeCatalogLoad = useRef<AbortController | null>(null)
  const savingInProgress = useRef(false)
  const errorMessage = useRef<HTMLDivElement>(null)

  const assignedServices = assignments ? assignments.services : []
  const assignedIds = new Set(assignedServices.map((service) => service.id))
  const isDirty = editing && !loading && !loadError && !sameSelection(selection, assignedIds)

  const guard = useGuardedFormState(isDirty, () => setSelection(new Set(assignedIds)))

  // The visible set of Services (read-only and editable alike): every active
  // Service, plus any assigned Service even if it is inactive, so a stale
  // assignment stays observable and removable. An inactive, unassigned
  // Service is not a valid assignment candidate and is dropped entirely, not
  // merely disabled or hidden behind styling.
  const assignable =
    catalog === null
      ? []
      : mergeAssignedIntoCatalog(catalog, assignedServices).filter(
          (service) => service.active || assignedIds.has(service.id),
        )

  const loadAssignments = useCallback(async () => {
    activeAssignmentsLoad.current?.abort()
    const controller = new AbortController()
    activeAssignmentsLoad.current = controller
    setLoading(true)
    setLoadError(null)
    setFeedback(null)
    try {
      const loaded = await listStaffMemberAssignments(staffMemberId, controller.signal)
      if (controller.signal.aborted || activeAssignmentsLoad.current !== controller) return
      setAssignments(loaded)
      setSelection(new Set(loaded.services.map((service) => service.id)))
      onVersionChange(loaded.version)
    } catch (caught) {
      if (controller.signal.aborted || activeAssignmentsLoad.current !== controller) return
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setLoadError(safeStaffError(caught, 'Назначените услуги не могат да бъдат заредени.'))
    } finally {
      if (activeAssignmentsLoad.current === controller) {
        activeAssignmentsLoad.current = null
        setLoading(false)
      }
    }
  }, [staffMemberId, onAuthenticationRequired, onVersionChange, setFeedback])

  useEffect(() => {
    void loadAssignments()
    return () => {
      const controller = activeAssignmentsLoad.current
      activeAssignmentsLoad.current = null
      controller?.abort()
    }
  }, [loadAssignments])

  const loadCatalog = useCallback(async () => {
    activeCatalogLoad.current?.abort()
    const controller = new AbortController()
    activeCatalogLoad.current = controller
    setCatalogLoading(true)
    setCatalogError(null)
    try {
      const everyService = await loadEveryService(controller.signal)
      if (controller.signal.aborted || activeCatalogLoad.current !== controller) return
      setCatalog(everyService)
    } catch (caught) {
      if (controller.signal.aborted || activeCatalogLoad.current !== controller) return
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setCatalogError(safeStaffError(caught, 'Услугите не могат да бъдат заредени.'))
    } finally {
      if (activeCatalogLoad.current === controller) {
        activeCatalogLoad.current = null
        setCatalogLoading(false)
      }
    }
  }, [onAuthenticationRequired])

  // The full Service catalog is needed both for the read-only view (to show
  // every assignable Service, assigned or not) and for the edit-mode
  // checkbox list, so it is loaded once per StaffMember rather than only
  // while actively editing. An unmount or a StaffMember change aborts any
  // in-flight request.
  useEffect(() => {
    void loadCatalog()
    return () => {
      const controller = activeCatalogLoad.current
      activeCatalogLoad.current = null
      controller?.abort()
    }
  }, [staffMemberId, loadCatalog])

  // Entering edit mode always starts from the currently saved assignment, so
  // a previous unsaved (and discarded, or not-yet-saved) selection is never
  // carried into a new editing session.
  useEffect(() => {
    if (editing) {
      setSelection(new Set(assignedIds))
    }
    // `assignedIds` intentionally omitted: it is a new Set on every render, so
    // depending on it would reset the selection on every keystroke elsewhere
    // in the page. It is only read at the moment editing starts, from the
    // ref-stable `assignments` snapshot already in scope.
  }, [editing])

  useEffect(() => {
    if (feedback?.kind === 'error') errorMessage.current?.focus()
  }, [feedback])

  const toggle = (serviceId: string) => {
    setSelection((current) => {
      const next = new Set(current)
      if (next.has(serviceId)) {
        next.delete(serviceId)
      } else {
        next.add(serviceId)
      }
      return next
    })
  }

  const cancel = () => {
    guard.guard(() => {
      setSelection(new Set(assignedIds))
      onEditingDone()
    })
  }

  const submit = async () => {
    if (savingInProgress.current) return
    savingInProgress.current = true
    setSaving(true)
    const publish = beginFeedback()
    try {
      const updated = await replaceStaffMemberAssignments(staffMemberId, {
        serviceIds: Array.from(selection),
        expectedVersion: staffMemberVersion,
      })
      if (!publish(null)) return
      guard.unregisterDirty()
      setAssignments(updated)
      setSelection(new Set(updated.services.map((service) => service.id)))
      onVersionChange(updated.version)
      setFeedback({ kind: 'success', text: 'Назначените услуги са запазени.' })
      onEditingDone()
    } catch (caught) {
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      publish({
        kind: 'error',
        category: errorCategory(caught),
        text: safeStaffError(caught, 'Назначените услуги не могат да бъдат запазени.'),
        reload: isConcurrentUpdate(caught),
      })
    } finally {
      savingInProgress.current = false
      setSaving(false)
    }
  }

  if (loading) {
    return (
      <p className="business-list-state" aria-live="polite">
        Зареждане на назначените услуги…
      </p>
    )
  }

  if (loadError || !assignments) {
    return (
      <div className="feedback-action-layout">
        <div className="status-message status-error" role="alert">
          <p>{loadError ?? 'Назначените услуги не могат да бъдат заредени.'}</p>
        </div>
        <Button type="button" variant="secondary" onClick={() => void loadAssignments()}>
          Зареди отново
        </Button>
      </div>
    )
  }

  const renderList = () => {
    if (catalogError) {
      return (
        <div className="feedback-action-layout">
          <div className="status-message status-error" role="alert">
            <p>{catalogError}</p>
          </div>
          <Button type="button" variant="secondary" onClick={() => void loadCatalog()}>
            Зареди отново
          </Button>
        </div>
      )
    }
    if (catalogLoading || catalog === null) {
      return (
        <p className="business-list-state" aria-live="polite">
          Зареждане на услугите…
        </p>
      )
    }
    if (assignable.length === 0) {
      return <p className="business-list-state">Няма създадени услуги за назначаване.</p>
    }
    return (
      <div className="business-table-container assignment-table-container">
        <table className="business-table assignment-table">
          <caption className="visually-hidden">
            {editing ? 'Назначаване на услуги' : 'Услуги'}
          </caption>
          <thead>
            <tr>
              <th scope="col">Услуга</th>
              <th scope="col">Назначена</th>
            </tr>
          </thead>
          <tbody>
            {assignable.map((service) => (
              <tr key={service.id}>
                <td data-label="Услуга">{service.name}</td>
                <td data-label="Назначена" className="assignment-checkbox-cell">
                  {editing ? (
                    <input
                      type="checkbox"
                      className="assignment-checkbox"
                      aria-label={service.name}
                      checked={selection.has(service.id)}
                      onChange={() => toggle(service.id)}
                    />
                  ) : assignedIds.has(service.id) ? (
                    <>
                      <span className="assignment-symbol" aria-hidden="true">
                        ✓
                      </span>
                      <span className="visually-hidden">Да</span>
                    </>
                  ) : (
                    <>
                      <span className="assignment-symbol" aria-hidden="true">
                        ✕
                      </span>
                      <span className="visually-hidden">Не</span>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )
  }

  return (
    <div className="feedback-action-layout">
      {renderList()}
      {feedback && (
        <div
          ref={feedback.kind === 'error' ? errorMessage : undefined}
          className={`status-message status-${feedback.kind}`}
          role={feedback.kind === 'error' ? 'alert' : 'status'}
          aria-live={feedback.kind === 'success' ? 'polite' : undefined}
          tabIndex={feedback.kind === 'error' ? -1 : undefined}
        >
          <p>{feedback.text}</p>
        </div>
      )}
      {feedback?.reload && (
        <Button type="button" variant="secondary" onClick={() => void loadAssignments()}>
          Зареди актуалните данни
        </Button>
      )}
      {!readOnly && (
        <div className="action-group">
          {editing ? (
            <>
              <Button
                type="button"
                disabled={saving || catalogLoading || !!catalogError || !isDirty}
                onClick={() => void submit()}
              >
                {saving ? 'Запазване…' : 'Запази промените'}
              </Button>
              <Button type="button" variant="secondary" disabled={saving} onClick={cancel}>
                Отказ
              </Button>
            </>
          ) : (
            <Button type="button" onClick={onRequestEdit}>
              Редактирай услугите
            </Button>
          )}
        </div>
      )}
    </div>
  )
}
