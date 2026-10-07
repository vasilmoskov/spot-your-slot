package bg.spotyourslot.business;

import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow published contract for the first step of a guest booking attempt (ADR-0023): it resolves a
 * Business by its public slug and locks the Business row {@code FOR SHARE} at any lifecycle
 * status, inside the caller's transaction, so a replay of an earlier booking can still be answered
 * after the Business was suspended while a new booking can be rejected as unavailable.
 *
 * <p>It reveals only the internal identifier and the lifecycle status to the booking
 * orchestration and never the profile, timezone, contact data, version, or a timestamp. The
 * caller's transaction is required ({@code MANDATORY}); this capability never opens one. The lock is
 * held until that transaction ends. Failures are thrown, never returned, and mark the caller's
 * transaction rollback-only.
 */
public interface BusinessBookingAccess {
    /**
     * The slug is canonicalized (stripped and lowercased) and validated here, so the slug grammar
     * keeps one owner. A malformed slug and a reserved public root return empty without issuing any
     * SQL. Every other lookup issues exactly one statement.
     *
     * @return the Business, or empty when no Business has the slug (DRAFT and SUSPENDED
     *         Businesses are returned, so the caller decides what each status permits)
     */
    Optional<BookingBusiness> lockBySlug(String slug);

    /**
     * @param businessId identifies the Business for internal orchestration only and must never be
     *        serialized publicly
     */
    record BookingBusiness(UUID businessId, LifecycleStatus status) {
        public BookingBusiness {
            Objects.requireNonNull(businessId, "businessId");
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * A PostgreSQL serialization failure ({@code 40001}) or deadlock ({@code 40P01}). The caller's
     * transaction is aborted; retry is possible only in a completely new transaction. The message
     * is fixed and no cause, SQL, or identifier is retained.
     */
    final class ConcurrentConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConcurrentConflict() {
            super("Business booking access conflicted with a concurrent change", null, false, true);
        }
    }

    /** Any other failure. Not retryable by this capability; the message is fixed and cause-free. */
    final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Failure() {
            super("Business booking access failed", null, false, true);
        }
    }
}
