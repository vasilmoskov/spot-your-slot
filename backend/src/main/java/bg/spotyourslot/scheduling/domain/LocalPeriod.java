package bg.spotyourslot.scheduling.domain;

import java.time.LocalTime;
import java.util.Objects;

/**
 * A half-open local wall-clock range {@code [start, end)} within one local date.
 * Both bounds have whole-minute precision, so the representable range is
 * {@code 00:00} through {@code 23:59}.
 */
public record LocalPeriod(LocalTime start, LocalTime end) {
    public LocalPeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        requireWholeMinute(start, "start");
        requireWholeMinute(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Local period start must be before its end");
        }
    }

    private static void requireWholeMinute(LocalTime time, String name) {
        if (time.getSecond() != 0 || time.getNano() != 0) {
            throw new IllegalArgumentException("Local period " + name + " must have whole-minute precision");
        }
    }
}
