import { expect, test, type Request } from '@playwright/test'
import { API_ORIGIN } from './support/environment'
import { recordApiRequests, type AuditablePage } from './support/publicProfile'

// Deterministic checks of the request audit itself. A fake page and fake requests replace the
// browser, so every ordering (a header read still pending, a read that fails because the context
// closed, a request the page aborted) is produced on purpose, with no timer and no real network.

type Listener = (request: Request) => void

class FakePage implements AuditablePage {
  private readonly listeners = new Map<string, Set<Listener>>()

  on(event: string, listener: Listener): this {
    const set = this.listeners.get(event) ?? new Set<Listener>()
    set.add(listener)
    this.listeners.set(event, set)
    return this
  }

  off(event: string, listener: Listener): this {
    this.listeners.get(event)?.delete(listener)
    return this
  }

  listenerCount(): number {
    return [...this.listeners.values()].reduce((sum, set) => sum + set.size, 0)
  }

  emit(event: string, request: Request): void {
    for (const listener of [...(this.listeners.get(event) ?? [])]) listener(request)
  }
}

type FakeOptions = {
  url?: string
  method?: string
  headers: Promise<Record<string, string>>
  failure?: string
}

function fakeRequest(options: FakeOptions): Request {
  return {
    url: () => options.url ?? `${API_ORIGIN}/api/public/businesses/example`,
    method: () => options.method ?? 'GET',
    allHeaders: () => options.headers,
    failure: () => (options.failure ? { errorText: options.failure } : null),
  } as unknown as Request
}

function deferredHeaders() {
  let resolve!: (headers: Record<string, string>) => void
  let reject!: (error: Error) => void
  const promise = new Promise<Record<string, string>>((ok, fail) => {
    resolve = ok
    reject = fail
  })
  return { promise, resolve, reject }
}

const CLOSED = 'request.allHeaders: Target page, context or browser has been closed'

test.describe('API request audit', () => {
  test('waits for a pending header read before it reports the cookie flag', async () => {
    const page = new FakePage()
    const audit = recordApiRequests(page)
    const read = deferredHeaders()
    page.emit('request', fakeRequest({ headers: read.promise }))

    let stopped = false
    const result = audit.stop().then((requests) => {
      stopped = true
      return requests
    })
    // Let every already-queued microtask run: stop() must still be waiting for the read.
    await Promise.resolve()
    await Promise.resolve()
    expect(stopped).toBe(false)

    read.resolve({ cookie: 'SPOTYOURSESSION=value' })
    expect(await result).toEqual([
      { method: 'GET', path: '/api/public/businesses/example', hasCookie: true },
    ])
  })

  test('reports a request without a Cookie header as having sent none', async () => {
    const page = new FakePage()
    const audit = recordApiRequests(page)
    page.emit('request', fakeRequest({ headers: Promise.resolve({ accept: '*/*' }) }))
    expect(await audit.stop()).toEqual([
      { method: 'GET', path: '/api/public/businesses/example', hasCookie: false },
    ])
  })

  test('surfaces a failed header read instead of treating it as "no cookie was sent"', async () => {
    const page = new FakePage()
    const audit = recordApiRequests(page)
    page.emit('request', fakeRequest({ headers: Promise.reject(new Error(CLOSED)) }))
    await expect(audit.stop()).rejects.toThrow(
      /could not read request headers \(GET \/api\/public\/businesses\/example: request\.allHeaders: Target page/,
    )
  })

  test('drops a request the page aborted, even when its header read fails', async () => {
    const page = new FakePage()
    const audit = recordApiRequests(page)
    const aborted = fakeRequest({
      headers: Promise.reject(new Error(CLOSED)),
      failure: 'net::ERR_ABORTED',
    })
    page.emit('request', aborted)
    page.emit('requestfailed', aborted)
    page.emit('request', fakeRequest({ headers: Promise.resolve({}) }))
    expect(await audit.stop()).toEqual([
      { method: 'GET', path: '/api/public/businesses/example', hasCookie: false },
    ])
  })

  test('keeps a request that failed for another reason and judges it by its headers', async () => {
    const page = new FakePage()
    const audit = recordApiRequests(page)
    const refused = fakeRequest({
      headers: Promise.resolve({ cookie: 'a=b' }),
      failure: 'net::ERR_CONNECTION_REFUSED',
    })
    page.emit('request', refused)
    page.emit('requestfailed', refused)
    expect(await audit.stop()).toEqual([
      { method: 'GET', path: '/api/public/businesses/example', hasCookie: true },
    ])
  })

  test('ignores other origins and stops listening once stopped', async () => {
    const page = new FakePage()
    const audit = recordApiRequests(page)
    expect(page.listenerCount()).toBe(2)
    page.emit(
      'request',
      fakeRequest({ url: 'http://example.invalid/api/x', headers: Promise.resolve({}) }),
    )
    const first = await audit.stop()
    expect(first).toEqual([])
    expect(page.listenerCount()).toBe(0)

    page.emit('request', fakeRequest({ headers: Promise.resolve({}) }))
    expect(await audit.stop()).toBe(first)
  })

  test('a header read that fails after stop() has settled never becomes an unhandled rejection', async () => {
    const page = new FakePage()
    const audit = recordApiRequests(page)
    const read = deferredHeaders()
    page.emit('request', fakeRequest({ headers: read.promise }))
    const unhandled: unknown[] = []
    const record = (reason: unknown): void => {
      unhandled.push(reason)
    }
    process.on('unhandledRejection', record)
    try {
      const result = audit.stop()
      read.reject(new Error(CLOSED))
      await expect(result).rejects.toThrow(/could not read request headers/)
      // Give the runtime one macrotask turn to report any unhandled rejection that exists.
      await new Promise<void>((resolve) => setImmediate(resolve))
      expect(unhandled).toEqual([])
    } finally {
      process.off('unhandledRejection', record)
    }
  })
})
