package bg.spotyourslot.workforce;

import java.util.Optional;
import java.util.UUID;

/**
 * Narrow published contract for modules that must stabilize a same-Business
 * StaffMember reference. It exposes no persistence record.
 */
public interface StaffMemberReferenceAccess {
    /**
     * Locks the StaffMember row with {@code FOR SHARE} inside the caller's
     * transaction, so its active state cannot change until that transaction ends.
     *
     * @return the reference, or empty when the StaffMember does not exist in the Business
     */
    Optional<StaffMemberReference> lockReference(UUID businessId, UUID staffMemberId);

    record StaffMemberReference(UUID id, boolean active) {
    }

    final class StaffMemberReferenceFailure extends RuntimeException {
        public StaffMemberReferenceFailure(Throwable cause) {
            super("StaffMember reference access failed", cause);
        }
    }
}
