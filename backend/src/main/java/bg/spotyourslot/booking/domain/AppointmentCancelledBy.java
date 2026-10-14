package bg.spotyourslot.booking.domain;

/**
 * Who cancelled an Appointment (ADR-0028 section 8). {@link #BUSINESS} is the only actor the
 * application writes. {@link #UNKNOWN} exists solely for rows cancelled before the attribution
 * columns existed (migration {@code V14}); it carries no cancellation time and is never produced
 * by a transition.
 */
public enum AppointmentCancelledBy {
    BUSINESS,
    UNKNOWN
}
