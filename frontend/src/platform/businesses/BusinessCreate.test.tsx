import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { BusinessCreate } from './BusinessCreate'
import { createBusiness, type BusinessDetails } from './api'

vi.mock('./api', async (importOriginal) => {
  const original = await importOriginal<typeof import('./api')>()
  return { ...original, createBusiness: vi.fn() }
})

const mockedCreateBusiness = vi.mocked(createBusiness)
const created: BusinessDetails = {
  id: 'business-a',
  slug: 'studio-a',
  displayName: 'Студио А',
  businessType: 'BEAUTY_STUDIO',
  status: 'DRAFT',
  timezone: 'Europe/Sofia',
  description: null,
  city: null,
  postalCode: null,
  street: null,
  streetNumber: null,
  addressDetails: null,
  phone: null,
  contactEmail: null,
  version: 0,
  createdAt: '2026-08-25T10:00:00Z',
  updatedAt: '2026-08-25T10:00:00Z',
}

function rejectedCreation(error: Error) {
  let reject!: (reason: Error) => void
  const promise = new Promise<BusinessDetails>((_resolve, rejectPromise) => {
    reject = rejectPromise
  })
  mockedCreateBusiness.mockReturnValueOnce(promise)
  return () => reject(error)
}

function completeRequiredFields() {
  fireEvent.change(screen.getByLabelText('Име на бизнеса'), {
    target: { value: 'Студио А' },
  })
  fireEvent.change(screen.getByLabelText(/^Идентификатор в уеб адреса/), {
    target: { value: 'studio-a' },
  })
}

beforeEach(() => mockedCreateBusiness.mockReset())

describe('BusinessCreate', () => {
  it('creates once and opens the returned DRAFT/version-zero Business', async () => {
    const onCreated = vi.fn()
    mockedCreateBusiness.mockResolvedValue(created)
    render(
      <BusinessCreate
        onAuthenticationRequired={vi.fn()}
        onCreated={onCreated}
        onCancel={vi.fn()}
      />,
    )
    completeRequiredFields()

    const submit = screen.getByRole('button', { name: 'Създай бизнес' })
    fireEvent.click(submit)
    fireEvent.click(submit)

    await waitFor(() => expect(mockedCreateBusiness).toHaveBeenCalledOnce())
    expect(onCreated).toHaveBeenCalledWith('business-a')
    expect(screen.getByText(/ще можете да поканите собственик/))
      .toBeInTheDocument()
  })

  it('shows safe validation and slug-conflict feedback without internal details', async () => {
    const rejectConflict = rejectedCreation(
      new ApiError(
        409,
        'BUSINESS_SLUG_CONFLICT',
        'Този адрес на бизнеса вече се използва.',
      ),
    )
    render(
      <BusinessCreate
        onAuthenticationRequired={vi.fn()}
        onCreated={vi.fn()}
        onCancel={vi.fn()}
      />,
    )
    completeRequiredFields()
    fireEvent.click(screen.getByRole('button', { name: 'Създай бизнес' }))
    rejectConflict()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Този адрес на бизнеса вече се използва.',
    )
    expect(screen.getByRole('alert')).toHaveFocus()

    const rejectUnexpected = rejectedCreation(new Error('SQL constraint secret'))
    fireEvent.click(screen.getByRole('button', { name: 'Създай бизнес' }))
    rejectUnexpected()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Бизнесът не може да бъде създаден.',
    )
    expect(screen.queryByText(/SQL|constraint|secret/)).not.toBeInTheDocument()
  })

  it('clears stale authentication when creation returns 401', async () => {
    const onAuthenticationRequired = vi.fn()
    const rejectAuthentication = rejectedCreation(
      new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'),
    )
    render(
      <BusinessCreate
        onAuthenticationRequired={onAuthenticationRequired}
        onCreated={vi.fn()}
        onCancel={vi.fn()}
      />,
    )
    completeRequiredFields()
    fireEvent.click(screen.getByRole('button', { name: 'Създай бизнес' }))
    rejectAuthentication()

    await waitFor(() =>
      expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'),
    )
  })
})
