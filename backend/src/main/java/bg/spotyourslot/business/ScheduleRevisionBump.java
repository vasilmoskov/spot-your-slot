package bg.spotyourslot.business;

import java.util.UUID;

/**
 * Narrow published contract for the modules that change availability-affecting schedule data
 * (ADR-0025). It advances the Business's schedule revision, which takes the revision row's exclusive
 * lock until the caller's transaction ends and creates a new row version.
 *
 * <p>The operation requires a caller-owned transaction and never opens one, so the revision change
 * commits or rolls back together with the schedule write it accompanies. The caller invokes it only
 * for a mutation it has already accepted, after the StaffMember lock (when the mutation has one) and
 * before the aggregate mutation; it must never call it speculatively. It is never upgraded from
 * {@link ScheduleRevisionGuard#lockShared} in the same transaction.
 *
 * <p>Failures are thrown, never returned, and mark the caller's transaction rollback-only:
 * {@link ScheduleRevisionConcurrentConflict} for a deadlock or serialization victim and
 * {@link ScheduleRevisionFailure} for everything else, including a Business without a revision row.
 */
public interface ScheduleRevisionBump {
    /**
     * @return the new revision number
     */
    long advance(UUID businessId);
}
