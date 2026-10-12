package bg.spotyourslot.workforce.application;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

/** The caller precondition of the set-based reads: one or more distinct, non-null identifiers. */
final class ServiceSelection {
    private ServiceSelection() {
    }

    static void require(Collection<UUID> serviceIds) {
        Objects.requireNonNull(serviceIds, "serviceIds");
        if (serviceIds.isEmpty()) {
            throw new IllegalArgumentException("Service identifiers must be non-empty and distinct");
        }
        HashSet<UUID> distinct = new HashSet<>();
        for (UUID serviceId : serviceIds) {
            Objects.requireNonNull(serviceId, "serviceId");
            if (!distinct.add(serviceId)) {
                throw new IllegalArgumentException("Service identifiers must be non-empty and distinct");
            }
        }
    }
}
