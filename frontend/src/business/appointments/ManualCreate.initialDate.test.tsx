import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import { chooseCustomer, chooseServices, chooseStaff, installBackend, renderForm } from './testHarness'
import { FakeBackend } from './testFixtures'

vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
let backend: FakeBackend
const ORIGINAL_TZ = process.env['TZ']

beforeEach(() => {
  backend = installBackend(mockedRequest)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  if (ORIGINAL_TZ === undefined) delete process.env['TZ']
  else process.env['TZ'] = ORIGINAL_TZ
})

// The harness clock is 2026-10-02 08:00 UTC: the Business (Sofia) day is 2026-10-02.
async function toDateStep(options: Parameters<typeof renderForm>[0] = {}) {
  renderForm(options)
  await chooseCustomer()
  await chooseServices('Дамско подстригване')
  await chooseStaff()
  return screen.findByLabelText('Дата')
}

describe('the date of a new Appointment draft', () => {
  it('uses the date selected in the Calendar when it is today or later', async () => {
    const date = await toDateStep({ initialDate: '2026-11-10' })
    await waitFor(() => expect(date).toHaveValue('2026-11-10'))
  })

  it('accepts today from the Calendar', async () => {
    const date = await toDateStep({ initialDate: '2026-10-02' })
    await waitFor(() => expect(date).toHaveValue('2026-10-02'))
  })

  it('falls back to today in the Business timezone when the Calendar date is in the past', async () => {
    const date = await toDateStep({ initialDate: '2026-09-15' })
    await waitFor(() => expect(date).toHaveValue('2026-10-02'))
  })

  it('uses today when the form is opened without a Calendar date', async () => {
    const date = await toDateStep({ initialDate: null })
    await waitFor(() => expect(date).toHaveValue('2026-10-02'))
  })

  it('counts the Business day, not the browser day, whatever the browser timezone', async () => {
    // 23:30 UTC on 2 October is already 3 October in Sofia; a browser in Los Angeles is still on 2 October.
    for (const zone of ['America/Los_Angeles', 'Pacific/Kiritimati', 'UTC']) {
      process.env['TZ'] = zone
      cleanup()
      backend = installBackend(mockedRequest)
      const date = await toDateStep({ now: new Date('2026-10-02T23:30:00Z'), initialDate: '2026-10-02' })
      await waitFor(() => expect(date).toHaveValue('2026-10-03'))
    }
  })

  it('proposes no start and no StaffMember, and reads availability for the proposed date', async () => {
    const date = await toDateStep({ initialDate: '2026-11-10' })
    await waitFor(() => expect(date).toHaveValue('2026-11-10'))
    expect(screen.queryByRole('radio', { checked: true })).toBeNull()
    await waitFor(() =>
      expect(
        backend.calls.some((call) => call.path.includes('/availability') && call.path.includes('date=2026-11-10')),
      ).toBe(true),
    )
  })

  it('never overwrites a date the owner chose or empties, and keeps the field editable', async () => {
    const date = (await toDateStep({ initialDate: '2026-11-10' })) as HTMLInputElement
    await waitFor(() => expect(date).toHaveValue('2026-11-10'))
    fireEvent.change(date, { target: { value: '2026-12-01' } })
    expect(date).toHaveValue('2026-12-01')
    fireEvent.change(date, { target: { value: '' } })
    // The empty date is the owner's: it stays empty and the existing validation answers it.
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(date).toHaveValue('')
  })
})
