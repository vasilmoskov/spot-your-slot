import { useCallback, useEffect, useRef } from 'react'

// Browser history for the in-memory steps of the guest-booking journey (ADR-0026). Each step adds one
// history entry whose state is only a step marker: a random per-journey label and the step ordinal.
// It never carries a choice, a Customer detail, a note or an attempt ID, and the URL is never
// changed. A refresh starts a new journey, so the marker of an older document matches nothing.
//
// The entry index always equals the step ordinal: the profile is entry 0, the first step entry 1.

type Marker = { spyBooking: { journey: string; step: number } }

/** A random label that tells this journey's entries from any other document's. Not a secret. */
export function newJourneyId(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function marker(journey: string, step: number): Marker {
  return { spyBooking: { journey, step } }
}

export function pushJourneyEntry(journey: string, step: number): void {
  window.history.pushState(marker(journey, step), '')
}

/** The step of a history state that belongs to `journey`, or null for any other entry. */
export function journeyStepOf(state: unknown, journey: string): number | null {
  if (typeof state !== 'object' || state === null) return null
  const candidate = (state as Partial<Marker>).spyBooking
  if (typeof candidate !== 'object' || candidate === null) return null
  const { journey: label, step } = candidate as { journey?: unknown; step?: unknown }
  return label === journey && typeof step === 'number' && Number.isInteger(step) ? step : null
}

export type PopVerdict = 'accept' | 'restore'

type Options = {
  journey: string
  // The step now shown; read at event time so a stale closure can never decide.
  step: number
  // May the journey be at `target` now? A step that is not reachable or that is frozen is restored.
  verdict: (target: number) => PopVerdict
  onStep: (step: number) => void
  // History left the journey backwards (the profile entry). `restore` puts the shown entry back.
  onLeft: (restore: () => void) => void
}

/**
 * Keeps the journey's steps and the browser's entries in step. Moving forward is `advance`; moving
 * back, in the page or with the browser, is a history traversal that ends in `onStep`. A traversal
 * the journey refuses is undone with `history.go`, and that undo is not treated as navigation.
 */
export function useJourneyHistory({ journey, step, verdict, onStep, onLeft }: Options) {
  const latest = useRef({ step, verdict, onStep, onLeft })
  latest.current = { step, verdict, onStep, onLeft }
  // Traversals that this hook started itself and must not interpret.
  const ignored = useRef(0)
  // The highest entry index that exists. Entries beyond the shown step are forward history, which a
  // push removes; keeping it lets a return to an earlier-visited step go forward instead of pushing.
  const top = useRef(step)

  useEffect(() => {
    const onPop = (event: PopStateEvent) => {
      if (ignored.current > 0) {
        ignored.current -= 1
        return
      }
      const { step: current, verdict: decide, onStep: setStep, onLeft: left } = latest.current
      const target = journeyStepOf(event.state, journey)
      if (target === null) {
        left(() => {
          ignored.current += 1
          window.history.go(current)
        })
        return
      }
      if (target === current) return
      if (decide(target) === 'accept') {
        setStep(target)
      } else {
        ignored.current += 1
        window.history.go(current - target)
      }
    }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [journey])

  const advance = useCallback(
    (next: number) => {
      pushJourneyEntry(journey, next)
      top.current = next
      latest.current.onStep(next)
    },
    [journey],
  )

  // Moves to `target` keeping the entry index equal to the step ordinal. An earlier step is a history
  // traversal back, a step that still has its forward entry a traversal forward (so the browser's
  // Back and Forward stay coherent), and any other later step pushes the entries up to it.
  const goTo = useCallback(
    (target: number) => {
      const current = latest.current.step
      if (target === current) return
      if (target <= top.current) {
        window.history.go(target - current)
        return
      }
      for (let entry = current + 1; entry <= target; entry += 1) pushJourneyEntry(journey, entry)
      top.current = target
      latest.current.onStep(target)
    },
    [journey],
  )

  const back = useCallback(() => window.history.back(), [])

  // Leaves the journey from the shown step: the entries are rewound to the profile without the
  // journey interpreting the traversal.
  const rewindToProfile = useCallback(() => {
    ignored.current += 1
    window.history.go(-latest.current.step)
  }, [])

  return { advance, goTo, back, rewindToProfile }
}
