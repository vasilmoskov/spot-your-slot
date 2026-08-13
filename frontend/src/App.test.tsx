import '@testing-library/jest-dom/vitest'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { App } from './App'

describe('App', () => {
  it('renders the Bulgarian bootstrap shell', () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: 'Намери удобен час' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('начален етап')
  })
})
