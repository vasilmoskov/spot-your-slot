package bg.spotyourslot.publicbooking.web;

/** Raised when the Booking limiter rejects a public request; it carries only the retry delay. */
final class PublicRequestRateLimited extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    PublicRequestRateLimited(long retryAfterSeconds) {
        super("Public booking request was rate limited", null, false, false);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
