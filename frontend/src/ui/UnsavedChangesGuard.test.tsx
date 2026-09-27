import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Button } from './Button'
import {
  UnsavedChangesGuardProvider,
  useGuardedFormState,
  useUnsavedChangesGuard,
} from './UnsavedChangesGuard'

function GuardedForm({ initial = '' }: { initial?: string }) {
  const [value, setValue] = useState(initial)
  const isDirty = value !== initial
  const guard = useGuardedFormState(isDirty, () => setValue(initial))
  return (
    <div>
      <label>
        Поле
        <input value={value} onChange={(event) => setValue(event.target.value)} />
      </label>
      <Button
        type="button"
        onClick={() => guard.guard(() => setValue(`${initial}-navigated`))}
      >
        Придвижи се
      </Button>
      <Button
        type="button"
        onClick={() => guard.guard(() => setValue(`${initial}-navigated-again`))}
      >
        Друго действие
      </Button>
    </div>
  )
}

function Harness({ mount = true, initial = '' }: { mount?: boolean; initial?: string }) {
  return (
    <UnsavedChangesGuardProvider>
      {mount && <GuardedForm initial={initial} />}
    </UnsavedChangesGuardProvider>
  )
}

describe('UnsavedChangesGuard', () => {
  it('proceeds immediately when the registered form is clean', () => {
    render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Придвижи се' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Поле')).toHaveValue('-navigated')
  })

  it('has no visible title, an accessible name, the exact body copy, and both actions in the required order', () => {
    render(<Harness />)
    fireEvent.change(screen.getByLabelText('Поле'), { target: { value: 'чернова' } })
    fireEvent.click(screen.getByRole('button', { name: 'Придвижи се' }))

    const dialog = screen.getByRole('alertdialog', { name: 'Незапазени промени' })
    expect(dialog).toBeInTheDocument()
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.queryByText('Отказ')).not.toBeInTheDocument()
    expect(dialog.querySelector('h1, h2, h3, h4, h5, h6')).toBeNull()
    expect(dialog).toHaveTextContent(
      'Направените промени няма да бъдат запазени. Сигурни ли сте, че искате да продължите?',
    )
    const buttons = within(dialog).getAllByRole('button')
    expect(buttons.map((button) => button.textContent)).toEqual([
      'Продължи редактирането',
      'Откажи промените',
    ])
  })

  it('does not stack a duplicate dialog when the form is already dirty', () => {
    render(<Harness />)
    fireEvent.change(screen.getByLabelText('Поле'), { target: { value: 'чернова' } })
    fireEvent.click(screen.getByRole('button', { name: 'Придвижи се' }))
    fireEvent.click(screen.getByRole('button', { name: 'Придвижи се' }))

    expect(screen.getAllByRole('alertdialog')).toHaveLength(1)
  })

  it('retains the original pending action and ignores a second guarded attempt while the dialog is open', () => {
    render(<Harness />)
    fireEvent.change(screen.getByLabelText('Поле'), { target: { value: 'чернова' } })
    fireEvent.click(screen.getByRole('button', { name: 'Придвижи се' }))
    fireEvent.click(screen.getByRole('button', { name: 'Друго действие' }))

    expect(screen.getAllByRole('alertdialog')).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Откажи промените' }))

    expect(screen.getByLabelText('Поле')).toHaveValue('-navigated')
    expect(screen.getByLabelText('Поле')).not.toHaveValue('-navigated-again')
  })

  it('preserves the value and does not navigate when continuing to edit', () => {
    render(<Harness />)
    fireEvent.change(screen.getByLabelText('Поле'), { target: { value: 'чернова' } })
    fireEvent.click(screen.getByRole('button', { name: 'Придвижи се' }))
    fireEvent.click(screen.getByRole('button', { name: 'Продължи редактирането' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Поле')).toHaveValue('чернова')
  })

  it('discards and proceeds to the pending destination when confirmed', () => {
    render(<Harness />)
    fireEvent.change(screen.getByLabelText('Поле'), { target: { value: 'чернова' } })
    fireEvent.click(screen.getByRole('button', { name: 'Придвижи се' }))
    fireEvent.click(screen.getByRole('button', { name: 'Откажи промените' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Поле')).toHaveValue('-navigated')
  })

  it('returns to editing on Escape without discarding', () => {
    render(<Harness />)
    fireEvent.change(screen.getByLabelText('Поле'), { target: { value: 'чернова' } })
    fireEvent.click(screen.getByRole('button', { name: 'Придвижи се' }))
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' })

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Поле')).toHaveValue('чернова')
  })

  it('focuses the safe "continue editing" action by default, never the destructive one', () => {
    render(<Harness />)
    fireEvent.change(screen.getByLabelText('Поле'), { target: { value: 'чернова' } })
    fireEvent.click(screen.getByRole('button', { name: 'Придвижи се' }))
    expect(screen.getByRole('button', { name: 'Продължи редактирането' })).toHaveFocus()
  })

  it('restores focus to the control that triggered the guard when continuing to edit', () => {
    render(<Harness />)
    fireEvent.change(screen.getByLabelText('Поле'), { target: { value: 'чернова' } })
    const trigger = screen.getByRole('button', { name: 'Придвижи се' })
    trigger.focus()
    fireEvent.click(trigger)
    fireEvent.click(screen.getByRole('button', { name: 'Продължи редактирането' }))

    expect(trigger).toHaveFocus()
  })

  it('registers beforeunload protection only while dirty and removes it afterward', () => {
    const addSpy = vi.spyOn(window, 'addEventListener')
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    render(<Harness />)

    expect(addSpy).not.toHaveBeenCalledWith('beforeunload', expect.any(Function))

    fireEvent.change(screen.getByLabelText('Поле'), { target: { value: 'чернова' } })
    expect(addSpy).toHaveBeenCalledWith('beforeunload', expect.any(Function))

    fireEvent.change(screen.getByLabelText('Поле'), { target: { value: '' } })
    expect(removeSpy).toHaveBeenCalledWith('beforeunload', expect.any(Function))
  })

  it('removes the guard when the guarded form unmounts', () => {
    const { rerender } = render(<Harness />)
    fireEvent.change(screen.getByLabelText('Поле'), { target: { value: 'чернова' } })

    function ProbeAfterUnmount() {
      const guard = useUnsavedChangesGuard()
      return <span data-testid="dirty-flag">{String(guard.isDirty)}</span>
    }

    rerender(
      <UnsavedChangesGuardProvider>
        <ProbeAfterUnmount />
      </UnsavedChangesGuardProvider>,
    )

    expect(screen.getByTestId('dirty-flag')).toHaveTextContent('false')
  })

  it('clears the pending navigation after a confirmed discard, leaving no dialog behind', () => {
    render(<Harness />)
    fireEvent.change(screen.getByLabelText('Поле'), { target: { value: 'чернова' } })
    fireEvent.click(screen.getByRole('button', { name: 'Придвижи се' }))
    fireEvent.click(screen.getByRole('button', { name: 'Откажи промените' }))

    expect(screen.queryAllByRole('alertdialog')).toHaveLength(0)
    expect(screen.getByLabelText('Поле')).toHaveValue('-navigated')
  })
})
