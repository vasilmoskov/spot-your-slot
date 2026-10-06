package bg.spotyourslot.booking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The half-open occupied range {@code [start, end)} of one {@code CONFIRMED} Appointment. */
public record BlockingWindow(UUID staffMemberId, Instant start, Instant end) {
    public BlockingWindow {
        Objects.requireNonNull(staffMemberId, "staffMemberId");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Blocking window start must be before its end");
        }
    }
}
