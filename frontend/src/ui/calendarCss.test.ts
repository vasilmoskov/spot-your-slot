import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync('src/styles.css', 'utf8')

function block(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`(?:^|\\n)${escaped}\\s*\\{([^}]*)\\}`).exec(css)?.[1] ?? ''
}

function rem(declarations: string, property: string): number {
  const match = new RegExp(`${property}:\\s*([0-9.]+)rem`).exec(declarations)
  return match ? Number(match[1]) : Number.NaN
}

/**
 * Static guard of the CSS prerequisites of the calendar day cells. It does NOT prove a rendered width:
 * jsdom does not lay out. The widths are developer-rendered measurements from the M6 review (day cells
 * 30x44 px at 320 px and 38x44 px at 375 px; above the WCAG 2.2 minimum of 24x24 px, below 44x44 px, an
 * accepted calendar exception recorded in task 08b). This test guards the 44 px height and the layout
 * rules those measurements assumed. A real-browser computed-size assertion at the supported narrow
 * widths belongs to the browser phase (M7).
 */
describe('the calendar day-cell CSS prerequisites (not a rendered-size proof)', () => {
  it('keeps the 44 px day-cell height', () => {
    expect(rem(block('.booking-day'), 'height')).toBeGreaterThanOrEqual(2.75)
  })

  it('keeps the full-width fixed seven-column grid with at most 2 px spacing', () => {
    const grid = block('.booking-calendar-grid')
    expect(grid).toMatch(/table-layout:\s*fixed/)
    expect(grid).toMatch(/width:\s*100%/)
    const spacing = /border-spacing:\s*([0-9.]+)px/.exec(grid)
    expect(spacing).not.toBeNull()
    expect(Number(spacing![1])).toBeLessThanOrEqual(2)
  })
})
