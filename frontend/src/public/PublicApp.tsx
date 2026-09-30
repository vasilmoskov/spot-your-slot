import { useEffect, useState } from 'react'
import { PublicBusinessPage } from './PublicBusinessPage'
import { readPublicRoute, type PublicRoute } from './route'

// Canonicalization replaces the history entry; a public page never pushes one,
// so Back can never return to the non-canonical spelling.
function canonicalRoute(): PublicRoute | null {
  const route = readPublicRoute()
  if (route?.canonicalPath) window.history.replaceState(window.history.state, '', route.canonicalPath)
  return route
}

/** The public application: selected at load, never links to administration. */
export function PublicApp() {
  const [route, setRoute] = useState<PublicRoute | null>(canonicalRoute)

  useEffect(() => {
    const onNavigate = () => {
      const next = canonicalRoute()
      // Only an entry of the other application can lead away from a public page;
      // it needs a fresh document, chosen again at load.
      if (!next) window.location.reload()
      else setRoute(next)
    }
    window.addEventListener('popstate', onNavigate)
    return () => window.removeEventListener('popstate', onNavigate)
  }, [])

  return route ? <PublicBusinessPage key={route.slug} slug={route.slug} /> : null
}
