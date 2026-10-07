package bg.spotyourslot.booking;

/**
 * A violated precondition of {@link GuestBooking}: it was invoked inside a transaction or an
 * active transaction synchronization. It is a sanitized internal failure, not a booking outcome,
 * never retried, and thrown before any SQL, lock, clock read, availability call, or Customer call.
 * The message is fixed and no cause is retained.
 */
public final class BookingOrchestrationFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BookingOrchestrationFailure() {
        super("Guest booking must be invoked without an active transaction", null, false, true);
    }
}
