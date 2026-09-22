package bg.spotyourslot.catalog;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ServiceReferenceAccess {
    List<ServiceReference> findReferences(
            UUID businessId, Collection<UUID> serviceIds);

    List<ServiceReference> lockReferences(
            UUID businessId, Collection<UUID> serviceIds);

    record ServiceReference(UUID id, String name, boolean active) {
    }

    final class ServiceReferenceFailure extends RuntimeException {
        public ServiceReferenceFailure(Throwable cause) {
            super("Service reference access failed", cause);
        }
    }
}
