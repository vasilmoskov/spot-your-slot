import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { UnsavedChangesGuardProvider } from '../../ui/UnsavedChangesGuard'
import { getCustomer, updateCustomer } from './api'
import { CustomerDetail } from './CustomerDetail'
import { detail } from './testFixtures'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getCustomer: vi.fn(),
  updateCustomer: vi.fn(),
}))

const mockedGet = vi.mocked(getCustomer)
const mockedUpdate = vi.mocked(updateCustomer)

function renderDetail(
  options: { initialSuccess?: string; onInitialSuccessShown?: () => void } = {},
) {
  const handlers = {
    onAuthenticationRequired: vi.fn(),
    onBack: vi.fn(),
  }
  render(
    <UnsavedChangesGuardProvider>
      <CustomerDetail
        customerId="customer-1"
        initialSuccess={options.initialSuccess}
        onInitialSuccessShown={options.onInitialSuccessShown}
        {...handlers}
      />
    </UnsavedChangesGuardProvider>,
  )
  return handlers
}

const type = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } })

async function openEditor() {
  fireEvent.click(await screen.findByRole('button', { name: 'Редактирай' }))
  return screen.findByLabelText('Име')
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedUpdate.mockReset()
  mockedGet.mockResolvedValue(detail())
})

describe('CustomerDetail read mode', () => {
  it('shows the name as the header, then only the approved contact data, with grouped phone and no technical metadata', async () => {
    renderDetail()
    const page = await screen.findByRole('region', { name: 'Данни за клиента' })
    expect(screen.getByRole('heading', { level: 2, name: 'Мария Тестова' })).toBeInTheDocument()
    expect(within(page).getByText('+359 895 555 777')).toBeInTheDocument()
    expect(within(page).getByText('maria@example.test')).toBeInTheDocument()
    expect(page).not.toHaveTextContent(/customer-1|версия|2026|\b3\b|статус|история/i)
    expect(screen.queryAllByRole('heading', { level: 1 })).toHaveLength(0)
  })

  it('omits the phone row when there is no phone instead of an empty labelled container', async () => {
    mockedGet.mockResolvedValue(detail({ phone: null }))
    renderDetail()
    await screen.findByText('maria@example.test')
    expect(screen.queryByText('Телефон')).toBeNull()
    expect(screen.getByText('Имейл')).toBeInTheDocument()
  })

  it('omits the email row when there is no email instead of an empty labelled container', async () => {
    mockedGet.mockResolvedValue(detail({ email: null }))
    renderDetail()
    await screen.findByText('+359 895 555 777')
    expect(screen.queryByText('Имейл')).toBeNull()
    expect(screen.getByText('Телефон')).toBeInTheDocument()
  })

  it('shows a one-time success message only after the Customer has finished loading', async () => {
    const shown = vi.fn()
    let resolve!: (value: ReturnType<typeof detail>) => void
    mockedGet.mockReturnValue(new Promise((res) => (resolve = res)))
    renderDetail({ initialSuccess: 'Клиентът е добавен.', onInitialSuccessShown: shown })
    expect(screen.getByText('Зареждане на клиента…')).toBeInTheDocument()
    expect(screen.queryByText('Клиентът е добавен.')).toBeNull()
    resolve(detail())
    expect(await screen.findByText('Клиентът е добавен.')).toBeInTheDocument()
    expect(shown).toHaveBeenCalledOnce()
  })

  it.each([
    ['unknown', new ApiError(404, 'CUSTOMER_NOT_FOUND', 'raw 8f1c')],
    ['malformed', new ApiError(400, 'VALIDATION_ERROR', 'raw')],
  ])('shows the same safe unavailable state for a %s ID without a retry', async (_name, error) => {
    mockedGet.mockRejectedValue(error)
    const handlers = renderDetail()
    expect(await screen.findByRole('alert')).toHaveTextContent('Клиентът не е намерен.')
    expect(screen.queryByRole('button', { name: 'Зареди отново' })).toBeNull()
    expect(screen.queryByText(/raw|8f1c/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към клиентите' }))
    expect(handlers.onBack).toHaveBeenCalledOnce()
  })

  it('offers a retry for an unexpected failure', async () => {
    mockedGet.mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'SQLState 23505'))
    renderDetail()
    expect(await screen.findByRole('alert')).toHaveTextContent('Възникна неочаквана грешка.')
    mockedGet.mockResolvedValue(detail())
    fireEvent.click(screen.getByRole('button', { name: 'Зареди отново' }))
    expect(await screen.findByText('Мария Тестова')).toBeInTheDocument()
  })

  it('disables the back action while a save is in flight', async () => {
    mockedUpdate.mockReturnValue(new Promise(() => undefined))
    renderDetail()
    fireEvent.click(await screen.findByRole('button', { name: 'Редактирай' }))
    fireEvent.change(screen.getByLabelText('Име'), { target: { value: 'Нова' } })
    expect(screen.getByRole('button', { name: 'Обратно към клиентите' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(screen.getByRole('button', { name: 'Обратно към клиентите' })).toBeDisabled()
  })

  it('always offers the edit action: editing an existing Customer is allowed in every Business state', async () => {
    renderDetail()
    expect(await screen.findByRole('button', { name: 'Редактирай' })).toBeEnabled()
  })
})

describe('CustomerDetail editing', () => {
  beforeEach(() => {
    renderDetail()
  })

  it('prefills the form and sends the current expected version', async () => {
    mockedUpdate.mockResolvedValue(detail({ displayName: 'Мария Нова', version: 4 }))
    await openEditor()
    expect(screen.getByLabelText('Име')).toHaveValue('Мария Тестова')
    expect(screen.getByLabelText('Телефон')).toHaveValue('+359895555777')
    type('Име', 'Мария Нова')
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await waitFor(() => expect(mockedUpdate).toHaveBeenCalledTimes(1))
    expect(mockedUpdate).toHaveBeenCalledWith('customer-1', {
      displayName: 'Мария Нова',
      phone: '+359895555777',
      email: 'maria@example.test',
      expectedVersion: 3,
    })
    expect(await screen.findByText('Промените са запазени.')).toBeInTheDocument()
    expect(screen.getByText('Мария Нова')).toBeInTheDocument()
    expect(screen.queryByLabelText('Име')).toBeNull()
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('allows removing the phone while the email remains', async () => {
    mockedUpdate.mockResolvedValue(detail({ phone: null, version: 4 }))
    await openEditor()
    type('Телефон', '')
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await waitFor(() => expect(mockedUpdate).toHaveBeenCalled())
    const sent = mockedUpdate.mock.calls[0]![1]
    expect(sent.phone).toBeUndefined()
    expect(sent.email).toBe('maria@example.test')
    expect(await screen.findByText('Промените са запазени.')).toBeInTheDocument()
    expect(screen.queryByText('Телефон')).toBeNull()
  })

  it('allows removing the email while the phone remains', async () => {
    mockedUpdate.mockResolvedValue(detail({ email: null, version: 4 }))
    await openEditor()
    type('Имейл', '')
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    await waitFor(() => expect(mockedUpdate).toHaveBeenCalled())
    expect(mockedUpdate.mock.calls[0]![1].email).toBeUndefined()
    expect(mockedUpdate.mock.calls[0]![1].phone).toBe('+359895555777')
  })

  it('rejects removing both contacts locally and keeps the form open', async () => {
    await openEditor()
    type('Телефон', '')
    type('Имейл', '')
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(screen.getByText('Въведете телефон или имейл.')).toBeInTheDocument()
    expect(mockedUpdate).not.toHaveBeenCalled()
  })

  it('maps a duplicate contact inline and preserves every entered value', async () => {
    mockedUpdate.mockRejectedValue(
      new ApiError(409, 'CUSTOMER_CONTACT_CONFLICT', 'x', { email: 'x' }),
    )
    await openEditor()
    type('Имейл', 'taken@example.test')
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(
      await screen.findByText('Този имейл адрес вече е записан за друг клиент.'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Имейл')).toHaveValue('taken@example.test')
    expect(screen.getByLabelText('Имейл')).toHaveFocus()
  })

  it('does not retry automatically after a generic concurrent conflict', async () => {
    mockedUpdate.mockRejectedValue(new ApiError(409, 'CUSTOMER_CONCURRENT_CONFLICT', 'x'))
    await openEditor()
    type('Име', 'Нова')
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Операцията не можа да бъде завършена. Опитайте отново.',
    )
    expect(mockedUpdate).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: 'Зареди актуалните данни' })).toBeNull()
  })

  it('shows safe feedback for an unexpected failure and keeps the entered values', async () => {
    mockedUpdate.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'org.postgresql'))
    await openEditor()
    type('Име', 'Нова')
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Възникна неочаквана грешка.')
    expect(screen.getByLabelText('Име')).toHaveValue('Нова')
  })

  it('blocks duplicate saves while pending', async () => {
    mockedUpdate.mockReturnValue(new Promise(() => undefined))
    await openEditor()
    type('Име', 'Нова')
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(screen.getByRole('button', { name: 'Запазване…' })).toBeDisabled()
    fireEvent.submit(screen.getByRole('button', { name: 'Запазване…' }).closest('form')!)
    expect(mockedUpdate).toHaveBeenCalledTimes(1)
  })
})

describe('CustomerDetail concurrent update', () => {
  beforeEach(() => {
    renderDetail()
  })

  async function conflict() {
    mockedUpdate.mockRejectedValue(new ApiError(409, 'CUSTOMER_CONCURRENT_UPDATE', 'raw'))
    await openEditor()
    type('Име', 'Моята промяна')
    fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Данните за клиента са променени. Обновете данните и опитайте отново.',
    )
  }

  it('keeps the entered values and offers a guarded reload without overwriting', async () => {
    await conflict()
    expect(screen.getByLabelText('Име')).toHaveValue('Моята промяна')
    expect(mockedUpdate).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Зареди актуалните данни' })).toBeInTheDocument()
  })

  it('asks before discarding the entered values, and Остани keeps them', async () => {
    await conflict()
    fireEvent.click(screen.getByRole('button', { name: 'Зареди актуалните данни' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('Имате незапазени промени.')
    expect(screen.getByRole('button', { name: 'Остани' })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(screen.getByLabelText('Име')).toHaveValue('Моята промяна')
    expect(mockedGet).toHaveBeenCalledTimes(1)
  })

  it('replaces the form with the latest server data after the discard is confirmed', async () => {
    await conflict()
    mockedGet.mockResolvedValue(detail({ displayName: 'Име от сървъра', version: 9 }))
    fireEvent.click(screen.getByRole('button', { name: 'Зареди актуалните данни' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Напусни' }))
    expect(await screen.findByText('Име от сървъра')).toBeInTheDocument()
    expect(screen.queryByLabelText('Име')).toBeNull()
    expect(mockedGet).toHaveBeenCalledTimes(2)
    // The next edit is based on the reloaded version.
    mockedUpdate.mockResolvedValue(detail({ version: 10 }))
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Запази промените' }))
    await waitFor(() => expect(mockedUpdate).toHaveBeenCalledTimes(2))
    expect(mockedUpdate.mock.calls[1]![1].expectedVersion).toBe(9)
  })
})

describe('CustomerDetail unsaved-changes guard', () => {
  beforeEach(() => {
    renderDetail()
  })

  it('cancels a clean edit without a dialog', async () => {
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(screen.queryByLabelText('Име')).toBeNull()
  })

  it('asks before canceling a dirty edit; Остани restores focus, Напусни resets the form', async () => {
    await openEditor()
    type('Име', 'Промяна')
    const cancel = screen.getByRole('button', { name: 'Отказ' })
    cancel.focus()
    fireEvent.click(cancel)
    const dialog = await screen.findByRole('alertdialog')
    expect(within(dialog).getAllByRole('button').map((button) => button.textContent)).toEqual([
      'Остани',
      'Напусни',
    ])
    expect(screen.getByRole('button', { name: 'Остани' })).toHaveFocus()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(screen.getByLabelText('Име')).toHaveValue('Промяна')
    expect(cancel).toHaveFocus()

    fireEvent.click(cancel)
    fireEvent.click(await screen.findByRole('button', { name: 'Напусни' }))
    expect(screen.queryByLabelText('Име')).toBeNull()
    expect(screen.getByText('Мария Тестова')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Редактирай' }))
    expect(await screen.findByLabelText('Име')).toHaveValue('Мария Тестова')
  })

  it('does not treat a reverted value as an unsaved change', async () => {
    await openEditor()
    type('Име', 'Промяна')
    type('Име', 'Мария Тестова')
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })
})
