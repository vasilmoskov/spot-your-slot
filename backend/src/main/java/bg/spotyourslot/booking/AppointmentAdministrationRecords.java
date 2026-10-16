package bg.spotyourslot.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Published, immutable inputs and results of {@link AppointmentAdministration}. */
public final class AppointmentAdministrationRecords {
    private AppointmentAdministrationRecords() {
    }

    /**
     * @param customerId an existing Customer of the selected Business
     * @param staffMemberId the one StaffMember who performs the whole visit
     * @param start the requested start instant
     * @param services one to five distinct Services with the terms the owner reviewed
     * @param internalNote an optional private note, or {@code null}
     */
    public record CreateAppointmentCommand(
            UUID customerId,
            UUID staffMemberId,
            Instant start,
            List<ReviewedService> services,
            String internalNote) {
        public CreateAppointmentCommand {
            services = services == null ? null : List.copyOf(services);
        }

        @Override
        public String toString() {
            return "CreateAppointmentCommand[redacted]";
        }
    }

    /**
     * The one atomic change of a stored Appointment's start and/or StaffMember (ADR-0028 section 3).
     * The Customer, the Services, the duration, and the price cannot be named here.
     *
     * @param appointmentId the Appointment of the selected Business
     * @param expectedVersion the version the owner saw; required, not negative
     * @param start the new start instant, or {@code null} to keep the current start
     * @param staffMemberId the new StaffMember, or {@code null} to keep the current one; naming the
     *        current StaffMember is the same as {@code null}
     */
    public record RescheduleAppointmentCommand(
            UUID appointmentId, long expectedVersion, Instant start, UUID staffMemberId) {
        @Override
        public String toString() {
            return "RescheduleAppointmentCommand[redacted]";
        }
    }

    /** The terms of one Service as the owner reviewed them; compared, never stored. */
    public record ReviewedService(
            UUID serviceId, String serviceName, int durationMinutes, long priceCents) {
        @Override
        public String toString() {
            return "ReviewedService[redacted]";
        }
    }

    /**
     * The created visit. It carries no Customer name, phone, email, or Customer note.
     */
    public record CreatedAppointment(
            UUID id,
            String reference,
            String status,
            String source,
            Instant start,
            Instant end,
            String timezone,
            UUID staffMemberId,
            String staffDisplayName,
            UUID customerId,
            List<CreatedService> services,
            int durationMinutes,
            BigDecimal price,
            String internalNote,
            long version,
            Instant createdAt) {
        public CreatedAppointment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(timezone, "timezone");
            services = List.copyOf(services);
        }

        @Override
        public String toString() {
            return "CreatedAppointment[redacted]";
        }
    }

    /** One stored Service line, in its stored position. */
    public record CreatedService(int position, String name, int durationMinutes, BigDecimal price) {
        @Override
        public String toString() {
            return "CreatedService[redacted]";
        }
    }

    /**
     * The updated visit: the stored facts only (no Customer name, phone, email, or Customer note).
     * Customer identity, Services, duration, price, source, and reference are exactly as before.
     */
    public record RescheduledAppointment(
            UUID id,
            String reference,
            String status,
            String source,
            Instant start,
            Instant end,
            String timezone,
            UUID staffMemberId,
            String staffDisplayName,
            UUID customerId,
            List<CreatedService> services,
            int durationMinutes,
            BigDecimal price,
            String internalNote,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        public RescheduledAppointment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(timezone, "timezone");
            Objects.requireNonNull(updatedAt, "updatedAt");
            services = List.copyOf(services);
        }

        @Override
        public String toString() {
            return "RescheduledAppointment[redacted]";
        }
    }

    /**
     * The Business cancellation of one Appointment (ADR-0028 section 8): no reason, no actor, no
     * time. The actor is always the Business and the time is the attempt's clock instant.
     *
     * @param appointmentId the Appointment of the selected Business
     * @param expectedVersion the version the owner saw; required, not negative
     */
    public record CancelAppointmentCommand(UUID appointmentId, long expectedVersion) {
        @Override
        public String toString() {
            return "CancelAppointmentCommand[redacted]";
        }
    }

    /**
     * The edit of the private internal note (ADR-0028 section 9).
     *
     * @param appointmentId the Appointment of the selected Business
     * @param expectedVersion the version the owner saw; required, not negative
     * @param internalNote the new note as submitted; required (not {@code null}); a blank note clears it
     */
    public record EditInternalNoteCommand(UUID appointmentId, long expectedVersion, String internalNote) {
        @Override
        public String toString() {
            return "EditInternalNoteCommand[redacted]";
        }
    }

    /**
     * The verified result of a cancellation or an internal-note edit: the stored facts of the
     * Appointment read after the transaction completed. It carries no Customer name, phone, email, or
     * Customer note, and no cancellation actor.
     */
    public record UpdatedAppointment(
            UUID id,
            String reference,
            String status,
            String source,
            Instant start,
            Instant end,
            String timezone,
            UUID staffMemberId,
            String staffDisplayName,
            UUID customerId,
            List<CreatedService> services,
            int durationMinutes,
            BigDecimal price,
            String internalNote,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        public UpdatedAppointment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(timezone, "timezone");
            Objects.requireNonNull(updatedAt, "updatedAt");
            services = List.copyOf(services);
        }

        @Override
        public String toString() {
            return "UpdatedAppointment[redacted]";
        }
    }
}
