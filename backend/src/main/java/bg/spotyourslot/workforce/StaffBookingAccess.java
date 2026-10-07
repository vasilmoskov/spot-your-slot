package bg.spotyourslot.workforce;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Narrow published contract that lets a guest booking attempt stabilize the StaffMembers who may
 * perform a Service and snapshot the one it assigns (ADR-0023). It locks the qualifying StaffMember
 * rows {@code FOR SHARE} inside the caller's transaction, in ascending identifier order, so an
 * activity or assignment change cannot commit until that transaction ends; a change committed after
 * the caller's snapshot makes the lock fail with a serialization failure instead.
 *
 * <p>The caller's transaction is required ({@code MANDATORY}); this capability never opens one.
 * Failures are thrown, never returned, and mark the caller's transaction rollback-only. It exposes
 * no contact data, schedule, version, or assignment detail beyond eligibility.
 */
public interface StaffBookingAccess {
    /**
     * Locks, in ascending identifier order, every StaffMember of the Business who is active and
     * assigned to the Service as of the caller's snapshot; when {@code requestedStaffMemberId} is not
     * null, only that StaffMember and only if qualifying. One statement. Takes no other lock.
     *
     * @return the locked StaffMembers ordered by identifier; empty when none qualifies (a missing,
     *         foreign, inactive, or unassigned StaffMember is indistinguishable)
     */
    List<BookingStaffMember> lockEligibleForBooking(
            UUID businessId, UUID serviceId, UUID requestedStaffMemberId);

    /**
     * @param displayName the canonical StaffMember display name, copied unchanged into the
     *        Appointment snapshot
     * @param createdAt the creation instant, the second tie-breaker of the deterministic
     *        assignment rule
     */
    record BookingStaffMember(UUID id, String displayName, Instant createdAt) {
        public BookingStaffMember {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** A PostgreSQL serialization failure or deadlock; retryable only in a completely new transaction. */
    final class ConcurrentConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConcurrentConflict() {
            super("StaffMember booking access conflicted with a concurrent change", null, false, true);
        }
    }

    /** Any other failure; not retryable by this capability. Fixed message, no cause. */
    final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Failure() {
            super("StaffMember booking access failed", null, false, true);
        }
    }
}
