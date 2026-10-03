package bg.spotyourslot.customer.application;

import bg.spotyourslot.business.BusinessLifecycleAccess;
import bg.spotyourslot.business.BusinessLifecycleAccess.BusinessLifecycle;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.customer.CustomerConcurrentConflict;
import bg.spotyourslot.customer.CustomerOperationFailure;
import bg.spotyourslot.customer.application.CustomerAdministrationException.BusinessAccessDenied;
import bg.spotyourslot.customer.application.CustomerAdministrationException.BusinessSuspended;
import bg.spotyourslot.customer.application.CustomerAdministrationException.ConcurrentUpdate;
import bg.spotyourslot.customer.application.CustomerAdministrationException.ConflictField;
import bg.spotyourslot.customer.application.CustomerAdministrationException.ContactConflict;
import bg.spotyourslot.customer.application.CustomerAdministrationException.CustomerNotFound;
import bg.spotyourslot.customer.application.CustomerAdministrationException.InputField;
import bg.spotyourslot.customer.application.CustomerAdministrationException.InvalidInput;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CreateCustomerCommand;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CustomerDetails;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CustomerPage;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CustomerSearchCommand;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CustomerSummary;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.UpdateCustomerCommand;
import bg.spotyourslot.customer.application.CustomerInputValidator.PageInput;
import bg.spotyourslot.customer.domain.Customer;
import bg.spotyourslot.customer.domain.CustomerProfile;
import bg.spotyourslot.customer.domain.CustomerSearchCriteria;
import bg.spotyourslot.customer.domain.CustomerSortField;
import bg.spotyourslot.customer.domain.NewCustomer;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.DuplicateEmail;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.DuplicatePhone;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.UnexpectedFailure;
import bg.spotyourslot.customer.infrastructure.CustomerStore;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The private, owner-only Customer administration use cases (ADR-0021). The Business always comes
 * from the authenticated selection, never from a request value.
 *
 * <p>Reads are read-only transactions with non-locking authorization. Each mutation is one
 * transaction that locks the Business lifecycle row, then the user's Membership row, and only then
 * performs the optimistic Customer write, matching the Service and StaffMember lock order. No
 * operation opens a nested transaction, uses a savepoint, or merges, moves, or implicitly matches
 * Customers. Persistence failures are classified here and discarded: only fixed typed exceptions
 * leave this class, and nothing is logged.
 */
@Service
public class CustomerAdministrationService {
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";

    private final CustomerStore store;
    private final CustomerInputValidator validator;
    private final BusinessLifecycleAccess businesses;
    private final SelectedBusinessOwnerAccess owners;
    private final Clock clock;
    private final Supplier<UUID> ids;

    @Autowired
    public CustomerAdministrationService(
            CustomerStore store,
            CustomerInputValidator validator,
            BusinessLifecycleAccess businesses,
            SelectedBusinessOwnerAccess owners,
            Clock clock) {
        this(store, validator, businesses, owners, clock, UUID::randomUUID);
    }

    CustomerAdministrationService(
            CustomerStore store,
            CustomerInputValidator validator,
            BusinessLifecycleAccess businesses,
            SelectedBusinessOwnerAccess owners,
            Clock clock,
            Supplier<UUID> ids) {
        this.store = store;
        this.validator = validator;
        this.businesses = businesses;
        this.owners = owners;
        this.clock = clock;
        this.ids = ids;
    }

    @Transactional(readOnly = true)
    public CustomerPage list(
            AuthenticatedBusinessContext context,
            Integer page,
            Integer size,
            String sort,
            String direction) {
        return page(context, CustomerSearchCriteria.none(), page, size, sort, direction);
    }

    @Transactional(readOnly = true)
    public CustomerPage search(
            AuthenticatedBusinessContext context, CustomerSearchCommand command) {
        BusinessSelection selection = authorizeRead(context);
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        CustomerSearchCriteria criteria = validator.validateSearch(command.search());
        return read(selection, criteria, command.page(), command.size(), command.sort(),
                command.direction());
    }

    @Transactional(readOnly = true)
    public CustomerDetails get(AuthenticatedBusinessContext context, UUID customerId) {
        BusinessSelection selection = authorizeRead(context);
        UUID validatedId = validator.validateCustomerId(customerId);
        return details(persist(() -> store.findById(selection.businessId(), validatedId))
                .orElseThrow(CustomerNotFound::new));
    }

    @Transactional
    public CustomerDetails create(
            AuthenticatedBusinessContext context, CreateCustomerCommand command) {
        BusinessSelection selection = authorizeMutation(context);
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        CustomerProfile profile = validator.validateProfile(
                command.displayName(), command.phone(), command.email());
        requireFreeIdentifiers(selection.businessId(), profile, null);
        Customer created = persist(() -> store.insert(new NewCustomer(
                ids.get(), selection.businessId(), profile, clock.instant())));
        return details(created);
    }

