import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

// The project-wide action-order standard (UI guide section 6): inside one action group the safe or
// cancelling action comes before the confirming one (Cancel before Save or Create, the safe action
// before a confirming or destructive one), and journey navigation puts Back before Next or Confirm.
// The order is the DOM order, never a CSS reversal. This test reads every component source.

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

describe('the action-order standard', () => {
  const files = sourceFiles(SOURCE_ROOT)

  it('finds the action groups it is supposed to check', () => {
    const cancelGroups = files
      .flatMap((file) => groupsOf(readFileSync(file, 'utf8')))
      .filter((items) => items.some(isCancelling))
    expect(cancelGroups.length).toBeGreaterThanOrEqual(15)
  })

  it('places the cancelling or safe action before every confirming action in a group', () => {
    const violations: string[] = []
    for (const file of files) {
      for (const items of groupsOf(readFileSync(file, 'utf8'))) {
        const cancelAt = items.findIndex(isCancelling)
        if (cancelAt > 0) violations.push(file.replace(SOURCE_ROOT, 'src'))
      }
    }
    expect(violations).toEqual([])
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
})
