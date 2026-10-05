import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../identity/api'
import { UnsavedChangesGuardProvider, useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import { createCustomer, type CustomerDetails } from './api'
import { CustomerCreate } from './CustomerCreate'
import { deferred, detail } from './testFixtures'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  createCustomer: vi.fn(),
}))

const mockedCreate = vi.mocked(createCustomer)

function renderCreate(readOnly = false) {
  const handlers = {
    onAuthenticationRequired: vi.fn(),
    onBusinessSuspended: vi.fn(),
    onCreated: vi.fn(),
    onCancel: vi.fn(),
  }
  render(
    <UnsavedChangesGuardProvider>
      <CustomerCreate readOnly={readOnly} {...handlers} />
    </UnsavedChangesGuardProvider>,
  )
  return handlers
}

const type = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
const submit = () => fireEvent.click(screen.getByRole('button', { name: 'Добави' }))

beforeEach(() => {
  mockedCreate.mockReset()
})

describe('CustomerCreate form', () => {
  it('has labelled fields, the approved hint and no account, status or note controls', () => {
    renderCreate()
    for (const label of ['Име', 'Телефон', 'Имейл']) {
      const input = screen.getByLabelText(label)
      expect(input).toHaveAttribute('autocomplete', 'off')
    }
    expect(screen.getByLabelText('Телефон')).toHaveAttribute('type', 'tel')
    expect(screen.getByLabelText('Имейл')).toHaveAttribute('type', 'email')
    expect(screen.getByText('Попълнете телефон или имейл.')).toBeInTheDocument()
    expect(screen.queryByLabelText(/парола|статус|бележк|покан/i)).toBeNull()
    expect(screen.getByRole('button', { name: 'Обратно към клиентите' })).toBeInTheDocument()
  })

  it('shows no error for an untouched form', () => {
    renderCreate()
    expect(document.querySelectorAll('.field-error')).toHaveLength(0)
  })

  it('shows every local error on submit, focuses the first and sends nothing', () => {
    renderCreate()
    submit()
    expect(screen.getByText('Въведете име на клиента до 200 знака.')).toBeInTheDocument()
    expect(screen.getByText('Въведете телефон или имейл.')).toBeInTheDocument()
    expect(screen.queryByText('Попълнете телефон или имейл.')).toBeNull()
    expect(screen.getByLabelText('Име')).toHaveFocus()
    expect(mockedCreate).not.toHaveBeenCalled()
  })

  it('reports the contact error once, on the group, without duplicating both fields', () => {
    renderCreate()
    type('Име', 'Мария Тестова')
    submit()
    expect(screen.getAllByText('Въведете телефон или имейл.')).toHaveLength(1)
    expect(screen.getByLabelText('Телефон')).toHaveFocus()
    expect(screen.getByLabelText('Телефон')).toHaveAccessibleDescription('Въведете телефон или имейл.')
    expect(screen.getByLabelText('Имейл')).toHaveAccessibleDescription('Въведете телефон или имейл.')
    type('Имейл', 'maria@example.test')
    expect(screen.queryByText('Въведете телефон или имейл.')).toBeNull()
  })

  it('shows an invalid non-empty phone and email immediately, linked with aria-describedby', () => {
    renderCreate()
    type('Телефон', '12ab')
    type('Имейл', 'мария@example.test')
    expect(screen.getByLabelText('Телефон')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Телефон')).toHaveAccessibleDescription(
      'Въведеният телефонен номер не е валиден.',
    )
    expect(screen.getByLabelText('Имейл')).toHaveAccessibleDescription(
      'Въведеният имейл адрес не е валиден.',
    )
  })

  it.each([
    ['phone only', { phone: '0895555777', email: '' }, { displayName: 'Мария Тестова', phone: '0895555777' }],
    ['email only', { phone: '', email: ' Maria@Example.test ' }, { displayName: 'Мария Тестова', email: 'Maria@Example.test' }],
    [
      'both',
      { phone: '+359 895 555 777', email: 'maria@example.test' },
      { displayName: 'Мария Тестова', phone: '+359 895 555 777', email: 'maria@example.test' },
    ],
  ])('sends %s', async (_name, contacts, expected) => {
    mockedCreate.mockResolvedValue(detail())
    const handlers = renderCreate()
    type('Име', '  Мария Тестова ')
    type('Телефон', contacts.phone)
    type('Имейл', contacts.email)
    submit()
    await waitFor(() => expect(handlers.onCreated).toHaveBeenCalledWith('customer-1'))
    const sent = mockedCreate.mock.calls[0]![0]
    expect(sent).toEqual(expected)
    expect(Object.keys(JSON.parse(JSON.stringify(sent))).sort()).toEqual(Object.keys(expected).sort())
  })

  it('does not show the unsaved-changes dialog after a successful save', async () => {
    mockedCreate.mockResolvedValue(detail())
    const handlers = renderCreate()
    type('Име', 'Мария Тестова')
    type('Имейл', 'maria@example.test')
    submit()
    await waitFor(() => expect(handlers.onCreated).toHaveBeenCalled())
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('blocks duplicate submission while pending', async () => {
    const pending = deferred<CustomerDetails>()
    mockedCreate.mockReturnValue(pending.promise)
    renderCreate()
    type('Име', 'Мария Тестова')
    type('Имейл', 'maria@example.test')
    submit()
    expect(screen.getByRole('button', { name: 'Запазване…' })).toBeDisabled()
    fireEvent.submit(screen.getByRole('button', { name: 'Запазване…' }).closest('form')!)
    expect(mockedCreate).toHaveBeenCalledTimes(1)
  })
})

function GuardedLeave({ onLeave }: { onLeave: () => void }) {
  const guard = useUnsavedChangesGuard()
  return (
    <button type="button" onClick={() => guard.guard(onLeave)}>
      Към другаде
    </button>
  )
}

describe('CustomerCreate discard', () => {
  it('really resets the form when a confirmed discard leaves it mounted', () => {
    const onLeave = vi.fn()
    render(
      <UnsavedChangesGuardProvider>
        <CustomerCreate
          readOnly={false}
          onAuthenticationRequired={vi.fn()}
          onBusinessSuspended={vi.fn()}
          onCreated={vi.fn()}
          onCancel={vi.fn()}
        />
        <GuardedLeave onLeave={onLeave} />
      </UnsavedChangesGuardProvider>,
    )
    type('Име', 'Мария')
    type('Телефон', 'abc')
    fireEvent.click(screen.getByRole('button', { name: 'Към другаде' }))
    expect(onLeave).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))
    expect(onLeave).toHaveBeenCalledOnce()
    // The form stayed mounted but starts clean again: values and validation state.
    expect(screen.getByLabelText('Име')).toHaveValue('')
    expect(screen.getByLabelText('Телефон')).toHaveValue('')
    expect(document.querySelectorAll('.field-error')).toHaveLength(0)
    // And it is no longer dirty, so a second leave shows no dialog.
    fireEvent.click(screen.getByRole('button', { name: 'Към другаде' }))
    expect(onLeave).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('disables the back action while the request is pending', () => {
    mockedCreate.mockReturnValue(new Promise(() => undefined))
    renderCreate()
    type('Име', 'Мария')
    type('Имейл', 'maria@example.test')
    submit()
    expect(screen.getByRole('button', { name: 'Обратно към клиентите' })).toBeDisabled()
  })
})

describe('CustomerCreate backend failures', () => {
  const fill = () => {
    type('Име', 'Мария Тестова')
    type('Телефон', '0895555777')
    type('Имейл', 'maria@example.test')
    submit()
  }

  it('maps validation field errors inline with frontend text and keeps the values', async () => {
    mockedCreate.mockRejectedValue(
      new ApiError(400, 'VALIDATION_ERROR', 'Проверете', { phone: 'SQL leak', email: 'x' }),
    )
    renderCreate()
    fill()
    expect(await screen.findByText('Въведеният телефонен номер не е валиден.')).toBeInTheDocument()
    expect(screen.getByText('Въведеният имейл адрес не е валиден.')).toBeInTheDocument()
    expect(screen.queryByText('SQL leak')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByLabelText('Телефон')).toHaveFocus()
    expect(screen.getByLabelText('Име')).toHaveValue('Мария Тестова')
    expect(screen.getByLabelText('Телефон')).toHaveValue('0895555777')
    // Editing a field drops only its own backend message.
    type('Телефон', '0895555778')
    expect(screen.queryByText('Въведеният телефонен номер не е валиден.')).toBeNull()
    expect(screen.getByText('Въведеният имейл адрес не е валиден.')).toBeInTheDocument()
  })

  it('maps duplicate phone and email conflicts to their own fields without a form alert', async () => {
    mockedCreate.mockRejectedValue(
      new ApiError(409, 'CUSTOMER_CONTACT_CONFLICT', 'x', { phone: 'a', email: 'b' }),
    )
    renderCreate()
    fill()
    expect(
      await screen.findByText('Този телефонен номер вече е записан за друг клиент.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Този имейл адрес вече е записан за друг клиент.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('maps a backend contact error to the contact group', async () => {
    mockedCreate.mockRejectedValue(new ApiError(400, 'VALIDATION_ERROR', 'x', { contact: 'x' }))
    renderCreate()
    fill()
    expect(await screen.findByText('Въведете телефон или имейл.')).toBeInTheDocument()
  })

  it.each([
    ['CUSTOMER_CONCURRENT_CONFLICT', 409, 'Операцията не можа да бъде завършена. Опитайте отново.'],
    ['INTERNAL_ERROR', 500, 'Възникна неочаквана грешка.'],
    ['ACCESS_DENIED', 403, 'Нямате достъп до тази операция.'],
    ['VALIDATION_ERROR', 400, 'Проверете въведените данни.'],
  ])('shows the safe message for %s without retrying', async (code, status, text) => {
    mockedCreate.mockRejectedValue(new ApiError(status, code, 'org.postgresql raw detail'))
    renderCreate()
    fill()
    expect(await screen.findByRole('alert')).toHaveTextContent(text)
    expect(screen.queryByText(/postgresql/)).toBeNull()
    expect(mockedCreate).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText('Име')).toHaveValue('Мария Тестова')
  })

  it('reports a suspended Business so the application can enter read-only mode', async () => {
    mockedCreate.mockRejectedValue(new ApiError(409, 'BUSINESS_SUSPENDED', 'x'))
    const handlers = renderCreate()
    fill()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Спрян бизнес може само да преглежда данните си.',
    )
    expect(handlers.onBusinessSuspended).toHaveBeenCalledOnce()
  })

  it('hands an expired session to the application', async () => {
    mockedCreate.mockRejectedValue(new ApiError(401, 'AUTH_REQUIRED', 'Необходим е вход.'))
    const handlers = renderCreate()
    fill()
    await waitFor(() => expect(handlers.onAuthenticationRequired).toHaveBeenCalledWith('Необходим е вход.'))
  })

  it('renders no editable fields for a suspended Business', () => {
    renderCreate(true)
    expect(screen.queryByLabelText('Име')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Добави' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Обратно към клиентите' })).toBeInTheDocument()
  })

  it('goes back through the supplied cancel action', () => {
    const handlers = renderCreate()
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към клиентите' }))
    expect(handlers.onCancel).toHaveBeenCalledOnce()
  })
})
