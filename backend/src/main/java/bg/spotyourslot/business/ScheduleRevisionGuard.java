package bg.spotyourslot.business;

import java.util.UUID;

/**
 * Narrow published contract that lets booking coordinate with schedule mutations (ADR-0025). It
 * locks the Business's schedule revision row {@code FOR SHARE} and holds the lock until the caller's
 * transaction ends. Shared locks do not conflict with each other, so bookings never block each
 * other; an in-flight {@link ScheduleRevisionBump} makes the caller wait, and a bump that committed
 * after the caller's snapshot makes the lock fail with a serialization failure.
 *
 * <p>The guarantee exists only inside a snapshot, so the operation requires a caller-owned
 * transaction that was started repeatable-read or serializable (the same rule as the availability
 * query); a missing transaction, an unexposed isolation level, or a weaker level fails before any
 * statement. It never opens a transaction.
 *
 * <p>Failures are thrown, never returned, and mark the caller's transaction rollback-only. A
 * {@link ScheduleRevisionConcurrentConflict} may be retried only in a completely new transaction.
 */
public interface ScheduleRevisionGuard {
    /**
     * @return the revision number the caller now holds protected
     */
    long lockShared(UUID businessId);
}
