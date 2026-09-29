package bg.spotyourslot.scheduling.domain;

import java.time.Instant;
import java.util.Objects;

/** An occupied half-open instant range {@code [start, end)} for one StaffMember. */
public record BusyInterval(Instant start, Instant end) {
    public BusyInterval {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Busy interval start must be before its end");
        }
    }
}
