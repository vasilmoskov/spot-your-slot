import { API_BASE_URL } from '../identity/api'

// Mirrors the allowlisted response of `GET /api/public/businesses/{slug}`
// (ADR-0017). The lifecycle status, other identifiers and booking state are not
// part of the contract and are deliberately not modelled. Since ADR-0026 each
// Service also carries its public reference (`id`); this page does not use it,
// so the decoder still copies only the fields below and drops it.
export type PublicAddress = {
  city: string | null
  postalCode: string | null
  street: string | null
  streetNumber: string | null
  details: string | null
}

export type PublicService = {
  name: string
  description: string | null
  durationMinutes: number
  price: number
}

export type PublicBusinessProfile = {
  slug: string
  displayName: string
  businessType: string
  description: string | null
  phone: string | null
  address: PublicAddress | null
  services: PublicService[]
}

export type PublicProfileResult =
  | { kind: 'profile'; profile: PublicBusinessProfile }
  | { kind: 'unavailable' }

// Every failure other than the single "page unavailable" answer: a network
// error, a server error or a body that is not the documented contract. It
// carries no backend detail, so nothing internal can be rendered from it.
export class PublicProfileLoadError extends Error {
  constructor() {
    super('The public Business page could not be loaded.')
    this.name = 'PublicProfileLoadError'
  }
}

const UNAVAILABLE_CODE = 'BUSINESS_PAGE_UNAVAILABLE'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function nullableText(value: unknown): string | null | undefined {
  if (value === null) return null
  return typeof value === 'string' ? value : undefined
}

function decodeAddress(value: unknown): PublicAddress | null | undefined {
  if (value === null) return null
  if (!isRecord(value)) return undefined
  const city = nullableText(value.city)
  const postalCode = nullableText(value.postalCode)
  const street = nullableText(value.street)
  const streetNumber = nullableText(value.streetNumber)
  const details = nullableText(value.details)
  if (
    city === undefined ||
    postalCode === undefined ||
    street === undefined ||
    streetNumber === undefined ||
    details === undefined
  ) {
    return undefined
  }
  return { city, postalCode, street, streetNumber, details }
}

function decodeService(value: unknown): PublicService | undefined {
  if (!isRecord(value)) return undefined
  const description = nullableText(value.description)
  if (
    typeof value.name !== 'string' ||
    description === undefined ||
    typeof value.durationMinutes !== 'number' ||
    !Number.isFinite(value.durationMinutes) ||
    typeof value.price !== 'number' ||
    !Number.isFinite(value.price)
  ) {
    return undefined
  }
  return {
    name: value.name,
    description,
    durationMinutes: value.durationMinutes,
    price: value.price,
  }
}

// Copies only the documented fields, so an unexpected extra property in a
// response can never reach the page.
export function decodePublicProfile(body: unknown): PublicBusinessProfile | undefined {
  if (!isRecord(body) || !Array.isArray(body.services)) return undefined
  const description = nullableText(body.description)
  const phone = nullableText(body.phone)
  const address = decodeAddress(body.address)
  if (
    typeof body.slug !== 'string' ||
    typeof body.displayName !== 'string' ||
    body.displayName.trim() === '' ||
    typeof body.businessType !== 'string' ||
    description === undefined ||
    phone === undefined ||
    address === undefined
  ) {
    return undefined
  }
  const services: PublicService[] = []
  for (const candidate of body.services) {
    const service = decodeService(candidate)
    if (!service) return undefined
    services.push(service)
  }
  return {
    slug: body.slug,
    displayName: body.displayName,
    businessType: body.businessType,
    description,
    phone,
    address,
    services,
  }
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return undefined
  }
}

/**
 * Reads one public Business page. The request is a plain GET without
 * credentials or a CSRF token, so it never creates or touches a session.
 * An aborted request rejects with the browser's AbortError; callers ignore it.
 */
export async function fetchPublicBusinessProfile(
  slug: string,
  signal?: AbortSignal,
): Promise<PublicProfileResult> {
  let response: Response
  try {
    response = await fetch(`${API_BASE_URL}/api/public/businesses/${encodeURIComponent(slug)}`, {
      method: 'GET',
      credentials: 'omit',
      headers: { Accept: 'application/json' },
      ...(signal ? { signal } : {}),
    })
  } catch (error) {
    if (signal?.aborted) throw error
    throw new PublicProfileLoadError()
  }

  const body = await readJson(response).catch(() => undefined)

  if (response.status === 404 && isRecord(body) && body.code === UNAVAILABLE_CODE) {
    return { kind: 'unavailable' }
  }
  if (response.status === 200) {
    const profile = decodePublicProfile(body)
    if (profile) return { kind: 'profile', profile }
  }
  throw new PublicProfileLoadError()
}
