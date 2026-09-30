import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

// jsdom does not evaluate stylesheets, so the durable layout rules from
// docs/ui-design-guidelines.md are pinned against the shared CSS source.
const css = readFileSync('src/styles.css', 'utf8')

function rule(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\>]/g, '\\$&')
  const match = css.match(new RegExp(`(?:^|\\n)${escaped} \\{[^}]*\\}`))
  return match?.[0] ?? ''
}

describe('shared dialog sizing', () => {
  it('sizes every modal panel to its content within readable bounds', () => {
    for (const selector of ['.unsaved-changes-overlay .confirmation-panel', '.schedule-dialog-panel']) {
      const block = rule(selector)
      expect(block, selector).toContain('width: fit-content')
      expect(block, selector).toMatch(/min-width: min\(100%, \d+rem\)/)
      expect(block, selector).toMatch(/max-width: min\(100%, \d+rem\)/)
      expect(block, selector).not.toMatch(/\swidth: min\(100%/)
    }
    // The copy dialog only raises the ceiling; it is still content-sized.
    expect(rule('.schedule-copy-dialog-panel')).toContain('max-width: min(100%, 34rem)')
    expect(rule('.schedule-copy-dialog-panel')).not.toMatch(/\swidth:/)
  })

  it('sizes inline confirmation panels to their content too', () => {
    const block = rule('.confirmation-panel')
    expect(block).toContain('width: fit-content')
    expect(block).toContain('max-width: min(100%, 32rem)')
  })

  it('keeps the period-dialog fields wide enough to share a row', () => {
    expect(rule('.schedule-dialog-panel')).toContain('min-width: min(100%, 24rem)')
  })
})

describe('content-sized compact elements', () => {
  it('sizes period chips to their content and never stretches them in a column', () => {
    const chip = rule('.schedule-period-chip')
    expect(chip).toContain('width: fit-content')
    expect(chip).toContain('align-self: flex-start')
    expect(chip).toContain('flex-wrap: wrap')
    expect(rule('.exception-periods')).toContain('align-items: flex-start')
    expect(css).toMatch(/@media \(min-width: 48rem\) \{\s*\.schedule-day-body \{[^}]*align-items: flex-start/)
    expect(rule('.schedule-add-period')).toContain('align-self: flex-start')
  })

  it('keeps the chip controls at a usable target size and unshrinkable', () => {
    const remove = rule('.schedule-period-chip-remove')
    expect(remove).toContain('flex: none')
    expect(remove).toContain('width: 1.5rem')
    expect(remove).toContain('height: 1.5rem')
    expect(remove).toContain('min-height: 1.5rem')
    expect(rule('.schedule-period-chip-label')).toContain('min-height: 1.5rem')
  })
})

describe('consistent vertical spacing', () => {
  it('stacks the page header, banner, feedback and content with one layout gap', () => {
    const main = rule('.platform-main')
    expect(main).toContain('display: grid')
    expect(main).toContain('gap: var(--space-4)')
    expect(rule('.platform-page-header')).toContain('margin-bottom: 0')
    expect(rule('.schedule-tabs')).toContain('margin-bottom: 0')
    expect(rule('.shell-banner')).toContain('margin: 0')
  })
})

describe('schedule-change table', () => {
  it('shows the responsive sort control wherever the table becomes cards', () => {
    const cards = css.slice(css.indexOf('@media (min-width: 48rem) and (max-width: 63.999rem)'))
    expect(cards).toContain('.exception-list-toolbar .responsive-sort-select')
    expect(cards).toContain('.exception-table thead')
    expect(rule('.status-badge-info')).toContain('background: var(--color-action-primary-subtle)')
  })
})
