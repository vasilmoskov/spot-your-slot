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
      Резултати на страница
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

/**
 * The shared pagination region: range summary, page-size selector and
 * Previous/Next. It is presentational: the caller owns the state (server- or
 * client-side) and decides whether a change is pushed to history.
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
  const from = totalElements === 0 ? 0 : page * size + 1
  const to = Math.min((page + 1) * size, totalElements)
  const summary = useRef<HTMLParagraphElement>(null)
  const previousPage = useRef(page)

  // A page change can disable the button that was just used, which would drop
  // focus to <body>; keep it inside the region on the (accurate) summary.
  useEffect(() => {
    if (previousPage.current === page) return
    previousPage.current = page
    const active = document.activeElement
    if (!active || active === document.body || (active as HTMLButtonElement).disabled) {
      summary.current?.focus()
    }
  }, [page])

  return (
    <nav className="business-pagination" aria-label={label}>
      <div className="business-pagination-summary">
        <p ref={summary} tabIndex={-1} aria-live="polite">
          Показани {from}–{to} от {totalElements}
        </p>
        <span>
          Страница {page + 1} от {totalPages}
        </span>
      </div>
      <div className="business-pagination-controls">
        <PageSizeSelect id={`${idPrefix}-page-size`} value={size} onChange={onSizeChange} />
        <div className="business-pagination-actions">
          <Button
            type="button"
            variant="secondary"
            disabled={page === 0}
            onClick={() => onPageChange(page - 1)}
          >
            Предишна
          </Button>
          <Button
            type="button"
            variant="secondary"
            disabled={page + 1 >= totalPages}
            onClick={() => onPageChange(page + 1)}
          >
            Следваща
          </Button>
        </div>
      </div>
    </nav>
  )
}
