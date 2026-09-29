package bg.spotyourslot.catalog;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow published contract that lets availability calculation read the one
 * Service fact it needs: the occupied duration of a bookable Service. It
 * exposes no name, price, version, or timestamp and takes no lock.
 */
public interface ServiceAvailabilityAccess {
    /**
     * Reads inside the caller's transaction; a caller without one is rejected.
     *
     * @return the Service, or empty when it does not exist in the Business or is inactive
     */
    Optional<BookableService> findBookableService(UUID businessId, UUID serviceId);

    /**
     * @param duration the committed Service duration; the Catalog invariant is one
     *        through 480 whole minutes, which the caller verifies defensively
     */
    record BookableService(UUID id, Duration duration) {
        public BookableService {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(duration, "duration");
        }
    }

    final class ServiceAvailabilityFailure extends RuntimeException {
        public ServiceAvailabilityFailure(Throwable cause) {
            super("Service availability access failed", cause);
        }
    }
}
