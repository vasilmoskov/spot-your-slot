package bg.spotyourslot.booking.infrastructure;

import bg.spotyourslot.booking.domain.BlockingWindow;
import bg.spotyourslot.scheduling.BusyIntervalSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Booking-owned implementation of the Scheduling busy-interval seam (ADR-0016, ADR-0022). It
 * replaces the temporary placeholder: occupied time is the half-open
 * {@code [start_at, occupied_until)} of {@code CONFIRMED} Appointments only.
 *
 * <p>It requires the caller's transaction ({@code MANDATORY}) so its answer belongs to the
 * orchestration's database snapshot, never opens or suspends one, and answers all requested
 * StaffMembers of the Business with one bulk statement. An absent key means no busy time. The
 * returned map and every list are deeply immutable, and each list is ordered by start and then end.
 * {@code scheduling} never depends on this class.
 */
@Component
public class BookingBusyIntervalSource implements BusyIntervalSource {
    private final AppointmentStore appointments;

    public BookingBusyIntervalSource(AppointmentStore appointments) {
        this.appointments = appointments;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, List<BusyWindow>> findBusyWindows(
            UUID businessId, Collection<UUID> staffMemberIds, Instant from, Instant to) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(staffMemberIds, "staffMemberIds");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        requireOrderedWindow(from, to);
        return grouped(appointments.findBlockingWindows(businessId, staffMemberIds, from, to));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, List<BusyWindow>> findBusyWindows(
            UUID businessId,
            Collection<UUID> staffMemberIds,
            Instant from,
            Instant to,
            UUID ignoredAppointmentId) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(staffMemberIds, "staffMemberIds");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(ignoredAppointmentId, "ignoredAppointmentId");
        requireOrderedWindow(from, to);
        return grouped(appointments.findBlockingWindows(
                businessId, staffMemberIds, from, to, ignoredAppointmentId));
    }

    private static void requireOrderedWindow(Instant from, Instant to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("The query window start must not be after its end");
        }
    }

    private static Map<UUID, List<BusyWindow>> grouped(List<BlockingWindow> windows) {
        Map<UUID, List<BusyWindow>> grouped = new LinkedHashMap<>();
        for (BlockingWindow window : windows) {
            grouped.computeIfAbsent(window.staffMemberId(), key -> new ArrayList<>())
                    .add(new BusyWindow(window.start(), window.end()));
        }
        Map<UUID, List<BusyWindow>> result = new LinkedHashMap<>();
        grouped.forEach((staffMemberId, list) -> result.put(staffMemberId, List.copyOf(list)));
        return Map.copyOf(result);
    }
}
