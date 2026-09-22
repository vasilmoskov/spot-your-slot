package bg.spotyourslot.workforce.application;

import bg.spotyourslot.business.BusinessLifecycleAccess;
import bg.spotyourslot.business.BusinessLifecycleAccess.BusinessLifecycle;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.catalog.ServiceReferenceAccess;
import bg.spotyourslot.catalog.ServiceReferenceAccess.ServiceReference;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberApplicationException.BusinessAccessDenied;
import bg.spotyourslot.workforce.StaffMemberApplicationException.BusinessSuspended;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ConcurrentUpdate;
import bg.spotyourslot.workforce.StaffMemberApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ServiceInactive;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ServiceNotFound;
import bg.spotyourslot.workforce.StaffMemberApplicationException.StaffMemberNotFound;
import bg.spotyourslot.workforce.StaffMemberRecords.AssignedServiceSummary;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberAssignments;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberPage;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import bg.spotyourslot.workforce.application.StaffMemberInputValidator.PageInput;
import bg.spotyourslot.workforce.infrastructure.NewStaffMemberRow;
import bg.spotyourslot.workforce.infrastructure.StaffMemberProfileUpdateRow;
import bg.spotyourslot.workforce.infrastructure.StaffMemberRow;
import bg.spotyourslot.workforce.infrastructure.StaffMemberStore;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffMemberAdministrationService implements StaffMemberAdministration {
    private final StaffMemberStore store;
    private final StaffMemberInputValidator validator;
    private final BusinessLifecycleAccess businesses;
    private final SelectedBusinessOwnerAccess owners;
    private final ServiceReferenceAccess serviceReferences;
    private final Clock clock;

    public StaffMemberAdministrationService(
            StaffMemberStore store,
            StaffMemberInputValidator validator,
            BusinessLifecycleAccess businesses,
            SelectedBusinessOwnerAccess owners,
            ServiceReferenceAccess serviceReferences,
            Clock clock) {
        this.store = store;
        this.validator = validator;
        this.businesses = businesses;
        this.owners = owners;
        this.serviceReferences = serviceReferences;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public StaffMemberPage list(
            AuthenticatedBusinessContext context, int page, int size) {
        BusinessSelection selection = authorizeRead(context);
        PageInput validated = validator.validatePage(page, size);
        var staffMembers = store.list(
                        selection.businessId(), validated.page(), validated.size())
                .stream()
                .map(this::details)
                .toList();
        return new StaffMemberPage(
                staffMembers,
                validated.page(),
                validated.size(),
                store.count(selection.businessId()));
    }

    @Override
    @Transactional(readOnly = true)
    public StaffMemberDetails get(
            AuthenticatedBusinessContext context, UUID staffMemberId) {
        BusinessSelection selection = authorizeRead(context);
        UUID validatedStaffMemberId = validator.validateStaffMemberId(staffMemberId);
        return details(requireStaffMember(selection.businessId(), validatedStaffMemberId));
    }

    @Override
    @Transactional
    public StaffMemberDetails create(
            AuthenticatedBusinessContext context, CreateStaffMemberCommand command) {
        BusinessSelection selection = authorizeMutation(context);
        CreateStaffMemberCommand validated = validator.validateCreate(command);
        Instant now = clock.instant();
        var creation = new NewStaffMemberRow(
                UUID.randomUUID(),
                selection.businessId(),
                validated.displayName(),
                validated.contactEmail(),
                validated.contactPhone(),
                now);
        return details(store.create(creation));
    }

    @Override
    @Transactional
    public StaffMemberDetails update(
            AuthenticatedBusinessContext context,
            UUID staffMemberId,
            UpdateStaffMemberCommand command) {
        BusinessSelection selection = authorizeMutation(context);
        UUID validatedStaffMemberId = validator.validateStaffMemberId(staffMemberId);
        UpdateStaffMemberCommand validated = validator.validateUpdate(command);
        StaffMemberRow current = requireStaffMember(
                selection.businessId(), validatedStaffMemberId);
        requireCurrentVersion(current, validated.expectedVersion());
        var update = new StaffMemberProfileUpdateRow(
                validated.displayName(),
                validated.contactEmail(),
                validated.contactPhone(),
                validated.expectedVersion(),
                clock.instant());
        return details(store.updateProfile(
                        selection.businessId(), validatedStaffMemberId, update)
                .orElseThrow(ConcurrentUpdate::new));
    }

    @Override
    @Transactional
    public StaffMemberDetails deactivate(
            AuthenticatedBusinessContext context,
            UUID staffMemberId,
            StaffMemberVersionCommand command) {
        return transition(context, staffMemberId, command, true);
    }

    @Override
    @Transactional
    public StaffMemberDetails reactivate(
            AuthenticatedBusinessContext context,
            UUID staffMemberId,
            StaffMemberVersionCommand command) {
        return transition(context, staffMemberId, command, false);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public StaffMemberAssignments listServiceAssignments(
            AuthenticatedBusinessContext context, UUID staffMemberId) {
        BusinessSelection selection = authorizeRead(context);
        UUID validatedStaffMemberId = validator.validateStaffMemberId(staffMemberId);
        StaffMemberRow staffMember = requireStaffMember(
                selection.businessId(), validatedStaffMemberId);
        List<UUID> assignedIds = store.listAssignedServiceIds(
                selection.businessId(), validatedStaffMemberId);
        List<ServiceReference> references = serviceReferences.findReferences(
                selection.businessId(), assignedIds);
        requireExactReferences(assignedIds, references);
        return assignments(staffMember, references);
    }

    @Override
    @Transactional
    public StaffMemberAssignments replaceServiceAssignments(
            AuthenticatedBusinessContext context,
            UUID staffMemberId,
            ReplaceServiceAssignmentsCommand command) {
        BusinessSelection selection = authorizeMutation(context);
        UUID validatedStaffMemberId = validator.validateStaffMemberId(staffMemberId);
        ReplaceServiceAssignmentsCommand validated = validator.validateAssignments(command);
        StaffMemberRow current = requireStaffMember(
                selection.businessId(), validatedStaffMemberId);
        requireCurrentVersion(current, validated.expectedVersion());

        StaffMemberRow guarded = store.advanceAssignmentVersion(
                        selection.businessId(),
                        validatedStaffMemberId,
                        validated.expectedVersion(),
                        clock.instant())
                .orElseThrow(ConcurrentUpdate::new);
        Set<UUID> currentIds = new HashSet<>(store.listAssignedServiceIds(
                selection.businessId(), validatedStaffMemberId));
        Set<UUID> desiredIds = new HashSet<>(validated.serviceIds());
        List<UUID> additions = validated.serviceIds().stream()
                .filter(serviceId -> !currentIds.contains(serviceId))
                .toList();
        List<UUID> removals = currentIds.stream()
                .filter(serviceId -> !desiredIds.contains(serviceId))
                .toList();

        List<ServiceReference> lockedAdditions = serviceReferences.lockReferences(
                selection.businessId(), additions);
        requireExactReferences(additions, lockedAdditions);
        if (lockedAdditions.stream().anyMatch(reference -> !reference.active())) {
            throw new ServiceInactive();
        }

        store.reconcileServiceAssignments(
                selection.businessId(), validatedStaffMemberId, removals, additions);
        List<ServiceReference> references = serviceReferences.findReferences(
                selection.businessId(), validated.serviceIds());
        requireExactReferences(validated.serviceIds(), references);
        return assignments(guarded, references);
    }

    private StaffMemberDetails transition(
            AuthenticatedBusinessContext context,
            UUID staffMemberId,
            StaffMemberVersionCommand command,
            boolean deactivate) {
        BusinessSelection selection = authorizeMutation(context);
        UUID validatedStaffMemberId = validator.validateStaffMemberId(staffMemberId);
        long expectedVersion = validator.validateVersion(command);
        StaffMemberRow current = requireStaffMember(
                selection.businessId(), validatedStaffMemberId);
        requireCurrentVersion(current, expectedVersion);
        if (current.active() != deactivate) {
            throw new InvalidLifecycleTransition();
        }

        Instant now = clock.instant();
        return details((deactivate
                        ? store.deactivate(
                                selection.businessId(),
                                validatedStaffMemberId,
                                expectedVersion,
                                now)
                        : store.reactivate(
                                selection.businessId(),
                                validatedStaffMemberId,
                                expectedVersion,
                                now))
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

    private StaffMemberRow requireStaffMember(UUID businessId, UUID staffMemberId) {
        return store.findByBusinessIdAndId(businessId, staffMemberId)
                .orElseThrow(StaffMemberNotFound::new);
    }

    private void requireCurrentVersion(StaffMemberRow current, long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ConcurrentUpdate();
        }
    }

    private StaffMemberDetails details(StaffMemberRow row) {
        return new StaffMemberDetails(
                row.id(),
                row.displayName(),
                row.contactEmail(),
                row.contactPhone(),
                row.active(),
                row.version(),
                row.createdAt(),
                row.updatedAt());
    }

    private StaffMemberAssignments assignments(
            StaffMemberRow row, List<ServiceReference> references) {
        return new StaffMemberAssignments(
                row.id(),
                row.version(),
                row.createdAt(),
                row.updatedAt(),
                references.stream()
                        .map(reference -> new AssignedServiceSummary(
                                reference.id(), reference.name(), reference.active()))
                        .toList());
    }

    private void requireExactReferences(
            List<UUID> requestedIds, List<ServiceReference> references) {
        Set<UUID> requested = new HashSet<>(requestedIds);
        Set<UUID> resolved = references.stream()
                .map(ServiceReference::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!resolved.equals(requested)) {
            throw new ServiceNotFound();
        }
    }

    private record BusinessSelection(UUID userId, UUID businessId) {
    }
}
