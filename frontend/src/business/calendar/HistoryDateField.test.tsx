import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { HistoryDateField } from './HistoryDateField'
import { backspace, paste, select, type } from './keyboardTestSupport'
import { caretAfterDigits, displayFromIso, formatDigits, formatEntry, parseDisplayDate } from './historyDate'

afterEach(cleanup)

function renderField(initial: string | null = null) {
  const onChange = vi.fn()
  function Harness() {
    const [applied, setApplied] = useState<string | null>(initial)
    return (
      <HistoryDateField
        id="date"
        label="Дата"
        value={applied}
        onChange={(next) => {
          onChange(next)
          setApplied(next)
        }}
      />
    )
  }
  render(<Harness />)
  const input = screen.getByLabelText('Дата') as HTMLInputElement
  const native = document.querySelector('input[type="date"]') as HTMLInputElement
  return { input, native, onChange }
}

describe('the history date text helpers', () => {
  it('formats the digits with dots and only adds the trailing dot while typing forward', () => {
    expect(formatDigits('2', true)).toBe('2')
    expect(formatDigits('22', true)).toBe('22.')
    expect(formatDigits('22', false)).toBe('22')
    expect(formatDigits('221', true)).toBe('22.1')
    expect(formatDigits('2210', true)).toBe('22.10.')
    expect(formatDigits('2210', false)).toBe('22.10')
    expect(formatDigits('22102026', true)).toBe('22.10.2026')
  })

  it('places the caret after the same digit, past an adjacent dot only when typing forward', () => {
    expect(caretAfterDigits('22.10.2026', 2, true)).toBe(3)
    expect(caretAfterDigits('22.10.2026', 2, false)).toBe(2)
    expect(caretAfterDigits('22.10.2026', 0, true)).toBe(0)
    expect(caretAfterDigits('22.1', 3, true)).toBe(4)
  })

  it('formats only a date in progress and leaves everything else exactly as entered', () => {
    expect(formatEntry('22102026', true)).toEqual({ text: '22.10.2026', formatted: true })
    expect(formatEntry('22.1', true)).toEqual({ text: '22.1', formatted: true })
    expect(formatEntry('22.', false)).toEqual({ text: '22', formatted: true })
    expect(formatEntry('221020267', true)).toEqual({ text: '221020267', formatted: false })
    expect(formatEntry('22.10.20267', true)).toEqual({ text: '22.10.20267', formatted: false })
    expect(formatEntry('22.10.2026', true)).toEqual({ text: '22.10.2026', formatted: false })
    expect(formatEntry('2.10.2026', true)).toEqual({ text: '2.10.2026', formatted: false })
    expect(formatEntry('22a', true)).toEqual({ text: '22a', formatted: false })
  })

  it('parses complete dates strictly: no rolling into another month, no clamping, leap years by the calendar', () => {
    expect(parseDisplayDate('')).toEqual({ kind: 'empty' })
    expect(parseDisplayDate('22.10.')).toEqual({ kind: 'partial' })
    expect(parseDisplayDate('22.10.2026')).toEqual({ kind: 'valid', iso: '2026-10-22' })
    expect(parseDisplayDate('31.02.2026')).toEqual({ kind: 'invalid' })
    expect(parseDisplayDate('31.04.2026')).toEqual({ kind: 'invalid' })
    expect(parseDisplayDate('29.02.2028')).toEqual({ kind: 'valid', iso: '2028-02-29' })
    expect(parseDisplayDate('29.02.2026')).toEqual({ kind: 'invalid' })
    expect(parseDisplayDate('29.02.2100')).toEqual({ kind: 'invalid' })
    expect(parseDisplayDate('00.10.2026')).toEqual({ kind: 'invalid' })
    expect(parseDisplayDate('22.13.2026')).toEqual({ kind: 'invalid' })
    // Outside the supported range, including the year the native input produced from a half-typed year.
    expect(parseDisplayDate('22.10.0026')).toEqual({ kind: 'invalid' })
    expect(parseDisplayDate('01.01.2101')).toEqual({ kind: 'invalid' })
    expect(displayFromIso('2026-10-22')).toBe('22.10.2026')
  })
})

