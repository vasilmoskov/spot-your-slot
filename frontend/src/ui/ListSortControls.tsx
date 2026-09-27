import type { ListPageSize, ListSortDirection } from '../navigation'
import { LIST_PAGE_SIZES } from '../navigation'

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
