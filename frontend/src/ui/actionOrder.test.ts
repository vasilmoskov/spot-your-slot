import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

// The project-wide action-order standard (UI guide section 6, as decided in the Phase 11 Part 1 review, which
// supersedes the earlier "Cancel before Save" rule): inside one decision group the affirmative or primary action
// is on the left and «Отказ» is the rightmost action in red; journey navigation still puts Назад before Напред.
// The order is the DOM order (and so the keyboard order), never a CSS reversal. This test reads every source.

const SOURCE_ROOT = join(__dirname, '..')

function sourceFiles(directory: string): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry)
    if (statSync(path).isDirectory()) return sourceFiles(path)
    return path.endsWith('.tsx') && !path.includes('.test.') ? [path] : []
  })
}

// The top-level pieces of an action group: a <Button …>…</Button> or a {…} expression.
function groupItems(inner: string): string[] | null {
  const items: string[] = []
  let index = 0
  while (index < inner.length) {
    if (/\s/.test(inner[index]!)) {
      index += 1
      continue
    }
    let end = index
    if (inner.startsWith('<Button', index)) {
      let depth = 0
      while (end < inner.length) {
        if (inner.startsWith('<Button', end)) depth += 1
        if (inner.startsWith('</Button>', end)) {
          depth -= 1
          if (depth === 0) {
            end += '</Button>'.length
            break
          }
        }
        end += 1
      }
    } else if (inner[index] === '{') {
      let depth = 0
      while (end < inner.length) {
        if (inner[end] === '{') depth += 1
        else if (inner[end] === '}') {
          depth -= 1
          if (depth === 0) {
            end += 1
            break
          }
        }
        end += 1
      }
    } else {
      return null
    }
    items.push(inner.slice(index, end))
    index = end
  }
  return items
}

function groupsOf(source: string): string[][] {
  const groups: string[][] = []
  for (const match of source.matchAll(/<div className="action-group[^"]*">\n/g)) {
    const start = match.index! + match[0].length
    let depth = 1
    let cursor = start
    let end = -1
    while (depth > 0) {
      const open = source.indexOf('<div', cursor)
      const close = source.indexOf('</div>', cursor)
      if (close === -1) break
      if (open !== -1 && open < close) {
        depth += 1
        cursor = open + 4
      } else {
        depth -= 1
        if (depth === 0) end = close
        cursor = close + 6
      }
    }
    const items = end === -1 ? null : groupItems(source.slice(start, end))
    if (items) groups.push(items)
  }
  return groups
}

const CANCELLING = [
  />\s*Отказ\s*</,
  /cancelDeactivationButton/,
  /setDeactivateConfirmation\(false\)/,
  /setLifecycleConfirmation\(false\)/,
  /setResendEmail\(null\)/,
]

const isCancelling = (item: string) => CANCELLING.some((pattern) => pattern.test(item))
const isOtkaz = (item: string) => />\s*Отказ\s*</.test(item)

describe('the action-order standard', () => {
  const files = sourceFiles(SOURCE_ROOT)

  it('finds the action groups it is supposed to check', () => {
    const cancelGroups = files
      .flatMap((file) => groupsOf(readFileSync(file, 'utf8')))
      .filter((items) => items.some(isCancelling))
    expect(cancelGroups.length).toBeGreaterThanOrEqual(15)
  })

  // A decision group is a group of actions that contains an «Отказ» control.
  const otkazGroups = () =>
    files.flatMap((file) =>
      groupsOf(readFileSync(file, 'utf8'))
        .filter((items) => items.some(isOtkaz))
        .map((items) => ({ file: file.replace(SOURCE_ROOT, 'src'), items })),
    )

  it('finds the decision groups it is supposed to check', () => {
    expect(otkazGroups().length).toBeGreaterThanOrEqual(18)
  })

  it('makes «Отказ» the rightmost action of its decision group, after the affirmative action', () => {
    const violations = otkazGroups()
      .filter(({ items }) => items.findIndex(isOtkaz) !== items.length - 1)
      .map(({ file }) => file)
    expect(violations).toEqual([])
  })

  it('draws every «Отказ» button in the approved danger style', () => {
    const offenders: string[] = []
    for (const { file, items } of otkazGroups()) {
      for (const item of items.filter(isOtkaz)) {
        if (!/variant="destructive"/.test(item)) offenders.push(file)
      }
    }
    expect(offenders).toEqual([])
  })

  it('keeps an outlined «Отказ» beside a solid destructive affirmative action so the two stay distinguishable', () => {
    const offenders: string[] = []
    for (const { file, items } of otkazGroups()) {
      const hasDestructiveAction = items.some((item) => !isOtkaz(item) && /variant="destructive"|danger/.test(item))
      const cancel = items.find(isOtkaz)!
      // A conditional destructive action (a ternary on `danger`) conditions the outline in the same way.
      if (hasDestructiveAction !== /button--outline/.test(cancel)) offenders.push(file)
    }
    expect(offenders).toEqual([])
  })

  it('never uses a positive tabindex, so the keyboard order is the DOM order', () => {
    const offenders = files.filter((file) => /tabIndex=\{[1-9]/.test(readFileSync(file, 'utf8')))
    expect(offenders.map((file) => file.replace(SOURCE_ROOT, 'src'))).toEqual([])
  })

  it('puts Back before Next and Confirm in the booking journey', () => {
    const steps = ['steps.tsx', 'DetailsStep.tsx', 'ReviewStep.tsx'].map((name) =>
      readFileSync(join(SOURCE_ROOT, 'public', 'booking', name), 'utf8'),
    )
    for (const source of steps) {
      for (const match of source.matchAll(/<StepActions>([\s\S]*?)<\/StepActions>/g)) {
        const block = match[1]!
        const back = block.search(/Назад|\{back\}/)
        const forward = block.search(/type="submit"|Потвърди|\{nextLabel\}|onClick=\{onSubmit\}/)
        if (back !== -1 && forward !== -1) expect(back).toBeLessThan(forward)
      }
    }
  })

  it('never reverses the order with CSS', () => {
    const css = readFileSync(join(SOURCE_ROOT, 'styles.css'), 'utf8')
    expect(css).not.toMatch(/flex-direction:\s*(row|column)-reverse/)
    expect(css).not.toMatch(/\border:\s*-?\d/)
  })

  it('keeps the two actions of one decision together, never at opposite edges (global action grouping)', () => {
    const css = readFileSync(join(SOURCE_ROOT, 'styles.css'), 'utf8')
    const offenders: string[] = []
    for (const match of css.matchAll(/(^|\n)([^{}@]*(?:actions|action-group)[^{}]*)\{([^}]*)\}/g)) {
      const selector = match[2]!.trim()
      // A page toolbar that separates unrelated actions is not a decision group (recorded exception).
      if (selector === '.schedule-page-actions') continue
      if (/justify-content:\s*space-between/.test(match[3]!)) offenders.push(selector)
    }
    expect(offenders).toEqual([])
  })
})
