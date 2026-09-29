package bg.spotyourslot.catalog.application;

import bg.spotyourslot.catalog.ServiceAvailabilityAccess;
import bg.spotyourslot.catalog.infrastructure.ServicePersistenceException.UnexpectedFailure;
import bg.spotyourslot.catalog.infrastructure.ServiceStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ServiceAvailabilityAccessService implements ServiceAvailabilityAccess {
    private final ServiceStore store;

    public ServiceAvailabilityAccessService(ServiceStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<BookableService> findBookableService(UUID businessId, UUID serviceId) {
        try {
            return store.findByBusinessIdAndId(businessId, serviceId)
                    .filter(row -> row.active())
                    .map(row -> new BookableService(
                            row.id(), Duration.ofMinutes(row.durationMinutes())));
        } catch (UnexpectedFailure exception) {
            throw new ServiceAvailabilityFailure(exception);
        }
    }
}
