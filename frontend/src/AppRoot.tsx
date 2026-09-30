import { useState } from 'react'
import { App } from './App'
import { PublicApp } from './public/PublicApp'
import { readPublicRoute } from './public/route'

/**
 * Chooses the application once, at load: an exact `/{slug}` path opens the
 * unauthenticated public Business page, everything else the existing
 * application. The two never link to each other, so crossing is a full load.
 */
export function AppRoot() {
  const [isPublic] = useState(() => readPublicRoute() !== null)
  return isPublic ? <PublicApp /> : <App />
}
