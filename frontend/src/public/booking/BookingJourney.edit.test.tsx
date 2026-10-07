import '@testing-library/jest-dom/vitest'
import { cleanup, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ATTEMPT_ONE,
  SERVICE_COLOUR,
  STAFF_GEORGI,
  button,
  chooseStaff,
  click,
  heading,
  installServer,
  next,
  openPage,
  parsedBody,
  pickDate,
  pickSlot,
  reachReview,
  startFromService,
  stubAttemptIds,
  submit,
  typeInto,
  type FakeServer,
} from './testSupport'

let server: FakeServer

beforeEach(() => {
  window.history.replaceState(null, '', '/example-studio')
  server = installServer()
  stubAttemptIds(ATTEMPT_ONE)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

const step = () =>
  (window.history.state as { spyBooking?: { step?: number } } | null)?.spyBooking?.step ?? null

async function edit(name: string, expectedHeading: string) {
  await click(screen.getByRole('button', { name }))
  await waitFor(() => expect(heading()).toHaveTextContent(expectedHeading))
}

async function backToReview() {
  await click(button('Към прегледа'))
  await waitFor(() => expect(heading()).toHaveTextContent('Преглед и потвърждение'))
}

const summary = () => document.querySelector('.booking-summary') as HTMLElement

describe('editing one step from the review', () => {
  it('opens the details with «Към прегледа» instead of «Напред» and returns directly after the edit', async () => {
    await openPage()
    await reachReview()
    const length = window.history.length
    await edit('Промени данните', 'Вашите данни')
    expect(screen.queryByRole('button', { name: 'Напред' })).toBeNull()
    expect(screen.getByLabelText('Име')).toHaveValue('Иван Петров')

    typeInto('Име', 'Иван Георгиев')
    await backToReview()
    expect(summary()).toHaveTextContent('Иван Георгиев')
    expect(summary()).toHaveTextContent('10:00 – 10:45')
    // The return is the browser's forward traversal: no history entry was added, and the entry is the review's.
    expect(window.history.length).toBe(length)
    expect(step()).toBe(5)
    // Nothing was sent and the attempt was not created by looking at the review.
    expect(server.posts).toHaveLength(0)

    await submit()
    expect(server.posts).toHaveLength(1)
    expect(parsedBody(server.posts[0])).toMatchObject({
      attemptId: ATTEMPT_ONE,
      customer: { displayName: 'Иван Георгиев', phone: '0888 123 456' },
    })
  })

  it('does not return from the details while they are invalid', async () => {
    await openPage()
    await reachReview()
    await edit('Промени данните', 'Вашите данни')
    typeInto('Име', '')
    await click(button('Към прегледа'))
    expect(heading()).toHaveTextContent('Вашите данни')
    expect(screen.getByText('Въведете име.')).toBeInTheDocument()
    expect(screen.getByLabelText('Име')).toHaveFocus()
  })

  it('returns straight from the date and time once a time is chosen, without visiting the details', async () => {
    await openPage()
    await reachReview()
    const visited: string[] = []
    await edit('Промени датата и часа', 'Дата и час')
    visited.push(heading().textContent ?? '')
    await pickDate('четвъртък, 8 октомври 2026 г.')
    // The old time belongs to another date, so it is no longer chosen and the way back is closed.
    expect(button('Напред')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Към прегледа' })).toBeNull()
    await pickSlot('09:00')
    expect(button('Към прегледа')).toBeEnabled()
    await backToReview()
    expect(summary()).toHaveTextContent('четвъртък, 8 октомври 2026 г.')
    expect(summary()).toHaveTextContent('09:00 – 09:45')
    expect(summary()).toHaveTextContent('Иван Петров')
    expect(visited).toEqual(['Дата и час'])
  })

  it('asks only for the invalidated selections when the StaffMember changes, and keeps the rest', async () => {
    await openPage()
    await reachReview()
    await edit('Промени служителя', 'Избор на служител')
    await chooseStaff('Георги')
    // The chosen time depended on the StaffMember, so the review is not reachable yet.
    expect(screen.queryByRole('button', { name: 'Към прегледа' })).toBeNull()
    await next()
    expect(heading()).toHaveTextContent('Дата и час')
    expect(button('Напред')).toBeDisabled()
    await pickSlot('11:00')
    expect(screen.queryByRole('button', { name: 'Напред' })).toBeNull()
    await backToReview()

    expect(summary()).toHaveTextContent('Георги Петров')
    expect(summary()).toHaveTextContent('11:00 – 11:45')
    // The details were never asked for again.
    expect(summary()).toHaveTextContent('Иван Петров')
    expect(summary()).toHaveTextContent('0888 123 456')
    await submit()
    expect(parsedBody(server.posts[0])).toMatchObject({
      staffMemberId: STAFF_GEORGI,
      start: '2026-10-07T11:00:00+03:00',
      customer: { displayName: 'Иван Петров', phone: '0888 123 456' },
    })
  })

  it('asks for the StaffMember preference, the time and nothing else when the Service changes', async () => {
    await openPage()
    await reachReview()
    await edit('Промени услугата', 'Избор на услуга')
    await click(screen.getByRole('radio', { name: /Боядисване/ }))
    expect(screen.queryByRole('button', { name: 'Към прегледа' })).toBeNull()
    await next()
    expect(heading()).toHaveTextContent('Избор на служител')
    expect(screen.getByRole('radio', { name: /Без предпочитание/ })).toBeChecked()
    await next()
    expect(heading()).toHaveTextContent('Дата и час')
    await pickSlot('10:00')
    await backToReview()
    expect(summary()).toHaveTextContent('Боядисване')
    expect(summary()).toHaveTextContent('Иван Петров')
    await submit()
    expect(parsedBody(server.posts[0])).toMatchObject({
      serviceId: SERVICE_COLOUR,
      customer: { displayName: 'Иван Петров', phone: '0888 123 456' },
    })
  })

  it('keeps an unchanged choice and goes back at once from the first steps', async () => {
    await openPage()
    await reachReview()
    await edit('Промени служителя', 'Избор на служител')
    // Nothing invalidated: the way back is offered immediately and reaches the review by going forward.
    await backToReview()
    expect(summary()).toHaveTextContent('Без предпочитание')
    expect(step()).toBe(5)
    expect(server.of('GET', '/availability')).toHaveLength(1)
  })

  it('never returns to an actionable review with a stale time, not even with the browser Forward', async () => {
    await openPage()
    await reachReview()
    await edit('Промени служителя', 'Избор на служител')
    await chooseStaff('Георги')
    expect(step()).toBe(2)

    // The review still exists as a forward entry, but the choices no longer reach it.
    window.history.go(3)
    await waitFor(() => expect(step()).toBe(2))
    expect(heading()).toHaveTextContent('Избор на служител')
    expect(screen.queryByRole('button', { name: /Потвърди резервацията/ })).toBeNull()

    // The date and time step itself may be reached again (it is the next step), the details are not.
    await next()
    expect(heading()).toHaveTextContent('Дата и час')
    window.history.go(1)
    await waitFor(() => expect(step()).toBe(3))
    expect(heading()).toHaveTextContent('Дата и час')
  })
})

describe('the browser history around an edit', () => {
  it('lets Back from the review go to the details and Forward return to the review', async () => {
    await openPage()
    await reachReview()
    await edit('Промени данните', 'Вашите данни')
    await backToReview()

    window.history.back()
    await waitFor(() => expect(heading()).toHaveTextContent('Вашите данни'))
    expect(screen.getByLabelText('Име')).toHaveValue('Иван Петров')
    // Reaching the review ended the edit: a step visited with the browser's Back is an ordinary step again.
    expect(screen.getByRole('button', { name: 'Напред' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Към прегледа' })).toBeNull()
    window.history.forward()
    await waitFor(() => expect(heading()).toHaveTextContent('Преглед и потвърждение'))
  })

  it('keeps the entry index equal to the step when a longer way back needs new entries', async () => {
    await openPage()
    await reachReview()
    await edit('Промени служителя', 'Избор на служител')
    await chooseStaff('Георги')
    await next()
    await pickSlot('11:00')
    await backToReview()
    expect(step()).toBe(5)

    // Back walks through the steps that were pushed on the way: details, then date and time.
    window.history.back()
    await waitFor(() => expect(step()).toBe(4))
    expect(heading()).toHaveTextContent('Вашите данни')
    window.history.back()
    await waitFor(() => expect(step()).toBe(3))
    expect(heading()).toHaveTextContent('Дата и час')
  })

  it('leaves the journey with the right number of steps after an edit', async () => {
    await openPage()
    await reachReview()
    await edit('Промени данните', 'Вашите данни')
    await backToReview()
    // The journey is dirty, so leaving asks first; confirming goes back to the profile entry.
    await click(button('Към страницата на бизнеса'))
    await click(button('Напусни'))
    await waitFor(() => expect(window.history.state).toBeNull())
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Примерно студио')
  })
})

describe('a frozen attempt cannot be edited', () => {
  it('disables every edit action while a send is pending', async () => {
    let release: (response: Response) => void = () => undefined
    server.on((request) =>
      request.method === 'POST'
        ? (new Promise<Response>((resolve) => {
            release = resolve
          }) as unknown as Response)
        : undefined,
    )
    await openPage()
    await reachReview()
    await submit()
    for (const name of ['Промени услугата', 'Промени служителя', 'Промени датата и часа', 'Промени данните']) {
      expect(screen.getByRole('button', { name })).toBeDisabled()
    }
    release(new Response(JSON.stringify({}), { status: 500 }))
  })
})

describe('the selected Service above the heading', () => {
  it('sits above the heading of the middle steps and is absent on the first step and the review', async () => {
    await openPage()
    await startFromService('Подстригване')
    const order = () => {
      const context = document.querySelector('.booking-context')
      const heading1 = document.querySelector('h1')
      if (!context || !heading1) return null
      return context.compareDocumentPosition(heading1) & Node.DOCUMENT_POSITION_FOLLOWING ? 'context-first' : 'heading-first'
    }
    expect(heading()).toHaveTextContent('Избор на служител')
    expect(order()).toBe('context-first')
    await next()
    expect(order()).toBe('context-first')
    await pickSlot('10:00')
    await next()
    expect(heading()).toHaveTextContent('Вашите данни')
    expect(order()).toBe('context-first')

    typeInto('Име', 'Иван Петров')
    typeInto('Телефон', '0888 123 456')
    await next()
    expect(heading()).toHaveTextContent('Преглед и потвърждение')
    expect(document.querySelector('.booking-context')).toBeNull()

    await edit('Промени услугата', 'Избор на услуга')
    expect(document.querySelector('.booking-context')).toBeNull()
  })
})
