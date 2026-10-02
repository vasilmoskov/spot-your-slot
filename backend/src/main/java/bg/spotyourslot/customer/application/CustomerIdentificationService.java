package bg.spotyourslot.customer.application;

import bg.spotyourslot.customer.CustomerConcurrentConflict;
import bg.spotyourslot.customer.CustomerIdentification;
import bg.spotyourslot.customer.CustomerIdentity;
import bg.spotyourslot.customer.CustomerMatchOutcome;
import bg.spotyourslot.customer.CustomerMatchOutcome.CreatedCustomer;
import bg.spotyourslot.customer.CustomerMatchOutcome.ExistingCustomer;
import bg.spotyourslot.customer.CustomerMatchOutcome.IdentityConflict;
import bg.spotyourslot.customer.CustomerMatchOutcome.InvalidIdentity;
import bg.spotyourslot.customer.CustomerOperationFailure;
import bg.spotyourslot.customer.CustomerReferenceAccess;
import bg.spotyourslot.customer.IdentityField;
import bg.spotyourslot.customer.domain.Customer;
import bg.spotyourslot.customer.domain.CustomerField;
import bg.spotyourslot.customer.domain.CustomerProfile;
import bg.spotyourslot.customer.domain.InvalidCustomerData;
import bg.spotyourslot.customer.domain.NewCustomer;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.UnexpectedFailure;
import bg.spotyourslot.customer.infrastructure.CustomerStore;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the published Customer contracts (ADR-0020). Both operations require a caller-owned
 * transaction ({@code MANDATORY}, checked by the proxy before any clock, ID, or SQL work), open no
 * transaction of their own, use no savepoint, and write nothing except the single insert of a new
 * Customer.
 *
 * <p>{@code findOrCreate} runs at most three Customer SQL statements and never loops:
 * <ol>
 *   <li>one holder lookup ({@link CustomerStore#findHolders});
 *   <li>when no supplied identifier is held, at most one
 *       {@code INSERT ... ON CONFLICT DO NOTHING} ({@link CustomerStore#insertIfAbsent});
 *   <li>when that insert returns no row, one final holder lookup.
 * </ol>
 * ADR-0020's "two attempts" means the initial resolution followed by this one bounded re-read, not
 * two inserts. If the final lookup finds no holder or cannot produce a truth-table result, the
 * call throws {@link CustomerConcurrentConflict}. The clock is read and the ID generated only when
 * the single insert is actually attempted.
 *
 * <p>Any thrown exception propagates through the transactional proxy and marks the caller's
 * transaction rollback-only. Persistence failures are classified here and discarded: {@code 40001}
 * and {@code 40P01} become {@link CustomerConcurrentConflict}, every other persistence failure
 * becomes {@link CustomerOperationFailure}. No infrastructure exception crosses the boundary.
 * Nothing is logged.
 */
@Component
public class CustomerIdentificationService implements CustomerIdentification, CustomerReferenceAccess {
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";

    private final CustomerStore store;
    private final Clock clock;
    private final Supplier<UUID> ids;

    @Autowired
    public CustomerIdentificationService(CustomerStore store, Clock clock) {
        this(store, clock, UUID::randomUUID);
    }

    CustomerIdentificationService(CustomerStore store, Clock clock, Supplier<UUID> ids) {
        this.store = store;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CustomerMatchOutcome findOrCreate(UUID businessId, CustomerIdentity identity) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(identity, "identity");

        CustomerProfile profile;
        try {
            profile = CustomerProfile.fromInput(
                    identity.displayName(), identity.phone(), identity.email());
        } catch (InvalidCustomerData invalid) {
            return new InvalidIdentity(fields(invalid.fields()));
        }

        try {
            return resolve(businessId, profile);
        } catch (CustomerPersistenceException failure) {
            throw sanitized(failure);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<CustomerReference> find(UUID businessId, UUID customerId) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(customerId, "customerId");
        try {
            return store.findById(businessId, customerId)
                    .map(customer -> new CustomerReference(customer.id()));
        } catch (CustomerPersistenceException failure) {
            throw sanitized(failure);
        }
    }

    private CustomerMatchOutcome resolve(UUID businessId, CustomerProfile profile) {
        Optional<CustomerMatchOutcome> first = decide(profile, holders(businessId, profile));
        if (first.isPresent()) {
            return first.get();
        }

        UUID id = ids.get();
        Instant now = clock.instant();
        Optional<Customer> inserted = store.insertIfAbsent(new NewCustomer(id, businessId, profile, now));
        if (inserted.isPresent()) {
            return new CreatedCustomer(inserted.get().id());
        }

        // The insert lost a race (or collided on the ID): resolve once more from the stored state.
        return decide(profile, holders(businessId, profile))
                .orElseThrow(CustomerConcurrentConflict::new);
    }

    private List<Customer> holders(UUID businessId, CustomerProfile profile) {
        return store.findHolders(businessId, profile.phone(), profile.email());
    }

    /**
     * The approved truth table over the Customers holding the supplied identifiers. Empty means
     * nobody holds a supplied identifier, so a new Customer may be created. Only the supplied
     * identifiers are considered, and the result does not depend on row order.
     */
    static Optional<CustomerMatchOutcome> decide(CustomerProfile profile, List<Customer> holders) {
        Optional<Customer> phoneHolder = holders.stream()
                .filter(customer -> profile.phone() != null && profile.phone().equals(customer.phone()))
                .findFirst();
        Optional<Customer> emailHolder = holders.stream()
                .filter(customer -> profile.email() != null && profile.email().equals(customer.email()))
                .findFirst();

        if (profile.phone() != null && profile.email() != null) {
            if (phoneHolder.isPresent() && emailHolder.isPresent()) {
                return Optional.of(phoneHolder.get().id().equals(emailHolder.get().id())
                        ? new ExistingCustomer(phoneHolder.get().id())
                        : new IdentityConflict());
            }
            if (phoneHolder.isPresent() || emailHolder.isPresent()) {
                return Optional.of(new IdentityConflict());
            }
            return Optional.empty();
        }
        Optional<Customer> holder = profile.phone() != null ? phoneHolder : emailHolder;
        return holder.map(customer -> new ExistingCustomer(customer.id()));
    }

    static Set<IdentityField> fields(Set<CustomerField> fields) {
        Set<IdentityField> mapped = EnumSet.noneOf(IdentityField.class);
        for (CustomerField field : fields) {
            mapped.add(switch (field) {
                case DISPLAY_NAME -> IdentityField.DISPLAY_NAME;
                case PHONE -> IdentityField.PHONE;
                case EMAIL -> IdentityField.EMAIL;
                case CONTACT -> IdentityField.CONTACT;
            });
        }
        return mapped;
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
