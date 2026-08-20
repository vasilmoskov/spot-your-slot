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

export type BusinessPage = {
  businesses: BusinessSummary[]
  page: number
  size: number
  totalElements: number
}

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
