package bg.spotyourslot.customer.application;

import bg.spotyourslot.customer.CustomerConcurrentConflict;
import bg.spotyourslot.customer.CustomerDirectory;
import bg.spotyourslot.customer.CustomerOperationFailure;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.UnexpectedFailure;
import bg.spotyourslot.customer.infrastructure.CustomerStore;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link CustomerDirectory}: plain, lock-free, tenant-scoped reads inside the caller's
 * transaction. Persistence failures are classified here and discarded; nothing is logged.
 */
@Component
public class CustomerDirectoryService implements CustomerDirectory {
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";

    private final CustomerStore store;

    public CustomerDirectoryService(CustomerStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, String> displayNames(UUID businessId, Collection<UUID> customerIds) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(customerIds, "customerIds");
        Set<UUID> requested = new LinkedHashSet<>(customerIds);
        if (requested.isEmpty()) {
            return Map.of();
        }
        try {
            return Map.copyOf(store.findDisplayNames(businessId, requested));
        } catch (CustomerPersistenceException failure) {
            throw sanitized(failure);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<CustomerContact> findContact(UUID businessId, UUID customerId) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(customerId, "customerId");
        try {
            return store.findById(businessId, customerId).map(customer -> new CustomerContact(
                    customer.id(), customer.displayName(), customer.phone(), customer.email()));
        } catch (CustomerPersistenceException failure) {
            throw sanitized(failure);
        }
    }

    private static RuntimeException sanitized(CustomerPersistenceException failure) {
        if (failure instanceof UnexpectedFailure unexpected
                && (SERIALIZATION_FAILURE.equals(unexpected.sqlState())
                        || DEADLOCK_DETECTED.equals(unexpected.sqlState()))) {
            return new CustomerConcurrentConflict();
        }
        return new CustomerOperationFailure();
    }
}
