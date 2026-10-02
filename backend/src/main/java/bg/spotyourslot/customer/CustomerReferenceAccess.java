package bg.spotyourslot.customer;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A plain same-Business Customer reference lookup. It requires a caller-owned transaction, takes no
 * lock, writes nothing, and returns empty for a missing, guessed, or foreign-Business ID.
 */
public interface CustomerReferenceAccess {
    Optional<CustomerReference> find(UUID businessId, UUID customerId);

    record CustomerReference(UUID id) {
        public CustomerReference {
            Objects.requireNonNull(id, "id");
        }
    }
}
