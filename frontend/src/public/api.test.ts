import { afterEach, describe, expect, it, vi } from 'vitest'
import { decodePublicProfile, fetchPublicBusinessProfile, PublicProfileLoadError } from './api'

const profileBody = {
  slug: 'example-studio',
  displayName: 'Примерно студио',
  businessType: 'HAIR_SALON',
  description: null,
  phone: '+359 88 000 0000',
  address: {
    city: 'София',
    postalCode: '1000',
    street: 'Примерна улица',
    streetNumber: '1',
    details: null,
  },
  services: [{ name: 'Примерна услуга', description: null, durationMinutes: 45, price: 25 }],
}

const unavailableBody = {
  detail: 'Страницата не е налична.',
  instance: '/api/public/businesses',
  status: 404,
  title: 'Заявката не може да бъде изпълнена.',
  code: 'BUSINESS_PAGE_UNAVAILABLE',
}

function respond(status: number, body: unknown) {
  return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status })
}

const fetchMock = vi.fn()

function useFetch(impl: (...args: unknown[]) => Promise<Response>) {
  fetchMock.mockReset()
  fetchMock.mockImplementation(impl)
  vi.stubGlobal('fetch', fetchMock)
}

afterEach(() => vi.unstubAllGlobals())

describe('fetchPublicBusinessProfile', () => {
  it('sends one credential-free GET without a CSRF token', async () => {
    useFetch(async () => respond(200, profileBody))
    await fetchPublicBusinessProfile('example-studio')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toMatch(/\/api\/public\/businesses\/example-studio$/)
    expect(init.method).toBe('GET')
    expect(init.credentials).toBe('omit')
    expect(init.body).toBeUndefined()
    expect(Object.keys(init.headers as Record<string, string>)).toEqual(['Accept'])
  })

  it('percent-encodes the slug into a single path segment', async () => {
    useFetch(async () => respond(404, unavailableBody))
    await fetchPublicBusinessProfile('a/b?c#d')
    expect((fetchMock.mock.calls[0] as [string])[0]).toMatch(/businesses\/a%2Fb%3Fc%23d$/)
  })

  it('decodes a profile', async () => {
    useFetch(async () => respond(200, profileBody))
    await expect(fetchPublicBusinessProfile('example-studio')).resolves.toEqual({
      kind: 'profile',
      profile: profileBody,
    })
  })

  it('decodes the revised contract in which each Service carries its public reference', async () => {
    const reference = '6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11'
    useFetch(async () =>
      respond(200, {
        ...profileBody,
        services: profileBody.services.map((service) => ({ ...service, id: reference })),
      }),
    )
    const result = await fetchPublicBusinessProfile('example-studio')
    // The page does not use the reference, so it is dropped and the decoded profile is unchanged.
    expect(result).toEqual({ kind: 'profile', profile: profileBody })
    expect(JSON.stringify(result)).not.toContain(reference)
  })

  it('keeps only the documented fields of a response', async () => {
    useFetch(async () =>
      respond(200, {
        ...profileBody,
        id: 'internal-id',
        status: 'ACTIVE',
        contactEmail: 'private@example.invalid',
        services: [{ ...profileBody.services[0], id: 's-1', active: true }],
      }),
    )
    const result = await fetchPublicBusinessProfile('example-studio')
    expect(JSON.stringify(result)).not.toMatch(/internal-id|ACTIVE|private@|s-1|active/)
  })

  it('reports the single unavailable answer', async () => {
    useFetch(async () => respond(404, unavailableBody))
    await expect(fetchPublicBusinessProfile('gone')).resolves.toEqual({ kind: 'unavailable' })
  })

  it.each([
    ['a 404 that is not the unavailable problem', 404, { code: 'SOMETHING_ELSE' }],
    ['a 404 without a body', 404, ''],
    ['a server error', 500, { code: 'INTERNAL_ERROR', detail: 'boom' }],
    ['a 200 with invalid JSON', 200, '<html>'],
    ['a 200 that is not the contract', 200, { hello: 'world' }],
    ['a 200 with a malformed service', 200, { ...profileBody, services: [{ name: 1 }] }],
    ['a 200 with a malformed address', 200, { ...profileBody, address: { city: 5 } }],
    ['a 200 with a blank name', 200, { ...profileBody, displayName: ' ' }],
    ['a 200 array', 200, []],
  ])('treats %s as a load failure without leaking detail', async (_name, status, body) => {
    useFetch(async () => respond(status, body))
    const failure = await fetchPublicBusinessProfile('x').catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(PublicProfileLoadError)
    expect((failure as Error).message).not.toMatch(/boom|SOMETHING_ELSE|INTERNAL/)
  })

  it('maps a network failure to a load failure', async () => {
    useFetch(async () => {
      throw new TypeError('Failed to fetch')
    })
    await expect(fetchPublicBusinessProfile('x')).rejects.toBeInstanceOf(PublicProfileLoadError)
  })

  it('rejects with the abort error when the request was aborted', async () => {
    const controller = new AbortController()
    useFetch(
      (_url, init) =>
        new Promise((_resolve, reject) => {
          ;(init as RequestInit).signal?.addEventListener('abort', () =>
            reject(new DOMException('Aborted', 'AbortError')),
          )
        }),
    )
    const pending = fetchPublicBusinessProfile('x', controller.signal)
    controller.abort()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })
})

describe('decodePublicProfile', () => {
  it('accepts an empty Service list and an absent address', () => {
    expect(decodePublicProfile({ ...profileBody, address: null, services: [] })).toMatchObject({
      address: null,
      services: [],
    })
  })
})
