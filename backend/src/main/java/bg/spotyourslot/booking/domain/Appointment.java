package bg.spotyourslot.booking.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A persisted Appointment: one booked time of one StaffMember for one Customer of one Business
 * (ADR-0022). It carries the snapshots agreed at booking time and no Customer contact data. It is
 * deeply immutable and redacts {@link #toString()} because the note and names are personal data.
 */
public record Appointment(
        UUID id,
        UUID businessId,
        UUID customerId,
        UUID serviceId,
        UUID staffMemberId,
        AppointmentSource source,
        AppointmentStatus status,
        Instant startAt,
        Instant endAt,
        Instant occupiedUntil,
        String timezone,
        int durationMinutes,
        BigDecimal priceEur,
        String serviceName,
        String staffDisplayName,
        String customerNote,
        String publicReference,
        BookingAttempt attempt,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public Appointment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(staffMemberId, "staffMemberId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        Objects.requireNonNull(occupiedUntil, "occupiedUntil");
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(priceEur, "priceEur");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(staffDisplayName, "staffDisplayName");
        Objects.requireNonNull(publicReference, "publicReference");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        AppointmentRules.requireInstant(startAt);
        AppointmentRules.requireInstant(endAt);
        AppointmentRules.requireDuration(durationMinutes);
        if (!endAt.equals(startAt.plus(Duration.ofMinutes(durationMinutes)))) {
            throw new IllegalArgumentException("Appointment end must match its duration");
        }
        if (!occupiedUntil.equals(endAt)) {
            throw new IllegalArgumentException("Appointment occupied end must equal its end");
        }
        priceEur = AppointmentRules.requirePrice(priceEur);
        AppointmentRules.requireTimezone(timezone);
        AppointmentRules.requireSnapshotName(serviceName);
        AppointmentRules.requireSnapshotName(staffDisplayName);
        AppointmentRules.requireNote(customerNote);
        AppointmentRules.requirePublicReference(publicReference);
        if (version < 0) {
            throw new IllegalArgumentException("Appointment version must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Appointment update time must not precede creation");
        }
        if (source == AppointmentSource.ONLINE && attempt == null) {
            throw new IllegalArgumentException("An online Appointment requires a booking attempt");
        }
    }

    /** Only a {@code CONFIRMED} Appointment blocks its StaffMember's time. */
    public boolean blocksTime() {
        return status == AppointmentStatus.CONFIRMED;
    }

    @Override
    public String toString() {
        return "Appointment[redacted]";
    }
}
