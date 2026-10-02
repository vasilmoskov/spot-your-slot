package bg.spotyourslot.customer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A persisted Business-scoped Customer: an opaque identity, the immutable owning Business, the
 * canonical {@link CustomerProfile}, the optimistic version, and the audit instants. It is deeply
 * immutable (a record of immutable values). A Customer has no lifecycle, note, account or
 * Membership link, and no Appointment data, and is never an identifier across Businesses.
 */
public record Customer(
        UUID id,
        UUID businessId,
        CustomerProfile profile,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public Customer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("Customer version must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Customer update time must not precede creation");
        }
    }

    public String displayName() {
        return profile.displayName();
    }

    public String phone() {
        return profile.phone();
    }

    public String email() {
        return profile.email();
    }
}
