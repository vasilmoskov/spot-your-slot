package bg.spotyourslot.catalog.application;

import bg.spotyourslot.catalog.ServiceBookingAccess;
import bg.spotyourslot.catalog.infrastructure.ServiceStore;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link ServiceBookingAccess}: {@code MANDATORY}, so it joins the caller's transaction,
 * and sanitized, because failures are classified from the SQLState only and discarded.
 */
@Component
public class ServiceBookingAccessService implements ServiceBookingAccess {
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";

    private final ServiceStore store;

    public ServiceBookingAccessService(ServiceStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BookingService> lockForBooking(UUID businessId, UUID serviceId) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(serviceId, "serviceId");
        try {
            return store.lockReferences(businessId, List.of(serviceId)).stream()
                    .findFirst()
                    .map(row -> new BookingService(
                            row.id(), row.name(), row.durationMinutes(), row.price(), row.active()));
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
