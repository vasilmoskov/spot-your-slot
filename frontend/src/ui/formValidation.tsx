import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import { ApiError } from '../identity/api'

export type FieldErrors<K extends string> = Partial<Record<K, string>>

// Attributes that programmatically tie a control to its inline error message.
export function fieldControlProps(id: string, error: string | undefined) {
  return {
    id,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': error ? `${id}-error` : undefined,
  }
}

export function FieldError({ id, error }: { id: string; error: string | undefined }) {
  if (!error) return null
  return (
    <p id={`${id}-error`} className="field-error">
      {error}
    </p>
  )
}

// What a form's submit handler may report back after the request finished:
// backend messages for specific fields, shown inline instead of a form alert.
export type SubmitOutcome<K extends string> = { fieldErrors?: FieldErrors<K> } | void

// Usable backend field errors: a VALIDATION_ERROR whose `fieldErrors` name at
// least one of the form's known fields. Unknown names are dropped, never shown.
export function backendFieldErrors<K extends string>(
  error: unknown,
  known: readonly K[],
  codes: readonly string[] = ['VALIDATION_ERROR'],
): FieldErrors<K> | undefined {
  if (!(error instanceof ApiError) || !codes.includes(error.code)) return undefined
  if (!error.fieldErrors) return undefined
  const result: FieldErrors<K> = {}
  for (const field of known) {
    const message = error.fieldErrors[field]
    if (message) result[field] = message
  }
  return Object.keys(result).length > 0 ? result : undefined
}

type Options<K extends string, V> = {
  // DOM order of the fields; the first invalid one receives focus.
  order: readonly K[]
  values: V
  // Every locally knowable error for the current values.
  validate: (values: V) => FieldErrors<K>
  isEmpty: (field: K, values: V) => boolean
}

// One validation policy for a form:
//  - an untouched empty field shows no error (no premature "required");
//  - leaving a field (blur) touches it; a touched field is validated on every
//    change, and its error disappears the moment the value is valid;
//  - a non-empty value that is already invalid shows its error at once;
//  - submit validates every field, shows all errors, and focuses the first;
//  - a backend field error is shown under its field until that field changes.
export function useFieldValidation<K extends string, V>({
  order,
  values,
  validate,
  isEmpty,
}: Options<K, V>) {
  const [touched, setTouched] = useState<ReadonlySet<K>>(new Set())
  const [submitted, setSubmitted] = useState(false)
  const [serverErrors, setServerErrors] = useState<FieldErrors<K>>({})
  const [focusRequest, setFocusRequest] = useState<{ field: K; count: number } | null>(null)
  const controls = useRef<Partial<Record<K, HTMLElement | null>>>({})

  const local = validate(values)
  const errors: FieldErrors<K> = {}
  for (const field of order) {
    const message = local[field]
    if (message && (submitted || touched.has(field) || !isEmpty(field, values))) {
      errors[field] = message
    } else if (serverErrors[field]) {
      errors[field] = serverErrors[field]
    }
  }

  // A layout effect: focus moves in the same commit that renders the error,
  // so the message is never visible while focus is still elsewhere.
  useLayoutEffect(() => {
    if (focusRequest) controls.current[focusRequest.field]?.focus()
  }, [focusRequest])

  const requestFocus = useCallback((field: K) => {
    setFocusRequest((current) => ({ field, count: (current?.count ?? 0) + 1 }))
  }, [])

  const controlRef = useCallback(
    (field: K) => (element: HTMLElement | null) => {
      controls.current[field] = element
    },
    [],
  )

  const touch = useCallback((field: K) => {
    setTouched((current) => (current.has(field) ? current : new Set(current).add(field)))
  }, [])

  // Editing a field drops any backend message for it; local validation takes
  // over from here.
  const edited = useCallback((field: K) => {
    setServerErrors((current) => {
      if (!current[field]) return current
      const next = { ...current }
      delete next[field]
      return next
    })
  }, [])

  // Returns true when the form may be submitted.
  const validateAll = useCallback((): boolean => {
    setSubmitted(true)
    setServerErrors({})
    const first = order.find((field) => local[field])
    if (!first) return true
    requestFocus(first)
    return false
  }, [local, order, requestFocus])

  const applyServerErrors = useCallback(
    (fieldErrors: FieldErrors<K>) => {
      setServerErrors(fieldErrors)
      const first = order.find((field) => fieldErrors[field])
      if (first) requestFocus(first)
    },
    [order, requestFocus],
  )

  return { errors, controlRef, touch, edited, validateAll, applyServerErrors }
}
