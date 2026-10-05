import { ApiError, request } from './api'

// The one backend signal that the selected Business context is gone: the session filter has
// cleared a selection the user no longer holds an active Membership for, so the request is
// rejected with 403 ACTIVE_BUSINESS_REQUIRED. A missing record (*_NOT_FOUND), a role problem
// (ACCESS_DENIED), a suspended Business, an expired login, and network or server failures are
// different outcomes and never count as a lost context.
export function isBusinessContextLost(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 403 && error.code === 'ACTIVE_BUSINESS_REQUIRED'
}

let listener: (() => void) | null = null

/** Registers the application's recovery for a lost Business context; returns its unregister. */
export function onBusinessContextLost(next: () => void): () => void {
  listener = next
  return () => {
    if (listener === next) listener = null
  }
}

/**
 * The request helper for every Business-scoped endpoint. It behaves exactly like `request`; in
 * addition a lost Business context is announced once to the application, which refreshes the
 * session and leaves the Business screens if the context is really gone. The error is rethrown
 * unchanged, so each screen still shows its own safe message.
 */
export async function businessRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  try {
    return await request<T>(path, options)
  } catch (error) {
    if (isBusinessContextLost(error)) listener?.()
    throw error
  }
}
