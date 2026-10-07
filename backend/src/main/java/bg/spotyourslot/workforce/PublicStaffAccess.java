package bg.spotyourslot.workforce;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Narrow published read contract for the StaffMembers a guest may choose for a Service
 * (ADR-0026). It exposes only the identifier and the display name: no contact data, schedule,
 * assignment detail, version, or timestamp. It writes nothing and takes no lock.
 */
public interface PublicStaffAccess {
    /**
     * Reads inside the caller's transaction; a caller without one is rejected. One statement.
     *
     * @return the active StaffMembers of the Business assigned to the Service, ordered by the
     *         canonical display name and then identifier (the existing StaffMember name order)
     */
    List<PublicStaffMember> findBookableStaff(UUID businessId, UUID serviceId);

    /**
     * @param id a public reference only inside its Business and never an authority (ADR-0026)
     */
    record PublicStaffMember(UUID id, String displayName) {
        public PublicStaffMember {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    /** An unexpected persistence failure; the message is fixed and the cause is server-side only. */
    final class PublicStaffFailure extends RuntimeException {
        public PublicStaffFailure(Throwable cause) {
            super("Public StaffMember access failed", cause);
        }
    }
}
