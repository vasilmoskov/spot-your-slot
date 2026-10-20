import { expect, type Locator } from '@playwright/test'

/**
 * Asserts the shared pagination region after the range sentence was removed: `from` is the one-based number of the
 * first record on the page and `total` the number of results. Up to ten results need no region at all; otherwise the
 * region marks the page that starts at `from` as current and reaches exactly the last page that `total` implies, so a
 * region that still belongs to a different result set cannot pass. A narrow screen shows `current / pages` instead of
 * the numbered pages. The size selector changes before the new page arrives, so the whole check is retried.
 */
export async function expectShown(region: Locator, from: number, total: number): Promise<void> {
  if (total <= 10) {
    await expect(region).toHaveCount(0)
    return
  }
  await expect(async () => {
    const size = Number(await region.getByRole('combobox').inputValue())
    const current = Math.floor((from - 1) / size) + 1
    const pages = Math.ceil(total / size)
    const compact = region.locator('.pagination-compact')
    if (await compact.isVisible()) {
      await expect(compact).toHaveText(`${current} / ${pages}`, { timeout: 500 })
      return
    }
    await expect(region.getByLabel(`Страница ${current}, текуща`)).toBeVisible({ timeout: 500 })
    await expect(region.getByLabel(`Страница ${pages}`).first()).toBeVisible({ timeout: 500 })
    await expect(region.getByLabel(`Страница ${pages + 1}`)).toHaveCount(0, { timeout: 500 })
  }).toPass({ timeout: 10_000 })
}
