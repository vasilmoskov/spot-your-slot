import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { BusinessForm } from './BusinessForm'
import type { BusinessDetails } from './api'

const business: BusinessDetails = {
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

describe('BusinessForm', () => {
  it('uses the approved two-column field order without visible helper text', () => {
    const { container } = render(
      <BusinessForm busy={false} submitLabel="Създай бизнес" onSubmit={vi.fn()} />,
    )

    const columns = container.querySelectorAll('.business-information-column')
    const labels = (column: Element) =>
      Array.from(column.querySelectorAll('label')).map((label) =>
        label.firstChild?.textContent?.trim(),
      )

    expect(labels(columns[0]!)).toEqual([
      'Име на бизнеса',
      'Идентификатор в уеб адреса',
      'Дейност',
      'Телефон (по избор)',
      'Имейл за контакт (по избор)',
    ])
    expect(labels(columns[1]!)).toEqual([
      'Улица (по избор)',
      'Номер (по избор)',
      'Пощенски код (по избор)',
      'Град (по избор)',
      'Допълнителни указания (по избор)',
    ])
    expect(
      screen.getByLabelText('Описание (по избор)').closest(
        '.business-information-columns',
      ),
    ).toBeNull()
    expect(document.body).not.toHaveTextContent(/примерен адрес|Например вход/)
  })

  it('offers every BusinessType and submits only approved creation fields', () => {
    const onSubmit = vi.fn()
    render(
      <BusinessForm busy={false} submitLabel="Създай бизнес" onSubmit={onSubmit} />,
    )

    const activity = screen.getByLabelText('Дейност')
    expect(activity.querySelectorAll('option')).toHaveLength(7)
    expect(screen.getByText('Фризьорски салон')).toBeInTheDocument()
    expect(screen.getByText('Бръснарница')).toBeInTheDocument()
    expect(screen.getByText('Студио за маникюр')).toBeInTheDocument()
    expect(screen.getByText('Масажно студио')).toBeInTheDocument()
    expect(screen.getByText('Студио за грим')).toBeInTheDocument()
    expect(screen.getByText('Козметично студио')).toBeInTheDocument()
    expect(screen.getByText('Друг')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Име на бизнеса'), {
      target: { value: 'Студио А' },
    })
    fireEvent.change(screen.getByLabelText(/^Идентификатор в уеб адреса/), {
      target: { value: 'studio-a' },
    })
    fireEvent.change(activity, { target: { value: 'NAIL_STUDIO' } })
    fireEvent.submit(screen.getByRole('button', { name: 'Създай бизнес' }).closest('form')!)

    expect(onSubmit).toHaveBeenCalledWith({
      slug: 'studio-a',
      displayName: 'Студио А',
      businessType: 'NAIL_STUDIO',
      description: undefined,
      city: undefined,
      postalCode: undefined,
      street: undefined,
      streetNumber: undefined,
      addressDetails: undefined,
      phone: undefined,
      contactEmail: undefined,
    })
    expect(screen.queryByLabelText(/статус/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/версия/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/създаден/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/часова зона/i)).not.toBeInTheDocument()
  })

  it('prepopulates editable profile fields and returns the authoritative expected version', () => {
    const onSubmit = vi.fn()
    render(
      <BusinessForm
        business={business}
        busy={false}
        submitLabel="Запази промените"
        onSubmit={onSubmit}
      />,
    )

    expect(screen.getByLabelText('Име на бизнеса')).toHaveValue('Студио А')
    expect(screen.getByLabelText(/^Идентификатор в уеб адреса/)).toHaveValue('studio-a')
    expect(screen.getByLabelText('Дейност')).toHaveValue('BEAUTY_STUDIO')
    expect(screen.queryByLabelText(/часова зона/i)).not.toBeInTheDocument()
    expect(screen.getByLabelText('Описание (по избор)')).toHaveValue('Описание')
    expect(screen.getByLabelText('Град (по избор)')).toHaveAttribute('maxlength', '100')
    expect(screen.getByLabelText('Пощенски код (по избор)')).toHaveAttribute('maxlength', '20')
    expect(screen.getByLabelText('Улица (по избор)')).toHaveAttribute('maxlength', '200')
    expect(screen.getByLabelText('Номер (по избор)')).toHaveAttribute('maxlength', '50')
    expect(screen.getByLabelText(/^Допълнителни указания/)).toHaveAttribute('maxlength', '500')
    expect(screen.getByLabelText('Телефон (по избор)')).toHaveAttribute('maxlength', '50')
    expect(screen.getByLabelText('Имейл за контакт (по избор)')).toHaveAttribute(
      'maxlength',
      '320',
    )
    expect(screen.getByLabelText('Описание (по избор)')).toHaveAttribute(
      'maxlength',
      '2000',
    )

    fireEvent.submit(
      screen.getByRole('button', { name: 'Запази промените' }).closest('form')!,
    )

    expect(onSubmit).toHaveBeenCalledWith({
      slug: 'studio-a',
      displayName: 'Студио А',
      businessType: 'BEAUTY_STUDIO',
      timezone: 'Europe/Sofia',
      description: 'Описание',
      city: 'София',
      postalCode: '1000',
      street: 'Примерна',
      streetNumber: '1',
      addressDetails: 'вход А',
      phone: '+359 2 000 0000',
      contactEmail: 'contact@example.invalid',
      expectedVersion: 4,
    })
  })

  it('uses Bulgarian required and email validation messages', () => {
    render(
      <BusinessForm busy={false} submitLabel="Създай бизнес" onSubmit={vi.fn()} />,
    )
    const name = screen.getByLabelText('Име на бизнеса') as HTMLInputElement
    const email = screen.getByLabelText(
      'Имейл за контакт (по избор)',
    ) as HTMLInputElement

    fireEvent.invalid(name)
    expect(name.validationMessage).toBe('Моля, попълнете това поле.')
    fireEvent.input(name, { target: { value: 'Студио' } })
    expect(name.validationMessage).toBe('')

    fireEvent.input(email, { target: { value: 'невалиден' } })
    fireEvent.invalid(email)
    expect(email.validationMessage).toBe('Моля, въведете валиден имейл адрес.')
  })

  it('disables submission while a mutation is in progress', () => {
    render(
      <BusinessForm busy submitLabel="Запази промените" onSubmit={vi.fn()} />,
    )

    expect(screen.getByRole('button', { name: 'Запазване…' })).toBeDisabled()
  })

  describe('public address (slug)', () => {
    const RESERVED = 'Изберете друг публичен адрес на бизнеса.'
    const NOTE = 'Публичният адрес не може да се променя след активиране.'

    function slugInput(): HTMLInputElement {
      return screen.getByLabelText('Идентификатор в уеб адреса') as HTMLInputElement
    }

    it('shows the reserved-address error inline on create and keeps the value', () => {
      render(<BusinessForm busy={false} submitLabel="Създай бизнес" onSubmit={vi.fn()} />)

      fireEvent.change(slugInput(), { target: { value: 'Login' } })

      expect(screen.getByText(RESERVED)).toBeInTheDocument()
      expect(slugInput()).toHaveValue('Login')
      expect(slugInput()).toHaveAttribute('aria-invalid', 'true')
      expect(slugInput()).toHaveAccessibleDescription(RESERVED)
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('blocks submit for a reserved address, focuses the slug and sends nothing', () => {
      const onSubmit = vi.fn()
      render(<BusinessForm busy={false} submitLabel="Създай бизнес" onSubmit={onSubmit} />)
      fireEvent.change(screen.getByLabelText('Име на бизнеса'), { target: { value: 'Студио' } })
      fireEvent.change(slugInput(), { target: { value: 'booking' } })

      fireEvent.click(screen.getByRole('button', { name: 'Създай бизнес' }))

      expect(onSubmit).not.toHaveBeenCalled()
      expect(slugInput()).toHaveFocus()
      expect(slugInput()).toHaveValue('booking')
      expect(screen.getByLabelText('Име на бизнеса')).toHaveValue('Студио')
    })

    it.each(['booking-studio', 'my-book', 'appointments-bg'])(
      'accepts the near-miss %s',
      async (slug) => {
        const onSubmit = vi.fn()
        render(<BusinessForm busy={false} submitLabel="Създай бизнес" onSubmit={onSubmit} />)
        fireEvent.change(screen.getByLabelText('Име на бизнеса'), { target: { value: 'Студио' } })
        fireEvent.change(slugInput(), { target: { value: slug } })

        fireEvent.click(screen.getByRole('button', { name: 'Създай бизнес' }))

        await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce())
        expect(screen.queryByText(RESERVED)).not.toBeInTheDocument()
      },
    )

    it('maps a backend slug field error under the slug field without a form alert', async () => {
      const onSubmit = vi.fn().mockResolvedValue({ fieldErrors: { slug: RESERVED } })
      render(<BusinessForm busy={false} submitLabel="Създай бизнес" onSubmit={onSubmit} />)
      fireEvent.change(screen.getByLabelText('Име на бизнеса'), { target: { value: 'Студио' } })
      fireEvent.change(slugInput(), { target: { value: 'studio' } })

      fireEvent.click(screen.getByRole('button', { name: 'Създай бизнес' }))

      expect(await screen.findByText(RESERVED)).toBeInTheDocument()
      expect(slugInput()).toHaveFocus()
      expect(slugInput()).toHaveValue('studio')
      expect(slugInput()).toHaveAccessibleDescription(RESERVED)
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()

      fireEvent.change(slugInput(), { target: { value: 'studio-2' } })
      expect(screen.queryByText(RESERVED)).not.toBeInTheDocument()
    })

    it('rejects changing a DRAFT slug to a reserved value', () => {
      const onSubmit = vi.fn()
      render(
        <BusinessForm
          business={business}
          busy={false}
          submitLabel="Запази промените"
          onSubmit={onSubmit}
        />,
      )

      fireEvent.change(slugInput(), { target: { value: 'admin' } })
      fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

      expect(screen.getByText(RESERVED)).toBeInTheDocument()
      expect(slugInput()).toHaveFocus()
      expect(onSubmit).not.toHaveBeenCalled()
    })

    it('lets a grandfathered reserved DRAFT slug stay while another field is saved', async () => {
      const onSubmit = vi.fn()
      render(
        <BusinessForm
          business={{ ...business, slug: 'login' }}
          busy={false}
          submitLabel="Запази промените"
          onSubmit={onSubmit}
        />,
      )
      expect(screen.queryByText(RESERVED)).not.toBeInTheDocument()

      fireEvent.change(screen.getByLabelText('Име на бизнеса'), { target: { value: 'Студио Б' } })
      fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

      await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce())
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining({ slug: 'login', displayName: 'Студио Б', expectedVersion: 4 }),
      )
    })

    it('still rejects moving a grandfathered reserved DRAFT to another reserved value', () => {
      render(
        <BusinessForm
          business={{ ...business, slug: 'login' }}
          busy={false}
          submitLabel="Запази промените"
          onSubmit={vi.fn()}
        />,
      )

      fireEvent.change(slugInput(), { target: { value: 'logout' } })

      expect(screen.getByText(RESERVED)).toBeInTheDocument()
    })

    it.each(['ACTIVE', 'SUSPENDED'] as const)(
      'shows the slug read-only for %s, with the explanation and no editable control',
      (status) => {
        const { container } = render(
          <BusinessForm
            business={{ ...business, status }}
            busy={false}
            submitLabel="Запази промените"
            onSubmit={vi.fn()}
          />,
        )

        expect(container.querySelector('input[name="slug"]')).toBeNull()
        expect(screen.queryByRole('textbox', { name: /Идентификатор/ })).not.toBeInTheDocument()
        expect(screen.getByText('Идентификатор в уеб адреса')).toBeInTheDocument()
        expect(screen.getByText('studio-a')).toBeInTheDocument()
        expect(screen.getByText(NOTE)).toBeInTheDocument()
        expect(container.querySelector('[disabled]')).toBeNull()
        expect(screen.getByLabelText('Име на бизнеса')).toBeEnabled()
        expect(document.body).not.toHaveTextContent(/статус|SUSPENDED|ACTIVE|DRAFT/i)
      },
    )

    it.each(['ACTIVE', 'SUSPENDED'] as const)(
      'lets other fields change for %s and submits the stored slug unchanged',
      async (status) => {
        const onSubmit = vi.fn()
        render(
          <BusinessForm
            business={{ ...business, status, slug: 'api' }}
            busy={false}
            submitLabel="Запази промените"
            onSubmit={onSubmit}
          />,
        )

        fireEvent.change(screen.getByLabelText('Име на бизнеса'), {
          target: { value: 'Студио Б' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Запази промените' }))

        await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce())
        expect(onSubmit).toHaveBeenCalledWith(
          expect.objectContaining({ slug: 'api', displayName: 'Студио Б', expectedVersion: 4 }),
        )
        expect(screen.queryByText(RESERVED)).not.toBeInTheDocument()
      },
    )

    it('measures unsaved changes without the read-only slug', () => {
      const onDirtyChange = vi.fn()
      render(
        <BusinessForm
          business={{ ...business, status: 'ACTIVE' }}
          busy={false}
          submitLabel="Запази промените"
          onDirtyChange={onDirtyChange}
          onSubmit={vi.fn()}
        />,
      )
      const name = screen.getByLabelText('Име на бизнеса')

      fireEvent.change(name, { target: { value: 'Друго име' } })
      expect(onDirtyChange).toHaveBeenLastCalledWith(true)

      fireEvent.change(name, { target: { value: 'Студио А' } })
      expect(onDirtyChange).toHaveBeenLastCalledWith(false)
    })

    it('reports a changed DRAFT slug as an unsaved change', () => {
      const onDirtyChange = vi.fn()
      render(
        <BusinessForm
          business={business}
          busy={false}
          submitLabel="Запази промените"
          onDirtyChange={onDirtyChange}
          onSubmit={vi.fn()}
        />,
      )

      fireEvent.change(slugInput(), { target: { value: 'studio-b' } })
      expect(onDirtyChange).toHaveBeenLastCalledWith(true)
      fireEvent.change(slugInput(), { target: { value: 'studio-a' } })
      expect(onDirtyChange).toHaveBeenLastCalledWith(false)
    })
  })
})
