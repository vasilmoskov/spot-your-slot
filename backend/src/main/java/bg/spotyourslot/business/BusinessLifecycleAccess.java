package bg.spotyourslot.business;

import java.util.Optional;
import java.util.UUID;

public interface BusinessLifecycleAccess {
    Optional<BusinessLifecycle> findLifecycle(UUID businessId);

    Optional<BusinessLifecycle> lockLifecycle(UUID businessId);

    enum LifecycleStatus {
        DRAFT,
        ACTIVE,
        SUSPENDED
    }

    record BusinessLifecycle(
            UUID businessId,
            LifecycleStatus status) {
    }
}
