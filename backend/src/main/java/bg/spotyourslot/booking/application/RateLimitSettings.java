package bg.spotyourslot.booking.application;

import java.time.Duration;

/**
 * The limiter budgets (ADR-0026). Every value is an explicit, validated bound. The initial values
 * are the approved ones (10, 5, 300 per 15 minutes) plus the Phase 5 aggregate and capacity
 * settings.
 *
 * @param window the one fixed window length shared by every budget
 * @param bookingPerAddressAndBusiness booking attempts per remote address and Business
 * @param bookingPerContact booking attempts per Business and canonical contact identifier
 * @param bookingPerAddress booking attempts per remote address across all Businesses
 * @param availabilityPerAddressAndBusiness {@code booking-options} and {@code availability} reads
 *        per remote address and Business
 * @param availabilityPerAddress reads per remote address across all Businesses
 * @param maxEntries the most counters retained at once; a request needing a new counter beyond it
 *        is rejected (fail closed) and no active counter is ever evicted
 */
public record RateLimitSettings(
        Duration window,
        int bookingPerAddressAndBusiness,
        int bookingPerContact,
        int bookingPerAddress,
        int availabilityPerAddressAndBusiness,
        int availabilityPerAddress,
        int maxEntries) {
    /** A capacity below this could not hold even a few concurrent callers' counters. */
    static final int MIN_ENTRIES = 16;

    public static final RateLimitSettings DEFAULTS = new RateLimitSettings(
            Duration.ofMinutes(15), 10, 5, 30, 300, 600, 50_000);

    public RateLimitSettings {
        if (window == null || window.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("The rate-limit window must be at least one second");
        }
        requirePositive(bookingPerAddressAndBusiness);
        requirePositive(bookingPerContact);
        requirePositive(bookingPerAddress);
        requirePositive(availabilityPerAddressAndBusiness);
        requirePositive(availabilityPerAddress);
        if (maxEntries < MIN_ENTRIES) {
            throw new IllegalArgumentException("The rate-limit capacity is too small");
        }
    }

    private static void requirePositive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Every rate-limit budget must be positive");
        }
    }
}