describe('the history date field', () => {
  it('starts genuinely empty, with its label, a calendar button and no error', () => {
    const { input, onChange } = renderField()
    expect(input).toHaveValue('')
    // No format hint: nothing is selected and nothing is suggested.
    expect(input).not.toHaveAttribute('placeholder')
    expect(screen.getByRole('button', { name: 'Избери дата от календара' })).toBeInTheDocument()
    expect(document.querySelector('.field-error')).toBeNull()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('keeps day and month while the year is typed digit by digit (the reported dd.mm.0026 defect)', () => {
    const { input, onChange } = renderField()
    type(input, '22')
    expect(input).toHaveValue('22.')
    type(input, '10')
    expect(input).toHaveValue('22.10.')
    type(input, '2')
    expect(input).toHaveValue('22.10.2')
    type(input, '0')
    type(input, '2')
    expect(input).toHaveValue('22.10.202')
    expect(onChange).not.toHaveBeenCalled()
    type(input, '6')
    expect(input).toHaveValue('22.10.2026')
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenLastCalledWith('2026-10-22')
  })

  it('formats typed digits and typed dots to the same date', () => {
    const digits = renderField()
    type(digits.input, '22102026')
    expect(digits.input).toHaveValue('22.10.2026')
    expect(digits.onChange).toHaveBeenLastCalledWith('2026-10-22')
    cleanup()
    const dotted = renderField()
    type(dotted.input, '22.10.2026')
    expect(dotted.input).toHaveValue('22.10.2026')
    expect(dotted.onChange).toHaveBeenCalledTimes(1)
    expect(dotted.onChange).toHaveBeenLastCalledWith('2026-10-22')
  })

  it('accepts either pasted format', () => {
    const first = renderField()
    paste(first.input, '22102026')
    expect(first.input).toHaveValue('22.10.2026')
    expect(first.onChange).toHaveBeenLastCalledWith('2026-10-22')
    cleanup()
    const second = renderField()
    paste(second.input, '22.10.2026')
    expect(second.input).toHaveValue('22.10.2026')
    expect(second.onChange).toHaveBeenLastCalledWith('2026-10-22')
  })

  it('never turns malformed text into a date: it stays as entered, is shown invalid and is not applied', () => {
    for (const malformed of ['22.10.20267', '221020267', 'x22.10.2026', '22/10/2026', '22-10-2026', '22.10.2026 г.', 'на 22 октомври 2026', '2.10.2026', '22..10.2026']) {
      const { input, onChange } = renderField()
      paste(input, malformed)
      expect(input, malformed).toHaveValue(malformed)
      expect(screen.getByText('Въведете валидна дата.'), malformed).toBeInTheDocument()
      expect(onChange, malformed).not.toHaveBeenCalled()
      cleanup()
    }
  })

  it('a ninth digit after a complete date is kept and refused instead of being dropped', () => {
    const { input, onChange } = renderField()
    type(input, '22102026')
    expect(onChange).toHaveBeenCalledTimes(1)
    type(input, '7')
    expect(input).toHaveValue('22.10.20267')
    expect(screen.getByText('Въведете валидна дата.')).toBeInTheDocument()
    // Nothing new was applied for the malformed text; the earlier date stays in force until it is replaced.
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('a letter is kept and refused, not silently removed', () => {
    const { input, onChange } = renderField()
    type(input, '22a')
    expect(input).toHaveValue('22.a')
    expect(screen.getByText('Въведете валидна дата.')).toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('typing a digit in the middle of a complete date never discards the last year digit', () => {
    const { input, onChange } = renderField()
    paste(input, '22.10.2026')
    select(input, 4, 4)
    type(input, '5')
    expect(input).toHaveValue('22.150.2026')
    expect(screen.getByText('Въведете валидна дата.')).toBeInTheDocument()
    expect(onChange).toHaveBeenCalledTimes(1)
    backspace(input)
    expect(input).toHaveValue('22.10.2026')
    expect(document.querySelector('.field-error')).toBeNull()
  })

  it('never applies partial input, and shows no error for a partial keystroke', () => {
    const { input, onChange } = renderField()
    type(input, '221')
    expect(input).toHaveValue('22.1')
    expect(onChange).not.toHaveBeenCalled()
    expect(document.querySelector('.field-error')).toBeNull()
    expect(input).not.toHaveAttribute('aria-invalid')
  })

  it('shows an error for incomplete input only on blur, and hides it again while typing', () => {
    const { input, onChange } = renderField()
    type(input, '2210')
    fireEvent.blur(input)
    expect(screen.getByText('Въведете валидна дата.')).toBeInTheDocument()
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('Въведете валидна дата.')
    type(input, '2')
    expect(document.querySelector('.field-error')).toBeNull()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('does not blur-error on an empty field', () => {
    const { input } = renderField()
    fireEvent.blur(input)
    expect(document.querySelector('.field-error')).toBeNull()
  })

  it('rejects an impossible complete date at once, without rolling it into another month or clamping it', () => {
    const { input, onChange } = renderField()
    type(input, '31022026')
    expect(input).toHaveValue('31.02.2026')
    expect(screen.getByText('Въведете валидна дата.')).toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
    cleanup()
    const leapless = renderField()
    type(leapless.input, '29022026')
    expect(leapless.input).toHaveValue('29.02.2026')
    expect(screen.getByText('Въведете валидна дата.')).toBeInTheDocument()
    expect(leapless.onChange).not.toHaveBeenCalled()
  })

  it('accepts the leap day of a leap year', () => {
    const { input, onChange } = renderField()
    type(input, '29022028')
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenLastCalledWith('2028-02-29')
    expect(document.querySelector('.field-error')).toBeNull()
  })

  it('keeps the last applied date while a replacement is being typed, and applies the replacement when complete', () => {
    const { input, onChange } = renderField()
    paste(input, '22.10.2026')
    expect(onChange).toHaveBeenCalledTimes(1)
    backspace(input)
    backspace(input)
    expect(input).toHaveValue('22.10.20')
    expect(onChange).toHaveBeenCalledTimes(1)
    type(input, '27')
    expect(input).toHaveValue('22.10.2027')
    expect(onChange).toHaveBeenCalledTimes(2)
    expect(onChange).toHaveBeenLastCalledWith('2027-10-22')
  })

  it('does not apply the same unchanged date again', () => {
    const { input, onChange } = renderField()
    paste(input, '22.10.2026')
    backspace(input)
    type(input, '6')
    expect(input).toHaveValue('22.10.2026')
    expect(onChange).toHaveBeenCalledTimes(1)
    paste(input, '')
    select(input, 0, input.value.length)
    paste(input, '22102026')
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('clears the filter when the field is emptied, and applies again after re-entry', () => {
    const { input, onChange } = renderField()
    paste(input, '22.10.2026')
    select(input, 0, input.value.length)
    backspace(input)
    expect(input).toHaveValue('')
    expect(onChange).toHaveBeenLastCalledWith(null)
    expect(onChange).toHaveBeenCalledTimes(2)
    type(input, '23102026')
    expect(onChange).toHaveBeenLastCalledWith('2026-10-23')
    expect(onChange).toHaveBeenCalledTimes(3)
  })

  it('does not report a clear when nothing was applied', () => {
    const { input, onChange } = renderField()
    type(input, '2')
    backspace(input)
    expect(input).toHaveValue('')
    expect(onChange).not.toHaveBeenCalled()
  })

  it('can erase the dot the formatting added, one backspace at a time', () => {
    const { input } = renderField()
    type(input, '2210')
    expect(input).toHaveValue('22.10.')
    backspace(input)
    expect(input).toHaveValue('22.10')
    backspace(input)
    expect(input).toHaveValue('22.1')
    backspace(input)
    expect(input).toHaveValue('22')
    backspace(input)
    expect(input).toHaveValue('2')
  })

  it('replaces a selection and edits in the middle without losing characters or moving the caret to the end', () => {
    const { input, onChange } = renderField()
    paste(input, '22.10.2026')
    // Replace the month: select "10" and type "11".
    select(input, 3, 5)
    type(input, '1')
    type(input, '1')
    expect(input).toHaveValue('22.11.2026')
    expect(input.selectionStart).toBe(5)
    expect(onChange).toHaveBeenLastCalledWith('2026-11-22')
    // Replace one digit of the day by selecting it and typing over it.
    select(input, 1, 2)
    type(input, '5')
    expect(input).toHaveValue('25.11.2026')
    expect(input.selectionStart).toBe(2)
    expect(onChange).toHaveBeenLastCalledWith('2026-11-25')
    // Delete a digit in the middle of the year.
    select(input, 8, 8)
    backspace(input)
    expect(input).toHaveValue('25.11.226')
    expect(input.selectionStart).toBe(7)
  })

  it('selects a calendar date: the text and the applied date follow, and a native clear removes it', () => {
    const { input, native, onChange } = renderField()
    fireEvent.change(native, { target: { value: '2026-12-05' } })
    expect(input).toHaveValue('05.12.2026')
    expect(onChange).toHaveBeenLastCalledWith('2026-12-05')
    expect(document.querySelector('.field-error')).toBeNull()
    fireEvent.change(native, { target: { value: '' } })
    expect(input).toHaveValue('')
    expect(onChange).toHaveBeenLastCalledWith(null)
  })

  it('opens the native calendar from the button, which is a real focusable button', () => {
    const { native } = renderField()
    const showPicker = vi.fn()
    native.showPicker = showPicker
    const button = screen.getByRole('button', { name: 'Избери дата от календара' })
    expect(button).toHaveAttribute('type', 'button')
    button.focus()
    expect(button).toHaveFocus()
    fireEvent.click(button)
    expect(showPicker).toHaveBeenCalledTimes(1)
    // The picker's own input is only the popup: out of the tab order and hidden from assistive technology.
    expect(native).toHaveAttribute('tabindex', '-1')
    expect(native).toHaveAttribute('aria-hidden', 'true')
    expect(native).toHaveAttribute('min', '2000-01-01')
    expect(native).toHaveAttribute('max', '2100-12-31')
  })

  it('judges a native or fallback value by the typed-text rules: below or above the supported range applies nothing', () => {
    const { input, native, onChange } = renderField()
    fireEvent.change(native, { target: { value: '2026-12-05' } })
    expect(onChange).toHaveBeenCalledTimes(1)
    for (const outside of ['1999-12-31', '0026-10-22', '2101-01-01']) {
      fireEvent.change(native, { target: { value: outside } })
      expect(screen.getByText('Въведете валидна дата.'), outside).toBeInTheDocument()
      expect(onChange, outside).toHaveBeenCalledTimes(1)
    }
    expect(input).toHaveValue('01.01.2101')
    // A valid replacement applies again and clears the error.
    fireEvent.change(native, { target: { value: '2026-12-06' } })
    expect(input).toHaveValue('06.12.2026')
    expect(document.querySelector('.field-error')).toBeNull()
    expect(onChange).toHaveBeenLastCalledWith('2026-12-06')
    expect(onChange).toHaveBeenCalledTimes(2)
  })

  it('an incomplete native edit keeps the applied filter and the typed segments; only a real clear removes it', () => {
    const { input, native, onChange } = renderField()
    fireEvent.change(native, { target: { value: '2026-12-05' } })
    expect(onChange).toHaveBeenCalledTimes(1)
    // A date input mid-edit reports an empty value with bad input; that is not a clear.
    Object.defineProperty(native, 'validity', { configurable: true, value: { badInput: true } })
    fireEvent.change(native, { target: { value: '' } })
    expect(input).toHaveValue('05.12.2026')
    expect(onChange).toHaveBeenCalledTimes(1)
    // The same empty value without bad input is the user clearing the field.
    fireEvent.change(native, { target: { value: '2026-12-05' } })
    Object.defineProperty(native, 'validity', { configurable: true, value: { badInput: false } })
    fireEvent.change(native, { target: { value: '' } })
    expect(input).toHaveValue('')
    expect(onChange).toHaveBeenLastCalledWith(null)
    expect(onChange).toHaveBeenCalledTimes(2)
  })

  it('notices a native clear that fires no event (the last segment erased) on key release or blur, and only after a date was held', () => {
    const { input, native, onChange } = renderField()
    // A native input that never held a date does not clear a typed draft.
    type(input, '3102')
    fireEvent.keyUp(native)
    fireEvent.blur(native)
    expect(input).toHaveValue('31.02.')
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.change(native, { target: { value: '2026-12-05' } })
    expect(onChange).toHaveBeenLastCalledWith('2026-12-05')
    // The segments are erased one by one: mid-edit (bad input) it is not a clear, fully erased it is.
    Object.defineProperty(native, 'validity', { configurable: true, value: { badInput: true } })
    native.value = ''
    fireEvent.keyUp(native)
    expect(input).toHaveValue('05.12.2026')
    expect(onChange).toHaveBeenCalledTimes(1)
    Object.defineProperty(native, 'validity', { configurable: true, value: { badInput: false } })
    fireEvent.keyUp(native)
    expect(input).toHaveValue('')
    expect(onChange).toHaveBeenLastCalledWith(null)
    expect(onChange).toHaveBeenCalledTimes(2)
    // Releasing another key afterwards changes nothing.
    fireEvent.keyUp(native)
    expect(onChange).toHaveBeenCalledTimes(2)
  })

  it('does not write the applied date back into a native input that is being edited', () => {
    const { native } = renderField()
    fireEvent.change(native, { target: { value: '2026-12-05' } })
    expect(native.value).toBe('2026-12-05')
    // Typing into the text field moves the native input to the new applied date, and only then.
    const text = screen.getByLabelText('Дата', { exact: true }) as HTMLInputElement
    select(text, 0, text.value.length)
    paste(text, '07.01.2027')
    expect(native.value).toBe('2027-01-07')
  })

  it('shows the native date input, focused and labelled, when the picker API is missing or refuses', () => {
    for (const refuse of [false, true]) {
      const { native, onChange } = renderField()
      // jsdom has no picker API at all: the missing case is the default, the refusing one is installed.
      if (refuse) {
        native.showPicker = () => {
          throw new DOMException('refused', 'NotAllowedError')
        }
      }
      expect(native).toHaveAttribute('aria-hidden', 'true')
      fireEvent.click(screen.getByRole('button', { name: 'Избери дата от календара' }))
      const fallback = screen.getByLabelText('Дата от календара') as HTMLInputElement
      expect(fallback).toBe(native)
      expect(fallback).not.toHaveAttribute('aria-hidden')
      expect(fallback).not.toHaveAttribute('tabindex', '-1')
      expect(fallback).toHaveFocus()
      fireEvent.change(fallback, { target: { value: '2026-11-03' } })
      expect(screen.getByLabelText('Дата')).toHaveValue('03.11.2026')
      expect(onChange).toHaveBeenLastCalledWith('2026-11-03')
      cleanup()
    }
  })
})
