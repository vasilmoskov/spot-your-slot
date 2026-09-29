import { describe, expect, it } from 'vitest'
import { computeMenuPosition } from './menuPosition'

const VIEWPORT = { viewportWidth: 1024, viewportHeight: 768 }

describe('computeMenuPosition', () => {
  it('prefers opening to the right, aligned with the trigger top, when there is enough space', () => {
    const result = computeMenuPosition({
      triggerLeft: 100,
      triggerRight: 130,
      triggerTop: 200,
      triggerBottom: 228,
      menuWidth: 160,
      menuHeight: 90,
      ...VIEWPORT,
    })
    expect(result.side).toBe('right')
    expect(result.left).toBe(130)
    expect(result.top).toBe(200)
  })

  it('flips to the left of the trigger when the right side does not fit (e.g. the last weekday column)', () => {
    const result = computeMenuPosition({
      triggerLeft: 980,
      triggerRight: 1010,
      triggerTop: 200,
      triggerBottom: 228,
      menuWidth: 160,
      menuHeight: 90,
      ...VIEWPORT,
    })
    expect(result.side).toBe('left')
    expect(result.left).toBe(980 - 160)
    expect(result.top).toBe(200)
  })

  it('opens to the right for a trigger near the left edge (e.g. the first weekday column)', () => {
    const result = computeMenuPosition({
      triggerLeft: 8,
      triggerRight: 38,
      triggerTop: 200,
      triggerBottom: 228,
      menuWidth: 160,
      menuHeight: 90,
      ...VIEWPORT,
    })
    expect(result.side).toBe('right')
    expect(result.left).toBe(38)
  })

  it('flips above the trigger when there is not enough room below', () => {
    const result = computeMenuPosition({
      triggerLeft: 100,
      triggerRight: 130,
      triggerTop: 700,
      triggerBottom: 728,
      menuWidth: 160,
      menuHeight: 90,
      ...VIEWPORT,
    })
    expect(result.top).toBe(728 - 90)
  })

  it('clamps the final position to stay fully within a narrow viewport', () => {
    const result = computeMenuPosition({
      triggerLeft: 340,
      triggerRight: 360,
      triggerTop: 50,
      triggerBottom: 78,
      menuWidth: 220,
      menuHeight: 90,
      viewportWidth: 375,
      viewportHeight: 812,
    })
    expect(result.left).toBeGreaterThanOrEqual(0)
    expect(result.left + 220).toBeLessThanOrEqual(375)
  })

  it('respects a custom margin', () => {
    const result = computeMenuPosition({
      triggerLeft: 100,
      triggerRight: 130,
      triggerTop: 200,
      triggerBottom: 228,
      menuWidth: 160,
      menuHeight: 90,
      viewportWidth: 500,
      viewportHeight: 768,
      margin: 20,
    })
    // 130 + 160 + 20 = 310 <= 500, still fits right.
    expect(result.side).toBe('right')
    expect(result.left).toBe(130)
  })
})
