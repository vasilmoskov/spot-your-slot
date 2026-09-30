package bg.spotyourslot.catalog.application;

import bg.spotyourslot.catalog.PublicServiceAccess;
import bg.spotyourslot.catalog.infrastructure.ServicePersistenceException.UnexpectedFailure;
import bg.spotyourslot.catalog.infrastructure.ServiceStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PublicServiceAccessService implements PublicServiceAccess {
    private final ServiceStore store;

    public PublicServiceAccessService(ServiceStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<PublicService> findActiveServices(UUID businessId) {
        try {
            return store.findActivePublicServices(businessId).stream()
                    .map(row -> new PublicService(
                            row.name(), row.description(), row.durationMinutes(), row.price()))
                    .toList();
        } catch (UnexpectedFailure exception) {
            throw new PublicServiceFailure(exception);
        }
    }
}
