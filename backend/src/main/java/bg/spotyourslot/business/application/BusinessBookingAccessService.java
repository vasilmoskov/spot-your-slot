package bg.spotyourslot.business.application;

import bg.spotyourslot.business.BusinessBookingAccess;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.domain.BusinessSlug;
import bg.spotyourslot.business.domain.ReservedBusinessSlugs;
import bg.spotyourslot.business.infrastructure.BusinessStore;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link BusinessBookingAccess}. The operation is {@code MANDATORY}: it joins the
 * caller's transaction and never opens or suspends one. Failures are classified from the PostgreSQL
 * SQLState only and the underlying exception is discarded, because it can carry SQL and values.
 */
@Component
public class BusinessBookingAccessService implements BusinessBookingAccess {
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";

    private final BusinessStore store;

    public BusinessBookingAccessService(BusinessStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BookingBusiness> lockBySlug(String slug) {
        Optional<BusinessSlug> canonical = canonicalSlug(slug);
        if (canonical.isEmpty()) {
            return Optional.empty();
        }
        try {
            return store.lockBookingReferenceBySlug(canonical.orElseThrow())
                    .map(row -> new BookingBusiness(
                            row.id(), LifecycleStatus.valueOf(row.status().name())));
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    /** A malformed slug can never identify a Business and a reserved root is never a page. */
    private static Optional<BusinessSlug> canonicalSlug(String slug) {
        if (slug == null) {
            return Optional.empty();
        }
        BusinessSlug canonical;
        try {
            canonical = new BusinessSlug(slug);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        if (ReservedBusinessSlugs.isReserved(canonical)) {
            return Optional.empty();
        }
        return Optional.of(canonical);
    }

    private static RuntimeException translate(DataAccessException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return switch (sqlException.getSQLState()) {
                    case SERIALIZATION_FAILURE, DEADLOCK_DETECTED -> new ConcurrentConflict();
                    default -> new Failure();
                };
            }
        }
        return new Failure();
    }
}
