import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

// Durable frontend rule (UI guide): an IANA timezone identifier is never displayed unless the product owner
// explicitly asks for it. Calculations keep using the timezone. A numeric UTC offset is not shown either (it superseded
// the earlier exception): a repeated daylight-saving time reads "03:30 — първи път" or "03:30 — втори път".
const ROOT = join(__dirname, '..')

function sources(directory: string): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry)
    if (statSync(path).isDirectory()) return sources(path)
    const production = /\.(ts|tsx)$/.test(path) && !/\.test\.|Fixtures|Harness|fixtures|testSupport/.test(path)
    return production ? [path] : []
  })
}

describe('no technical timezone text in the frontend', () => {
  const files = sources(ROOT)

  it('has no visible "Часова зона" label', () => {
    const offenders = files.filter((file) => readFileSync(file, 'utf8').includes('Часова зона'))
    expect(offenders.map((file) => file.replace(ROOT, 'src'))).toEqual([])
  })

  it('never renders a timezone value as text', () => {
    const offenders: string[] = []
    for (const file of files) {
      const source = readFileSync(file, 'utf8')
      if (/>\s*\{\s*[\w.?]*[tT]imezone\s*\}\s*</.test(source) || /Europe\/[A-Z]/.test(source)) {
        offenders.push(file.replace(ROOT, 'src'))
      }
    }
    expect(offenders).toEqual([])
  })

  it('never renders a numeric UTC offset', () => {
    const offenders = files.filter((file) => /UTC\s*[+−-]\s*\d|`UTC|'UTC[+-]/.test(readFileSync(file, 'utf8')))
    expect(offenders.map((file) => file.replace(ROOT, 'src'))).toEqual([])
  })
})
