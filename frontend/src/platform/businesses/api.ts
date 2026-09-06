import { request } from '../../identity/api'

export type BusinessType =
  | 'HAIR_SALON'
  | 'BARBERSHOP'
  | 'NAIL_STUDIO'
  | 'MASSAGE_STUDIO'
  | 'MAKEUP_STUDIO'
  | 'BEAUTY_STUDIO'
  | 'OTHER'

export type BusinessStatus = 'DRAFT' | 'ACTIVE' | 'SUSPENDED'

export type BusinessSummary = {
  id: string
  slug: string
  displayName: string
  businessType: BusinessType
  status: BusinessStatus
  timezone: string
  version: number
  createdAt: string
  updatedAt: string
}

export type BusinessDetails = BusinessSummary & {
  description: string | null
  city: string | null
  postalCode: string | null
  street: string | null
  streetNumber: string | null
  addressDetails: string | null
  phone: string | null
  contactEmail: string | null
}

export type BusinessPage = {
  businesses: BusinessSummary[]
  page: number
  size: number
  totalElements: number
}

export type CreateBusinessInput = {
  slug: string
  displayName: string
  businessType: BusinessType
  description?: string | undefined
  city?: string | undefined
  postalCode?: string | undefined
  street?: string | undefined
  streetNumber?: string | undefined
  addressDetails?: string | undefined
  phone?: string | undefined
  contactEmail?: string | undefined
}

export type UpdateBusinessInput = {
  slug: string
  displayName: string
  businessType: BusinessType
  timezone: string
  description?: string | undefined
  city?: string | undefined
  postalCode?: string | undefined
  street?: string | undefined
  streetNumber?: string | undefined
  addressDetails?: string | undefined
  phone?: string | undefined
  contactEmail?: string | undefined
  expectedVersion: number
}

export type LifecycleAction = 'activate' | 'suspend' | 'reactivate'

export function listBusinesses(
  page = 0,
  size = 50,
  signal?: AbortSignal,
): Promise<BusinessPage> {
  const options: RequestInit = signal ? { signal } : {}
  return request<BusinessPage>(
    `/api/platform/businesses?page=${page}&size=${size}`,
    options,
  )
}

export function getBusiness(
  businessId: string,
  signal?: AbortSignal,
): Promise<BusinessDetails> {
  return request<BusinessDetails>(
    `/api/platform/businesses/${encodeURIComponent(businessId)}`,
    signal ? { signal } : {},
  )
}

export function createBusiness(input: CreateBusinessInput): Promise<BusinessDetails> {
  return request<BusinessDetails>('/api/platform/businesses', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function updateBusiness(
  businessId: string,
  input: UpdateBusinessInput,
): Promise<BusinessDetails> {
  return request<BusinessDetails>(
    `/api/platform/businesses/${encodeURIComponent(businessId)}`,
    {
      method: 'PUT',
      body: JSON.stringify(input),
    },
  )
}

export function changeBusinessStatus(
  businessId: string,
  action: LifecycleAction,
  expectedVersion: number,
): Promise<BusinessDetails> {
  return request<BusinessDetails>(
    `/api/platform/businesses/${encodeURIComponent(businessId)}/${action}`,
    {
      method: 'POST',
      body: JSON.stringify({ expectedVersion }),
    },
  )
}

export function inviteBusinessOwner(
  businessId: string,
  email: string,
): Promise<void> {
  return request<void>(
    `/api/platform/identity/businesses/${encodeURIComponent(businessId)}/owner-invitation`,
    {
      method: 'POST',
      body: JSON.stringify({ email }),
    },
  )
}
