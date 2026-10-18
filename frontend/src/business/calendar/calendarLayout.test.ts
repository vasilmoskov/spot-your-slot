import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

// jsdom does not evaluate stylesheets, so the layout rules of the calendar are pinned in the source.
const css = readFileSync('src/styles.css', 'utf8')

function rule(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\>]/g, '\\$&')
  const match = css.match(new RegExp(`(?:^|\\n)${escaped} \\{[^}]*\\}`))
  return match?.[0] ?? ''
}

describe('calendar layout rules', () => {
  it('lets every text container shrink and wrap instead of overflowing the page', () => {
    for (const selector of [
      '.calendar-page',
      '.calendar-entry',
      '.calendar-week-day',
      '.calendar-staff-column',
      '.customer-history-entry',
    ]) {
      const block = rule(selector)
      expect(block, selector).toMatch(/min-width: 0|overflow-wrap: anywhere/)
    }
    expect(rule('.calendar-entry')).toContain('overflow-wrap: anywhere')
    expect(rule('.customer-history-entry')).toContain('overflow-wrap: anywhere')
    expect(rule('.calendar-period')).toContain('overflow-wrap: anywhere')
    expect(rule('.appointment-note')).toContain('overflow-wrap: anywhere')
  })

  it('scrolls the wide day grid inside itself, never the page', () => {
    expect(rule('.calendar-grid')).toContain('overflow-x: auto')
    expect(rule('.calendar-grid')).toContain('min-width: 0')
  })

  it('does not rely on colour alone for cancelled entries or non-working time', () => {
    expect(css).toMatch(/\.calendar-entry\.is-cancelled[\s\S]*?border-style: dashed/)
    expect(css).toMatch(/\.calendar-entry\.is-cancelled \.calendar-entry-customer[\s\S]*?text-decoration: line-through/)
    expect(rule('.calendar-timeline')).toContain('repeating-linear-gradient')
  })

  it('dims, but keeps, results while a refresh is pending', () => {
    expect(rule('.calendar-results.is-refreshing')).toContain('opacity: 0.6')
  })
})

describe('manual-create layout rules', () => {
  it('lets every text container shrink and wrap, and keeps the choices tappable', () => {
    for (const selector of ['.manual-create', '.manual-step']) {
      expect(rule(selector), selector).toContain('min-width: 0')
    }
    expect(css).toMatch(/\.manual-choices,\s*\.manual-slots \{[^}]*min-width: 0/)
    expect(rule('.manual-choice-title')).toContain('overflow-wrap: anywhere')
    expect(rule('.manual-review-row dd')).toContain('overflow-wrap: anywhere')
    expect(css).toMatch(/\.manual-choice,\s*\.manual-slot \{[^}]*min-height: 2\.75rem/)
  })

  it('keeps Back first and the forward action last in one row', () => {
    expect(rule('.manual-actions')).toContain('justify-content: space-between')
  })

  it('never relies on colour alone for an unavailable choice', () => {
    expect(rule('.manual-choice--unavailable')).toContain('cursor: not-allowed')
    expect(rule('.manual-choice-reason')).toContain('font-weight: 600')
  })
})
