package bg.spotyourslot.booking.domain;

/** A manual visit request that violates a domain bound. The message is fixed and carries no value. */
public final class InvalidManualVisit extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidManualVisit() {
        super("Manual Appointment request is invalid", null, false, false);
    }
}
