import '@testing-library/jest-dom/vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import {
  changeBusinessStatus,
  getBusiness,
  inviteBusinessOwner,
  updateBusiness,
  type BusinessDetails,
  type BusinessStatus,
  type LifecycleAction,
} from './api'
import { BusinessDetail } from './BusinessDetail'

vi.mock('./api', async (importOriginal) => {
  const original = await importOriginal<typeof import('./api')>()
  return {
    ...original,
    getBusiness: vi.fn(),
    updateBusiness: vi.fn(),
    changeBusinessStatus: vi.fn(),
    inviteBusinessOwner: vi.fn(),
  }
})

const mockedGetBusiness = vi.mocked(getBusiness)
const mockedUpdateBusiness = vi.mocked(updateBusiness)
const mockedChangeBusinessStatus = vi.mocked(changeBusinessStatus)
const mockedInviteBusinessOwner = vi.mocked(inviteBusinessOwner)

const draftBusiness: BusinessDetails = {
  id: 'business-a',
  slug: 'studio-a',
  displayName: 'Студио А',
  businessType: 'BEAUTY_STUDIO',
  status: 'DRAFT',
  timezone: 'Europe/Sofia',
  description: 'Описание',
  city: 'София',
  postalCode: '1000',
  street: 'Примерна',
  streetNumber: '1',
  addressDetails: 'вход А',
  phone: '+359 2 000 0000',
  contactEmail: 'contact@example.invalid',
  version: 4,
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-20T12:30:00Z',
}

function businessWithStatus(status: BusinessStatus): BusinessDetails {
  return { ...draftBusiness, status }
}

function renderDetail(onAuthenticationRequired = vi.fn()) {
  return render(
    <BusinessDetail
      businessId="business-a"
      onAuthenticationRequired={onAuthenticationRequired}
      onBack={vi.fn()}
    />,
  )
}

function section(title: string): HTMLDetailsElement {
  const heading = screen.getByText(title, { selector: 'summary > span:first-child' })
  return heading.closest('details') as HTMLDetailsElement
}

beforeEach(() => {
  mockedGetBusiness.mockReset()
  mockedUpdateBusiness.mockReset()
  mockedChangeBusinessStatus.mockReset()
  mockedInviteBusinessOwner.mockReset()
  mockedGetBusiness.mockResolvedValue(draftBusiness)
})

afterEach(() => vi.useRealTimers())

