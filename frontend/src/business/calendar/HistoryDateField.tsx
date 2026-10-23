import { useEffect, useLayoutEffect, useRef, useState, type ChangeEvent, type SyntheticEvent } from 'react'
import { SCHEDULE_MAX_DATE, SCHEDULE_MIN_DATE } from '../../navigation'
import { Button } from '../../ui/Button'
import { FieldError, fieldControlProps } from '../../ui/formValidation'
import { caretAfterDigits, digitsOf, displayFromNative, formatEntry, parseDisplayDate } from './historyDate'

const INVALID_DATE = 'Въведете валидна дата.'

type HistoryDateFieldProps = {
  id: string
  label: string
  /** The applied filter, a canonical date or none. */
  value: string | null
  /** Called only when the applied date changes: a complete valid date, or null once the field is cleared. */
  onChange: (date: string | null) => void
}

/**
 * A text date field (`дд.мм.гггг`) with a calendar button. A native date input cannot be controlled while a date is
 * incomplete: its value is empty until every segment is filled, and writing that empty value back wipes the segments
 * already typed. So the typed text is a local draft that is formatted as it is entered and applied only once it is a
 * complete valid date; partial or invalid text never reaches `onChange`, and the last applied date stays in force
 * meanwhile. The native date input is only the calendar popup.
 */
export function HistoryDateField({ id, label, value, onChange }: HistoryDateFieldProps) {
  const [text, setText] = useState('')
  const [left, setLeft] = useState(false)
  const [pickerFallback, setPickerFallback] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const nativeRef = useRef<HTMLInputElement>(null)
  const caret = useRef<number | null>(null)
  // Whether the native input holds a date, so that erasing every segment (which fires no event: '' stays '') can be
  // told from a native input that was never used.
  const nativeHolds = useRef(false)

  useLayoutEffect(() => {
    if (caret.current !== null && inputRef.current) {
      inputRef.current.setSelectionRange(caret.current, caret.current)
      caret.current = null
    }
  })

  const parsed = parseDisplayDate(text)
  const error = parsed.kind === 'invalid' || (left && parsed.kind === 'partial') ? INVALID_DATE : undefined

  function apply(next: ReturnType<typeof parseDisplayDate>) {
    if (next.kind === 'empty' && value !== null) onChange(null)
    else if (next.kind === 'valid' && next.iso !== value) onChange(next.iso)
  }

  function typed(event: ChangeEvent<HTMLInputElement>) {
    const raw = event.target.value
    const deleting = (event.nativeEvent as InputEvent).inputType?.startsWith('delete') ?? false
    const position = event.target.selectionStart ?? raw.length
    // A dot typed where a dot already stands (the one the field added itself) is the same dot: nothing changes.
    if (
      raw[position - 1] === '.' &&
      raw.slice(0, position - 1) + raw.slice(position) === text &&
      (text[position - 2] === '.' || text[position - 1] === '.')
    ) {
      caret.current = text[position - 1] === '.' ? position : position - 1
      setText(text)
      return
    }
    const digitsBefore = digitsOf(raw.slice(0, position)).length
    const { text: formatted, formatted: reformatted } = formatEntry(raw, !deleting)
    // Keep the caret after the same digit when the text was reformatted; otherwise the browser's caret is right.
    if (reformatted) caret.current = caretAfterDigits(formatted, Math.min(digitsBefore, digitsOf(formatted).length), !deleting)
    setLeft(false)
    setText(formatted)
    apply(parseDisplayDate(formatted))
  }

  // A value from the calendar or the visible fallback is judged by the rules of typed text. An empty value is an
  // intentional clear only when the browser says the input is not mid-edit: a date input with some segments filled
  // also reports '', and that must neither clear the applied filter nor be written back over what is being typed.
  function chosen(event: ChangeEvent<HTMLInputElement>) {
    const native = event.target.value
    if (native === '' && event.target.validity.badInput) return
    nativeHolds.current = native !== ''
    const next = displayFromNative(native)
    setLeft(false)
    setText(next)
    apply(parseDisplayDate(next))
  }

  // Erasing the last segment of a date input leaves its value '' and fires no event, so the clear is noticed when a
  // key is released or the input is left: the input held a date, is now empty, and is not mid-edit.
  function settleNative(event: SyntheticEvent<HTMLInputElement>) {
    const native = event.currentTarget
    if (native.value === '' && !native.validity.badInput && nativeHolds.current) {
      nativeHolds.current = false
      setLeft(false)
      setText('')
      apply({ kind: 'empty' })
    }
  }

  // The native picker opens from the button. A browser without `showPicker`, or one that refuses it, gets the native
  // date input itself, shown and focused, so the button never silently does nothing.
  function openCalendar() {
    const native = nativeRef.current
    if (native && typeof native.showPicker === 'function') {
      try {
        native.showPicker()
        return
      } catch {
        // Fall through to the visible native input.
      }
    }
    setPickerFallback(true)
  }

  useEffect(() => {
    if (pickerFallback) nativeRef.current?.focus()
  }, [pickerFallback])

  // The native input is uncontrolled, so a half-typed date in it is never overwritten; it follows the applied date
  // only when that changes (typed text, a calendar choice, a clear).
  useEffect(() => {
    const native = nativeRef.current
    if (native && native.value !== (value ?? '')) native.value = value ?? ''
    nativeHolds.current = value !== null
  }, [value])

  return (
    <div className="form-field history-date-field">
      <label htmlFor={id}>{label}</label>
      <div className="history-date-control">
        <input
          {...fieldControlProps(id, error)}
          ref={inputRef}
          type="text"
          inputMode="numeric"
          autoComplete="off"
          value={text}
          onChange={typed}
          onBlur={() => setLeft(true)}
        />
        <Button
          type="button"
          variant="secondary"
          className="history-date-button"
          aria-label="Избери дата от календара"
          onClick={openCalendar}
        >
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
            <rect x="3.5" y="5" width="17" height="15.5" rx="2" fill="none" stroke="currentColor" strokeWidth="2" />
            <path d="M3.5 10h17M8 3v4M16 3v4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
        </Button>
        <input
          ref={nativeRef}
          className={pickerFallback ? 'history-date-fallback' : 'history-date-native'}
          type="date"
          tabIndex={pickerFallback ? 0 : -1}
          aria-hidden={pickerFallback ? undefined : true}
          aria-label={pickerFallback ? 'Дата от календара' : undefined}
          min={SCHEDULE_MIN_DATE}
          max={SCHEDULE_MAX_DATE}
          defaultValue={value ?? ''}
          onChange={chosen}
          onKeyUp={settleNative}
          onBlur={settleNative}
        />
      </div>
      <FieldError id={id} error={error} />
    </div>
  )
}
