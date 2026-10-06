package bg.spotyourslot.scheduling.application;

import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.business.ScheduleRevisionBump;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.scheduling.ScheduleExceptionAdministration;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessAccessDenied;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessSuspended;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ConcurrentUpdate;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.OverlapConflict;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ScheduleExceptionNotFound;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberInactive;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberNotFound;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ExceptionPeriod;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ReplaceScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionAdministrationDetails;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionDetails;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionWindow;
import bg.spotyourslot.scheduling.application.ScheduleExceptionInputValidator.ValidatedReplacement;
import bg.spotyourslot.scheduling.application.ScheduleExceptionInputValidator.Window;
import bg.spotyourslot.scheduling.domain.NewScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionPersistenceException;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionStore;
import bg.spotyourslot.workforce.StaffMemberReferenceAccess;
import bg.spotyourslot.workforce.StaffMemberReferenceAccess.StaffMemberReference;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mutations lock, in order: the Business lifecycle row, the exact owner
 * Membership row, the StaffMember row (StaffMember-scoped kinds only), the
 * Business schedule revision row (advanced once per accepted create, replace, or
 * delete, ADR-0025), and finally the aggregate through the conditional store
 * statement. A request rejected for validation, a missing exception, or a stale
 * version never advances the revision. Overlap safety comes only from the
 * PostgreSQL exclusion constraints.
 */
@Service
public class ScheduleExceptionAdministrationService implements ScheduleExceptionAdministration {
    private final ScheduleExceptionStore store;
    private final ScheduleExceptionInputValidator validator;
    private final BusinessScheduleContextAccess businesses;
    private final SelectedBusinessOwnerAccess owners;
    private final StaffMemberReferenceAccess staffMembers;
    private final ScheduleRevisionBump scheduleRevision;
    private final Clock clock;

