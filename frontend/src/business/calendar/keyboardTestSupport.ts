import { act } from '@testing-library/react'

// Real keyboard-like edits of a text field for unit tests: each one sets the text the browser would hold after the
// edit, its caret, and the input type, then fires the browser's own `input` event.
const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!

/** One real edit of a text field: the browser's text after it, the caret after it, and the kind of edit. */
export function edit(input: HTMLInputElement, text: string, caret: number, inputType: string) {
  act(() => {
    setValue.call(input, text)
    input.setSelectionRange(caret, caret)
    input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType }))
  })
}

/** Types characters one by one at the current selection, like the keyboard does. */
export function type(input: HTMLInputElement, characters: string) {
  for (const character of characters) {
    const start = input.selectionStart ?? input.value.length
    const end = input.selectionEnd ?? start
    edit(input, input.value.slice(0, start) + character + input.value.slice(end), start + 1, 'insertText')
  }
}

export function backspace(input: HTMLInputElement) {
  const start = input.selectionStart ?? input.value.length
  const end = input.selectionEnd ?? start
  if (start !== end) return edit(input, input.value.slice(0, start) + input.value.slice(end), start, 'deleteContentBackward')
  if (start === 0) return
  edit(input, input.value.slice(0, start - 1) + input.value.slice(start), start - 1, 'deleteContentBackward')
}

export function paste(input: HTMLInputElement, text: string) {
  const start = input.selectionStart ?? input.value.length
  const end = input.selectionEnd ?? start
  edit(input, input.value.slice(0, start) + text + input.value.slice(end), start + text.length, 'insertFromPaste')
}

export function select(input: HTMLInputElement, start: number, end: number) {
  act(() => input.setSelectionRange(start, end))
}
