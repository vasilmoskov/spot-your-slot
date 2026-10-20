import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ListPagination, pageItems } from './ListSortControls'

afterEach(cleanup)

function renderPagination(page: number, totalElements: number, size: 10 | 25 | 50 = 10) {
  const onPageChange = vi.fn()
  const onSizeChange = vi.fn()
  const view = render(
    <ListPagination
      label="Странициране на тестовете"
      idPrefix="test"
      page={page}
      size={size}
      totalElements={totalElements}
      onPageChange={onPageChange}
      onSizeChange={onSizeChange}
    />,
  )
  return { onPageChange, onSizeChange, view }
}

describe('pageItems', () => {
  it('lists every page when there are few', () => {
    expect(pageItems(2, 3)).toEqual([1, 2, 3])
    expect(pageItems(1, 7)).toEqual([1, 2, 3, 4, 5, 6, 7])
  })

  it('keeps the first and last page, a bounded neighbourhood and ellipses for many pages', () => {
    expect(pageItems(5, 20)).toEqual([1, 'gap-before', 4, 5, 6, 'gap-after', 20])
    expect(pageItems(1, 20)).toEqual([1, 2, 3, 4, 'gap-after', 20])
    expect(pageItems(20, 20)).toEqual([1, 'gap-before', 17, 18, 19, 20])
    expect(pageItems(3, 9)).toEqual([1, 2, 3, 4, 'gap-after', 9])
  })

  it('shows a page instead of an ellipsis that would hide just one', () => {
    expect(pageItems(5, 9)).toEqual([1, 'gap-before', 4, 5, 6, 'gap-after', 9])
    expect(pageItems(4, 9)).toEqual([1, 2, 3, 4, 5, 'gap-after', 9])
  })
})

describe('ListPagination', () => {
  it('draws nothing for zero results', () => {
    renderPagination(0, 0)
    expect(screen.queryByRole('navigation')).toBeNull()
  })

  it('draws nothing for one page that fits the smallest size: no numbers, no useless disabled buttons, no selector', () => {
    renderPagination(0, 7)
    expect(screen.queryByRole('navigation')).toBeNull()
    expect(screen.queryByText(/Страница 1 от 1/)).toBeNull()
  })

  it('keeps the page-size selector when a smaller size exists, even if the chosen size gives one page', () => {
    const { onSizeChange } = renderPagination(0, 30, 50)
    const nav = screen.getByRole('navigation', { name: 'Странициране на тестовете' })
    expect(screen.queryByRole('button', { name: 'Следваща страница' })).toBeNull()
    expect(screen.queryByRole('button', { name: /Страница/ })).toBeNull()
    fireEvent.change(screen.getByLabelText('На страница'), { target: { value: '10' } })
    expect(onSizeChange).toHaveBeenCalledWith(10)
    expect(nav).toBeInTheDocument()
  })

  it('shows numbered pages with chevrons, marks the current page and carries no range sentence', () => {
    const { onPageChange } = renderPagination(1, 25)
    expect(screen.getByLabelText('Страница 2, текуща')).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByText(/Показани|Страница 2 от/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Страница 3' }))
    expect(onPageChange).toHaveBeenLastCalledWith(2)
    fireEvent.click(screen.getByRole('button', { name: 'Предишна страница' }))
    expect(onPageChange).toHaveBeenLastCalledWith(0)
    fireEvent.click(screen.getByRole('button', { name: 'Следваща страница' }))
    expect(onPageChange).toHaveBeenLastCalledWith(2)
    // The compact equivalent for a narrow screen is in the markup (the stylesheet shows one of the two).
    expect(document.querySelector('.pagination-compact')).toHaveTextContent('2 / 3')
  })

  it('uses non-interactive ellipses for many pages', () => {
    renderPagination(9, 200)
    expect(screen.getByRole('button', { name: 'Страница 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Страница 20' })).toBeInTheDocument()
    const gaps = document.querySelectorAll('.pagination-gap')
    expect(gaps).toHaveLength(2)
    gaps.forEach((gap) => expect(gap.tagName).toBe('SPAN'))
  })

  it('moves focus to the current page when the used button disappears', () => {
    const { view } = renderPagination(0, 25)
    const next = screen.getByRole('button', { name: 'Страница 2' })
    next.focus()
    view.rerender(
      <ListPagination label="Странициране на тестовете" idPrefix="test" page={1} size={10} totalElements={25} onPageChange={() => undefined} onSizeChange={() => undefined} />,
    )
    expect(screen.getByLabelText('Страница 2, текуща')).toHaveFocus()
  })
})
