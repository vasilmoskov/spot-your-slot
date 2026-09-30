import { isReservedBusinessSlug } from '../reservedSlugs'

// The exact public grammar of ADR-0018: one slug segment, lowercase letters and
// digits joined by single hyphens.
// Matched case-insensitively on the raw segment so that only ASCII letters are
// accepted (no Unicode letter can lowercase into the grammar).
const SLUG = /^[A-Za-z0-9]+(-[A-Za-z0-9]+)*$/
const MAX_SLUG_LENGTH = 100

export type PublicRoute = {
  // The canonical lowercase slug.
  slug: string
  // The location the page should show when the requested one is not canonical
  // (uppercase or a trailing slash); null when the URL is already canonical.
  canonicalPath: string | null
}

/**
 * Matches `/{slug}` exactly. Nothing else is a public page: the root, deeper
 * paths, reserved roots, invalid slugs and every path that merely contains a
 * reserved word all return null and stay with the existing application. The
 * hash is never inspected, so `/#/business/...` administration routes are
 * unaffected.
 */
export function readPublicRoute(
  location: Pick<Location, 'pathname' | 'search' | 'hash'> = window.location,
): PublicRoute | null {
  const match = /^\/([^/]+)\/?$/.exec(location.pathname)
  const segment = match?.[1]
  if (segment === undefined) return null
  if (segment.length > MAX_SLUG_LENGTH || !SLUG.test(segment)) return null
  const slug = segment.toLowerCase()
  if (isReservedBusinessSlug(slug)) return null
  const canonical = `/${slug}`
  return {
    slug,
    canonicalPath:
      location.pathname === canonical ? null : `${canonical}${location.search}${location.hash}`,
  }
}
