export function requiredEnvironment(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`Missing required E2E environment variable: ${name}`)
  return value
}

export const API_ORIGIN = `http://localhost:${requiredEnvironment('E2E_BACKEND_PORT')}`
