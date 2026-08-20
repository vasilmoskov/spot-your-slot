import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../../identity/api'
import { listBusinesses, type BusinessPage } from './api'
import {
  BUSINESS_STATUS_PRESENTATION,
  businessTypeLabel,
  formatBusinessUpdatedAt,
} from './presentation'

type BusinessListProps = {
  onAuthenticationRequired: (detail: string) => void
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; page: BusinessPage }
  | { kind: 'forbidden'; detail: string }
  | { kind: 'error' }

const GENERIC_ERROR = 'Списъкът с бизнеси не може да бъде зареден.'

export function BusinessList({ onAuthenticationRequired }: BusinessListProps) {
  const [state, setState] = useState<ListState>({ kind: 'loading' })
  const requestSequence = useRef(0)
  const activeRequest = useRef<{
    id: number
    controller: AbortController
  } | null>(null)

  const load = useCallback(async () => {
    if (activeRequest.current && !activeRequest.current.controller.signal.aborted) {
      return
    }

    const request = {
      id: ++requestSequence.current,
      controller: new AbortController(),
    }
    activeRequest.current = request
    setState({ kind: 'loading' })

    try {
      const page = await listBusinesses(0, 50, request.controller.signal)
      if (
        activeRequest.current?.id === request.id &&
        !request.controller.signal.aborted
      ) {
        setState({ kind: 'loaded', page })
      }
    } catch (error) {
      if (
        request.controller.signal.aborted ||
        activeRequest.current?.id !== request.id
      ) {
        return
      }

      if (error instanceof ApiError && error.status === 401) {
        onAuthenticationRequired(error.detail)
        return
      }
      if (error instanceof ApiError && error.status === 403) {
        setState({ kind: 'forbidden', detail: error.detail })
        return
      }
      setState({ kind: 'error' })
    } finally {
      if (activeRequest.current?.id === request.id) {
        activeRequest.current = null
      }
    }
  }, [onAuthenticationRequired])

  useEffect(() => {
    void load()
    return () => {
      activeRequest.current?.controller.abort()
    }
  }, [load])

  if (state.kind === 'loading') {
    return (
      <div className="platform-content" aria-live="polite" aria-busy="true">
        <p className="business-list-state">Зареждане на бизнесите…</p>
      </div>
    )
  }

  if (state.kind === 'forbidden') {
    return (
      <div className="platform-content">
        <p className="status-message status-error" role="alert">
          {state.detail}
        </p>
      </div>
    )
  }

  if (state.kind === 'error') {
    return (
      <div className="platform-content">
        <div className="status-message status-error" role="alert">
          <p>{GENERIC_ERROR}</p>
          <button type="button" className="secondary-button" onClick={() => void load()}>
            Опитай отново
          </button>
        </div>
      </div>
    )
  }

  if (state.page.businesses.length === 0) {
    return (
      <div className="platform-content" aria-live="polite">
        <p className="business-list-state">Все още няма създадени бизнеси.</p>
      </div>
    )
  }

  return (
    <div className="platform-content">
      <div className="business-table-container">
        <table className="business-table">
          <caption className="visually-hidden">Списък с бизнеси</caption>
          <thead>
            <tr>
              <th scope="col">Име</th>
              <th scope="col">Slug</th>
              <th scope="col">Тип</th>
              <th scope="col">Статус</th>
              <th scope="col">Часова зона</th>
              <th scope="col">Обновен</th>
            </tr>
          </thead>
          <tbody>
            {state.page.businesses.map((business) => {
              const status = BUSINESS_STATUS_PRESENTATION[business.status]
              return (
                <tr key={business.id}>
                  <td data-label="Име">{business.displayName}</td>
                  <td data-label="Slug">{business.slug}</td>
                  <td data-label="Тип">{businessTypeLabel(business.businessType)}</td>
                  <td data-label="Статус">
                    <span className={`status-badge status-badge-${status.tone}`}>
                      {status.label}
                    </span>
                  </td>
                  <td data-label="Часова зона">{business.timezone}</td>
                  <td data-label="Обновен">
                    <time dateTime={business.updatedAt}>
                      {formatBusinessUpdatedAt(business.updatedAt, business.timezone)}
                    </time>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