    @Transactional
    public CustomerDetails update(
            AuthenticatedBusinessContext context,
            UUID customerId,
            UpdateCustomerCommand command) {
        BusinessSelection selection = authorizeMutation(context);
        UUID validatedId = validator.validateCustomerId(customerId);
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        CustomerProfile profile = validator.validateProfile(
                command.displayName(), command.phone(), command.email());
        long expectedVersion = validator.validateExpectedVersion(command.expectedVersion());

        Customer current = persist(() -> store.findById(selection.businessId(), validatedId))
                .orElseThrow(CustomerNotFound::new);
        if (current.version() != expectedVersion) {
            throw new ConcurrentUpdate();
        }
        requireFreeIdentifiers(selection.businessId(), profile, validatedId);
        return details(persist(() -> store.update(
                        selection.businessId(),
                        validatedId,
                        profile,
                        expectedVersion,
                        clock.instant()))
                .orElseThrow(ConcurrentUpdate::new));
    }

    private CustomerPage page(
            AuthenticatedBusinessContext context,
            CustomerSearchCriteria criteria,
            Integer page,
            Integer size,
            String sort,
            String direction) {
        BusinessSelection selection = authorizeRead(context);
        return read(selection, criteria, page, size, sort, direction);
    }

    private CustomerPage read(
            BusinessSelection selection,
            CustomerSearchCriteria criteria,
            Integer page,
            Integer size,
            String sort,
            String direction) {
        PageInput paging = validator.validatePage(page, size);
        CustomerSortField sortField = validator.validateSort(sort);
        boolean ascending = validator.validateAscending(direction);
        List<CustomerSummary> items = persist(() -> store.list(
                        selection.businessId(),
                        criteria,
                        paging.page(),
                        paging.size(),
                        sortField,
                        ascending))
                .stream()
                .map(CustomerAdministrationService::summary)
                .toList();
        long total = persist(() -> store.count(selection.businessId(), criteria));
        return new CustomerPage(items, paging.page(), paging.size(), total);
    }

    /**
     * Reports every identifier already held by a different Customer of the Business, so a client
     * sees all conflicting fields together. The unique indexes stay the final arbiter for races.
     * It never reveals who holds the identifier.
     */
    private void requireFreeIdentifiers(UUID businessId, CustomerProfile profile, UUID selfId) {
        Set<ConflictField> conflicts = EnumSet.noneOf(ConflictField.class);
        for (Customer holder : persist(
                () -> store.findHolders(businessId, profile.phone(), profile.email()))) {
            if (holder.id().equals(selfId)) {
                continue;
            }
            if (profile.phone() != null && profile.phone().equals(holder.phone())) {
                conflicts.add(ConflictField.PHONE);
            }
            if (profile.email() != null && profile.email().equals(holder.email())) {
                conflicts.add(ConflictField.EMAIL);
            }
        }
        if (!conflicts.isEmpty()) {
            throw new ContactConflict(conflicts);
        }
    }

    private BusinessSelection authorizeRead(AuthenticatedBusinessContext context) {
        BusinessSelection selection = requireSelection(context);
        businesses.findLifecycle(selection.businessId()).orElseThrow(BusinessAccessDenied::new);
        if (owners.authorize(selection.userId(), selection.businessId())
                != Authorization.GRANTED) {
            throw new BusinessAccessDenied();
        }
        return selection;
    }

    private BusinessSelection authorizeMutation(AuthenticatedBusinessContext context) {
        BusinessSelection selection = requireSelection(context);
        BusinessLifecycle lifecycle = businesses.lockLifecycle(selection.businessId())
                .orElseThrow(BusinessAccessDenied::new);
        if (owners.lockAndAuthorize(selection.userId(), selection.businessId())
                != Authorization.GRANTED) {
            throw new BusinessAccessDenied();
        }
        if (lifecycle.status() == LifecycleStatus.SUSPENDED) {
            throw new BusinessSuspended();
        }
        return selection;
    }

    private BusinessSelection requireSelection(AuthenticatedBusinessContext context) {
        if (context == null || context.userId() == null) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required");
        }
        UUID businessId = context.selectedBusinessId().orElseThrow(SelectedBusinessRequired::new);
        return new BusinessSelection(context.userId(), validator.validateBusinessId(businessId));
    }

    private static <T> T persist(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (CustomerPersistenceException failure) {
            throw translated(failure);
        }
    }

    private static RuntimeException translated(CustomerPersistenceException failure) {
        if (failure instanceof DuplicatePhone) {
            return new ContactConflict(EnumSet.of(ConflictField.PHONE));
        }
        if (failure instanceof DuplicateEmail) {
            return new ContactConflict(EnumSet.of(ConflictField.EMAIL));
        }
        if (failure instanceof UnexpectedFailure unexpected
                && (SERIALIZATION_FAILURE.equals(unexpected.sqlState())
                        || DEADLOCK_DETECTED.equals(unexpected.sqlState()))) {
            return new CustomerConcurrentConflict();
        }
        return new CustomerOperationFailure();
    }

    private static CustomerSummary summary(Customer customer) {
        return new CustomerSummary(
                customer.id(), customer.displayName(), customer.phone(), customer.email());
    }

    private static CustomerDetails details(Customer customer) {
        return new CustomerDetails(
                customer.id(),
                customer.displayName(),
                customer.phone(),
                customer.email(),
                customer.version(),
                customer.createdAt(),
                customer.updatedAt());
    }

    private record BusinessSelection(UUID userId, UUID businessId) {
    }
}
