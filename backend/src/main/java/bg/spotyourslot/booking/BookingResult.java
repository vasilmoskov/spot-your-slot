package bg.spotyourslot.booking;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Every outcome of {@link GuestBooking#book}. The variants map to the codes of ADR-0026 and the
 * messages of ADR-0024 in the later public phase; none carries a submitted value, an identifier of
 * another record, or internal diagnostics.
 *
 * <p>{@link TemporarilyUnavailable} and {@link OutcomeUncertain} are deliberately separate: the
 * first is a <em>proven rollback</em> (nothing was committed), the second means a commit may have
 * succeeded or a stored replay could not be verified, so the guest must repeat the same attempt.
 */
public sealed interface BookingResult {
    /** A new Appointment was committed (HTTP 201). */
    record Created(BookedAppointment appointment) implements BookingResult {
        public Created {
            Objects.requireNonNull(appointment, "appointment");
        }
    }

    /** The attempt was already committed with the same request; nothing was written (HTTP 200). */
    record Replayed(BookedAppointment appointment) implements BookingResult {
        public Replayed {
            Objects.requireNonNull(appointment, "appointment");
        }
    }

    /** The Business is missing, DRAFT, or SUSPENDED for a new booking (collapsed, ADR-0017). */
    record BusinessUnavailable() implements BookingResult {
    }

    /** The request failed validation; only the field identifiers are reported. */
    record InvalidRequest(Set<BookingField> fields) implements BookingResult {
        public InvalidRequest {
            if (fields == null || fields.isEmpty()) {
                throw new IllegalArgumentException("At least one invalid field is required");
            }
            fields = Collections.unmodifiableSet(EnumSet.copyOf(fields));
        }
    }

    /** The Service is missing, foreign, or inactive. */
    record ServiceUnavailable() implements BookingResult {
    }

    /** The requested StaffMember is missing, foreign, inactive, or not assigned to the Service. */
    record StaffMemberUnavailable() implements BookingResult {
    }

    /** The start is not (or no longer) an offered, free slot. */
    record SlotUnavailable() implements BookingResult {
    }

    /** The submitted identity conflicts with stored Customers; deliberately uninformative (ADR-0020). */
    record IdentityConflict() implements BookingResult {
    }

    /** The attempt identifier was already used with a different normalized request. */
    record AttemptMismatch() implements BookingResult {
    }

    /** A proven rollback after exhausted retries or an internal failure; nothing was committed. */
    record TemporarilyUnavailable() implements BookingResult {
    }

    /** A commit may have succeeded, or a stored replay could not be verified; repeat the same attempt. */
    record OutcomeUncertain() implements BookingResult {
    }
}
