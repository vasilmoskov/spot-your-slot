package bg.spotyourslot.booking.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An Appointment about to be inserted. Status {@code CONFIRMED}, version 0, and the equal update
 * instant are implied; the Service end and the occupied end are derived from the snapshotted
 * duration as elapsed time. The current zero-buffer policy makes both equal ({@link #endAt()}).
 *
 * <p>The values are snapshots the caller read from the locked Service, StaffMember, and Business
 * rows (ADR-0022): Phase 2 neither reads nor recomputes them. No Customer name, phone, or email is
 * part of an Appointment. The record is deeply immutable and redacts {@link #toString()}, because
 * the note and names are personal data.
 */
public record NewAppointment(
        UUID id,
        UUID businessId,
        UUID customerId,
        UUID serviceId,
        UUID staffMemberId,
        AppointmentSource source,
        Instant startAt,
        int durationMinutes,
        BigDecimal priceEur,
        String timezone,
        String serviceName,
        String staffDisplayName,
        String customerNote,
        String publicReference,
        BookingAttempt attempt,
        Instant createdAt,
        List<AppointmentServiceLine> serviceLines) {
    public NewAppointment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(staffMemberId, "staffMemberId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(priceEur, "priceEur");
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(staffDisplayName, "staffDisplayName");
        Objects.requireNonNull(publicReference, "publicReference");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(serviceLines, "serviceLines");
        AppointmentRules.requireInstant(startAt);
        AppointmentRules.requireInstant(createdAt);
        AppointmentRules.requireDuration(durationMinutes);
        AppointmentRules.requireInstant(startAt.plus(Duration.ofMinutes(durationMinutes)));
        priceEur = AppointmentRules.requireVisitPrice(priceEur);
        AppointmentRules.requireTimezone(timezone);
        AppointmentRules.requireSnapshotName(serviceName);
        AppointmentRules.requireSnapshotName(staffDisplayName);
        AppointmentRules.requireNote(customerNote);
        AppointmentRules.requirePublicReference(publicReference);
        serviceLines = AppointmentRules.requireVisit(
                serviceLines, serviceId, serviceName, durationMinutes, priceEur);
        if (source == AppointmentSource.ONLINE && attempt == null) {
            throw new IllegalArgumentException("An online Appointment requires a booking attempt");
        }
    }

    /**
     * A single-Service Appointment: its one line is copied from the headline values, so every caller
     * written before multi-Service visits (ADR-0027) keeps working unchanged. The duration and price
     * are then the totals of that one line.
     */
    public NewAppointment(
            UUID id,
            UUID businessId,
            UUID customerId,
            UUID serviceId,
            UUID staffMemberId,
            AppointmentSource source,
            Instant startAt,
            int durationMinutes,
            BigDecimal priceEur,
            String timezone,
            String serviceName,
            String staffDisplayName,
            String customerNote,
            String publicReference,
            BookingAttempt attempt,
            Instant createdAt) {
        this(
                id,
                businessId,
                customerId,
                serviceId,
                staffMemberId,
                source,
                startAt,
                durationMinutes,
                priceEur,
                timezone,
                serviceName,
                staffDisplayName,
                customerNote,
                publicReference,
                attempt,
                createdAt,
                List.of(new AppointmentServiceLine(1, serviceId, serviceName, durationMinutes, priceEur)));
    }

    /** The number of Services in the visit, one to five. */
    public int serviceCount() {
        return serviceLines.size();
    }

    /** The Service end: the start plus the snapshotted duration in elapsed time. */
    public Instant endAt() {
        return startAt.plus(Duration.ofMinutes(durationMinutes));
    }

    /** The blocking end. Buffers are zero in the MVP, so it equals {@link #endAt()}. */
    public Instant occupiedUntil() {
        return endAt();
    }

    @Override
    public String toString() {
        return "NewAppointment[redacted]";
    }
}
