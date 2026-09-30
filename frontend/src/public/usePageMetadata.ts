import { useEffect } from 'react'

export type PageMetadata = {
  title: string
  description?: string
  canonicalUrl?: string
  noindex?: boolean
}

// Copies the previous state of one head element and puts it back, or removes
// the element again when this hook had to create it.
function setHeadElement(
  selector: string,
  create: () => HTMLElement,
  attribute: string,
  value: string,
): () => void {
  const existing = document.head.querySelector<HTMLElement>(selector)
  const element = existing ?? create()
  const previous = existing?.getAttribute(attribute) ?? null
  if (!existing) document.head.appendChild(element)
  element.setAttribute(attribute, value)
  return () => {
    if (!existing) {
      element.remove()
    } else if (previous === null) {
      element.removeAttribute(attribute)
    } else {
      element.setAttribute(attribute, previous)
    }
  }
}

function meta(name: string): () => HTMLElement {
  return () => {
    const element = document.createElement('meta')
    element.setAttribute('name', name)
    return element
  }
}

/**
 * Applies the title, description, canonical URL and robots hint of the current
 * public page (task 06a) and restores the shell defaults when the metadata
 * changes or the page unmounts. Values are only ever written through DOM
 * attributes and `document.title`, never as markup. Pass null to keep the
 * defaults, for example while a page is loading.
 *
 * The page is client-rendered: crawlers and link scrapers that do not run
 * JavaScript keep seeing the static shell metadata.
 */
export function usePageMetadata(metadata: PageMetadata | null): void {
  const title = metadata?.title
  const description = metadata?.description
  const canonicalUrl = metadata?.canonicalUrl
  const noindex = metadata?.noindex === true

  useEffect(() => {
    if (title === undefined) return undefined
    const restorers: (() => void)[] = []
    const previousTitle = document.title
    document.title = title
    restorers.push(() => {
      document.title = previousTitle
    })
    if (description !== undefined) {
      restorers.push(
        setHeadElement('meta[name="description"]', meta('description'), 'content', description),
      )
    }
    if (canonicalUrl !== undefined) {
      restorers.push(
        setHeadElement(
          'link[rel="canonical"]',
          () => {
            const element = document.createElement('link')
            element.setAttribute('rel', 'canonical')
            return element
          },
          'href',
          canonicalUrl,
        ),
      )
    }
    if (noindex) {
      restorers.push(setHeadElement('meta[name="robots"]', meta('robots'), 'content', 'noindex'))
    }
    return () => {
      for (const restore of restorers.reverse()) restore()
    }
  }, [title, description, canonicalUrl, noindex])
}
