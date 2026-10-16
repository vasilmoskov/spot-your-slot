package bg.spotyourslot.business;

import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

public interface BusinessScheduleContextAccess {
    Optional<BusinessScheduleContext> findScheduleContext(UUID businessId);

    Optional<BusinessScheduleContext> lockScheduleContext(UUID businessId);

    /**
     * The same {@code FOR SHARE} lock as {@link #lockScheduleContext}, for Business-side Appointment
     * administration (ADR-0029). It requires the caller's transaction and never opens one. A
     * PostgreSQL serialization failure or deadlock is reported as {@link ConcurrentConflict}, any
     * other failure as {@link Failure}; both are fixed, cause-free, and carry no SQL, SQLState, or
     * identifier, so no persistence exception crosses this boundary.
     */
    Optional<BusinessScheduleContext> lockScheduleContextSanitized(UUID businessId);

    record BusinessScheduleContext(
            UUID businessId,
            LifecycleStatus status,
            ZoneId timezone) {
    }

    /** A serialization failure or deadlock; retryable only in a completely new transaction. */
    final class ConcurrentConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConcurrentConflict() {
            super("Business schedule context lock conflicted with a concurrent change", null, false, false);
        }
    }

    /** Any other failure; not retryable by this capability. */
    final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Failure() {
            super("Business schedule context lock failed", null, false, true);
        }
    }
}
