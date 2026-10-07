package bg.spotyourslot.booking;

/**
 * The guest booking use case (issue #18, ADR-0022 to ADR-0025): one idempotent, race-safe attempt to
 * create a {@code CONFIRMED}, {@code ONLINE} Appointment for an unauthenticated guest.
 *
 * <p><b>Transaction ownership.</b> The operation owns its transactions. It must be invoked with no
 * active transaction and no active transaction synchronization, and it rejects any other call with
 * {@link BookingOrchestrationFailure} before it reads a clock or issues any SQL, lock,
 * availability, or Customer call. Each attempt (at most three) is a completely new
 * repeatable-read, read-write transaction that has ended before the next starts; there is no
 * {@code REQUIRES_NEW}, savepoint, or caller-transaction composition. It is therefore not
 * composable into a larger transaction.
 *
 * <p><b>Results.</b> Every domain outcome, including the known-rollback and uncertain ones, is a
 * {@link BookingResult}. Only a violated precondition (the caller transaction) is thrown, because it
 * is a programming error and not a booking outcome; it is never retried.
 */
public interface GuestBooking {
    BookingResult book(GuestBookingRequest request);
}
