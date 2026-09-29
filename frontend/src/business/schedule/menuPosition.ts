export type MenuPositionInput = {
  triggerLeft: number
  triggerRight: number
  triggerTop: number
  triggerBottom: number
  menuWidth: number
  menuHeight: number
  viewportWidth: number
  viewportHeight: number
  margin?: number
}

export type MenuPosition = {
  side: 'right' | 'left'
  top: number
  left: number
}

const DEFAULT_MARGIN = 8

/**
 * Collision-aware placement for the weekday overflow menu (and any other
 * viewport-anchored popup menu): prefers opening immediately to the right
 * of the trigger, aligned with its top edge, and flips to the left of the
 * trigger only when there is not enough horizontal viewport space —
 * regardless of which grid column (Monday through Sunday) the trigger sits
 * in. Vertically, prefers aligning with the trigger's top and flips above
 * the trigger when there is not enough room below. The final position is
 * always clamped so the menu stays fully within the viewport.
 *
 * Pure and framework-free so the placement decision itself — the part real
 * browser geometry cannot exercise reliably in jsdom — can be unit tested
 * deterministically; the caller supplies real `getBoundingClientRect()`
 * measurements.
 */
export function computeMenuPosition(input: MenuPositionInput): MenuPosition {
  const margin = input.margin ?? DEFAULT_MARGIN

  const fitsRight = input.triggerRight + input.menuWidth + margin <= input.viewportWidth
  const side: MenuPosition['side'] = fitsRight ? 'right' : 'left'
  const rawLeft = side === 'right' ? input.triggerRight : input.triggerLeft - input.menuWidth
  const maxLeft = Math.max(margin, input.viewportWidth - input.menuWidth - margin)
  const left = Math.min(Math.max(rawLeft, margin), maxLeft)

  const fitsBelow = input.triggerTop + input.menuHeight + margin <= input.viewportHeight
  const rawTop = fitsBelow ? input.triggerTop : input.triggerBottom - input.menuHeight
  const maxTop = Math.max(margin, input.viewportHeight - input.menuHeight - margin)
  const top = Math.min(Math.max(rawTop, margin), maxTop)

  return { side, top, left }
}
