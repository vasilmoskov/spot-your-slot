import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

// jsdom does not evaluate stylesheets, so the contrast of the shared status text is checked from the
// shared CSS source: the declared text color against the declared (or inherited) background.
const css = readFileSync('src/styles.css', 'utf8')

function token(name: string): string {
  const match = css.match(new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`))
  if (!match) throw new Error(`token ${name} not found`)
  return match[1]!
}

function channel(value: number): number {
  const unit = value / 255
  return unit <= 0.03928 ? unit / 12.92 : ((unit + 0.055) / 1.055) ** 2.4
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((index) => channel(parseInt(hex.slice(index, index + 2), 16)))
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!
}

export function contrast(foreground: string, background: string): number {
  const [light, dark] = [luminance(foreground), luminance(background)].sort((a, b) => b - a)
  return (light! + 0.05) / (dark! + 0.05)
}

function declaration(selector: string, property: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\>]/g, '\\$&')
  const block = css.match(new RegExp(`(?:^|\\n)${escaped} \\{([^}]*)\\}`))?.[1] ?? ''
  const value = block.match(new RegExp(`(?:^|;|\\s)${property}:\\s*var\\(--([a-z-]+)\\)`))?.[1]
  if (!value) throw new Error(`${selector} has no ${property} token`)
  return token(value)
}

describe('shared status text contrast (WCAG AA, 4.5:1 for normal text)', () => {
  const pairs: [string, string][] = [
    ['.status-error', 'danger panel'],
    ['.status-success', 'success panel'],
    ['.status-badge-success', 'success badge'],
  ]

  for (const [selector, label] of pairs) {
    it(`the ${label} text is readable on its own background`, () => {
      const ratio = contrast(declaration(selector, 'color'), declaration(selector, 'background'))
      expect(ratio, `${selector} measures ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5)
    })
  }

  it('keeps the base danger and success colors for borders and buttons', () => {
    expect(token('color-danger')).toBe('#d92d20')
    expect(token('color-success')).toBe('#168f6b')
    expect(css).toMatch(/\.status-error \{\s*border: 1px solid var\(--color-danger\);/)
    expect(css).toMatch(/\.status-success \{\s*border: 1px solid var\(--color-success\);/)
  })

  it('has no booking-specific override of the status text', () => {
    expect(css).not.toMatch(/\.booking-journey \.status-(error|success)/)
  })

  it('shows why the correction was needed: the base colors fail on the panels', () => {
    expect(contrast(token('color-danger'), token('color-danger-subtle'))).toBeLessThan(4.5)
    expect(contrast(token('color-success'), token('color-success-subtle'))).toBeLessThan(4.5)
  })
})

describe('the multi-Service selection text contrast (WCAG AA)', () => {
  it('keeps the reason of a disabled Service readable on the unavailable option background', () => {
    // `.booking-choice-reason` uses the secondary text color on `.booking-choice--unavailable`.
    expect(declaration('.booking-choice-reason', 'color')).toBe(token('color-text-secondary'))
    expect(declaration('.booking-choice--unavailable', 'background')).toBe(token('color-page-background'))
    const ratio = contrast(token('color-text-secondary'), token('color-page-background'))
    expect(ratio, `reason text measures ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5)
  })

  it('keeps the selection summary readable on its panel', () => {
    expect(declaration('.booking-selection-summary,\n.booking-selection-empty', 'background')).toBe(
      token('color-action-primary-subtle'),
    )
    const ratio = contrast(token('color-text-primary'), token('color-action-primary-subtle'))
    expect(ratio, `summary text measures ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5)
  })

  it('does not rely on colour alone for an unavailable option: the reason is text', () => {
    expect(css).toMatch(/\.booking-choice--unavailable \{[^}]*cursor: not-allowed;/)
    expect(css).toMatch(/\.booking-choice-reason \{[^}]*font-weight: 600;/)
  })
})
