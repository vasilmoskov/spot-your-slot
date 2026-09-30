import { describe, expect, it } from 'vitest'
import { RESERVED_BUSINESS_SLUGS } from '../reservedSlugs'
import { readPublicRoute } from './route'

const at = (pathname: string, search = '', hash = '') => readPublicRoute({ pathname, search, hash })

describe('readPublicRoute', () => {
  it('matches exactly one lowercase slug segment', () => {
    expect(at('/example-studio')).toEqual({ slug: 'example-studio', canonicalPath: null })
    expect(at('/a1-b2-c3')?.slug).toBe('a1-b2-c3')
  })

  it('canonicalizes an uppercase slug, keeping the query and hash', () => {
    expect(at('/Example-Studio', '?ref=x', '#top')).toEqual({
      slug: 'example-studio',
      canonicalPath: '/example-studio?ref=x#top',
    })
  })

  it('canonicalizes a trailing slash away', () => {
    expect(at('/example-studio/')).toEqual({
      slug: 'example-studio',
      canonicalPath: '/example-studio',
    })
    expect(at('/Example-Studio/')?.canonicalPath).toBe('/example-studio')
  })

  it('accepts a 100-character slug and rejects 101', () => {
    expect(at(`/${'a'.repeat(100)}`)?.slug).toHaveLength(100)
    expect(at(`/${'a'.repeat(101)}`)).toBeNull()
  })

  it.each([
    '/',
    '',
    '/-leading',
    '/trailing-',
    '/double--hyphen',
    '/under_score',
    '/with%20space',
    '/caf%C3%A9',
    '/%E2%84%AAelvin',
    '/dot.dot',
  ])('does not treat %s as a public page', (pathname) => {
    expect(at(pathname)).toBeNull()
  })

  it('never treats a deeper path as a Business slug', () => {
    expect(at('/example-studio/book')).toBeNull()
    expect(at('/example-studio//')).toBeNull()
    expect(at('//example-studio')).toBeNull()
    expect(at('/a/b/c')).toBeNull()
  })

  it.each([...RESERVED_BUSINESS_SLUGS])('leaves the reserved root /%s to the application', (root) => {
    expect(at(`/${root}`)).toBeNull()
    expect(at(`/${root}/`)).toBeNull()
    expect(at(`/${root.toUpperCase()}`)).toBeNull()
  })

  it('matches a slug that merely contains a reserved word', () => {
    for (const slug of ['salon-invitation', 'my-login', 'booking-studio', 'apiary', 'my-book']) {
      expect(at(`/${slug}`)?.slug).toBe(slug)
    }
  })

  it('never inspects the hash, so administration hash routes stay elsewhere', () => {
    expect(at('/', '', '#/business/services')).toBeNull()
    expect(at('/example-studio', '', '#/business/services')?.slug).toBe('example-studio')
  })
})
