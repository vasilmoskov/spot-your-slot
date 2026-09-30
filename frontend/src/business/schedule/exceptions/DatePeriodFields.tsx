import type { ReactNode, Ref } from 'react'
import { FieldError, fieldControlProps } from '../../../ui/formValidation'
import { SCHEDULE_MAX_DATE, SCHEDULE_MIN_DATE } from '../../../navigation'

export type DateControl = {
  id: string
  label: string
  value: string
  error: string | undefined
  controlRef?: Ref<HTMLInputElement>
  onChange: (value: string) => void
  onBlur?: () => void
}

type DatePeriodFieldsProps = {
  // With `last` this is an inclusive range ("От"/"До"); without it, a single
  // date labelled by `first.label`.
  first: DateControl
  last?: DateControl
  // Trailing content that belongs to the same row (for example the apply button).
  children?: ReactNode
}

// The one date presentation shared by the list window filter and the create and
// edit forms: "От" and "До" for a range, "Дата" for one day, each label above its
// input. Every control keeps a real visible label; errors sit below the row. The
// range is a group named "Период" for assistive technology only (aria-label, no
// visible text).
export function DatePeriodFields({ first, last, children }: DatePeriodFieldsProps) {
  const controls = last ? [first, last] : [first]
  return (
    <div className="date-period">
      <div
        className="date-period-row"
        {...(last ? { role: 'group', 'aria-label': 'Период' } : {})}
      >
        {controls.map((control) => (
          <div key={control.id} className="date-period-field">
            <label htmlFor={control.id}>{control.label}</label>
            <input
              {...fieldControlProps(control.id, control.error)}
              {...(control.controlRef ? { ref: control.controlRef } : {})}
              type="date"
              min={SCHEDULE_MIN_DATE}
              max={SCHEDULE_MAX_DATE}
              value={control.value}
              {...(control.onBlur ? { onBlur: control.onBlur } : {})}
              onChange={(event) => control.onChange(event.target.value)}
            />
          </div>
        ))}
        {children}
      </div>
      {controls.map((control) => (
        <FieldError key={control.id} id={control.id} error={control.error} />
      ))}
    </div>
  )
}
