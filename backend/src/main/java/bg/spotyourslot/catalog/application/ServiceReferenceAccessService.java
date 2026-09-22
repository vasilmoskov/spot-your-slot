package bg.spotyourslot.catalog.application;

import bg.spotyourslot.catalog.ServiceReferenceAccess;
import bg.spotyourslot.catalog.ServiceReferenceAccess.ServiceReference;
import bg.spotyourslot.catalog.ServiceReferenceAccess.ServiceReferenceFailure;
import bg.spotyourslot.catalog.infrastructure.ServicePersistenceException.UnexpectedFailure;
import bg.spotyourslot.catalog.infrastructure.ServiceRow;
import bg.spotyourslot.catalog.infrastructure.ServiceStore;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ServiceReferenceAccessService implements ServiceReferenceAccess {
    private final ServiceStore store;

    public ServiceReferenceAccessService(ServiceStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<ServiceReference> findReferences(
            UUID businessId, Collection<UUID> serviceIds) {
        try {
            return store.findReferences(businessId, serviceIds).stream()
                    .map(this::reference)
                    .toList();
        } catch (UnexpectedFailure exception) {
            throw new ServiceReferenceFailure(exception);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ServiceReference> lockReferences(
            UUID businessId, Collection<UUID> serviceIds) {
        try {
            return store.lockReferences(businessId, serviceIds).stream()
                    .map(this::reference)
                    .toList();
        } catch (UnexpectedFailure exception) {
            throw new ServiceReferenceFailure(exception);
        }
    }

    private ServiceReference reference(ServiceRow row) {
        return new ServiceReference(row.id(), row.name(), row.active());
    }
}
