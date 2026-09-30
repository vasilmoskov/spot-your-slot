import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { describe, expect, it, vi } from 'vitest'
import { DatePeriodFields, type DateControl } from './DatePeriodFields'

const control = (id: string, label: string, overrides: Partial<DateControl> = {}): DateControl => ({
  id,
  label,
  value: '2026-10-01',
  error: undefined,
  onChange: vi.fn(),
  ...overrides,
})

describe('DatePeriodFields', () => {
  it('names an inclusive range "Период" (accessibly only) with "От" and "До" as one accessible group', () => {
    render(<DatePeriodFields first={control('a', 'От')} last={control('b', 'До')} />)

    const group = screen.getByRole('group', { name: 'Период' })
    expect(within(group).getByLabelText('От')).toHaveValue('2026-10-01')
    expect(within(group).getByLabelText('До')).toHaveValue('2026-10-01')
    // The group name is for assistive technology only; nothing visible says "Период".
    expect(screen.queryByText('Период')).not.toBeInTheDocument()
    expect(screen.queryByText('Първа дата')).not.toBeInTheDocument()
    expect(screen.queryByText('Последна дата')).not.toBeInTheDocument()
  })

  it('shows only "Дата" for a single date, with no group', () => {
    render(<DatePeriodFields first={control('a', 'Дата')} />)

    expect(screen.getByLabelText('Дата')).toBeInTheDocument()
    expect(screen.queryByRole('group')).not.toBeInTheDocument()
    expect(screen.queryByText('Период')).not.toBeInTheDocument()
  })

  it('keeps the shared bounds and reports edits and blur', () => {
    const onChange = vi.fn()
    const onBlur = vi.fn()
    render(
      <DatePeriodFields first={control('a', 'От', { onChange, onBlur })} last={control('b', 'До')} />,
    )

    const input = screen.getByLabelText('От')
    expect(input).toHaveAttribute('type', 'date')
    expect(input).toHaveAttribute('min', '2000-01-01')
    expect(input).toHaveAttribute('max', '2100-12-31')
    fireEvent.change(input, { target: { value: '2026-10-05' } })
    fireEvent.blur(input)
    expect(onChange).toHaveBeenCalledWith('2026-10-05')
    expect(onBlur).toHaveBeenCalledTimes(1)
  })

  it('links each error to its own control below the row', () => {
    render(
      <DatePeriodFields
        first={control('a', 'От', { error: 'Въведете начална дата.' })}
        last={control('b', 'До', { error: 'Крайната дата не може да бъде преди началната.' })}
      />,
    )

    expect(screen.getByLabelText('От')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('От')).toHaveAccessibleDescription('Въведете начална дата.')
    expect(screen.getByLabelText('До')).toHaveAccessibleDescription(
      'Крайната дата не може да бъде преди началната.',
    )
  })

  it('renders trailing content in the same row', () => {
    render(
      <DatePeriodFields first={control('a', 'От')} last={control('b', 'До')}>
        <button type="submit">Покажи</button>
      </DatePeriodFields>,
    )
    expect(screen.getByRole('group', { name: 'Период' })).toContainElement(
      screen.getByRole('button', { name: 'Покажи' }),
    )
  })

  it('places every label above its input in the DOM, for a range and for a single date', () => {
    const { container, rerender } = render(
      <DatePeriodFields first={control('a', 'От')} last={control('b', 'До')} />,
    )
    for (const field of container.querySelectorAll('.date-period-field')) {
      expect(field.children[0]?.tagName).toBe('LABEL')
      expect(field.children[1]?.tagName).toBe('INPUT')
    }
    expect(container.querySelectorAll('.date-period-field')).toHaveLength(2)

    rerender(<DatePeriodFields first={control('a', 'Дата')} />)
    const single = container.querySelector('.date-period-field')!
    expect(single.children[0]).toHaveTextContent('Дата')
    expect(single.children[1]?.tagName).toBe('INPUT')
  })

  it('styles the field as a column, bottom-aligns trailing actions, and stacks without wrapping when narrow', () => {
    const css = readFileSync('src/styles.css', 'utf8')
    expect(css.match(/\.date-period-field \{[^}]*\}/)?.[0] ?? '').toContain('flex-direction: column')
    // Wide: the row aligns children to the bottom, so the action lines up with
    // the inputs rather than with their labels.
    const row = css.match(/\.date-period-row \{[^}]*\}/)?.[0] ?? ''
    expect(row).toContain('align-items: flex-end')
    expect(row).toContain('flex-wrap: wrap')
    expect(css.match(/\.date-period-row > \.button \{[^}]*\}/)?.[0] ?? '').toContain(
      'align-self: flex-end',
    )
    // Narrow: one column, no second column, action at the start.
    const narrow = css.slice(css.indexOf('@media (max-width: 30rem) {\n  /* A single stacked column'))
    expect(narrow).toContain('flex-direction: column')
    expect(narrow).toContain('flex-wrap: nowrap')
    expect(css).not.toContain('.date-period-legend')
  })
})
