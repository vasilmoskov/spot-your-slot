import { expect, test } from '@playwright/test'
import { expectNoHorizontalOverflow, overflowReport } from './support/browser'

// The overflow check of the mobile journeys, proven on a synthetic page (no application, no data, no network): it
// passes when only an intentionally scrolling region scrolls, and its failure message names the checkpoint, the route
// without its query, the widths and the structure of the offenders on BOTH edges, and nothing else.
const page = (body: string) => `<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{margin:0}.row{overflow-x:auto;width:100%}.row .wide{width:900px;height:20px}.bad-right{position:absolute;top:60px;left:300px;width:400px;height:20px}.bad-left{position:absolute;top:90px;left:-120px;width:200px;height:20px}</style>${body}`

test.use({ viewport: { width: 390, height: 700 } })

test.describe('the overflow check reports what overflows and what it must ignore', () => {
  test('lets an approved internal scroll region scroll', async ({ page: browserPage }) => {
    await browserPage.setContent(page('<div class="row calendar-week"><div class="wide">scroll</div></div>'))
    await expectNoHorizontalOverflow(browserPage, 'scrolling week row')
    const report = await overflowReport(browserPage, 'scrolling week row')
    expect(report.fits).toBe(true)
    expect(report.offenders).toEqual([])
  })

  test('names the checkpoint, route, widths and both-edge offenders, without any text or query', async ({ page: browserPage }) => {
    await browserPage.setContent(page(
      '<div class="bad-right">Мария Тестова maria@example.test</div><div class="bad-left">секрет-123</div>'
      + '<div class="row"><div class="wide">scroll</div></div>',
    ))
    await browserPage.evaluate(() => history.replaceState(null, '', '#/business/customers/9cf160aa?search=Мария&token=abc'))
    const report = await overflowReport(browserPage, 'customers list')
    expect(report).toMatchObject({ checkpoint: 'customers list', route: '#/business/customers/9cf160aa', viewport: 390, fits: false })
    expect(report.scroll).toBeGreaterThan(390)
    expect(report.offenders.map((o) => [o.element, o.side])).toEqual([['div.bad-right', 'right'], ['div.bad-left', 'left']])
    expect(report.offenders[0]).toMatchObject({ left: 300, right: 700 })
    expect(report.offenders[1]).toMatchObject({ left: -120, right: 80 })
    const message = await expectNoHorizontalOverflow(browserPage, 'customers list').then(() => '', (error: Error) => error.message)
    expect(message).toContain('horizontal overflow')
    expect(message).toContain('"checkpoint":"customers list"')
    // Nothing that was on the page leaks: no text, no query, no values.
    for (const secret of ['Мария', 'maria@example.test', 'секрет', 'token', 'abc', 'search=']) expect(message).not.toContain(secret)
  })
})
