/// <reference types="node" />
import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'

import { Button } from './Button'

const css = readFileSync('src/styles.css', 'utf8')

const style = document.createElement('style')
style.textContent = css
document.head.append(style)
const rules = Array.from(style.sheet!.cssRules).filter(
  (rule): rule is CSSStyleRule => 'selectorText' in rule,
)
style.remove()

function rule(selector: string) {
  const matches = rules.filter((entry) => entry.selectorText.split(',').map((part) => part.trim()).includes(selector))
  expect(matches.length).toBeGreaterThan(0)
  return matches[matches.length - 1]!.style
}

function resolve(value: string, variant: CSSStyleDeclaration): string {
  const variable = /^var\((--[\w-]+)\)$/.exec(value)
  return variable
    ? resolve(variant.getPropertyValue(variable[1]!) || rule(':root').getPropertyValue(variable[1]!), variant)
    : value
}

function luminance(hex: string) {
  const channels = hex.slice(1).match(/../g)!.map((channel) => {
    const value = parseInt(channel, 16) / 255
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
  })
  return channels[0]! * 0.2126 + channels[1]! * 0.7152 + channels[2]! * 0.0722
}

describe('semantic buttons and feedback layout', () => {
  it('keeps primary, secondary, destructive, disabled, and full-width navigation distinct', () => {
    const click = vi.fn()
    render(<>
      <Button>Запази</Button>
      <Button variant="secondary">Редактирай</Button>
      <Button variant="destructive">Спри временно</Button>
      <Button variant="secondary" disabled onClick={click}>Отказ</Button>
      <Button variant="navigation" aria-pressed>Лични данни</Button>
    </>)
    for (const [label, variant] of [['Запази', 'primary'], ['Редактирай', 'secondary'], ['Спри временно', 'destructive'], ['Отказ', 'secondary']] as const) {
      const button = screen.getByRole('button', { name: label })
      expect(button).toHaveClass('button', `button--${variant}`)
      expect(button).not.toHaveClass('button--navigation')
    }
    fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))
    expect(click).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Лични данни' })).toHaveAttribute('aria-pressed', 'true')
    expect(rule('.button').getPropertyValue('width')).toBe('fit-content')
    expect(rule('.button').getPropertyValue('align-self')).toBe('flex-start')
    expect(rule('.button').getPropertyValue('justify-self')).toBe('start')
    expect(rule('.button').getPropertyValue('flex').split(' ')[0]).toBe('0')
    expect(rule('.button--navigation').getPropertyValue('width')).toBe('100%')
  })

  it.each(['primary', 'secondary'])('uses the same filled-to-outline colors for %s actions', (name) => {
    const variant = rule(`.button--${name}`)
    const normal = rule('.button')
    expect(resolve(normal.getPropertyValue('color'), variant)).toBe('#ffffff')
    expect(resolve(normal.getPropertyValue('background'), variant)).toBe('#5b5ce2')
    expect(resolve(normal.getPropertyValue('border-color'), variant)).toBe('#5b5ce2')
    for (const selector of ['.button:hover:not(:disabled)', '.button:active:not(:disabled)']) {
      const state = rule(selector)
      expect(resolve(state.getPropertyValue('color'), variant)).toBe('#5b5ce2')
      expect(resolve(state.getPropertyValue('background'), variant)).toBe('#ffffff')
      expect(resolve(state.getPropertyValue('border-color'), variant)).toBe('#5b5ce2')
    }
    expect(rule('.button--primary')).toBe(rule('.button--secondary'))
    const focus = rule('.button:focus-visible')
    expect(focus.getPropertyValue('outline')).toContain('var(--color-focus)')
    expect(focus.getPropertyValue('color')).toBe('')
    expect(focus.getPropertyValue('background')).toBe('')
  })

  it('keeps destructive, disabled, and selected-navigation colors separate', () => {
    const destructive = rule('.button--destructive')
    expect(resolve(rule('.button').getPropertyValue('background'), destructive)).toBe('#d92d20')
    const hover = rule('.button:hover:not(:disabled)')
    expect(resolve(hover.getPropertyValue('background'), destructive)).toBe('#ffffff')
    expect(resolve(hover.getPropertyValue('color'), destructive)).toBe('#d92d20')
    const disabled = rule('.button:disabled')
    expect(disabled.getPropertyValue('color')).toBe('var(--color-disabled-text)')
    expect(disabled.getPropertyValue('background')).toBe('var(--color-disabled-background)')
    expect(disabled.getPropertyValue('cursor')).toBe('not-allowed')
    expect(rules.some((entry) => /\.button:(hover|active)(?!:not\(:disabled\))/.test(entry.selectorText))).toBe(false)
    const selected = rule('.button--navigation[aria-pressed="true"]')
    expect(resolve(selected.getPropertyValue('--button-text'), selected)).toBe('#4747c7')
    expect(resolve(selected.getPropertyValue('--button-background'), selected)).toBe('#eef1ff')
  })

  it('keeps tertiary navigation text-only with hover and keyboard focus', () => {
    const link = rule('.text-link')
    expect(link.getPropertyValue('width')).toBe('fit-content')
    expect(link.getPropertyValue('color')).toBe('var(--color-action-primary)')
    expect(link.getPropertyValue('border')).toBe('')
    expect(link.getPropertyValue('background')).toBe('')
    expect(rule('.text-link:hover').getPropertyValue('text-decoration')).toBe('underline')
    expect(rule('.text-link:hover').getPropertyValue('color')).toBe('var(--color-action-primary-hover)')
    expect(rule('a:focus-visible').getPropertyValue('outline')).toContain('var(--color-focus)')
  })

  it('restricts action colors to shared semantic selectors and excludes non-action elements', () => {
    function inspect(entries: CSSRuleList) {
      for (const entry of Array.from(entries)) {
        if ('cssRules' in entry) inspect((entry as CSSGroupingRule).cssRules)
        if (!('selectorText' in entry)) continue
        const styled = entry as CSSStyleRule
        const hasColor = ['color', 'background', 'background-color', 'border-color'].some(
          (property) => styled.style.getPropertyValue(property),
        )
        for (const selector of styled.selectorText.split(',').map((part) => part.trim())) {
          if (hasColor && /button|action|navigation-toggle/.test(selector)) {
            expect(selector).toMatch(/^\.button(?=$|[:.-])/)
          }
        }
      }
    }
    const sheet = document.createElement('style')
    sheet.textContent = css
    document.head.append(sheet)
    try {
      inspect(sheet.sheet!.cssRules)
    } finally {
      sheet.remove()
    }
    render(<>
      <span className="status-badge status-badge-success">Активен</span>
      <a className="navigation-link" href="#profile">Профил</a>
      <details><summary>Данни за бизнеса</summary></details>
    </>)
    for (const label of ['Активен', 'Профил', 'Данни за бизнеса']) {
      const element = screen.getByText(label)
      expect(element).not.toHaveClass('button')
      expect(element.matches('.button--primary, .button--secondary')).toBe(false)
    }
  })

  it.each(['primary', 'secondary', 'destructive', 'navigation'])('maintains readable %s labels in each interactive state', (name) => {
    const variant = rule(`.button--${name}`)
    for (const selector of ['.button', '.button:hover:not(:disabled)', '.button:active:not(:disabled)', '.button:disabled']) {
      const state = rule(selector)
      const foreground = resolve(state.getPropertyValue('color'), variant)
      const background = resolve(state.getPropertyValue('background'), variant)
      const values = [luminance(foreground), luminance(background === 'transparent' ? '#ffffff' : background)].sort((a, b) => b - a)
      expect((values[0]! + 0.05) / (values[1]! + 0.05)).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('enforces intrinsic notice width and separate feedback/action rows', () => {
    const notice = rule('.status-message')
    expect(notice.getPropertyValue('width')).toBe('fit-content')
    expect(notice.getPropertyValue('max-width')).toBe('100%')
    expect(notice.getPropertyValue('align-self')).toBe('flex-start')
    expect(notice.getPropertyValue('justify-self')).toBe('start')
    expect(notice.getPropertyValue('text-align')).toBe('left')
    expect(notice.getPropertyValue('overflow-wrap')).toBe('anywhere')
    expect(notice.getPropertyValue('padding')).toBe('var(--space-2)')
    expect(rule('.feedback-action-layout').getPropertyValue('display')).toBe('grid')
    expect(rule('.feedback-action-layout').getPropertyValue('gap')).toBe('var(--space-3)')
    expect(rule('.feedback-action-controls').getPropertyValue('gap')).toBe('var(--space-3)')
  })
})
