import { useUnsavedChangesGuard } from '../ui/UnsavedChangesGuard'

// Switching the Business discards whatever form is open, so it goes through the shared
// unsaved-changes guard like every other discarding transition.
export function useGuardedBusinessAction(action: (businessId: string) => void) {
  const guard = useUnsavedChangesGuard()
  return (businessId: string) => guard.guard(() => action(businessId))
}
