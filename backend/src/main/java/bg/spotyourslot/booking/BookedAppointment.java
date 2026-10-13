package bg.spotyourslot.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The facts a guest sees about a booked Appointment: the stored snapshots and the Appointment's
 * <em>current</em> status. It contains no identifier, Customer contact data, or note. A replay
 * returns the original snapshots, never values recomputed from the current Service or StaffMember.
 *
 * @param reference the short informational public reference; it grants no access
 * @param serviceName the headline: the name of the first Service of the visit
 * @param durationMinutes the visit total
 * @param price the visit total in EUR with its stored scale
 * @param timezone the Business timezone snapshot
 * @param services the Service lines of the visit in execution order (the public-profile order at
 *        booking time), as stored; never recomputed from the current Services
 */
public record BookedAppointment(
        String reference,
        Status status,
        String serviceName,
        int durationMinutes,
        BigDecimal price,
        String staffDisplayName,
        Instant start,
        Instant end,
        String timezone,
        List<BookedService> services) {
    public BookedAppointment {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(staffDisplayName, "staffDisplayName");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(services, "services");
        services = List.copyOf(services);
        if (services.isEmpty()) {
            throw new IllegalArgumentException("A visit has at least one Service line");
        }
    }

    /** A single-Service visit: its one line is the headline (compatible with the pre-visit shape). */
    public BookedAppointment(
            String reference,
            Status status,
            String serviceName,
            int durationMinutes,
            BigDecimal price,
            String staffDisplayName,
            Instant start,
            Instant end,
            String timezone) {
        this(
                reference,
                status,
                serviceName,
                durationMinutes,
                price,
                staffDisplayName,
                start,
                end,
                timezone,
                List.of(new BookedService(serviceName, durationMinutes, price)));
    }

    /**
     * One stored Service line of a visit.
     *
     * @param price the line price in EUR with its stored scale
     */
    public record BookedService(String name, int durationMinutes, BigDecimal price) {
        public BookedService {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(price, "price");
        }
    }

    /** The current Appointment status; a later-cancelled Appointment replays as {@link #CANCELLED}. */
    public enum Status {
        CONFIRMED,
        CANCELLED
    }
}
