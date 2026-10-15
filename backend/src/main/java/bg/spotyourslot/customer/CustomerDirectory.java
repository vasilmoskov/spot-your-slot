package bg.spotyourslot.customer;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow published read contract that lets the Business calendar show who an Appointment is for
 * (ADR-0028 sections 11 and 12). It performs no authorization (the caller has already authorized
 * the owner), requires the caller's transaction so its answer belongs to the caller's snapshot,
 * takes no lock, writes nothing, and answers every request with one statement. A missing, guessed,
 * or foreign-Business identifier is simply absent from the answer, so it cannot be told apart.
 *
 * <p>The module that implements it never depends on the module that calls it.
 */
public interface CustomerDirectory {
    /**
     * The display names of the requested Customers of the Business, keyed by identifier. An
     * empty request issues no statement. Failures are thrown as {@link CustomerOperationFailure}.
     */
    Map<UUID, String> displayNames(UUID businessId, Collection<UUID> customerIds);

    /**
     * The contact facts of one Customer for the owner-only Appointment detail, or empty.
     * {@code phone} and {@code email} are each nullable, never both.
     */
    Optional<CustomerContact> findContact(UUID businessId, UUID customerId);

    /** Personal data: {@link #toString()} is redacted. */
    record CustomerContact(UUID id, String displayName, String phone, String email) {
        public CustomerContact {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
        }

        @Override
        public String toString() {
            return "CustomerContact[redacted]";
        }
    }
}
