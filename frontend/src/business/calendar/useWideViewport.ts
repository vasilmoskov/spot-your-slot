import { useEffect, useState } from 'react'

// The positional day grid needs room; below this width the same data is shown as a chronological list.
export const WIDE_VIEWPORT_QUERY = '(min-width: 900px)'

function matches(): boolean {
  if (typeof window.matchMedia !== 'function') return true
  return window.matchMedia(WIDE_VIEWPORT_QUERY).matches
}

export function useWideViewport(): boolean {
  const [wide, setWide] = useState(matches)
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const query = window.matchMedia(WIDE_VIEWPORT_QUERY)
    const update = () => setWide(query.matches)
    update()
    query.addEventListener('change', update)
    return () => query.removeEventListener('change', update)
  }, [])
  return wide
}
