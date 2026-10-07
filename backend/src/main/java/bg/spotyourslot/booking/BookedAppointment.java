package bg.spotyourslot.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The facts a guest sees about a booked Appointment: the stored snapshots and the Appointment's
 * <em>current</em> status. It contains no identifier, Customer contact data, or note. A replay
 * returns the original snapshots, never values recomputed from the current Service or StaffMember.
 *
 * @param reference the short informational public reference; it grants no access
 * @param price the EUR price with its stored scale
 * @param timezone the Business timezone snapshot
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
        String timezone) {
    public BookedAppointment {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(staffDisplayName, "staffDisplayName");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(timezone, "timezone");
    }

    /** The current Appointment status; a later-cancelled Appointment replays as {@link #CANCELLED}. */
    public enum Status {
        CONFIRMED,
        CANCELLED
    }
}
