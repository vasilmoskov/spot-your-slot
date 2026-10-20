import { useEffect, useRef } from 'react'
import type { ListPageSize, ListSortDirection } from '../navigation'
import { LIST_PAGE_SIZES } from '../navigation'
import { Button } from './Button'

type SortableColumnHeaderProps = {
  label: string
  active: boolean
  direction: ListSortDirection
  onSort: () => void
}

export function SortableColumnHeader({
  label,
  active,
  direction,
  onSort,
}: SortableColumnHeaderProps) {
  const ariaSort: 'ascending' | 'descending' | 'none' = !active
    ? 'none'
    : direction === 'asc'
      ? 'ascending'
      : 'descending'

  return (
    <th
      scope="col"
      aria-sort={ariaSort}
      className={active ? 'sortable-column-header is-active' : 'sortable-column-header'}
    >
      <button type="button" className="column-sort-trigger" onClick={onSort}>
        <span>{label}</span>
        <span className="sort-indicator-group" aria-hidden="true">
          <span className={active && direction === 'asc' ? 'sort-arrow is-active' : 'sort-arrow'}>
            ▲
          </span>
          <span className={active && direction === 'desc' ? 'sort-arrow is-active' : 'sort-arrow'}>
            ▼
          </span>
        </span>
      </button>
    </th>
  )
}

type PageSizeSelectProps = {
  id: string
  value: ListPageSize
  onChange: (size: ListPageSize) => void
}

export function PageSizeSelect({ id, value, onChange }: PageSizeSelectProps) {
  return (
    <label className="page-size-select" htmlFor={id}>
      На страница
      <select
        id={id}
        value={value}
        onChange={(event) => onChange(Number(event.target.value) as ListPageSize)}
      >
        {LIST_PAGE_SIZES.map((size) => (
          <option key={size} value={size}>
            {size}
          </option>
        ))}
      </select>
    </label>
  )
}

export type ResponsiveSortOption = {
  field: string
  ascLabel: string
  descLabel: string
}

type ResponsiveSortSelectProps = {
  id: string
  label: string
  options: readonly ResponsiveSortOption[]
  sort: string
  direction: ListSortDirection
  onChange: (next: { sort: string; direction: ListSortDirection }) => void
}

export function ResponsiveSortSelect({
  id,
  label,
  options,
  sort,
  direction,
  onChange,
}: ResponsiveSortSelectProps) {
  const value = `${sort}:${direction}`

  return (
    <label className="responsive-sort-select" htmlFor={id}>
      {label}
      <select
        id={id}
        value={value}
        onChange={(event) => {
          const [nextSort, nextDirection] = event.target.value.split(':')
          onChange({
            sort: nextSort as string,
            direction: nextDirection as ListSortDirection,
          })
        }}
      >
        {options.flatMap((option) => [
          <option key={`${option.field}:asc`} value={`${option.field}:asc`}>
            {option.ascLabel}
          </option>,
          <option key={`${option.field}:desc`} value={`${option.field}:desc`}>
            {option.descLabel}
          </option>,
        ])}
      </select>
    </label>
  )
}

type ListPaginationProps = {
  // Accessible name of the navigation landmark.
  label: string
  idPrefix: string
  // Zero-based; the caller passes an already valid (clamped) page.
  page: number
  size: ListPageSize
  totalElements: number
  onPageChange: (page: number) => void
  onSizeChange: (size: ListPageSize) => void
}

type PageItem = number | 'gap-before' | 'gap-after'

/**
 * The page numbers (one-based) of a bounded window: the first and last page, the current page with one neighbour on
 * each side, and a non-interactive ellipsis wherever more than one page is skipped. A gap of exactly one page shows
 * that page instead of an ellipsis.
 */
export function pageItems(current: number, total: number): PageItem[] {
  if (total <= 7) return Array.from({ length: total }, (_, index) => index + 1)
  const wanted = new Set<number>([1, total, current - 1, current, current + 1])
  if (current <= 3) for (let n = 1; n <= 4; n += 1) wanted.add(n)
  if (current >= total - 2) for (let n = total - 3; n <= total; n += 1) wanted.add(n)
  const sorted = [...wanted].filter((n) => n >= 1 && n <= total).sort((a, b) => a - b)
  const items: PageItem[] = []
  sorted.forEach((n, index) => {
    const previous = sorted[index - 1]
    if (previous !== undefined && n - previous === 2) items.push(previous + 1)
    else if (previous !== undefined && n - previous > 2) items.push(index <= sorted.indexOf(current) ? 'gap-before' : 'gap-after')
    items.push(n)
  })
  return items
}

/**
 * The shared pagination region: numbered pages with previous/next chevrons (a compact `2 / 12` on a narrow screen) and
 * a compact `На страница` selector. Nothing is drawn when neither is useful: one page needs no navigation, and the
 * selector stays only while a smaller size exists than the number of results. It is presentational: the caller owns
 * the state (server- or client-side) and decides whether a change is pushed to history.
 */
export function ListPagination({
  label,
  idPrefix,
  page,
  size,
  totalElements,
  onPageChange,
  onSizeChange,
}: ListPaginationProps) {
  const totalPages = Math.max(1, Math.ceil(totalElements / size))
  const needsNavigation = totalPages > 1
  const needsSize = totalElements > LIST_PAGE_SIZES[0]!
  const current = useRef<HTMLSpanElement>(null)
  const previousPage = useRef(page)

  // A page change can replace the button that was just used (the new current page is not a button) or disable it, which
  // would drop focus to <body>; keep it inside the region on the current page.
  useEffect(() => {
    if (previousPage.current === page) return
    previousPage.current = page
    const active = document.activeElement
    if (!active || active === document.body || !active.isConnected || (active as HTMLButtonElement).disabled) {
      current.current?.focus()
    }
  }, [page])

  if (!needsNavigation && !needsSize) return null

  return (
    <nav className="business-pagination" aria-label={label}>
      {needsNavigation && (
        <div className="pagination-pages">
          <Button
            type="button"
            variant="neutral"
            className="pagination-step"
            aria-label="Предишна страница"
            disabled={page === 0}
            onClick={() => onPageChange(page - 1)}
          >
            <span aria-hidden="true">‹</span>
          </Button>
          <div className="pagination-numbers">
            {pageItems(page + 1, totalPages).map((item) =>
              typeof item === 'string' ? (
                <span key={item} className="pagination-gap" aria-hidden="true">
                  …
                </span>
              ) : item === page + 1 ? (
                <span key={item} ref={current} tabIndex={-1} className="pagination-current" aria-current="page" aria-label={`Страница ${item}, текуща`}>
                  {item}
                </span>
              ) : (
                <button key={item} type="button" className="pagination-number" aria-label={`Страница ${item}`} onClick={() => onPageChange(item - 1)}>
                  {item}
                </button>
              ),
            )}
          </div>
          <span className="pagination-compact" aria-live="polite">
            {page + 1} / {totalPages}
          </span>
          <Button
            type="button"
            variant="neutral"
            className="pagination-step"
            aria-label="Следваща страница"
            disabled={page + 1 >= totalPages}
            onClick={() => onPageChange(page + 1)}
          >
            <span aria-hidden="true">›</span>
          </Button>
        </div>
      )}
      {needsSize && <PageSizeSelect id={`${idPrefix}-page-size`} value={size} onChange={onSizeChange} />}
    </nav>
  )
}
