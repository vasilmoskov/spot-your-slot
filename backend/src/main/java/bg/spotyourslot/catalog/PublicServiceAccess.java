package bg.spotyourslot.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Narrow published read contract for the Services shown on a public Business profile
 * (ADR-0017, amended by ADR-0026). It exposes only the Service identifier, name, description,
 * duration, and price: no version, timestamp, normalized value, or activity flag. The identifier is
 * a public reference only inside its Business and is never an authority (ADR-0026). It writes nothing and takes no lock.
 */
public interface PublicServiceAccess {
    /**
     * Reads inside the caller's transaction; a caller without one is rejected. One statement,
     * independent of the number of Services.
     *
     * @return every active Service of the Business, ordered by canonical name and then
     *         identifier, without a limit
     */
    List<PublicService> findActiveServices(UUID businessId);

    /**
     * @param price the committed EUR price with its stored scale; the currency is fixed by the
     *        MVP and therefore not part of the contract
     */
    record PublicService(
            UUID id, String name, String description, int durationMinutes, BigDecimal price) {
        public PublicService {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(price, "price");
        }
    }

    final class PublicServiceFailure extends RuntimeException {
        public PublicServiceFailure(Throwable cause) {
            super("Public Service access failed", cause);
        }
    }
}
