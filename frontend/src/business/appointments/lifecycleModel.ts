import { normalizeNote } from './model'
import type { LifecycleResult } from './lifecycleApi'

/**
 * The note as the backend would store it: line breaks to LF, edge white space removed, blank absent.
 * Both sides of every comparison go through this one function, so an authoritative value and an edited
 * one are compared as the backend compares them.
 */
export function canonicalNote(value: string | null): string | null {
  return value === null ? null : normalizeNote(value)
}

/** The editor is dirty exactly when the normalized edit differs from the authoritative note. */
export function isNoteDirty(draft: string, authoritative: string | null): boolean {
  return canonicalNote(draft) !== canonicalNote(authoritative)
}

function isObject(result: unknown): result is Record<string, unknown> {
  return typeof result === 'object' && result !== null
}

// A version that can be an acknowledgement of the request that was sent: a safe integer (never fractional,
// negative, infinite, or unsafe) strictly greater than the version the request carried.
function advancedVersion(value: unknown, expectedVersion: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value > expectedVersion
}

/**
 * A valid acknowledgement of the cancellation that was sent: the same Appointment, in the cancelled state,
 * at a version beyond the submitted one. Anything else on a 2xx is not evidence of success.
 */
export function isReadableCancellation(
  result: unknown,
  appointmentId: string,
  expectedVersion: number,
): result is LifecycleResult {
  return (
    isObject(result) &&
    result['id'] === appointmentId &&
    result['status'] === 'CANCELLED' &&
    advancedVersion(result['version'], expectedVersion)
  )
}

/**
 * A valid acknowledgement of the note edit that was sent: the same Appointment, a status the private
 * contract allows, a version beyond the submitted one, and exactly the canonical submitted note (an
 * explicit null for a cleared note). A response with the old or another note is contradictory.
 */
export function isReadableNoteResult(
  result: unknown,
  appointmentId: string,
  expectedVersion: number,
  submittedNote: string | null,
): result is LifecycleResult {
  return (
    isObject(result) &&
    result['id'] === appointmentId &&
    (result['status'] === 'CONFIRMED' || result['status'] === 'CANCELLED') &&
    advancedVersion(result['version'], expectedVersion) &&
    result['internalNote'] === canonicalNote(submittedNote)
  )
}
