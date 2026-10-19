import { describe, expect, it } from 'vitest'
import { ApiError } from '../../identity/api'
import {
  classifyLifecycleFailure,
  isBlocking,
  isUnresolved,
  lifecycleText,
  type LifecycleFailure,
} from './lifecycleErrors'
import { canonicalNote, isNoteDirty, isReadableCancellation, isReadableNoteResult } from './lifecycleModel'

const ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

describe('canonical note and the dirty rule', () => {
  it('compares the normalized edit with the authoritative note, in both directions', () => {
    expect(canonicalNote(null)).toBeNull()
    expect(canonicalNote('  \n ')).toBeNull()
    expect(canonicalNote(' a\r\nb ')).toBe('a\nb')
    expect(isNoteDirty('', null)).toBe(false)
    expect(isNoteDirty('   ', null)).toBe(false)
    expect(isNoteDirty('a', null)).toBe(true)
    expect(isNoteDirty('', 'a')).toBe(true)
    expect(isNoteDirty(' a ', 'a')).toBe(false)
    expect(isNoteDirty('a\r\nb', 'a\nb')).toBe(false)
    expect(isNoteDirty('a b', 'a  b')).toBe(true)
  })

  it('is independent of whether the edit could be submitted', () => {
    expect(isNoteDirty('я'.repeat(501), null)).toBe(true)
    expect(isNoteDirty('ред\u0000', null)).toBe(true)
  })
})

describe('readable successes', () => {
  const cancelOk = { id: ID, status: 'CANCELLED', version: 8, internalNote: null }
  const noteOk = { id: ID, status: 'CONFIRMED', version: 8, internalNote: 'a' }

  it('accepts a cancellation only for the same Appointment, cancelled, at an advanced safe-integer version', () => {
    expect(isReadableCancellation(cancelOk, ID, 7)).toBe(true)
    expect(isReadableCancellation({ ...cancelOk, status: 'CONFIRMED' }, ID, 7)).toBe(false)
    expect(isReadableCancellation({ ...cancelOk, id: 'x' }, ID, 7)).toBe(false)
    for (const version of [7, 6, 0, -1, 7.5, 8.2, NaN, Infinity, Number.MAX_SAFE_INTEGER + 2, '8', null, undefined]) {
      expect(isReadableCancellation({ ...cancelOk, version }, ID, 7)).toBe(false)
    }
    expect(isReadableCancellation({ id: ID, status: 'CANCELLED' }, ID, 7)).toBe(false)
    expect(isReadableCancellation(undefined, ID, 7)).toBe(false)
    expect(isReadableCancellation(null, ID, 7)).toBe(false)
    expect(isReadableCancellation('ok', ID, 7)).toBe(false)
  })

  it('accepts a note answer only for exactly the canonical submitted note at an advanced version', () => {
    expect(isReadableNoteResult(noteOk, ID, 7, 'a')).toBe(true)
    expect(isReadableNoteResult({ ...noteOk, status: 'CANCELLED' }, ID, 7, 'a')).toBe(true)
    expect(isReadableNoteResult({ ...noteOk, internalNote: null }, ID, 7, null)).toBe(true)
    expect(isReadableNoteResult({ ...noteOk, internalNote: null }, ID, 7, '  \n')).toBe(true)
    expect(isReadableNoteResult({ ...noteOk, internalNote: 'a\nb' }, ID, 7, ' a\r\nb ')).toBe(true)
    expect(isReadableNoteResult({ ...noteOk, internalNote: 'old' }, ID, 7, 'a')).toBe(false)
    expect(isReadableNoteResult({ ...noteOk, internalNote: 'b' }, ID, 7, 'a')).toBe(false)
    expect(isReadableNoteResult({ ...noteOk, internalNote: 'a' }, ID, 7, null)).toBe(false)
    expect(isReadableNoteResult({ ...noteOk, internalNote: null }, ID, 7, 'a')).toBe(false)
    expect(isReadableNoteResult({ ...noteOk, internalNote: undefined }, ID, 7, null)).toBe(false)
    expect(isReadableNoteResult({ ...noteOk, id: 'x' }, ID, 7, 'a')).toBe(false)
    expect(isReadableNoteResult({ ...noteOk, status: 'DELETED' }, ID, 7, 'a')).toBe(false)
    expect(isReadableNoteResult({ ...noteOk, status: undefined }, ID, 7, 'a')).toBe(false)
    for (const version of [7, 6, -1, 7.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 2, '8', undefined]) {
      expect(isReadableNoteResult({ ...noteOk, version }, ID, 7, 'a')).toBe(false)
    }
    expect(isReadableNoteResult('ok', ID, 7, 'a')).toBe(false)
  })
})

describe('failure classification', () => {
  const problem = (status: number, code: string) => new ApiError(status, code, 'x')

  it('maps every documented code to its own kind', () => {
    const table: [number, string, LifecycleFailure][] = [
      [400, 'VALIDATION_ERROR', 'validation'],
      [403, 'ACCESS_DENIED', 'access'],
      [403, 'ACTIVE_BUSINESS_REQUIRED', 'access'],
      [404, 'APPOINTMENT_NOT_FOUND', 'not-found'],
      [409, 'BUSINESS_NOT_ACTIVE', 'business-not-active'],
      [409, 'BUSINESS_SUSPENDED', 'business-suspended'],
      [409, 'APPOINTMENT_CONCURRENT_UPDATE', 'concurrent-update'],
      [409, 'APPOINTMENT_STATE_CONFLICT', 'state-conflict'],
      [503, 'APPOINTMENT_TEMPORARILY_UNAVAILABLE', 'temporarily-unavailable'],
      [503, 'APPOINTMENT_OUTCOME_UNCERTAIN', 'uncertain'],
    ]
    for (const [status, code, kind] of table) expect(classifyLifecycleFailure(problem(status, code))).toBe(kind)
  })

  it('treats a lost response, an unreadable success, an unknown code, and a 5xx as unresolved', () => {
    for (const error of [
      new TypeError('Failed to fetch'),
      problem(200, 'REQUEST_FAILED'),
      problem(409, 'NEW_CODE'),
      problem(500, 'INTERNAL_ERROR'),
      problem(502, 'REQUEST_FAILED'),
    ]) {
      expect(isUnresolved(classifyLifecycleFailure(error))).toBe(true)
    }
    for (const kind of ['validation', 'temporarily-unavailable', 'concurrent-update', 'state-conflict'] as const) {
      expect(isUnresolved(kind)).toBe(false)
    }
  })

  it('blocks only the access and lifecycle refusals', () => {
    expect(['access', 'business-not-active', 'business-suspended'].every((kind) => isBlocking(kind as LifecycleFailure))).toBe(true)
    expect(isBlocking('state-conflict')).toBe(false)
    expect(isBlocking('uncertain')).toBe(false)
  })

  it('never claims a success or a failure in an unresolved message', () => {
    for (const operation of ['cancel', 'note'] as const) {
      for (const kind of ['uncertain', 'unexpected'] as const) {
        expect(lifecycleText(operation, kind)).toMatch(/не е ясно/i)
        expect(lifecycleText(operation, kind)).not.toMatch(/не беше|е непроменен/)
      }
    }
  })
})
