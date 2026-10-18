import { describe, expect, it } from 'vitest'
import {
  buildBody,
  firstIncomplete,
  initialProposal,
  isDirty,
  isEffective,
  reduce,
  type Original,
  type Proposal,
} from './rescheduleModel'

const CURRENT = '11111111-1111-4111-8111-111111111111'
const OTHER = '22222222-2222-4222-8222-222222222222'
const original: Original = { staffMemberId: CURRENT, start: '2026-11-10T09:00:00+02:00', date: '2026-11-10', version: 7 }
const slot = (start: string) => ({ start, end: start })

function proposal(overrides: Partial<Proposal> = {}): Proposal {
  return { ...initialProposal(original, true), ...overrides }
}

describe('the starting proposal', () => {
  it('keeps the current StaffMember and the original date, with nothing else chosen', () => {
    expect(initialProposal(original, true)).toEqual({
      step: 'staff',
      fromReview: false,
      staffMemberId: CURRENT,
      date: '2026-11-10',
      start: null,
    })
  })

  it('chooses nobody when the current StaffMember cannot be kept', () => {
    expect(initialProposal(original, false).staffMemberId).toBeNull()
  })

  it('is not a change by itself', () => {
    const start = initialProposal(original, true)
    expect(isDirty(start, start)).toBe(false)
    expect(isEffective(start, original)).toBe(false)
  })
})

describe('upstream choices invalidate the start', () => {
  const chosen = proposal({ start: slot('2026-11-10T10:00:00+02:00') })

  it('a changed StaffMember clears the start but keeps the date', () => {
    const changed = reduce(chosen, { type: 'staff', staffMemberId: OTHER })
    expect(changed.start).toBeNull()
    expect(changed.date).toBe('2026-11-10')
  })

  it('a changed date clears the start', () => {
    expect(reduce(chosen, { type: 'date', date: '2026-11-11' }).start).toBeNull()
  })

  it('choosing the same StaffMember or the same date again keeps the start', () => {
    expect(reduce(chosen, { type: 'staff', staffMemberId: CURRENT })).toBe(chosen)
    expect(reduce(chosen, { type: 'date', date: '2026-11-10' })).toBe(chosen)
  })
})

describe('the steps', () => {
  it('walk forward and back, and an edit from the review returns to it when nothing is missing', () => {
    let state = proposal({ start: slot('2026-11-10T10:00:00+02:00') })
    state = reduce(state, { type: 'advance' })
    expect(state.step).toBe('datetime')
    state = reduce(state, { type: 'advance' })
    expect(state.step).toBe('review')
    state = reduce(state, { type: 'go', step: 'staff', fromReview: true })
    expect(reduce(state, { type: 'advance' }).step).toBe('review')
    expect(reduce(reduce(state, { type: 'staff', staffMemberId: OTHER }), { type: 'advance' }).step).toBe('datetime')
    expect(reduce(proposal({ step: 'datetime' }), { type: 'back' }).step).toBe('staff')
    expect(reduce(proposal(), { type: 'back' }).step).toBe('staff')
  })

  it('the first incomplete step is the StaffMember, then the start', () => {
    expect(firstIncomplete(proposal({ staffMemberId: null }))).toBe('staff')
    expect(firstIncomplete(proposal())).toBe('datetime')
    expect(firstIncomplete(proposal({ start: slot('2026-11-10T10:00:00+02:00') }))).toBe('review')
  })
})

