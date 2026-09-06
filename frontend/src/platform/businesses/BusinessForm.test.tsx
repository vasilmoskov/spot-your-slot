import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen } from '@testing-library/react'
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
})
