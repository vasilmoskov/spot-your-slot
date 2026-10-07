package bg.spotyourslot.workforce.application;

import bg.spotyourslot.workforce.StaffBookingAccess;
import bg.spotyourslot.workforce.infrastructure.StaffMemberStore;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link StaffBookingAccess}: {@code MANDATORY}, so it joins the caller's transaction,
 * and sanitized, because failures are classified from the SQLState only and discarded.
 */
@Component
public class StaffBookingAccessService implements StaffBookingAccess {
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";

    private final StaffMemberStore store;

    public StaffBookingAccessService(StaffMemberStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<BookingStaffMember> lockEligibleForBooking(
            UUID businessId, UUID serviceId, UUID requestedStaffMemberId) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(serviceId, "serviceId");
        try {
            return store.lockEligibleForBooking(businessId, serviceId, requestedStaffMemberId).stream()
                    .map(row -> new BookingStaffMember(row.id(), row.displayName(), row.createdAt()))
                    .toList();
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private static RuntimeException translate(RuntimeException exception) {
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
