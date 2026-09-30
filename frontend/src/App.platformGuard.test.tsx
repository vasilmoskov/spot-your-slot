import '@testing-library/jest-dom/vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { request, type Session } from './identity/api'
import {
  createBusiness,
  getBusiness,
  inviteBusinessOwner,
  listBusinesses,
  updateBusiness,
  type BusinessDetails,
} from './platform/businesses/api'

vi.mock('./identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./identity/api')>()),
  request: vi.fn(),
}))
vi.mock('./platform/businesses/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./platform/businesses/api')>()),
  createBusiness: vi.fn(),
  getBusiness: vi.fn(),
  listBusinesses: vi.fn(),
  updateBusiness: vi.fn(),
  inviteBusinessOwner: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
const mockedCreate = vi.mocked(createBusiness)
const mockedGet = vi.mocked(getBusiness)
const mockedList = vi.mocked(listBusinesses)
const mockedUpdate = vi.mocked(updateBusiness)
const mockedInvite = vi.mocked(inviteBusinessOwner)

const admin: Session = {
  email: 'admin@example.invalid',
  displayName: 'Админ',
  platformAdmin: true,
  businesses: [],
}

const business: BusinessDetails = {
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
  createdAt: '2026-08-19T09:00:00Z',
  updatedAt: '2026-08-19T09:00:00Z',
}

const RETIRED = ['Продължи редактирането', 'Откажи промените', 'Сигурни ли сте, че искате да продължите?']

function prompt() {
  return screen.getByRole('alertdialog', { name: 'Незапазени промени' })
}

beforeEach(() => {
  history.replaceState({}, '', '/')
  mockedRequest.mockReset()
  mockedRequest.mockResolvedValue(admin)
  mockedList.mockReset()
  mockedList.mockResolvedValue({ businesses: [], page: 0, size: 10, totalElements: 0 })
  mockedGet.mockReset()
  mockedGet.mockResolvedValue(business)
  mockedCreate.mockReset()
  mockedCreate.mockResolvedValue(business)
  mockedUpdate.mockReset()
  mockedInvite.mockReset()
  mockedInvite.mockResolvedValue(undefined)
})

