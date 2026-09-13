import type { ComponentProps } from 'react'

type ButtonProps = ComponentProps<'button'> & {
  variant?: 'primary' | 'secondary' | 'destructive' | 'navigation'
}

export function Button({ variant = 'primary', className = '', ...props }: ButtonProps) {
  return <button {...props} className={`button button--${variant} ${className}`.trim()} />
}
