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

  it('lets the day grid scroll inside the page instead of widening it (Phase 11 regression)', () => {
    // Six StaffMember columns widened the whole page because the grid's parent had an automatic minimum width.
    expect(block('.calendar-day')).toMatch(/min-width:\s*0/)
    expect(block('.calendar-grid')).toMatch(/overflow-x:\s*auto/)
    expect(block('.calendar-results')).toMatch(/min-width:\s*0/)
  })

  it('keeps the Appointment status badge at its own width beside a long wrapping date heading (Phase 11 regression)', () => {
    expect(block('.appointment-detail .business-detail-header > p')).toMatch(/flex:\s*0 0 auto/)
  })

  it('keeps the week in exactly one seven-column row at every width and scrolls only its own region (Phase 11 Part 1)', () => {
    const week = block('.calendar-week')
    expect(week).toMatch(/grid-template-columns:\s*repeat\(7,\s*minmax\(\s*[\d.]+rem,\s*1fr\)\)/)
    // The week region owns the horizontal scrolling, never the document.
    expect(week).toMatch(/overflow-x:\s*auto/)
    expect(week).toMatch(/min-width:\s*0/)
    // Visually hidden (absolutely positioned) card texts must be clipped by the region, not widen the document.
    expect(week).toMatch(/position:\s*relative/)
    expect(block('.calendar-page')).toMatch(/min-width:\s*0/)
    expect(block('.calendar-results')).toMatch(/min-width:\s*0/)
    // The columns stay readable: not narrower than 7 rem.
    expect(Number(/minmax\(\s*([\d.]+)rem/.exec(week)?.[1])).toBeGreaterThanOrEqual(7)
    // No breakpoint (or any other rule) may restyle the week into a stacked, wrapping, or auto-fitting layout.
    expect(css.match(/\.calendar-week \{/g)).toHaveLength(1)
    expect(css).not.toMatch(/\.calendar-week[^{]*\{[^}]*(auto-fit|auto-fill|flex-wrap|grid-template-columns:\s*1fr|grid-auto-flow:\s*row)/)
    for (const media of css.matchAll(/@media[^{]*\{((?:[^{}]*\{[^}]*\})*)\}/g)) {
      expect(media[1], 'a media query must not restyle the week').not.toMatch(/\.calendar-week(?!-day \.calendar-entry)[\s{,]/)
    }
    expect(css).not.toMatch(/\.calendar-week-day \{[^}]*(grid-column|grid-row|width:\s*100%|flex-basis)/)
    expect(css).not.toMatch(/(^|\n)(html|body)[^{]*\{[^}]*overflow-x:\s*(auto|scroll)/)
  })

  it('stacks card content from the top and never spreads it with space-between', () => {
    expect(block('.calendar-entry')).toMatch(/align-content:\s*start/)
    expect(block('.calendar-entry')).not.toMatch(/space-between/)
    expect(block('.calendar-block')).not.toMatch(/space-between/)
  })

  it('draws a cancelled card in the danger palette and keeps the dashed border and strike-through', () => {
    const cancelled = /\.calendar-entry\.is-cancelled,\s*\.customer-history-entry\.is-cancelled \{([^}]*)\}/.exec(css)?.[1] ?? ''
    expect(cancelled).toMatch(/border-style:\s*dashed/)
    expect(cancelled).toMatch(/background:\s*var\(--color-danger-subtle\)/)
    expect(cancelled).toMatch(/color:\s*var\(--color-danger-text\)/)
    expect(css).toMatch(/\.calendar-entry\.is-cancelled \.calendar-entry-customer,[^}]*text-decoration:\s*line-through/)
  })

  it('lays the toolbar out as wrapping rows', () => {
    expect(block('.calendar-toolbar-row')).toMatch(/flex-wrap:\s*wrap/)
  })

  it('lets a dated agenda title and a stacked card wrap, so a long label never widens the page (Phase 11 Part 1 regression)', () => {
    // The month list showed "Понеделник, 19 октомври 2026 г., 10:45–11:30" on one non-wrapping line at 375 px.
    expect(css).not.toMatch(/\n\.calendar-entry-time a \{[^}]*white-space:\s*nowrap/)
    // A week card's time may break at a space or a dash (a nowrap time overflowed Sunday's card, fifth review round),
    // but never inside the digits.
    expect(css).toMatch(/\n\.calendar-week-day \.calendar-entry-time a \{\s*white-space:\s*normal/)
    expect(css).toMatch(/\n\.calendar-entry-time a \{[^}]*overflow-wrap:\s*normal/)
  })
})