describe('platform Business create form and the shared guard', () => {
  async function openDirtyCreate() {
    history.replaceState({}, '', '/#/platform/businesses/new')
    render(<App />)
    fireEvent.change(await screen.findByLabelText('Име на бизнеса'), {
      target: { value: 'Незапазено' },
    })
  }

  it('is clean until something is typed', async () => {
    history.replaceState({}, '', '/#/platform/businesses/new')
    render(<App />)
    await screen.findByLabelText('Име на бизнеса')

    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('shows the shared dialog, "Остани" preserves the value and route, and Escape does the same', async () => {
    await openDirtyCreate()
    const hash = window.location.hash
    const trigger = screen.getByRole('link', { name: 'Профил' })
    trigger.focus()

    fireEvent.click(trigger)

    expect(
      Array.from(prompt().querySelectorAll('p')).map((line) => line.textContent),
    ).toEqual(['Имате незапазени промени.', 'Ако напуснете, те ще бъдат загубени.'])
    expect(screen.getByRole('button', { name: 'Остани' })).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Напусни' })).toBeInTheDocument()
    for (const old of RETIRED) expect(document.body.textContent).not.toContain(old)

    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Име на бизнеса')).toHaveValue('Незапазено')
    expect(window.location.hash).toBe(hash)

    fireEvent.click(trigger)
    fireEvent.keyDown(prompt(), { key: 'Escape' })
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Име на бизнеса')).toHaveValue('Незапазено')
    expect(trigger).toHaveFocus()
  })

  it('"Напусни" performs the original navigation exactly once', async () => {
    await openDirtyCreate()

    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))

    expect(await screen.findByRole('heading', { name: 'Профил' })).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(mockedCreate).not.toHaveBeenCalled()
  })

  it('guards the back button, browser Back and logout', async () => {
    await openDirtyCreate()

    fireEvent.click(screen.getByRole('button', { name: 'Обратно към бизнесите' }))
    expect(prompt()).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))

    await act(async () => {
      history.pushState({}, '', '/#/platform/businesses')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(prompt()).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(window.location.hash).toBe('#/platform/businesses/new')

    fireEvent.click(screen.getAllByRole('button', { name: 'Изход' })[0]!)
    expect(prompt()).toBeInTheDocument()
    expect(mockedRequest).not.toHaveBeenCalledWith('/api/auth/logout', expect.anything())
  })

  it('shows no false prompt after a successful create', async () => {
    await openDirtyCreate()
    fireEvent.change(screen.getByLabelText(/^Идентификатор в уеб адреса/), {
      target: { value: 'studio-a' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Създай бизнес' }))

    expect(await screen.findByRole('heading', { name: 'Студио А' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Профил' })).toBeInTheDocument()
  })

  it('keeps the form and guard after a failed create', async () => {
    mockedCreate.mockRejectedValue(new Error('offline'))
    await openDirtyCreate()
    fireEvent.change(screen.getByLabelText(/^Идентификатор в уеб адреса/), {
      target: { value: 'studio-a' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Създай бизнес' }))
    await screen.findByRole('alert')

    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))

    expect(prompt()).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(screen.getByLabelText('Име на бизнеса')).toHaveValue('Незапазено')
  })
})

describe('platform Business detail forms and the shared guard', () => {
  async function openDetail() {
    history.replaceState({}, '', '/#/platform/businesses/business-a')
    render(<App />)
    await screen.findByRole('heading', { name: 'Студио А' })
  }

  async function startEdit() {
    await openDetail()
    fireEvent.click(screen.getByText('Данни за бизнеса', { selector: 'summary > span:first-child' }))
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
  }

  it('does not prompt for a clean edit form and prompts once a value differs', async () => {
    await startEdit()
    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('does not treat restoring the original value as a change', async () => {
    await startEdit()
    const name = screen.getByLabelText('Име на бизнеса')
    fireEvent.input(name, { target: { value: 'Друго' } })
    fireEvent.input(name, { target: { value: 'Студио А' } })

    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('guards sidebar navigation and the form Cancel; "Остани" keeps the edit, "Напусни" leaves', async () => {
    await startEdit()
    fireEvent.input(screen.getByLabelText('Име на бизнеса'), { target: { value: 'Незапазено' } })

    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(prompt()).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Остани' })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(screen.getByLabelText('Име на бизнеса')).toHaveValue('Незапазено')

    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))
    expect(await screen.findByRole('heading', { name: 'Профил' })).toBeInTheDocument()
    expect(mockedUpdate).not.toHaveBeenCalled()
  })

  it('a confirmed "Напусни" from Cancel resets the form to the saved values', async () => {
    await startEdit()
    fireEvent.input(screen.getByLabelText('Име на бизнеса'), { target: { value: 'Незапазено' } })

    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))

    expect(screen.queryByLabelText('Име на бизнеса')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    expect(screen.getByLabelText('Име на бизнеса')).toHaveValue('Студио А')
    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('shows no false prompt after a successful save', async () => {
    mockedUpdate.mockResolvedValue({ ...business, displayName: 'Ново', version: 1 })
    await startEdit()
    fireEvent.input(screen.getByLabelText('Име на бизнеса'), { target: { value: 'Ново' } })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await screen.findByText('Промените са запазени.')

    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('guards a typed owner-invitation email and clears the guard after the invitation is sent', async () => {
    await openDetail()
    fireEvent.click(screen.getByText('Покана', { selector: 'summary > span:first-child' }))
    fireEvent.input(screen.getByLabelText('Имейл на собственика'), {
      target: { value: 'owner@example.invalid' },
    })

    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    expect(prompt()).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(screen.getByLabelText('Имейл на собственика')).toHaveValue('owner@example.invalid')

    fireEvent.click(screen.getByRole('button', { name: 'Изпрати покана' }))
    await waitFor(() => expect(mockedInvite).toHaveBeenCalledOnce())
    await screen.findByText('Заявката за покана е изпратена.')

    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('leaving from a dirty invitation email clears the input and navigates once', async () => {
    await openDetail()
    fireEvent.click(screen.getByText('Покана', { selector: 'summary > span:first-child' }))
    fireEvent.input(screen.getByLabelText('Имейл на собственика'), {
      target: { value: 'owner@example.invalid' },
    })

    fireEvent.click(screen.getByRole('link', { name: 'Профил' }))
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))

    expect(await screen.findByRole('heading', { name: 'Профил' })).toBeInTheDocument()
    expect(mockedInvite).not.toHaveBeenCalled()
  })
})
