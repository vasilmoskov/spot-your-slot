package bg.spotyourslot.catalog;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow published contract that lets a guest booking attempt stabilize and snapshot one Service
 * (ADR-0023). It locks the Service row {@code FOR SHARE} inside the caller's transaction, so its
 * activity, name, duration, and price cannot change until that transaction ends; a change committed
 * after the caller's snapshot makes the lock fail with a serialization failure instead.
 *
 * <p>The caller's transaction is required ({@code MANDATORY}); this capability never opens one.
 * Failures are thrown, never returned, and mark the caller's transaction rollback-only. It exposes
 * no description, version, or timestamp.
 */
public interface ServiceBookingAccess {
    /**
     * @return the Service with its committed facts (including an inactive one, so the caller
     *         decides), or empty when it does not exist in the Business; a foreign Service is
     *         indistinguishable from a missing one
     */
    Optional<BookingService> lockForBooking(UUID businessId, UUID serviceId);

    /**
     * Locks every requested Service of the Business {@code FOR SHARE} with one statement
     * (ADR-0027). The rows are locked in ascending identifier order, which is PostgreSQL's
     * {@code uuid} order (the unsigned bytewise order), because the statement sorts before it locks;
     * the caller never decides a lock order. The result is returned in the public-profile order
     * (canonical name, then identifier), which is the execution order of a visit.
     *
     * @param serviceIds one to five distinct identifiers; anything else is rejected
     * @return the requested Services that exist in the Business, including inactive ones so the
     *         caller decides; a missing or foreign Service is simply absent
     */
    List<BookingService> lockAllForBooking(UUID businessId, Collection<UUID> serviceIds);

    /**
     * @param name the canonical Service name, copied unchanged into the Appointment snapshot
     * @param price the committed EUR price with its stored scale
     */
    record BookingService(UUID id, String name, int durationMinutes, BigDecimal price, boolean active) {
        public BookingService {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(price, "price");
        }
    }

    /** A PostgreSQL serialization failure or deadlock; retryable only in a completely new transaction. */
    final class ConcurrentConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConcurrentConflict() {
            super("Service booking access conflicted with a concurrent change", null, false, true);
        }
    }

    /** Any other failure; not retryable by this capability. Fixed message, no cause. */
    final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Failure() {
            super("Service booking access failed", null, false, true);
        }
    }
}
