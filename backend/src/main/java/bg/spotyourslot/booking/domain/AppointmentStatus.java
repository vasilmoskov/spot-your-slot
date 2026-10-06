package bg.spotyourslot.booking.domain;

/**
 * The MVP Appointment lifecycle (ADR-0022). Only {@link #CONFIRMED} blocks the StaffMember's time;
 * {@link #CANCELLED} rows are retained as history. Cancellation attribution, {@code COMPLETED}, and
 * {@code NO_SHOW} are deferred.
 */
public enum AppointmentStatus {
    CONFIRMED,
    CANCELLED
}
