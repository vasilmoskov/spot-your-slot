import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { UnsavedChangesGuardProvider, useGuardedFormState } from '../ui/UnsavedChangesGuard'
import type { Business } from '../identity/api'
import { BusinessSelection } from './BusinessSelection'

const businesses: Business[] = [
  { id: 'a', displayName: 'Студио А', role: 'BUSINESS_OWNER', status: 'ACTIVE' },
  { id: 'b', displayName: 'Студио Б', role: 'BUSINESS_OWNER', status: 'SUSPENDED' },
  { id: 'c', displayName: 'Студио В', role: 'BUSINESS_OWNER', status: 'DRAFT' },
]

const card = (name: string) => screen.getByRole('article', { name })
const show = (name: string) => fireEvent.click(within(card(name)).getByRole('button', { name: 'Покажи' }))

function DirtyForm() {
  useGuardedFormState(true, () => undefined)
  return <p>Отворена форма</p>
}

function renderSelection(
  props: Partial<Parameters<typeof BusinessSelection>[0]> = {},
  extra?: React.ReactNode,
) {
  const onManage = vi.fn()
  const result = render(
    <UnsavedChangesGuardProvider>
      {extra}
      <BusinessSelection
        businesses={businesses}
        activeBusinessId={undefined}
        busy={false}
        onManage={onManage}
        {...props}
      />
    </UnsavedChangesGuardProvider>,
  )
  return { onManage, ...result }
}

describe('BusinessSelection button and card semantics', () => {
  it('names every button exactly "Покажи" (no longer label) and puts the Business context on its card', () => {
    renderSelection()
    const buttons = screen.getAllByRole('button')
    expect(buttons).toHaveLength(3)
    for (const button of buttons) {
      expect(button).toHaveAccessibleName('Покажи')
      expect(button.textContent).toBe('Покажи')
      expect(button).not.toHaveAttribute('aria-label')
    }
    for (const business of businesses) {
      const article = card(business.displayName)
      // The card is named by its own heading.
      const heading = within(article).getByRole('heading', { level: 2, name: business.displayName })
      expect(article.getAttribute('aria-labelledby')).toBe(heading.id)
      expect(within(article).getAllByRole('button')).toHaveLength(1)
    }
    expect(screen.queryByText('Управлявай')).not.toBeInTheDocument()
  })

  it('passes the Business of the clicked card, not another', () => {
    const { onManage } = renderSelection()
    show('Студио В')
    expect(onManage).toHaveBeenCalledExactlyOnceWith('c')
  })

  it('shows no visible "Избран" badge; the selection is a checkmark plus programmatic and hidden-text semantics', () => {
    renderSelection({ activeBusinessId: 'b' })
    expect(screen.queryByText('Избран')).not.toBeInTheDocument()
    const selected = card('Студио Б')
    expect(selected).toHaveAttribute('aria-current', 'true')
    expect(selected).toHaveClass('is-selected')
    const check = selected.querySelector('.business-choice-check')
    expect(check).toHaveAttribute('aria-hidden', 'true')
    expect(within(selected).getByText('Текущо избран бизнес')).toHaveClass('visually-hidden')
    for (const other of ['Студио А', 'Студио В']) {
      expect(card(other)).not.toHaveAttribute('aria-current')
      expect(card(other)).not.toHaveClass('is-selected')
      expect(card(other).querySelector('.business-choice-check')).toBeNull()
    }
  })

  it.each([
    ['a', 'Активен'],
    ['b', 'Временно спрян'],
    ['c', 'Предстои активиране'],
  ])('keeps the lifecycle badge separate from the selection for Business %s (%s)', (id, label) => {
    renderSelection({ activeBusinessId: id })
    const selected = document.querySelector('[aria-current="true"]') as HTMLElement
    const badge = within(selected).getByText(label)
    expect(badge).toHaveClass('status-badge')
    // The checkmark is not inside the badge and the badge text is only the lifecycle.
    expect(badge.textContent).toBe(label)
    expect(badge.querySelector('.business-choice-check')).toBeNull()
    expect(within(selected).getByText('Текущо избран бизнес')).toBeInTheDocument()
  })

  it('presents each lifecycle in words without technical values', () => {
    renderSelection({ activeBusinessId: 'b' })
    expect(screen.getByText('Временно спрян')).toBeInTheDocument()
    expect(screen.getByText('Активен')).toBeInTheDocument()
    expect(screen.getByText('Предстои активиране')).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/ACTIVE|SUSPENDED|DRAFT|BUSINESS_OWNER/)
  })
})

describe('BusinessSelection behavior', () => {
  it('goes through the shared unsaved-changes guard before switching Business', () => {
    const { onManage } = renderSelection({ activeBusinessId: 'a' }, <DirtyForm />)
    show('Студио Б')
    expect(onManage).not.toHaveBeenCalled()
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Имате незапазени промени.')
    expect(screen.getByRole('button', { name: 'Остани' })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: 'Остани' }))
    expect(onManage).not.toHaveBeenCalled()

    show('Студио Б')
    fireEvent.click(screen.getByRole('button', { name: 'Напусни' }))
    expect(onManage).toHaveBeenCalledExactlyOnceWith('b')
  })

  it('switches at once when nothing is unsaved', () => {
    const { onManage } = renderSelection()
    show('Студио А')
    expect(onManage).toHaveBeenCalledExactlyOnceWith('a')
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('disables every button while a request is running, and says plainly when nothing is available', () => {
    const { rerender } = renderSelection({ activeBusinessId: 'a', busy: true })
    for (const button of screen.getAllByRole('button')) expect(button).toBeDisabled()
    rerender(
      <UnsavedChangesGuardProvider>
        <BusinessSelection businesses={[]} activeBusinessId={undefined} busy={false} onManage={vi.fn()} />
      </UnsavedChangesGuardProvider>,
    )
    expect(screen.getByText('Нямате бизнеси, които можете да управлявате.')).toBeInTheDocument()
  })

  it('wraps a very long Business name inside its own heading', () => {
    const long = 'Козметично и масажно студио „Слънчогледова поляна“ на Александра-Екатерина Константинова-Благоева'
    renderSelection({ businesses: [{ ...businesses[0]!, displayName: long }] })
    expect(card(long)).toHaveTextContent(long)
    expect(within(card(long)).getByRole('button', { name: 'Покажи' })).toBeInTheDocument()
  })
})