    public ScheduleExceptionAdministrationService(
            ScheduleExceptionStore store,
            ScheduleExceptionInputValidator validator,
            BusinessScheduleContextAccess businesses,
            SelectedBusinessOwnerAccess owners,
            StaffMemberReferenceAccess staffMembers,
            ScheduleRevisionBump scheduleRevision,
            Clock clock) {
        this.store = store;
        this.validator = validator;
        this.businesses = businesses;
        this.owners = owners;
        this.staffMembers = staffMembers;
        this.scheduleRevision = scheduleRevision;
        this.clock = clock;
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public ScheduleExceptionWindow list(
            AuthenticatedBusinessContext context, LocalDate from, LocalDate to) {
        BusinessSelection selection = authorizeRead(context);
        Window window = validator.validateWindow(from, to);
        List<ScheduleExceptionDetails> exceptions = store
                .findOverlapping(selection.businessId(), window.from(), window.to())
                .stream()
                .map(this::details)
                .toList();
        return new ScheduleExceptionWindow(
                window.from(), window.to(), selection.timezone(), exceptions);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public ScheduleExceptionAdministrationDetails get(
            AuthenticatedBusinessContext context, UUID exceptionId) {
        BusinessSelection selection = authorizeRead(context);
        ScheduleException stored = requireException(selection.businessId(), exceptionId);
        return administrationDetails(stored, selection.timezone());
    }

    @Override
    @Transactional
    public ScheduleExceptionAdministrationDetails create(
            AuthenticatedBusinessContext context, CreateScheduleExceptionCommand command) {
        BusinessSelection selection = authorizeMutation(context);
        ScheduleExceptionContent content = validator.validateCreate(command);
        lockStaffMemberIfScoped(selection.businessId(), content);
        advanceScheduleRevision(selection.businessId());
        NewScheduleException newException = new NewScheduleException(
                UUID.randomUUID(), selection.businessId(), content, clock.instant());
        ScheduleException created = persist(() -> store.insert(newException));
        return administrationDetails(created, selection.timezone());
    }

    @Override
    @Transactional
    public ScheduleExceptionAdministrationDetails replace(
            AuthenticatedBusinessContext context,
            UUID exceptionId,
            ReplaceScheduleExceptionCommand command) {
        BusinessSelection selection = authorizeMutation(context);
        ValidatedReplacement replacement = validator.validateReplacement(command);
        ScheduleException current = requireException(selection.businessId(), exceptionId);
        ScheduleExceptionContent stored = current.content();
        ScheduleExceptionContent content = validator.content(
                stored.kind(), stored.staffMemberId(), replacement.shape());
        lockStaffMemberIfScoped(selection.businessId(), content);
        requireCurrentVersion(current, replacement.expectedVersion());
        advanceScheduleRevision(selection.businessId());
        ScheduleException replaced = persist(() -> store.replace(
                        selection.businessId(),
                        current.id(),
                        replacement.expectedVersion(),
                        content,
                        clock.instant()))
                .orElseThrow(ConcurrentUpdate::new);
        return administrationDetails(replaced, selection.timezone());
    }

    @Override
    @Transactional
    public void delete(
            AuthenticatedBusinessContext context, UUID exceptionId, Long expectedVersion) {
        BusinessSelection selection = authorizeMutation(context);
        long validatedVersion = validator.validateExpectedVersion(expectedVersion);
        ScheduleException current = requireException(selection.businessId(), exceptionId);
        lockStaffMemberIfScoped(selection.businessId(), current.content());
        requireCurrentVersion(current, validatedVersion);
        advanceScheduleRevision(selection.businessId());
        boolean deleted = persist(() -> store.delete(
                selection.businessId(), current.id(), validatedVersion));
        if (!deleted) {
            throw new ConcurrentUpdate();
        }
    }

    private BusinessSelection authorizeRead(AuthenticatedBusinessContext context) {
        UUID userId = requireUserId(context);
        UUID businessId = requireSelectedBusinessId(context);
        BusinessScheduleContext scheduleContext = businesses.findScheduleContext(businessId)
                .orElseThrow(BusinessAccessDenied::new);
        if (owners.authorize(userId, businessId) != Authorization.GRANTED) {
            throw new BusinessAccessDenied();
        }
        return new BusinessSelection(businessId, scheduleContext.timezone());
    }

    private BusinessSelection authorizeMutation(AuthenticatedBusinessContext context) {
        UUID userId = requireUserId(context);
        UUID businessId = requireSelectedBusinessId(context);
        BusinessScheduleContext scheduleContext = businesses.lockScheduleContext(businessId)
                .orElseThrow(BusinessAccessDenied::new);
        if (owners.lockAndAuthorize(userId, businessId) != Authorization.GRANTED) {
            throw new BusinessAccessDenied();
        }
        if (scheduleContext.status() == LifecycleStatus.SUSPENDED) {
            throw new BusinessSuspended();
        }
        return new BusinessSelection(businessId, scheduleContext.timezone());
    }

    private UUID requireUserId(AuthenticatedBusinessContext context) {
        if (context == null || context.userId() == null) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required");
        }
        return context.userId();
    }

    private UUID requireSelectedBusinessId(AuthenticatedBusinessContext context) {
        return context.selectedBusinessId().orElseThrow(SelectedBusinessRequired::new);
    }

    private ScheduleException requireException(UUID businessId, UUID exceptionId) {
        if (exceptionId == null) {
            throw new ScheduleExceptionNotFound();
        }
        return store.findByBusinessIdAndId(businessId, exceptionId)
                .orElseThrow(ScheduleExceptionNotFound::new);
    }

    private void lockStaffMemberIfScoped(UUID businessId, ScheduleExceptionContent content) {
        if (!content.kind().isStaffScoped()) {
            return;
        }
        StaffMemberReference reference = staffMembers.lockReference(
                        businessId, content.staffMemberId())
                .orElseThrow(StaffMemberNotFound::new);
        if (!reference.active()) {
            throw new StaffMemberInactive();
        }
    }

    private void requireCurrentVersion(ScheduleException current, long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ConcurrentUpdate();
        }
    }

    private void advanceScheduleRevision(UUID businessId) {
        try {
            scheduleRevision.advance(businessId);
        } catch (ScheduleRevisionConcurrentConflict conflict) {
            throw new ConcurrentUpdate();
        }
    }

    private <T> T persist(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ScheduleExceptionPersistenceException.OverlapConflict exception) {
            throw new OverlapConflict();
        } catch (ScheduleExceptionPersistenceException.ConcurrentWriteConflict exception) {
            throw new ConcurrentUpdate();
        }
    }

    private ScheduleExceptionAdministrationDetails administrationDetails(
            ScheduleException stored, ZoneId timezone) {
        return new ScheduleExceptionAdministrationDetails(details(stored), timezone);
    }

    private ScheduleExceptionDetails details(ScheduleException stored) {
        ScheduleExceptionContent content = stored.content();
        return new ScheduleExceptionDetails(
                stored.id(),
                content.kind(),
                content.staffMemberId(),
                content.firstDate(),
                content.lastDate(),
                content.allDay(),
                content.periods().stream()
                        .map(period -> new ExceptionPeriod(period.start(), period.end()))
                        .toList(),
                stored.version(),
                stored.createdAt(),
                stored.updatedAt());
    }

    private record BusinessSelection(UUID businessId, ZoneId timezone) {
    }
}
