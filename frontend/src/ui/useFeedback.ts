import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../identity/api'

export type Feedback = {
  kind: 'error' | 'info' | 'success'
  text: string
  category?: 'transient' | 'validation' | 'blocking'
  reload?: boolean
}

export type FeedbackAttempt = (feedback: Feedback | null) => boolean
export const FEEDBACK_DURATION = 5_000

export function errorCategory(error: unknown): NonNullable<Feedback['category']> {
  if (!(error instanceof ApiError)) return 'transient'
  if (['BUSINESS_CONCURRENT_UPDATE', 'INVITATION_INVALID'].includes(error.code)) {
    return 'blocking'
  }
  if ([
    'VALIDATION_ERROR',
    'BUSINESS_SLUG_CONFLICT',
    'AUTH_FAILED',
    'CURRENT_PASSWORD_INVALID',
    'INVITATION_CREDENTIAL_MISMATCH',
  ].includes(error.code)) {
    return 'validation'
  }
  return 'transient'
}

// Context owners clear on navigation/operation changes; unmount also invalidates
// pending responses. A newer attempt cannot be overwritten by an older request.
export function useFeedback(context: string) {
  const [feedback, update] = useState<Feedback | null>(null)
  const generation = useRef(0)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const cancelTimer = useCallback(() => {
    clearTimeout(timer.current)
    timer.current = undefined
  }, [])

  const setFeedback = useCallback((next: Feedback | null) => {
    generation.current += 1
    cancelTimer()
    update(next)
    const previousFocus = document.activeElement
    if (next && !next.reload && (!next.category || next.category === 'transient')) {
      timer.current = setTimeout(() => {
        const focused = document.activeElement
        if (
          focused?.matches('.status-message') && focused.textContent === next.text &&
          previousFocus instanceof HTMLElement && previousFocus.isConnected
        ) {
          previousFocus.focus()
        }
        timer.current = undefined
        update(null)
      }, FEEDBACK_DURATION)
    }
  }, [cancelTimer])

  const beginFeedback = useCallback((): FeedbackAttempt => {
    setFeedback(null)
    const attempt = generation.current
    return (next) => {
      if (attempt !== generation.current) return false
      setFeedback(next)
      return true
    }
  }, [setFeedback])

  useEffect(() => {
    setFeedback(null)
    return () => {
      generation.current += 1
      cancelTimer()
    }
  }, [context, cancelTimer, setFeedback])

  return { feedback, setFeedback, beginFeedback }
}
