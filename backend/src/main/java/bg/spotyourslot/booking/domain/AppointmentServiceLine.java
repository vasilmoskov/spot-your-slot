package bg.spotyourslot.booking.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One Service of a visit (ADR-0027): the immutable snapshot of a Service as it was when the visit
 * was booked. {@code position} is the 1-based execution order, which is the public-profile order of
 * the Services. The name, duration, and price are copied from the locked Service row; the line price
 * is a Service price (at most ten integer digits and two fraction digits), never a visit total.
 */
public record AppointmentServiceLine(
        int position,
        UUID serviceId,
        String serviceName,
        int durationMinutes,
        BigDecimal priceEur) {
    public AppointmentServiceLine {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(priceEur, "priceEur");
        AppointmentRules.requireLinePosition(position);
        AppointmentRules.requireDuration(durationMinutes);
        AppointmentRules.requireSnapshotName(serviceName);
        priceEur = AppointmentRules.requirePrice(priceEur);
    }
}
