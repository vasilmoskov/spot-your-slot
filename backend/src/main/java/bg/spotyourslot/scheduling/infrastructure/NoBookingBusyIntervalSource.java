package bg.spotyourslot.scheduling.infrastructure;

import bg.spotyourslot.scheduling.BusyIntervalSource;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * TEMPORARY placeholder: no Appointment exists yet, so nothing is busy. It
 * performs no SQL.
 *
 * <p>It is deliberately an ordinary required bean, not a conditional fallback.
 * When the Booking module supplies the real {@link BusyIntervalSource} there
 * will be two beans and startup fails until this class and its wiring test
 * are deleted, so the placeholder cannot silently keep hiding booked time.
 */
@Component
public class NoBookingBusyIntervalSource implements BusyIntervalSource {
    @Override
    public Map<UUID, List<BusyWindow>> findBusyWindows(
            UUID businessId, Collection<UUID> staffMemberIds, Instant from, Instant to) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(staffMemberIds, "staffMemberIds");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return Map.of();
    }
}
