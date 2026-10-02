package bg.spotyourslot.customer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A Customer about to be inserted: application-generated identity, the owning Business, the
 * validated profile, and the creation instant. Version 0 and the equal update instant are implied.
 */
public record NewCustomer(UUID id, UUID businessId, CustomerProfile profile, Instant createdAt) {
    public NewCustomer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
