import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../identity/api'
import { UnsavedChangesGuardProvider } from '../../ui/UnsavedChangesGuard'
import { ManualCreate } from './ManualCreate'
import { CREATED_ID, createdResponse, type FakeBackend } from './testFixtures'
import {
  chooseCustomer,
  chooseServices,
  chooseStaff,
  chooseTime,
  confirm,
  deferred,
  installBackend,
  NOW,
  next,
  renderForm,
} from './testHarness'

// The development server renders the application in React StrictMode, which runs every effect twice. The
// form must still react to the answers of the server (a Phase 11 review defect: a "closed" flag that was only
// ever set made every create answer be ignored, so the form stayed on "Създаване на часа…" forever).
vi.mock('../../identity/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../identity/api')>()),
  request: vi.fn(),
}))

const mockedRequest = vi.mocked(request)
let backend: FakeBackend

beforeEach(() => {
  backend = installBackend(mockedRequest)
})

afterEach(() => {
  cleanup()
})

async function toReviewStrict() {
  const handlers = renderForm({ strict: true })
  await chooseCustomer()
  await chooseServices('Дамско подстригване')
  await chooseStaff()
  await chooseTime()
  await screen.findByRole('heading', { level: 2, name: 'Вътрешна бележка' })
  next()
  await screen.findByRole('button', { name: 'Създай час' })
  return handlers
}

describe('manual create under React StrictMode', () => {
  it('navigates to the created Appointment after a success', async () => {
    const handlers = await toReviewStrict()
    confirm()
    await waitFor(() => expect(handlers.onCreated).toHaveBeenCalledWith(CREATED_ID))
  })

  it('shows a rejection and enables the form again', async () => {
    backend.create = () => {
      throw backend.problem(400, 'VALIDATION_ERROR')
    }
    await toReviewStrict()
    confirm()
    expect(await screen.findByRole('alert')).toHaveTextContent('Проверете въведените данни.')
    expect(screen.getByRole('button', { name: 'Създай час' })).toBeEnabled()
  })

  it('freezes after an uncertain outcome', async () => {
    backend.create = () => {
      throw backend.problem(503, 'APPOINTMENT_OUTCOME_UNCERTAIN')
    }
    await toReviewStrict()
    confirm()
    await screen.findByRole('alert')
    expect(screen.queryByRole('button', { name: 'Създай час' })).toBeNull()
  })
})

describe('manual create under React StrictMode: genuine staleness is still ignored', () => {
  it('sends one POST for repeated confirmations and never repeats it after an uncertain outcome', async () => {
    backend.create = () => {
      throw backend.problem(503, 'APPOINTMENT_OUTCOME_UNCERTAIN')
    }
    await toReviewStrict()
    const button = screen.getByRole('button', { name: 'Създай час' })
    // One batch: the button is still enabled for every click, so only the synchronous guard can stop them.
    act(() => {
      fireEvent.click(button)
      fireEvent.click(button)
      fireEvent.doubleClick(button)
    })
    await screen.findByRole('alert')
    expect(backend.posts()).toHaveLength(1)
    await act(async () => {
      await Promise.resolve()
    })
    expect(backend.posts()).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Създай час' })).toBeNull()
  })

  it('ignores an answer that arrives after the form was left', async () => {
    const answer = deferred<ReturnType<typeof createdResponse>>()
    backend.create = () => answer.promise
    const handlers = await toReviewStrict()
    confirm()
    handlers.view.unmount()
    await act(async () => {
      answer.resolve(createdResponse())
    })
    expect(handlers.onCreated).not.toHaveBeenCalled()
    expect(backend.posts()).toHaveLength(1)
  })

  it('ignores the answer of a previous Business or journey once a new form replaced it', async () => {
    const answer = deferred<ReturnType<typeof createdResponse>>()
    backend.create = () => answer.promise
    const created = { first: vi.fn(), second: vi.fn() }
    const form = (key: string, onCreated: () => void) => (
      <StrictMode>
        <UnsavedChangesGuardProvider>
          <ManualCreate
            key={key}
            now={() => NOW}
            onCancel={vi.fn()}
            onCreated={onCreated}
            onAddCustomer={vi.fn()}
            onAuthenticationRequired={vi.fn()}
          />
        </UnsavedChangesGuardProvider>
      </StrictMode>
    )
    const view = render(form('business-a', created.first))
    await chooseCustomer()
    await chooseServices('Дамско подстригване')
    await chooseStaff()
    await chooseTime()
    await screen.findByRole('heading', { level: 2, name: 'Вътрешна бележка' })
    next()
    await screen.findByRole('button', { name: 'Създай час' })
    confirm()
    // The Business switches: the shell mounts a new form under another key.
    view.rerender(form('business-b', created.second))
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Клиент')
    await act(async () => {
      answer.resolve(createdResponse())
    })
    expect(created.first).not.toHaveBeenCalled()
    expect(created.second).not.toHaveBeenCalled()
    // The new journey is untouched by the old answer.
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Клиент')
    expect(backend.posts()).toHaveLength(1)
  })
})
