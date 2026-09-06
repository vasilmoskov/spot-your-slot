const API = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

const SAFE_FALLBACK = 'Заявката не може да бъде изпълнена.'

type ProblemResponse = {
  code?: unknown
  detail?: unknown
  title?: unknown
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly detail: string

  constructor(status: number, code: string, detail: string) {
    super(detail)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.detail = detail
  }
}

let csrf: { headerName: string; token: string } | undefined

async function token() {
  if (!csrf) {
    const response = await fetch(`${API}/api/auth/csrf`, { credentials: 'include' })
    csrf = await response.json()
  }
  return csrf!
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.method && options.method !== 'GET') {
    const value = await token()
    headers.set(value.headerName, value.token)
    headers.set('Content-Type', 'application/json')
  }
  const response = await fetch(`${API}${path}`, { ...options, headers, credentials: 'include' })
  if (!response.ok) {
    let problem: ProblemResponse = {}
    try {
      problem = await response.json()
    } catch {
      // The public fallback intentionally does not expose malformed response content.
    }
    throw new ApiError(
      response.status,
      typeof problem.code === 'string' ? problem.code : 'REQUEST_FAILED',
      typeof problem.detail === 'string'
        ? problem.detail
        : problem.code === 'AUTH_REQUIRED' && typeof problem.title === 'string'
          ? problem.title
          : SAFE_FALLBACK,
    )
  }
  if (response.status === 204) {
    return undefined as T
  }
  const responseBody = await response.text()
  if (responseBody === '') {
    return undefined as T
  }
  try {
    return JSON.parse(responseBody) as T
  } catch {
    throw new ApiError(response.status, 'REQUEST_FAILED', SAFE_FALLBACK)
  }
}

export type Business = {
  id: string
  displayName: string
  role: 'BUSINESS_OWNER' | 'MANAGER' | 'STAFF'
  status: string
}

export type Session = {
  displayName: string
  platformAdmin: boolean
  businesses: Business[]
  activeBusinessId?: string
}
