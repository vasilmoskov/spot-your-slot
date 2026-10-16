package bg.spotyourslot.identity;

import java.util.UUID;

public interface SelectedBusinessOwnerAccess {
    Authorization authorize(UUID userId, UUID businessId);

    Authorization lockAndAuthorize(UUID userId, UUID businessId);

    /**
     * The same {@code FOR SHARE} Membership lock as {@link #lockAndAuthorize}, for Business-side
     * Appointment administration (ADR-0029). It requires the caller's transaction. A PostgreSQL
     * serialization failure or deadlock is reported as {@link ConcurrentConflict}, any other failure
     * as {@link Failure}; both are fixed, cause-free, and carry no SQL, SQLState, or identifier.
     */
    Authorization lockAndAuthorizeSanitized(UUID userId, UUID businessId);

    enum Authorization {
        GRANTED,
        DENIED
    }

    /** A serialization failure or deadlock; retryable only in a completely new transaction. */
    final class ConcurrentConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConcurrentConflict() {
            super("Owner Membership lock conflicted with a concurrent change", null, false, false);
        }
    }

    /** Any other failure; not retryable by this capability. */
    final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Failure() {
            super("Owner Membership lock failed", null, false, true);
        }
    }
}
