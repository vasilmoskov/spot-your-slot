const API = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

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
    const problem = await response.json().catch(() => ({}))
    throw new Error(problem.detail ?? 'Заявката не може да бъде изпълнена.')
  }
  return response.status === 204 ? (undefined as T) : response.json()
}

export type Business = { id: string; displayName: string; role: 'BUSINESS_OWNER' | 'MANAGER' | 'STAFF'; status: string }
export type Session = { displayName: string; platformAdmin: boolean; businesses: Business[]; activeBusinessId?: string }