describe('an effective change', () => {
  it('needs another start or another StaffMember, and always a chosen start', () => {
    expect(isEffective(proposal({ start: slot('2026-11-10T10:00:00+02:00') }), original)).toBe(true)
    expect(isEffective(proposal({ staffMemberId: OTHER, start: slot(original.start) }), original)).toBe(true)
    expect(isEffective(proposal({ staffMemberId: OTHER }), original)).toBe(false)
    expect(isEffective(proposal({ staffMemberId: null, start: slot('2026-11-10T10:00:00+02:00') }), original)).toBe(false)
  })

  it('the same StaffMember at the same instant is a no-op, however the instant is spelled', () => {
    expect(isEffective(proposal({ start: slot(original.start) }), original)).toBe(false)
    expect(isEffective(proposal({ start: slot('2026-11-10T07:00:00Z') }), original)).toBe(false)
    expect(isEffective(proposal({ start: slot('2026-11-10T07:00:00.000Z') }), original)).toBe(false)
  })

  it('the other occurrence of a repeated local hour is a different instant', () => {
    const repeated: Original = { ...original, start: '2026-10-25T03:30:00+03:00' }
    expect(isEffective(proposal({ start: slot('2026-10-25T03:30:00+02:00') }), repeated)).toBe(true)
  })
})

describe('the request body', () => {
  it('always carries the version of the loaded Appointment and only what changes', () => {
    expect(buildBody(proposal({ start: slot('2026-11-10T10:00:00+02:00') }), original)).toEqual({
      expectedVersion: 7,
      start: '2026-11-10T10:00:00+02:00',
    })
    expect(buildBody(proposal({ staffMemberId: OTHER, start: slot(original.start) }), original)).toEqual({
      expectedVersion: 7,
      staffMemberId: OTHER,
    })
    expect(buildBody(proposal({ staffMemberId: OTHER, start: slot('2026-11-10T10:00:00+02:00') }), original)).toEqual({
      expectedVersion: 7,
      staffMemberId: OTHER,
      start: '2026-11-10T10:00:00+02:00',
    })
  })

  it('never names the current StaffMember or the unchanged start', () => {
    const body = buildBody(proposal({ start: slot('2026-11-10T10:00:00+02:00') }), original)
    expect(Object.keys(body)).not.toContain('staffMemberId')
    expect(Object.keys(buildBody(proposal({ staffMemberId: OTHER, start: slot(original.start) }), original))).not.toContain('start')
  })
})

describe('the dirty state is defined against the original Appointment', () => {
  const baseline = initialProposal(original, true)

  it('is dirty for any retained choice that differs, whether or not the proposal is complete', () => {
    expect(isDirty(proposal({ staffMemberId: OTHER }), baseline)).toBe(true)
    expect(isDirty(proposal({ date: '2026-11-11' }), baseline)).toBe(true)
    expect(isDirty(proposal({ staffMemberId: OTHER, date: '2026-11-11', start: null }), baseline)).toBe(true)
    expect(isDirty(proposal({ start: slot('2026-11-10T10:00:00+02:00') }), baseline)).toBe(true)
  })

  it('is clean again only when no changed value remains', () => {
    const changed = proposal({ staffMemberId: OTHER, date: '2026-11-11' })
    const staffBack = reduce(changed, { type: 'staff', staffMemberId: CURRENT })
    expect(isDirty(staffBack, baseline)).toBe(true) // the date still differs
    expect(isDirty(reduce(staffBack, { type: 'date', date: '2026-11-10' }), baseline)).toBe(false)
    const dateBack = reduce(changed, { type: 'date', date: '2026-11-10' })
    expect(isDirty(dateBack, baseline)).toBe(true) // the StaffMember still differs
  })

  it('a cleared StaffMember or date is not a retained choice', () => {
    expect(isDirty(proposal({ staffMemberId: null }), baseline)).toBe(false)
    expect(isDirty(proposal({ staffMemberId: null, date: '2026-11-11' }), baseline)).toBe(true)
    expect(isDirty(proposal({ date: '' }), baseline)).toBe(false)
    expect(isDirty(proposal({ staffMemberId: OTHER, date: '' }), baseline)).toBe(true)
  })

  it('a baseline without a StaffMember (the current one cannot be kept) is clean until one is chosen', () => {
    const unavailable = initialProposal(original, false)
    expect(isDirty(unavailable, unavailable)).toBe(false)
    expect(isDirty(reduce(unavailable, { type: 'staff', staffMemberId: OTHER }), unavailable)).toBe(true)
  })
})
