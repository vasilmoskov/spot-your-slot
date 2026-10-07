package bg.spotyourslot.booking;

/**
 * The Booking-owned abuse limiter for the unauthenticated guest booking surface (ADR-0026). It
 * decides before any database work, so a rejected request writes nothing. It is process-local, fixed
 * window, bounded in memory, and fails closed: see the implementation for the exact budgets and the
 * documented single-instance limitation.
 *
 * <p><b>Atomicity applies within one admission decision, not across decisions.</b> Each method is one
 * decision: it charges all of its counters or none, and a rejected decision changes no counter (it
 * creates, increments, and extends nothing). Address admission ({@link #admitBookingRequest},
 * {@link #admitAvailabilityRequest}) and contact admission ({@link #admitBookingContact}) are
 * separate decisions that a caller makes in sequence. The public booking endpoint admits the address
 * first; if the contact decision then rejects, the address counters stay charged, because the attempt
 * did reach the endpoint. Conversely, a rejected address decision never reaches the contact decision, so
 * it charges no contact counter. Within the contact decision, a phone and an email are charged
 * all-or-none. None of the methods stores or logs a raw address, slug, or contact value.
 */
public interface PublicBookingRateLimiter {
    /**
     * Charges one booking attempt to the aggregate budget of the remote address (across all
     * Businesses) and to the budget of that address for the Business.
     *
     * @param remoteAddress the servlet remote address (never a forwarded header)
     * @param businessSlug the slug exactly as the public route supplied it
     */
    Admission admitBookingRequest(String remoteAddress, String businessSlug);

    /**
     * Charges one {@code booking-options} or {@code availability} read to the aggregate read budget
     * of the remote address and to the read budget of that address for the Business.
     */
    Admission admitAvailabilityRequest(String remoteAddress, String businessSlug);

    /**
     * Charges one booking attempt to the budget of every canonical contact identifier supplied
     * (phone and email each, independently) for the Business. An identifier that cannot be
     * canonicalized charges nothing; a request without any canonical identifier is admitted.
     *
     * @param phone the submitted phone, or null
     * @param email the submitted email, or null
     */
    Admission admitBookingContact(String businessSlug, String phone, String email);

    /**
     * @param allowed whether the request may proceed
     * @param retryAfterSeconds when rejected, the whole seconds (at least 1) until the limiting
     *        window or the saturated capacity can free; zero when allowed
     */
    record Admission(boolean allowed, long retryAfterSeconds) {
        public Admission {
            if (allowed ? retryAfterSeconds != 0 : retryAfterSeconds < 1) {
                throw new IllegalArgumentException("Inconsistent admission");
            }
        }

        public static Admission permitted() {
            return new Admission(true, 0);
        }

        public static Admission rejected(long retryAfterSeconds) {
            return new Admission(false, retryAfterSeconds);
        }
    }
}