describe('BusinessDetail', () => {
  it.each([
    ['DRAFT', true],
    ['ACTIVE', false],
    ['SUSPENDED', false],
  ] as const)(
    'starts every available section collapsed for %s',
    async (status, invitationPresent) => {
      mockedGetBusiness.mockResolvedValue(businessWithStatus(status))
      renderDetail()

      await screen.findByRole('heading', { name: 'Студио А' })

      expect(section('Данни за бизнеса').open).toBe(false)
      if (invitationPresent) {
        expect(section('Покана').open).toBe(false)
      } else {
        expect(screen.queryByText('Покана', { selector: 'summary > span:first-child' }))
          .not.toBeInTheDocument()
      }
      expect(section('Активиране').open).toBe(false)
    },
  )

  it('starts read-only and cancels editing without sending or retaining changes', async () => {
    renderDetail()

    await screen.findByRole('heading', { name: 'Студио А' })
    expect(screen.getByText('Данни за бизнеса')).toBeInTheDocument()
    expect(screen.getByText('Покана')).toBeInTheDocument()
    expect(screen.getByText('Активиране')).toBeInTheDocument()
    expect(screen.queryByLabelText('Име на бизнеса')).not.toBeInTheDocument()

    fireEvent.click(section('Данни за бизнеса').querySelector('summary')!)
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    expect(section('Данни за бизнеса').open).toBe(true)
    fireEvent.change(screen.getByLabelText('Име на бизнеса'), {
      target: { value: 'Незаписано име' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))

    expect(mockedUpdateBusiness).not.toHaveBeenCalled()
    expect(screen.getByRole('heading', { name: 'Студио А' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    expect(screen.getByLabelText('Име на бизнеса')).toHaveValue('Студио А')
  })

  it('renders approved details and updates only editable fields with expectedVersion', async () => {
    mockedUpdateBusiness.mockResolvedValue({
      ...draftBusiness,
      displayName: 'Студио Б',
      version: 5,
    })
    renderDetail()

    expect(await screen.findByRole('heading', { name: 'Студио А' }))
      .toBeInTheDocument()
    expect(screen.getAllByText('Предстои активиране')).toHaveLength(3)
    expect(document.body).not.toHaveTextContent(/Версия 4/)
    expect(screen.getByText('contact@example.invalid')).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent('business-a')
    expect(document.body).not.toHaveTextContent('2026-08-01T09:00:00Z')

    fireEvent.click(section('Данни за бизнеса').querySelector('summary')!)
    const detailColumns = document.querySelectorAll(
      '.business-details-columns > .business-details-list',
    )
    const terms = (column: Element) =>
      Array.from(column.querySelectorAll('dt')).map((term) => term.textContent)
    expect(terms(detailColumns[0]!)).toEqual([
      'Име на бизнеса',
      'Идентификатор в уеб адреса',
      'Дейност',
      'Телефон (по избор)',
      'Имейл за контакт (по избор)',
    ])
    expect(terms(detailColumns[1]!)).toEqual([
      'Улица (по избор)',
      'Номер (по избор)',
      'Пощенски код (по избор)',
      'Град (по избор)',
      'Допълнителни указания (по избор)',
    ])
    expect(document.querySelector('.business-description-details dt'))
      .toHaveTextContent('Описание (по избор)')
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име на бизнеса'), {
      target: { value: 'Студио Б' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

    await waitFor(() =>
      expect(mockedUpdateBusiness).toHaveBeenCalledWith(
        'business-a',
        expect.objectContaining({
          displayName: 'Студио Б',
          expectedVersion: 4,
        }),
      ),
    )
    expect(await screen.findByRole('heading', { name: 'Студио Б' }))
      .toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Запази промените' })).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Промените са запазени.')
  })

  it.each([
    ['DRAFT', 'Активирай', 'activate', 'ACTIVE', 'Спри временно'],
    ['ACTIVE', 'Спри временно', 'suspend', 'SUSPENDED', 'Активирай отново'],
    ['SUSPENDED', 'Активирай отново', 'reactivate', 'ACTIVE', 'Спри временно'],
  ] as const)(
    'offers and confirms only the %s lifecycle action',
    async (status, actionLabel, action, resultingStatus, nextActionLabel) => {
      const current = businessWithStatus(status)
      mockedGetBusiness.mockResolvedValue(current)
      mockedChangeBusinessStatus.mockResolvedValue({
        ...current,
        status: resultingStatus,
        version: 5,
      })
      renderDetail()

      const actionButton = await screen.findByRole('button', { name: actionLabel })
      expect(actionButton).toHaveClass(
        status === 'ACTIVE' ? 'button--destructive' : 'button--primary',
      )
      expect(actionButton).not.toHaveClass('button--navigation')
      const lifecycleSection = section('Активиране')
      if (!lifecycleSection.open) {
        fireEvent.click(lifecycleSection.querySelector('summary')!)
      }
      expect(screen.queryAllByRole('button', {
        name: /^(Активирай|Спри временно|Активирай отново)$/,
      })).toHaveLength(1)
      fireEvent.click(actionButton)

      const dialog = screen.getByRole('alertdialog')
      expect(lifecycleSection.open).toBe(true)
      expect(lifecycleSection).toContainElement(dialog)
      if (status === 'DRAFT') {
        expect(dialog).toHaveTextContent(
          'Бизнесът може да бъде активиран, след като поканеният собственик приеме поканата.',
        )
        expect(dialog).not.toHaveTextContent(
          'Бизнесът ще стане активен, ако има активен собственик.',
        )
      }
      const confirm = dialog.querySelector('button:not(.button--secondary)') as HTMLButtonElement
      expect(confirm).toHaveFocus()
      fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
      expect(mockedChangeBusinessStatus).not.toHaveBeenCalled()

      fireEvent.click(screen.getByRole('button', { name: actionLabel }))
      const confirmation = screen.getByRole('alertdialog')
      const confirmedButton = confirmation.querySelector(
        'button:not(.button--secondary)',
      ) as HTMLButtonElement
      fireEvent.click(confirmedButton)

      await waitFor(() =>
        expect(mockedChangeBusinessStatus).toHaveBeenCalledWith(
          'business-a',
          action as LifecycleAction,
          4,
        ),
      )
      const feedback = await screen.findByRole('status')
      expect(feedback).toHaveClass('lifecycle-feedback')
      const feedbackLayout = feedback.closest('.feedback-action-layout') as HTMLElement
      expect(feedbackLayout).toContainElement(feedback)
      const currentStatus = lifecycleSection.querySelector(
        '.section-introduction',
      )!
      const nextAction = screen.getByRole('button', { name: nextActionLabel })
      expect(feedbackLayout).toContainElement(nextAction)
      expect(nextAction.parentElement).toHaveClass('feedback-action-controls')
      expect(feedback.nextElementSibling).toBe(nextAction.parentElement)
      expect(
        currentStatus.compareDocumentPosition(feedback) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).not.toBe(0)
      expect(
        feedback.compareDocumentPosition(nextAction) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).not.toBe(0)
      expect(document.body).not.toHaveTextContent(/Версия 5/)
    },
  )

  it('keeps owner and contact emails separate and confirms same-email resend', async () => {
    mockedInviteBusinessOwner.mockResolvedValue(undefined)
    renderDetail()

    const ownerEmail = await screen.findByLabelText('Имейл на собственика')
    fireEvent.click(section('Покана').querySelector('summary')!)
    expect(screen.getByText('contact@example.invalid')).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent(
      'Имейлът за поканата е отделен от имейла за контакт на бизнеса.',
    )
    fireEvent.change(ownerEmail, { target: { value: 'owner@example.invalid' } })
    fireEvent.click(screen.getByRole('button', { name: 'Изпрати покана' }))
    fireEvent.click(screen.getByRole('button', { name: 'Изпращане…' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Заявката за покана е изпратена.',
    )
    expect(mockedInviteBusinessOwner).toHaveBeenCalledOnce()
    expect(mockedInviteBusinessOwner).toHaveBeenCalledWith(
      'business-a',
      'owner@example.invalid',
    )
    expect(document.body).not.toHaveTextContent(/доставена|приета от|членство/i)

    fireEvent.click(screen.getByRole('button', { name: 'Изпрати покана' }))
    const resend = await screen.findByRole('alertdialog', {
      name: 'Изпращане на нова покана',
    })
    expect(section('Покана').open).toBe(true)
    expect(resend).toHaveTextContent('Предишната активна покана')
    expect(mockedInviteBusinessOwner).toHaveBeenCalledOnce()
    const confirm = screen.getByRole('button', { name: 'Потвърди изпращането' })
    expect(confirm).toHaveFocus()
    fireEvent.click(confirm)
    await waitFor(() => expect(mockedInviteBusinessOwner).toHaveBeenCalledTimes(2))
  })

  it('reopens the invitation section for a local error', async () => {
    mockedInviteBusinessOwner.mockRejectedValueOnce(
      new ApiError(400, 'VALIDATION_ERROR', 'Проверете въведените данни.'),
    )
    renderDetail()

    const ownerEmail = await screen.findByLabelText('Имейл на собственика')
    const invitationSection = section('Покана')
    fireEvent.click(invitationSection.querySelector('summary')!)
    fireEvent.change(ownerEmail, { target: { value: 'owner@example.invalid' } })
    fireEvent.click(screen.getByRole('button', { name: 'Изпрати покана' }))
    invitationSection.open = false

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Проверете въведените данни.')
    expect(invitationSection.open).toBe(true)
    expect(invitationSection).toContainElement(alert)
  })

  it('shows safe owner and lifecycle failures without claiming readiness', async () => {
    mockedChangeBusinessStatus.mockRejectedValueOnce(
      new ApiError(
        409,
        'BUSINESS_MISSING_ACTIVE_OWNER',
        'За активиране е необходим активен собственик.',
      ),
    )
    renderDetail()
    await screen.findByRole('heading', { name: 'Студио А' })
    const lifecycleSection = section('Активиране')
    fireEvent.click(lifecycleSection.querySelector('summary')!)
    fireEvent.click(screen.getByRole('button', { name: 'Активирай' }))
    fireEvent.click(screen.getByRole('button', { name: 'Потвърди активирането' }))
    lifecycleSection.open = false

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      'За да активирате бизнеса, собственикът трябва първо да приеме поканата.',
    )
    expect(alert).not.toHaveTextContent('За активиране е необходим активен собственик.')
    expect(alert).toHaveFocus()
    expect(alert.closest('details')).toHaveTextContent('Активиране')
    expect(lifecycleSection.open).toBe(true)
    const feedbackLayout = alert.closest('.feedback-action-layout') as HTMLElement
    const activate = screen.getByRole('button', { name: 'Активирай' })
    expect(feedbackLayout).toContainElement(activate)
    expect(activate.parentElement).toHaveClass('feedback-action-controls')
    expect(alert.nextElementSibling).toBe(activate.parentElement)
    expect(document.body).not.toHaveTextContent(/готов|проверен/i)
  })

  it('requires reloading after an optimistic-concurrency conflict', async () => {
    mockedUpdateBusiness.mockRejectedValueOnce(
      new ApiError(
        409,
        'BUSINESS_CONCURRENT_UPDATE',
        'Бизнесът е променен. Обновете данните и опитайте отново.',
      ),
    )
    mockedGetBusiness
      .mockResolvedValueOnce(draftBusiness)
      .mockResolvedValueOnce({ ...draftBusiness, version: 8 })
    renderDetail()

    await screen.findByRole('heading', { name: 'Студио А' })
    fireEvent.click(section('Данни за бизнеса').querySelector('summary')!)
    fireEvent.click(await screen.findByRole('button', { name: 'Редактирай' }))
    fireEvent.click(
      await screen.findByRole('button', { name: 'Запази промените' }),
    )
    section('Данни за бизнеса').open = false
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      'Бизнесът е променен. Обновете данните и опитайте отново.',
    )
    expect(document.body).not.toHaveTextContent(/Версия 4/)
    expect(mockedUpdateBusiness).toHaveBeenCalledOnce()
    expect(section('Данни за бизнеса').open).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: 'Зареди актуалните данни' }))
    await waitFor(() => expect(mockedGetBusiness).toHaveBeenCalledTimes(2))
    expect(mockedUpdateBusiness).toHaveBeenCalledOnce()
  })

  it.each(['success', 'error'] as const)('dismisses lifecycle %s feedback without removing the next action', async (kind) => {
    if (kind === 'success') {
      mockedChangeBusinessStatus.mockResolvedValue({ ...draftBusiness, status: 'ACTIVE', version: 5 })
    } else {
      mockedChangeBusinessStatus.mockRejectedValue(new ApiError(409, 'BUSINESS_MISSING_ACTIVE_OWNER', 'Няма собственик'))
    }
    renderDetail()
    await screen.findByRole('heading', { name: 'Студио А' })
    fireEvent.click(section('Активиране').querySelector('summary')!)
    vi.useFakeTimers()
    fireEvent.click(screen.getByRole('button', { name: 'Активирай' }))
    fireEvent.click(screen.getByRole('button', { name: 'Потвърди активирането' }))
    await act(async () => { await Promise.resolve() })
    const notice = screen.getByRole(kind === 'success' ? 'status' : 'alert')
    const nextAction = screen.getByRole('button', { name: kind === 'success' ? 'Спри временно' : 'Активирай' })
    expect(notice.closest('.feedback-action-layout')).toContainElement(nextAction)
    expect(nextAction.closest('.feedback-action-controls')).not.toContainElement(notice)
    await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })
    expect(notice).not.toBeInTheDocument()
    expect(nextAction).toBeInTheDocument()
  })

  it('clears an invitation notice when the selected Business changes', async () => {
    mockedInviteBusinessOwner.mockResolvedValue(undefined)
    const onAuthenticationRequired = vi.fn()
    const view = renderDetail(onAuthenticationRequired)
    await screen.findByRole('heading', { name: 'Студио А' })
    fireEvent.click(section('Покана').querySelector('summary')!)
    fireEvent.change(screen.getByLabelText('Имейл на собственика'), {
      target: { value: 'owner@example.invalid' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Изпрати покана' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Заявката за покана е изпратена.')
    mockedGetBusiness.mockResolvedValue({ ...draftBusiness, id: 'business-b', displayName: 'Студио Б' })
    view.rerender(<BusinessDetail businessId="business-b" onAuthenticationRequired={onAuthenticationRequired} onBack={vi.fn()} />)
    await screen.findByRole('heading', { name: 'Студио Б' })
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('retains blocking load feedback and a separate retry row', async () => {
    mockedGetBusiness.mockRejectedValue(new Error('network'))
    renderDetail()
    const notice = await screen.findByRole('alert')
    vi.useFakeTimers()
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(notice).toBeInTheDocument()
    const retry = screen.getByRole('button', { name: 'Зареди отново' })
    expect(notice).not.toContainElement(retry)
    expect(notice.closest('.feedback-action-layout')).toContainElement(retry)
  })

  it('handles authentication, not-found and recoverable load failures safely', async () => {
    const onAuthenticationRequired = vi.fn()
    mockedGetBusiness.mockRejectedValueOnce(
      new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'),
    )
    const first = renderDetail(onAuthenticationRequired)
    await waitFor(() =>
      expect(onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'),
    )
    first.unmount()

    mockedGetBusiness.mockRejectedValueOnce(
      new ApiError(404, 'BUSINESS_NOT_FOUND', 'Бизнесът не е намерен.'),
    )
    const second = renderDetail()
    expect(await screen.findByRole('alert')).toHaveTextContent('Бизнесът не е намерен.')
    second.unmount()

    mockedGetBusiness
      .mockRejectedValueOnce(new Error('SQL stack trace'))
      .mockResolvedValueOnce(draftBusiness)
    renderDetail()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Данните за бизнеса не могат да бъдат заредени.',
    )
    expect(document.body).not.toHaveTextContent(/SQL|stack trace/)
    fireEvent.click(screen.getByRole('button', { name: 'Зареди отново' }))
    expect(await screen.findByRole('heading', { name: 'Студио А' }))
      .toBeInTheDocument()
  })
})
