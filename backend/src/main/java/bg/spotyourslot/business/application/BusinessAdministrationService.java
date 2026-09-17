package bg.spotyourslot.business.application;

import bg.spotyourslot.business.BusinessAdministration;
import bg.spotyourslot.business.BusinessApplicationException.BusinessNotFound;
import bg.spotyourslot.business.BusinessApplicationException.BusinessSlugConflict;
import bg.spotyourslot.business.BusinessApplicationException.ConcurrentUpdate;
import bg.spotyourslot.business.BusinessApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.business.BusinessRecords.BusinessDetails;
import bg.spotyourslot.business.BusinessRecords.BusinessPage;
import bg.spotyourslot.business.BusinessRecords.BusinessSummary;
import bg.spotyourslot.business.BusinessRecords.CreateBusinessCommand;
import bg.spotyourslot.business.BusinessRecords.UpdateBusinessCommand;
import bg.spotyourslot.business.BusinessLifecycleAccess;
import bg.spotyourslot.business.BusinessLifecycleAccess.BusinessLifecycle;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.application.BusinessInputValidator.PageInput;
import bg.spotyourslot.business.domain.BusinessSlug;
import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessTimezone;
import bg.spotyourslot.business.infrastructure.BusinessProfileUpdateRow;
import bg.spotyourslot.business.infrastructure.BusinessRow;
import bg.spotyourslot.business.infrastructure.BusinessStore;
import bg.spotyourslot.business.infrastructure.NewBusinessRow;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessAdministrationService
        implements BusinessAdministration, BusinessLifecycleAccess {
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private final BusinessStore store;
    private final BusinessInputValidator validator;
    private final Clock clock;

    public BusinessAdministrationService(
            BusinessStore store, BusinessInputValidator validator, Clock clock) {
        this.store = store;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public BusinessPage list(int page, int size) {
        PageInput validated = validator.validatePage(page, size);
        var businesses = store.list(validated.page(), validated.size()).stream()
                .map(this::summary)
                .toList();
        return new BusinessPage(
                businesses, validated.page(), validated.size(), store.count());
    }

    @Override
    @Transactional(readOnly = true)
    public BusinessDetails get(UUID businessId) {
        UUID validatedId = validator.validateBusinessId(businessId);
        return details(requireBusiness(validatedId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<BusinessLifecycle> findLifecycle(UUID businessId) {
        return store.findById(businessId).map(this::lifecycle);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BusinessLifecycle> lockLifecycle(UUID businessId) {
        return store.findByIdForShare(businessId).map(this::lifecycle);
    }

    @Override
    @Transactional
    public BusinessDetails create(CreateBusinessCommand command) {
        CreateBusinessCommand validated = validator.validateCreate(command);
        Instant now = clock.instant();
        var newBusiness = new NewBusinessRow(
                UUID.randomUUID(),
                new BusinessSlug(validated.slug()),
                validated.displayName(),
                validated.businessType(),
                new BusinessTimezone(validated.timezone()),
                validated.description(),
                validated.city(),
                validated.postalCode(),
                validated.street(),
                validated.streetNumber(),
                validated.addressDetails(),
                validated.phone(),
                validated.contactEmail(),
                now);

        try {
            return details(store.create(newBusiness));
        } catch (DataIntegrityViolationException exception) {
            throw translateUniqueViolation(exception);
        }
    }

    @Override
    @Transactional
    public BusinessDetails update(UUID businessId, UpdateBusinessCommand command) {
        UUID validatedId = validator.validateBusinessId(businessId);
        UpdateBusinessCommand validated = validator.validateUpdate(command);
        BusinessRow current = requireBusiness(validatedId);
        requireCurrentVersion(current, validated.expectedVersion());

        var update = new BusinessProfileUpdateRow(
                new BusinessSlug(validated.slug()),
                validated.displayName(),
                validated.businessType(),
                new BusinessTimezone(validated.timezone()),
                validated.description(),
                validated.city(),
                validated.postalCode(),
                validated.street(),
                validated.streetNumber(),
                validated.addressDetails(),
                validated.phone(),
                validated.contactEmail(),
                validated.expectedVersion(),
                clock.instant());

        try {
            return details(store.updateProfile(validatedId, update)
                    .orElseThrow(ConcurrentUpdate::new));
        } catch (DataIntegrityViolationException exception) {
            throw translateUniqueViolation(exception);
        }
    }

    @Override
    @Transactional
    public BusinessDetails activateDraft(UUID businessId, long expectedVersion) {
        return transition(
                businessId, expectedVersion, BusinessStatus.DRAFT, BusinessStatus.ACTIVE);
    }

    @Override
    @Transactional
    public BusinessDetails suspendActive(UUID businessId, long expectedVersion) {
        return transition(
                businessId, expectedVersion, BusinessStatus.ACTIVE, BusinessStatus.SUSPENDED);
    }

    @Override
    @Transactional
    public BusinessDetails reactivateSuspended(UUID businessId, long expectedVersion) {
        return transition(
                businessId, expectedVersion, BusinessStatus.SUSPENDED, BusinessStatus.ACTIVE);
    }

    private BusinessDetails transition(
            UUID businessId,
            long expectedVersion,
            BusinessStatus requiredStatus,
            BusinessStatus targetStatus) {
        UUID validatedId = validator.validateBusinessId(businessId);
        long validatedVersion = validator.validateExpectedVersion(expectedVersion);
        BusinessRow current = requireBusiness(validatedId);
        requireCurrentVersion(current, validatedVersion);
        if (current.status() != requiredStatus
                || !current.status().canTransitionTo(targetStatus)) {
            throw new InvalidLifecycleTransition(current.status(), targetStatus);
        }

        return details(store.transition(
                        validatedId,
                        requiredStatus,
                        targetStatus,
                        validatedVersion,
                        clock.instant())
                .orElseThrow(ConcurrentUpdate::new));
    }

    private BusinessRow requireBusiness(UUID businessId) {
        return store.findById(businessId).orElseThrow(BusinessNotFound::new);
    }

    private void requireCurrentVersion(BusinessRow current, long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ConcurrentUpdate();
        }
    }

    private RuntimeException translateUniqueViolation(
            DataIntegrityViolationException exception) {
        if (containsSqlState(exception, UNIQUE_VIOLATION_SQL_STATE)) {
            return new BusinessSlugConflict();
        }
        return exception;
    }

    private boolean containsSqlState(Throwable failure, String expectedSqlState) {
        Set<Throwable> visited =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof SQLException sqlException
                    && sqlStateMatches(sqlException, expectedSqlState, visited)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean sqlStateMatches(
            SQLException exception, String expectedSqlState, Set<Throwable> visited) {
        SQLException current = exception;
        while (current != null) {
            if (expectedSqlState.equals(current.getSQLState())) {
                return true;
            }
            SQLException next = current.getNextException();
            if (next == null || !visited.add(next)) {
                return false;
            }
            current = next;
        }
        return false;
    }

    private BusinessSummary summary(BusinessRow row) {
        return new BusinessSummary(
                row.id(),
                row.slug().value(),
                row.displayName(),
                row.businessType(),
                row.status(),
                row.timezone().value(),
                row.version(),
                row.createdAt(),
                row.updatedAt());
    }

    private BusinessDetails details(BusinessRow row) {
        return new BusinessDetails(
                row.id(),
                row.slug().value(),
                row.displayName(),
                row.businessType(),
                row.status(),
                row.timezone().value(),
                row.description(),
                row.city(),
                row.postalCode(),
                row.street(),
                row.streetNumber(),
                row.addressDetails(),
                row.phone(),
                row.contactEmail(),
                row.version(),
                row.createdAt(),
                row.updatedAt());
    }

    private BusinessLifecycle lifecycle(BusinessRow row) {
        return new BusinessLifecycle(
                row.id(), LifecycleStatus.valueOf(row.status().name()));
    }
}
