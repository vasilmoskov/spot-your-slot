import { useCallback, useEffect, useRef, useState } from 'react'
import { Button } from '../../ui/Button'
import { useUnsavedChangesGuard } from '../../ui/UnsavedChangesGuard'
import type { StaffMemberSummary } from '../staff/api'
import { loadEveryStaffMember } from '../staff/fullCatalog'
import { isAuthenticationRequired } from '../staff/errors'
import { WorkingScheduleEditor } from './WorkingScheduleEditor'

type StaffWorkingScheduleProps = {
  readOnly: boolean
  onAuthenticationRequired: (detail: string) => void
}

export function StaffWorkingSchedule({ readOnly, onAuthenticationRequired }: StaffWorkingScheduleProps) {
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [staffMembers, setStaffMembers] = useState<StaffMemberSummary[]>([])
  const [selectedStaffMemberId, setSelectedStaffMemberId] = useState<string | null>(null)
  const activeLoad = useRef<AbortController | null>(null)
  const guard = useUnsavedChangesGuard()

  const load = useCallback(async () => {
    activeLoad.current?.abort()
    const controller = new AbortController()
    activeLoad.current = controller
    setLoading(true)
    setLoadError(null)
    try {
      const everyStaffMember = await loadEveryStaffMember(controller.signal)
      if (controller.signal.aborted || activeLoad.current !== controller) return
      setStaffMembers(everyStaffMember)
      setSelectedStaffMemberId((current) =>
        current && everyStaffMember.some((staffMember) => staffMember.id === current)
          ? current
          : (everyStaffMember[0]?.id ?? null),
      )
    } catch (caught) {
      if (controller.signal.aborted || activeLoad.current !== controller) return
      if (isAuthenticationRequired(caught)) {
        onAuthenticationRequired(caught.detail)
        return
      }
      setLoadError('Списъкът с екипа не може да бъде зареден.')
    } finally {
      if (activeLoad.current === controller) {
        activeLoad.current = null
        setLoading(false)
      }
    }
  }, [onAuthenticationRequired])

  useEffect(() => {
    void load()
    return () => {
      const controller = activeLoad.current
      activeLoad.current = null
      controller?.abort()
    }
  }, [load])

  const selectStaffMember = (staffMemberId: string) => {
    guard.guard(() => setSelectedStaffMemberId(staffMemberId))
  }

  if (loading) {
    return (
      <div className="platform-content" aria-live="polite" aria-busy="true">
        <p className="business-list-state">Зареждане на екипа…</p>
      </div>
    )
  }

  if (loadError) {
    return (
      <div className="platform-content">
        <div className="feedback-action-layout">
          <div className="status-message status-error" role="alert">
            <p>{loadError}</p>
          </div>
          <Button type="button" variant="secondary" onClick={() => void load()}>
            Опитай отново
          </Button>
        </div>
      </div>
    )
  }

  if (staffMembers.length === 0) {
    return (
      <div className="platform-content">
        <p className="business-list-state">
          Все още няма добавени членове на екипа. Работният график може да бъде управляван, след
          като добавите член на екипа.
        </p>
      </div>
    )
  }

  // staffMembers is confirmed non-empty above, so a fallback to the first
  // entry (when no id matches, e.g. right after a Business switch) is safe.
  const selected =
    staffMembers.find((staffMember) => staffMember.id === selectedStaffMemberId) ?? staffMembers[0]!

  return (
    <div className="platform-content">
      <div className="business-list-content">
        <div className="schedule-staff-selector">
          <label htmlFor="schedule-staff-member">Член на екипа</label>
          <select
            id="schedule-staff-member"
            value={selected.id}
            onChange={(event) => selectStaffMember(event.target.value)}
          >
            {staffMembers.map((staffMember) => (
              <option key={staffMember.id} value={staffMember.id}>
                {staffMember.displayName}
                {staffMember.active ? '' : ' (неактивен)'}
              </option>
            ))}
          </select>
        </div>
        <WorkingScheduleEditor
          key={selected.id}
          staffMemberId={selected.id}
          staffMemberActive={selected.active}
          readOnly={readOnly}
          onAuthenticationRequired={onAuthenticationRequired}
        />
      </div>
    </div>
  )
}
