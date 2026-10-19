import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from '../../identity/api'
import { APPOINTMENT_ONE, PAST_NOW, cancelled, confirmed, type FakeLifecycleBackend } from './lifecycleTestFixtures'
import {
  deferred,
  editNote,
  installBackend,
  navigate,
  noteInput,
  ready,
  renderDetail,
  rereadButton,
  saveNote,
  typeNote,
} from './lifecycleTestHarness'

vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
let backend: FakeLifecycleBackend

beforeEach(() => {
  backend = installBackend(mockedRequest)
})

afterEach(() => {
  cleanup()
})

const NOTE_PATH = `/api/business/appointments/${APPOINTMENT_ONE}/internal-note`
const editButton = () => screen.queryByRole('button', { name: /Редактирай бележката|Добави бележка/ })
const valueOf = (label: string) => screen.getByText(label, { selector: 'dt' }).nextElementSibling
const shownNote = () => valueOf('Вътрешна бележка')?.textContent
const frozenNote = () => valueOf('Изпратена вътрешна бележка (непотвърдена)')?.textContent
const cancelEdit = () => fireEvent.click(screen.getByRole('button', { name: 'Отказ' }))

describe('Internal note: where the editor exists', () => {
  it('offers "Добави бележка" for an empty note and "Редактирай бележката" for an existing one', async () => {
    renderDetail()
    await ready()
    expect(screen.getByRole('button', { name: 'Добави бележка' })).toBeInTheDocument()
    expect(shownNote()).toBe('Няма бележка.')
    cleanup()
    backend.current = confirmed({ internalNote: 'Предпочита сутрин.' })
    renderDetail()
    await ready()
    expect(screen.getByRole('button', { name: 'Редактирай бележката' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Бележки' })).toHaveTextContent('Предпочита сутрин.')
  })

  it('is available for a CANCELLED Appointment and for a started one', async () => {
    backend.current = cancelled({ internalNote: 'Отменен по телефона.' })
    renderDetail()
    await ready()
    expect(editButton()).toBeInTheDocument()
    cleanup()
    backend.current = confirmed()
    renderDetail({ now: PAST_NOW })
    await ready()
    expect(editButton()).toBeInTheDocument()
  })

  it.each([['DRAFT'], ['SUSPENDED']] as const)('is absent for a %s Business, which stays readable', async (businessStatus) => {
    backend.current = confirmed({ businessStatus, internalNote: 'Само за четене.' })
    renderDetail()
    await ready()
    expect(editButton()).toBeNull()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.getByRole('region', { name: 'Бележки' })).toHaveTextContent('Само за четене.')
  })

  it('is absent for a viewer who may not change anything', async () => {
    renderDetail({ canChange: false })
    await ready()
    expect(editButton()).toBeNull()
  })

  it('keeps the note out of everything but the detail: a long note wraps and nothing leaks', async () => {
    const long = `${'Дълъгдума'.repeat(60)}\nвтори ред`
    backend.current = confirmed({ internalNote: long })
    renderDetail()
    await ready()
    const dd = screen.getByText(/Дълъгдума/, { selector: 'dd' })
    expect(dd).toHaveClass('appointment-note')
    expect(dd.textContent).toBe(long)
    expect(document.title).not.toContain('Дълъгдума')
    expect(window.location.href).not.toContain('Дълъгдума')
  })
})

describe('Internal note: editing', () => {
  it('opens a labelled editor with the current note, focused, with Отказ before Запази бележката', async () => {
    backend.current = confirmed({ internalNote: 'Предпочита сутрин.' })
    renderDetail()
    await ready()
    editNote()
    expect(noteInput()).toHaveValue('Предпочита сутрин.')
    await waitFor(() => expect(noteInput()).toHaveFocus())
    const actions = screen.getAllByRole('button').filter((button) => ['Отказ', 'Запази бележката'].includes(button.textContent ?? ''))
    expect(actions.map((button) => button.textContent)).toEqual(['Отказ', 'Запази бележката'])
    expect(editButton()).toBeNull()
    expect(screen.queryByText('Предпочита сутрин.', { selector: 'dd' })).toBeNull()
    expect(screen.getByText('Вижда се само от вас и не се показва на клиента.')).toBeInTheDocument()
  })

  it('sends the normalized note once with the current version and then reads the Appointment again', async () => {
    renderDetail()
    await ready()
    editNote()
    typeNote('  Първи ред\r\nВтори ред  ')
    saveNote()
    expect(await screen.findByText('Бележката е запазена.')).toBeInTheDocument()
    expect(backend.posts()).toEqual([
      { path: NOTE_PATH, method: 'PUT', body: { expectedVersion: 7, internalNote: 'Първи ред\nВтори ред' } },
    ])
    expect(backend.calls.map((call) => call.method)).toEqual(['GET', 'PUT', 'GET'])
    expect(shownNote()).toBe('Първи ред\nВтори ред')
    expect(screen.queryByRole('textbox')).toBeNull()
    await waitFor(() => expect(editButton()).toHaveFocus())
    expect(document.body.textContent).not.toMatch(/версия|version/i)
  })

  it('does not show the new note before the authoritative re-read answered', async () => {
    const reread = deferred<ReturnType<typeof confirmed>>()
    renderDetail()
    await ready()
    editNote()
    typeNote('Нова бележка')
    backend.read = () => reread.promise
    saveNote()
    await waitFor(() => expect(backend.reads()).toHaveLength(2))
    expect(screen.queryByText('Бележката е запазена.')).toBeNull()
    expect(screen.queryByText('Нова бележка', { selector: 'dd' })).toBeNull()
    await act(async () => {
      reread.resolve(confirmed({ internalNote: 'Нова бележка', version: 8 }))
    })
    expect(await screen.findByText('Бележката е запазена.')).toBeInTheDocument()
    expect(screen.getByText('Нова бележка', { selector: 'dd' })).toBeInTheDocument()
  })

  it('clears the note with a blank string', async () => {
    backend.current = confirmed({ internalNote: 'За изтриване' })
    renderDetail()
    await ready()
    editNote()
    typeNote('  \n\t ')
    saveNote()
    await screen.findByText('Бележката е запазена.')
    expect(backend.posts()[0]!.body).toEqual({ expectedVersion: 7, internalNote: '' })
    expect(shownNote()).toBe('Няма бележка.')
    expect(screen.getByRole('button', { name: 'Добави бележка' })).toBeInTheDocument()
  })

  it('saves a note on a cancelled Appointment', async () => {
    backend.current = cancelled()
    renderDetail()
    await ready()
    editNote()
    typeNote('Клиентът се обади.')
    saveNote()
    await screen.findByText('Бележката е запазена.')
    expect(backend.posts()[0]!.body).toEqual({ expectedVersion: 8, internalNote: 'Клиентът се обади.' })
    expect(screen.getByText('Отменен')).toBeInTheDocument()
  })

  it.each([
    ['an unchanged note', 'Предпочита сутрин.', 'Предпочита сутрин.'],
    ['the same note with other edge white space', 'Предпочита сутрин.', '  Предпочита сутрин. \n'],
    ['the same note with Windows line breaks', 'ред едно\nред две', 'ред едно\r\nред две'],
    ['a blank edit of an empty note', null, '   '],
  ])('is a no-op for %s: no request, the version does not advance', async (_name, stored, typed) => {
    backend.current = confirmed({ internalNote: stored })
    renderDetail()
    await ready()
    editNote()
    typeNote(typed)
    saveNote()
    expect(await screen.findByText('Бележката не е променена.')).toBeInTheDocument()
    expect(backend.posts()).toHaveLength(0)
    expect(backend.reads()).toHaveLength(1)
    expect(backend.current.version).toBe(7)
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  it('sends one request for a double click, an Enter in the form, and a repeated save', async () => {
    const answer = deferred<ReturnType<FakeLifecycleBackend['result']>>()
    backend.note = () => answer.promise
    renderDetail()
    await ready()
    editNote()
    typeNote('Бележка')
    const save = screen.getByRole('button', { name: 'Запази бележката' })
    fireEvent.click(save)
    fireEvent.click(save)
    fireEvent.submit(save.closest('form')!)
    expect(backend.posts()).toHaveLength(1)
    expect(noteInput()).toHaveAttribute('readonly')
    expect(save).toBeDisabled()
    // A change typed while the request is pending is ignored: the submitted value is the only one.
    fireEvent.change(noteInput(), { target: { value: 'Друго' } })
    expect(noteInput()).toHaveValue('Бележка')
    await act(async () => {
      backend.current = confirmed({ internalNote: 'Бележка', version: 8 })
      answer.resolve(backend.result())
    })
    await screen.findByText('Бележката е запазена.')
    expect(backend.posts()).toHaveLength(1)
  })

  it('cancels the edit without a request and restores focus to the edit control', async () => {
    renderDetail()
    await ready()
    editNote()
    cancelEdit()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(editButton()).toHaveFocus()
    expect(backend.posts()).toHaveLength(0)
  })
})

describe('Internal note: validation without truncation', () => {
  it('accepts exactly 500 code points and counts a code point as one', async () => {
    renderDetail()
    await ready()
    editNote()
    const exact = '😀'.repeat(500)
    typeNote(exact)
    expect(screen.queryByText(/твърде дълга/)).toBeNull()
    saveNote()
    await screen.findByText('Бележката е запазена.')
    expect(backend.posts()[0]!.body).toEqual({ expectedVersion: 7, internalNote: exact })
  })

  it('refuses 501 code points inline, sends nothing, truncates nothing, and focuses the field', async () => {
    renderDetail()
    await ready()
    editNote()
    const tooLong = 'я'.repeat(501)
    typeNote(tooLong)
    const message = screen.getByText('Бележката е твърде дълга. Съкратете я.')
    expect(noteInput()).toHaveAttribute('aria-invalid', 'true')
    expect(noteInput()).toHaveAccessibleDescription(/твърде дълга/)
    expect(message).toHaveAttribute('id', 'internal-note-error')
    expect(noteInput()).not.toHaveAttribute('maxlength')
    screen.getByRole('button', { name: 'Отказ' }).focus()
    saveNote()
    expect(backend.posts()).toHaveLength(0)
    expect(noteInput()).toHaveValue(tooLong)
    await waitFor(() => expect(noteInput()).toHaveFocus())
  })

  it('judges the length after normalization, not of the raw text', async () => {
    renderDetail()
    await ready()
    editNote()
    typeNote(`   ${'я'.repeat(500)}   `)
    expect(screen.queryByText(/твърде дълга/)).toBeNull()
    saveNote()
    await screen.findByText('Бележката е запазена.')
  })

  it('refuses control characters inline and clears the error when the text is valid again', async () => {
    renderDetail()
    await ready()
    editNote()
    typeNote('ред\u0000край')
    expect(screen.getByText('Бележката съдържа непозволени знаци.')).toBeInTheDocument()
    saveNote()
    expect(backend.posts()).toHaveLength(0)
    typeNote('ред\tкрай')
    expect(screen.queryByText('Бележката съдържа непозволени знаци.')).toBeNull()
  })

  it('refuses an unpaired surrogate', async () => {
    renderDetail()
    await ready()
    editNote()
    typeNote('ред\uD800край')
    expect(screen.getByText('Бележката съдържа непозволени знаци.')).toBeInTheDocument()
  })
})

describe('Internal note: the unsaved-change guard', () => {
  it('is clean as soon as the normalized edit equals the authoritative note again', async () => {
    backend.current = confirmed({ internalNote: 'Предпочита сутрин.' })
    renderDetail()
    await ready()
    editNote()
    typeNote('Предпочита сутрин. И още')
    navigate()
    const dialog = screen.getByRole('alertdialog', { name: 'Непотвърдена бележка' })
    expect(dialog).toHaveTextContent('Промяната на бележката още не е запазена.')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Остани' }))
    typeNote('  Предпочита сутрин.\r\n')
    navigate()
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('is not dirty merely because the editor is open or because the text is ready to submit', async () => {
    backend.current = confirmed({ internalNote: 'Предпочита сутрин.' })
    const { navigated } = renderDetail()
    await ready()
    editNote()
    navigate()
    expect(navigated).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('is dirty when an existing note is blanked, even though a blank edit is ready to submit', async () => {
    backend.current = confirmed({ internalNote: 'Предпочита сутрин.' })
    renderDetail()
    await ready()
    editNote()
    typeNote('')
    navigate()
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
  })

  it('is dirty for an invalid edit that cannot be submitted', async () => {
    renderDetail()
    await ready()
    editNote()
    typeNote('я'.repeat(501))
    navigate()
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
  })

  it('keeps the edit on Остани and discards it on Напусни, then leaves cleanly', async () => {
    const { navigated } = renderDetail()
    await ready()
    editNote()
    typeNote('Нова бележка')
    navigate()
    const first = screen.getByRole('alertdialog')
    expect(within(first).getByRole('button', { name: 'Остани' })).toHaveFocus()
    fireEvent.click(within(first).getByRole('button', { name: 'Остани' }))
    expect(noteInput()).toHaveValue('Нова бележка')
    expect(navigated).not.toHaveBeenCalled()
    navigate()
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Напусни' }))
    expect(navigated).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(backend.posts()).toHaveLength(0)
    navigate()
    expect(navigated).toHaveBeenCalledTimes(2)
  })

  it('asks before the Отказ of a dirty edit discards it and not before a clean one', async () => {
    renderDetail()
    await ready()
    editNote()
    typeNote('Нещо')
    cancelEdit()
    expect(screen.getByRole('alertdialog', { name: 'Непотвърдена бележка' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(noteInput()).toHaveValue('Нещо')
    typeNote('')
    cancelEdit()
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(editButton()).toBeInTheDocument()
  })

  it('is clean after a successful save', async () => {
    renderDetail()
    await ready()
    editNote()
    typeNote('Бележка')
    saveNote()
    await screen.findByText('Бележката е запазена.')
    navigate()
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('arms the browser unload prompt only while dirty', async () => {
    renderDetail()
    await ready()
    const prompt = () => {
      const event = new Event('beforeunload', { cancelable: true })
      window.dispatchEvent(event)
      return event.defaultPrevented
    }
    expect(prompt()).toBe(false)
    editNote()
    expect(prompt()).toBe(false)
    typeNote('x')
    expect(prompt()).toBe(true)
    typeNote('')
    expect(prompt()).toBe(false)
  })
})

type Case = { name: string; error: () => unknown; text: string }

const KNOWN: Case[] = [
  {
    name: 'validation',
    error: () => backend.problem(400, 'VALIDATION_ERROR'),
    text: 'Бележката не беше променена. Проверете я и опитайте отново.',
  },
  {
    name: 'temporarily unavailable',
    error: () => backend.problem(503, 'APPOINTMENT_TEMPORARILY_UNAVAILABLE'),
    text: 'Бележката не беше променена. Опитайте отново след малко.',
  },
]

const REFUSED: Case[] = [
  { name: 'access denied', error: () => backend.problem(403, 'ACCESS_DENIED'), text: 'Нямате достъп до тази операция.' },
  {
    name: 'lost Business context',
    error: () => backend.problem(403, 'ACTIVE_BUSINESS_REQUIRED'),
    text: 'Нямате достъп до тази операция.',
  },
  {
    name: 'Business not active',
    error: () => backend.problem(409, 'BUSINESS_NOT_ACTIVE'),
    text: 'Бизнесът не е активен. Бележките могат да се променят само в активен бизнес.',
  },
  {
    name: 'Business suspended',
    error: () => backend.problem(409, 'BUSINESS_SUSPENDED'),
    text: 'Спрян бизнес може само да преглежда данните си.',
  },
]

const UNRESOLVED: Case[] = [
  {
    name: 'outcome uncertain',
    error: () => backend.problem(503, 'APPOINTMENT_OUTCOME_UNCERTAIN'),
    text: 'Не е ясно дали бележката е променена. Прочетете часа отново, преди да опитате пак.',
  },
  {
    name: 'internal error',
    error: () => backend.problem(500, 'INTERNAL_ERROR'),
    text: 'Възникна неочаквана грешка и не е ясно дали бележката е променена. Прочетете часа отново, преди да опитате пак.',
  },
  {
    name: 'unknown code',
    error: () => backend.problem(409, 'SOMETHING_NEW'),
    text: 'Възникна неочаквана грешка и не е ясно дали бележката е променена. Прочетете часа отново, преди да опитате пак.',
  },
  {
    name: 'lost response',
    error: () => new TypeError('Failed to fetch'),
    text: 'Не е ясно дали бележката е променена. Прочетете часа отново, преди да опитате пак.',
  },
  {
    name: 'unreadable success',
    error: () => new ApiError(200, 'REQUEST_FAILED', 'Заявката не може да бъде изпълнена.'),
    text: 'Не е ясно дали бележката е променена. Прочетете часа отново, преди да опитате пак.',
  },
]

async function saveWith(error: () => unknown, note = 'Нова бележка', stored: string | null = 'Стара бележка') {
  backend.current = confirmed({ internalNote: stored })
  backend.note = () => {
    throw error()
  }
  const view = renderDetail()
  await ready()
  editNote()
  typeNote(note)
  saveNote()
  return view
}

describe('Internal note: every documented result stays distinct', () => {
  it.each([...KNOWN, ...REFUSED, ...UNRESOLVED])('$name shows only its own frontend-owned message', async (scenario) => {
    await saveWith(scenario.error)
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe(scenario.text)
    expect(document.body.textContent).not.toContain('backend text that must never be shown')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(backend.posts()).toHaveLength(1)
    await waitFor(() => expect(alert).toHaveFocus())
  })

  it.each(KNOWN)('$name is a known rollback: the edit is kept and only an explicit retry exists', async (scenario) => {
    await saveWith(scenario.error)
    await screen.findByRole('alert')
    expect(noteInput()).toHaveValue('Нова бележка')
    expect(screen.queryByText('Бележката е запазена.')).toBeNull()
    // Still dirty: leaving warns.
    navigate()
    expect(screen.getByRole('alertdialog', { name: 'Непотвърдена бележка' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(backend.posts()).toHaveLength(1)
    // The owner's retry sends the same note again, once.
    backend.note = (body) => {
      backend.current = confirmed({ internalNote: body.internalNote, version: 8 })
      return backend.result()
    }
    saveNote()
    await screen.findByText('Бележката е запазена.')
    expect(backend.posts().map((call) => call.body)).toEqual([
      { expectedVersion: 7, internalNote: 'Нова бележка' },
      { expectedVersion: 7, internalNote: 'Нова бележка' },
    ])
  })

  it.each(REFUSED)('$name removes the editor and every other mutation path', async (scenario) => {
    await saveWith(scenario.error)
    await screen.findByRole('alert')
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(editButton()).toBeNull()
    expect(screen.queryByRole('button', { name: 'Отмени часа' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Промени часа' })).toBeNull()
    expect(backend.posts()).toHaveLength(1)
  })

  it.each(UNRESOLVED)('$name freezes the exact submitted note and permits no second request', async (scenario) => {
    await saveWith(scenario.error, '  Изпратена\r\nбележка  ')
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe(scenario.text)
    // The submitted (normalized) value is shown read-only; there is no editor and no mutation control.
    expect(frozenNote()).toBe('Изпратена\nбележка')
    expect(screen.queryByText('Стара бележка')).toBeNull()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(editButton()).toBeNull()
    expect(screen.queryByRole('button', { name: 'Запази бележката' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Отмени часа' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Промени часа' })).toBeNull()
    fireEvent.keyDown(document.body, { key: 'Enter' })
    expect(backend.posts()).toHaveLength(1)
    expect(backend.reads()).toHaveLength(1)
  })

  it('a blank submission that is unresolved is shown as a cleared note, not as an empty value', async () => {
    await saveWith(() => new TypeError('Failed to fetch'), '   ')
    await screen.findByRole('alert')
    expect(frozenNote()).toBe('Празна бележка (изчистване на бележката)')
  })

  it('warns with "Неясен резултат" when leaving and returns to the calendar without the ordinary prompt', async () => {
    const { onBack, navigated } = await saveWith(() => new TypeError('Failed to fetch'))
    await screen.findByRole('alert')
    navigate()
    const warning = screen.getByRole('alertdialog', { name: 'Неясен резултат' })
    expect(warning).toHaveTextContent('Не е ясно дали бележката е променена.')
    expect(within(warning).getByRole('button', { name: 'Остани' })).toHaveFocus()
    fireEvent.click(within(warning).getByRole('button', { name: 'Остани' }))
    expect(navigated).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Обратно към графика' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(onBack).toHaveBeenCalledTimes(1)
  })

  it('shows the authoritative note after the explicit re-read, whether or not the lost request committed', async () => {
    await saveWith(() => {
      backend.current = confirmed({ internalNote: 'Нова бележка', version: 8 })
      return new TypeError('Failed to fetch')
    })
    await screen.findByRole('alert')
    fireEvent.click(rereadButton())
    expect(await screen.findByText('Нова бележка', { selector: 'dd' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(editButton()).toBeInTheDocument()
    expect(backend.posts()).toHaveLength(1)
  })

  it('keeps a success for another Appointment, or without a version, unresolved', async () => {
    backend.note = () => ({ ...backend.result(), id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' })
    renderDetail()
    await ready()
    editNote()
    typeNote('x')
    saveNote()
    expect((await screen.findByRole('alert')).textContent).toContain('Не е ясно дали бележката е променена.')
    cleanup()
    backend.note = () => ({ id: APPOINTMENT_ONE, internalNote: 'x' }) as never
    renderDetail()
    await ready()
    editNote()
    typeNote('y')
    saveNote()
    expect((await screen.findByRole('alert')).textContent).toContain('Не е ясно дали бележката е променена.')
  })

  it('an expired session hands over to the shared recovery without an own message', async () => {
    const { onAuthenticationRequired } = await saveWith(() => backend.problem(401, 'AUTH_REQUIRED', 'Влезте отново.'))
    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledWith('Влезте отново.'))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('a missing Appointment replaces the page with the shared not-found state', async () => {
    await saveWith(() => backend.problem(404, 'APPOINTMENT_NOT_FOUND'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Часът не е намерен.')
    expect(screen.queryByRole('region', { name: 'Час' })).toBeNull()
  })

  it('an unexpected state conflict re-reads the Appointment and closes the editor', async () => {
    await saveWith(() => backend.problem(409, 'APPOINTMENT_STATE_CONFLICT'))
    expect((await screen.findByRole('alert')).textContent).toBe(
      'Бележката не може да бъде променена в сегашното състояние на часа.',
    )
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(backend.reads()).toHaveLength(2)
    expect(backend.posts()).toHaveLength(1)
  })
})

describe('Internal note: a concurrent update establishes a new baseline', () => {
  async function concurrent() {
    backend.current = confirmed({ internalNote: 'Стара бележка' })
    backend.note = () => {
      backend.current = confirmed({ internalNote: 'Бележка на колега', version: 9 })
      throw backend.problem(409, 'APPOINTMENT_CONCURRENT_UPDATE')
    }
    renderDetail()
    await ready()
    editNote()
    typeNote('Моя бележка')
    saveNote()
    return screen.findByRole('alert')
  }

  it('re-reads the Appointment, shows the returned note, and drops the obsolete edit', async () => {
    const alert = await concurrent()
    expect(alert.textContent).toBe(
      'Часът е променен междувременно и бележката не е запазена. Прегледайте актуалните данни и въведете промяната отново.',
    )
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(await screen.findByText('Бележка на колега', { selector: 'dd' })).toBeInTheDocument()
    expect(backend.reads()).toHaveLength(2)
    expect(backend.posts()).toHaveLength(1)
  })

  it('is clean against the returned note and never against the obsolete one', async () => {
    await concurrent()
    await screen.findByText('Бележка на колега', { selector: 'dd' })
    navigate()
    expect(screen.queryByRole('alertdialog')).toBeNull()
    // A new edit is compared with the returned note: restoring it is clean, the obsolete value is a change.
    editNote()
    expect(noteInput()).toHaveValue('Бележка на колега')
    typeNote('Стара бележка')
    navigate()
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    typeNote('Бележка на колега')
    navigate()
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('sends the returned version with the next explicit save', async () => {
    await concurrent()
    await screen.findByText('Бележка на колега', { selector: 'dd' })
    backend.note = (body) => {
      backend.current = confirmed({ internalNote: body.internalNote, version: 10 })
      return backend.result()
    }
    editNote()
    typeNote('Обединена бележка')
    saveNote()
    await screen.findByText('Бележката е запазена.')
    expect(backend.posts().map((call) => call.body)).toEqual([
      { expectedVersion: 7, internalNote: 'Моя бележка' },
      { expectedVersion: 9, internalNote: 'Обединена бележка' },
    ])
  })

  it('a no-op against the returned note sends nothing', async () => {
    await concurrent()
    await screen.findByText('Бележка на колега', { selector: 'dd' })
    editNote()
    typeNote('Бележка на колега')
    saveNote()
    expect(await screen.findByText('Бележката не е променена.')).toBeInTheDocument()
    expect(backend.posts()).toHaveLength(1)
  })
})

describe('Internal note: privacy', () => {
  it('never reaches the URL, history state, storage, cookies, title, or console', async () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const before = { url: window.location.href, state: JSON.stringify(window.history.state), title: document.title }
    window.localStorage.clear()
    window.sessionStorage.clear()
    const secret = 'ТАЙНА-БЕЛЕЖКА-4711'
    await saveWith(() => new TypeError('Failed to fetch'), secret)
    await screen.findByRole('alert')
    expect(window.location.href).toBe(before.url)
    expect(JSON.stringify(window.history.state)).toBe(before.state)
    expect(document.title).toBe(before.title)
    expect(JSON.stringify({ ...window.localStorage })).not.toContain(secret)
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
    expect(document.cookie).not.toContain(secret)
    for (const spy of [log, warn, error]) expect(spy).not.toHaveBeenCalled()
    vi.restoreAllMocks()
  })

  it('does not put the note in the editor draft before editing and never shows version or cancelled_by', async () => {
    backend.current = cancelled({ internalNote: 'Видима бележка' })
    renderDetail()
    await ready()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(document.body.textContent).not.toMatch(/версия|version|cancelled_by|cancelledBy|BUSINESS|\b8\b/i)
    editNote()
    expect(document.body.textContent).not.toMatch(/версия|version|cancelled_by|cancelledBy|BUSINESS|\b8\b/i)
  })
})
