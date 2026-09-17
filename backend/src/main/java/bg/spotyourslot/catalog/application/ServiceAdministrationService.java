package bg.spotyourslot.catalog.application;

import bg.spotyourslot.business.BusinessLifecycleAccess;
import bg.spotyourslot.business.BusinessLifecycleAccess.BusinessLifecycle;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.catalog.ServiceAdministration;
import bg.spotyourslot.catalog.ServiceApplicationException.BusinessAccessDenied;
import bg.spotyourslot.catalog.ServiceApplicationException.BusinessSuspended;
import bg.spotyourslot.catalog.ServiceApplicationException.ConcurrentUpdate;
import bg.spotyourslot.catalog.ServiceApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.catalog.ServiceApplicationException.ServiceNameConflict;
import bg.spotyourslot.catalog.ServiceApplicationException.ServiceNotFound;
import bg.spotyourslot.catalog.ServiceRecords.CreateServiceCommand;
import bg.spotyourslot.catalog.ServiceRecords.ServiceDetails;
import bg.spotyourslot.catalog.ServiceRecords.ServicePage;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.catalog.ServiceRecords.UpdateServiceCommand;
import bg.spotyourslot.catalog.application.ServiceInputValidator.PageInput;
import bg.spotyourslot.catalog.infrastructure.NewServiceRow;
import bg.spotyourslot.catalog.infrastructure.ServicePersistenceException.NameConflict;
import bg.spotyourslot.catalog.infrastructure.ServiceRow;
import bg.spotyourslot.catalog.infrastructure.ServiceStore;
import bg.spotyourslot.catalog.infrastructure.ServiceUpdateRow;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceAdministrationService implements ServiceAdministration {
    private final ServiceStore store;
    private final ServiceInputValidator validator;
    private final BusinessLifecycleAccess businesses;
    private final SelectedBusinessOwnerAccess owners;
    private final Clock clock;

    public ServiceAdministrationService(
            ServiceStore store,
            ServiceInputValidator validator,
            BusinessLifecycleAccess businesses,
            SelectedBusinessOwnerAccess owners,
            Clock clock) {
        this.store = store;
        this.validator = validator;
        this.businesses = businesses;
        this.owners = owners;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ServicePage list(
            AuthenticatedBusinessContext context, int page, int size) {
        BusinessSelection selection = authorizeRead(context);
        PageInput validated = validator.validatePage(page, size);
        var services = store.list(
                        selection.businessId(), validated.page(), validated.size())
                .stream()
                .map(this::details)
                .toList();
        return new ServicePage(
                services,
                validated.page(),
                validated.size(),
                store.count(selection.businessId()));
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceDetails get(
            AuthenticatedBusinessContext context, UUID serviceId) {
        BusinessSelection selection = authorizeRead(context);
        UUID validatedServiceId = validator.validateServiceId(serviceId);
        return details(requireService(selection.businessId(), validatedServiceId));
    }

    @Override
    @Transactional
    public ServiceDetails create(
            AuthenticatedBusinessContext context, CreateServiceCommand command) {
        BusinessSelection selection = authorizeMutation(context);
        CreateServiceCommand validated = validator.validateCreate(command);
        Instant now = clock.instant();
        var creation = new NewServiceRow(
                UUID.randomUUID(),
                selection.businessId(),
                validated.name(),
                validated.description(),
                validated.durationMinutes(),
                validated.price(),
                now);
        try {
            return details(store.create(creation));
        } catch (NameConflict exception) {
            throw new ServiceNameConflict();
        }
    }

    @Override
    @Transactional
    public ServiceDetails update(
            AuthenticatedBusinessContext context,
            UUID serviceId,
            UpdateServiceCommand command) {
        BusinessSelection selection = authorizeMutation(context);
        UUID validatedServiceId = validator.validateServiceId(serviceId);
        UpdateServiceCommand validated = validator.validateUpdate(command);
        ServiceRow current = requireService(selection.businessId(), validatedServiceId);
        requireCurrentVersion(current, validated.expectedVersion());
        var update = new ServiceUpdateRow(
                validated.name(),
                validated.description(),
                validated.durationMinutes(),
                validated.price(),
                validated.expectedVersion(),
                clock.instant());
        try {
            return details(store.update(selection.businessId(), validatedServiceId, update)
                    .orElseThrow(ConcurrentUpdate::new));
        } catch (NameConflict exception) {
            throw new ServiceNameConflict();
        }
    }

    @Override
    @Transactional
    public ServiceDetails deactivate(
            AuthenticatedBusinessContext context,
            UUID serviceId,
            ServiceVersionCommand command) {
        return transition(context, serviceId, command, true);
    }

    @Override
    @Transactional
    public ServiceDetails reactivate(
            AuthenticatedBusinessContext context,
            UUID serviceId,
            ServiceVersionCommand command) {
        return transition(context, serviceId, command, false);
    }

    private ServiceDetails transition(
            AuthenticatedBusinessContext context,
            UUID serviceId,
            ServiceVersionCommand command,
            boolean deactivate) {
        BusinessSelection selection = authorizeMutation(context);
        UUID validatedServiceId = validator.validateServiceId(serviceId);
        long expectedVersion = validator.validateVersion(command);
        ServiceRow current = requireService(selection.businessId(), validatedServiceId);
        requireCurrentVersion(current, expectedVersion);
        if (current.active() != deactivate) {
            throw new InvalidLifecycleTransition();
        }

        Instant now = clock.instant();
        return details((deactivate
                        ? store.deactivate(
                                selection.businessId(), validatedServiceId, expectedVersion, now)
                        : store.reactivate(
                                selection.businessId(), validatedServiceId, expectedVersion, now))
                .orElseThrow(ConcurrentUpdate::new));
    }

    private BusinessSelection authorizeRead(AuthenticatedBusinessContext context) {
        BusinessSelection selection = requireSelection(context);
        businesses.findLifecycle(selection.businessId())
                .orElseThrow(BusinessAccessDenied::new);
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
            throw new AuthenticationCredentialsNotFoundException(
                    "Authentication is required");
        }
        UUID businessId = context.selectedBusinessId()
                .orElseThrow(SelectedBusinessRequired::new);
        return new BusinessSelection(
                context.userId(), validator.validateBusinessId(businessId));
    }

    private ServiceRow requireService(UUID businessId, UUID serviceId) {
        return store.findByBusinessIdAndId(businessId, serviceId)
                .orElseThrow(ServiceNotFound::new);
    }

    private void requireCurrentVersion(ServiceRow current, long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ConcurrentUpdate();
        }
    }

    private ServiceDetails details(ServiceRow row) {
        return new ServiceDetails(
                row.id(),
                row.name(),
                row.description(),
                row.durationMinutes(),
                row.price(),
                row.active(),
                row.version(),
                row.createdAt(),
                row.updatedAt());
    }

    private record BusinessSelection(UUID userId, UUID businessId) {
    }
}
