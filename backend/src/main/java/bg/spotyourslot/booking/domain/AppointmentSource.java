package bg.spotyourslot.booking.domain;

/**
 * How an Appointment entered the system. It never changes authorization, conflict protection,
 * privacy, or lifecycle rules (ADR-0022). {@code MANUAL} is reserved for the Business calendar.
 */
public enum AppointmentSource {
    ONLINE,
    MANUAL
}
