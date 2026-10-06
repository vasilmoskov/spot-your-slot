package bg.spotyourslot.scheduling;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Scheduling-owned seam for occupied StaffMember time. The Booking module
 * implements it ({@code booking.infrastructure.BookingBusyIntervalSource},
 * ADR-0016, ADR-0022) and depends on {@code scheduling}; {@code scheduling}
 * never depends on Booking. Exactly one implementation must be a bean, so a
 * second one makes startup fail rather than silently hiding booked time.
 *
 * <p>Contract for every implementation:
 * <ul>
 *   <li>Query {@code [from, to)} is half-open, as is every returned window
 *       {@code [start, end)}; a window that only touches a boundary does not
 *       overlap and is not returned.
 *   <li>Only the requested StaffMembers of the requested Business appear as keys.
 *       An absent key means no busy time. The returned map and every list are
 *       deeply immutable, and each list is ordered by start, then end.
 *   <li>The implementation joins the caller's transaction, which the
 *       orchestration has already required to be repeatable-read or
 *       serializable, and answers all StaffMembers with one bulk query. It must
 *       not open its own transaction or query once per StaffMember; only then is
 *       its answer part of the orchestration's database snapshot.
 *   <li>Busy time always blocks, even where a schedule exception would too.
 * </ul>
 */
public interface BusyIntervalSource {
    Map<UUID, List<BusyWindow>> findBusyWindows(
            UUID businessId, Collection<UUID> staffMemberIds, Instant from, Instant to);

    /** An occupied half-open instant range {@code [start, end)}. */
    record BusyWindow(Instant start, Instant end) {
        public BusyWindow {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("Busy window start must be before its end");
            }
        }
    }
}
