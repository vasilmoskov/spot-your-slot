package bg.spotyourslot.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.PublicBookingRateLimiter.Admission;
import bg.spotyourslot.integration.MutableTestClock;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The public booking limiter (ADR-0026) with an injected clock: every budget, the window boundary,
 * expiry, capacity, contact canonicalization, and cross-Business aggregate protection. Nothing
 * sleeps; time only moves when a test moves it.
 */
class BookingRateLimiterTests {
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String ADDRESS = "203.0.113.10";
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes();

    private final MutableTestClock clock = new MutableTestClock(T0);

    private BookingRateLimiter limiter() {
        return limiter(RateLimitSettings.DEFAULTS);
    }

    private BookingRateLimiter limiter(RateLimitSettings settings) {
        return new BookingRateLimiter(clock, settings, SECRET);
    }

    private static RateLimitSettings settings(int maxEntries) {
        return new RateLimitSettings(WINDOW, 10, 5, 30, 300, 600, maxEntries);
    }

    private void advance(Duration duration) {
        clock.set(clock.instant().plus(duration));
    }

    // ---- the approved initial budgets ----

    @Test
    void theDefaultsAreTheApprovedInitialBudgetsAndTheFinalizedAggregatesAndCapacity() {
        RateLimitSettings defaults = RateLimitSettings.DEFAULTS;

        assertThat(defaults.window()).isEqualTo(Duration.ofMinutes(15));
        assertThat(defaults.bookingPerAddressAndBusiness()).isEqualTo(10);
        assertThat(defaults.bookingPerContact()).isEqualTo(5);
        assertThat(defaults.availabilityPerAddressAndBusiness()).isEqualTo(300);
        assertThat(defaults.bookingPerAddress()).isEqualTo(30);
        assertThat(defaults.availabilityPerAddress()).isEqualTo(600);
        assertThat(defaults.maxEntries()).isEqualTo(50_000);
    }

    @Test
    void invalidSettingsAreRejectedWithFixedMessages() {
        for (Runnable invalid : List.<Runnable>of(
                () -> new RateLimitSettings(Duration.ZERO, 1, 1, 1, 1, 1, 16),
                () -> new RateLimitSettings(null, 1, 1, 1, 1, 1, 16),
                () -> new RateLimitSettings(WINDOW, 0, 1, 1, 1, 1, 16),
                () -> new RateLimitSettings(WINDOW, 1, -1, 1, 1, 1, 16),
                () -> new RateLimitSettings(WINDOW, 1, 1, 0, 1, 1, 16),
                () -> new RateLimitSettings(WINDOW, 1, 1, 1, 0, 1, 16),
                () -> new RateLimitSettings(WINDOW, 1, 1, 1, 1, 0, 16),
                () -> new RateLimitSettings(WINDOW, 1, 1, 1, 1, 1, 15))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new BookingRateLimiter(clock, RateLimitSettings.DEFAULTS, new byte[8]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The limiter secret is too short");
    }

    @Test
    void tenBookingAttemptsPerAddressAndBusinessThenTheEleventhIsRejected() {
        BookingRateLimiter limiter = limiter();

        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed())
                    .as("attempt %d", attempt).isTrue();
        }

        Admission rejected = limiter.admitBookingRequest(ADDRESS, "studio-a");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(900);
    }

    @Test
    void theBookingBudgetIsPerAddressAndPerBusiness() {
        BookingRateLimiter limiter = limiter();
        exhaustBooking(limiter, ADDRESS, "studio-a");

        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isFalse();
        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-b").allowed()).isTrue();
        assertThat(limiter.admitBookingRequest("203.0.113.11", "studio-a").allowed()).isTrue();
        // The slug is canonicalized, so its case and edge whitespace never open a second budget.
        assertThat(limiter.admitBookingRequest(ADDRESS, " Studio-A ").allowed()).isFalse();
    }

    @Test
    void fiveBookingAttemptsPerBusinessAndCanonicalContactThenTheSixthIsRejected() {
        BookingRateLimiter limiter = limiter();

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", null).allowed())
                    .as("attempt %d", attempt).isTrue();
        }

        Admission rejected = limiter.admitBookingContact("studio-a", "0888 123 456", null);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(900);
        // The budget is per Business: the same guest at another Business is separate.
        assertThat(limiter.admitBookingContact("studio-b", "0888 123 456", null).allowed()).isTrue();
    }

    @Test
    void theContactBudgetIsIndependentOfTheAddress() {
        BookingRateLimiter limiter = limiter();
        // Five attempts for one phone from five different addresses still exhaust that phone.
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(limiter.admitBookingRequest("203.0.113." + attempt, "studio-a").allowed()).isTrue();
            assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", null).allowed()).isTrue();
        }

        assertThat(limiter.admitBookingRequest("203.0.113.99", "studio-a").allowed()).isTrue();
        assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", null).allowed()).isFalse();
    }

    @Test
    void differentWritingsOfOneContactShareOneBudget() {
        BookingRateLimiter limiter = limiter();
        List<String> phoneWritings = List.of(
                "0888 123 456", "+359 888 123 456", "+359888123456", " 0888-123-456 ", "00359888123456");
        for (String writing : phoneWritings) {
            assertThat(limiter.admitBookingContact("studio-a", writing, null).allowed()).as(writing).isTrue();
        }

        assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", null).allowed()).isFalse();

        BookingRateLimiter emails = limiter();
        List<String> emailWritings = List.of(
                "ivan@example.com", "IVAN@Example.COM", "  ivan@example.com ", "Ivan@EXAMPLE.com", "ivan@example.com");
        for (String writing : emailWritings) {
            assertThat(emails.admitBookingContact("studio-a", null, writing).allowed()).as(writing).isTrue();
        }
        assertThat(emails.admitBookingContact("studio-a", null, "IVAN@example.com").allowed()).isFalse();
    }

    @Test
    void aRequestWithBothPhoneAndEmailChargesEachIdentifierOnce() {
        BookingRateLimiter limiter = limiter();

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", "ivan@example.com").allowed())
                    .isTrue();
        }

        // Both identifiers are exhausted, and alternating one of them cannot evade the other.
        assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", "ivan@example.com").allowed())
                .isFalse();
        assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", "other@example.com").allowed())
                .isFalse();
        assertThat(limiter.admitBookingContact("studio-a", "0899 000 111", "ivan@example.com").allowed())
                .isFalse();
        assertThat(limiter.admitBookingContact("studio-a", "0899 000 111", "other@example.com").allowed())
                .isTrue();
    }

    @Test
    void aRejectedContactDecisionChargesNeitherIdentifier() {
        BookingRateLimiter limiter = limiter();
        for (int attempt = 1; attempt <= 5; attempt++) {
            limiter.admitBookingContact("studio-a", null, "ivan@example.com");
        }

        // The email is exhausted; the combined request is rejected and must not charge the phone.
        for (int attempt = 1; attempt <= 20; attempt++) {
            assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", "ivan@example.com").allowed())
                    .isFalse();
        }

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", null).allowed())
                    .as("the phone was never charged: attempt %d", attempt).isTrue();
        }
        assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", null).allowed()).isFalse();
    }

    @Test
    void aLaterContactRejectionRetainsTheEarlierAddressCharge() {
        BookingRateLimiter limiter = limiter();
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", null).allowed()).isTrue();
        }

        // The controller's order: the address decision first (admitted and charged), then the contact
        // decision (rejected). The two are separate decisions, so the address unit stays spent.
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).as("address %d", attempt).isTrue();
            assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", null).allowed())
                    .as("contact %d", attempt).isFalse();
        }

        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isFalse();
    }

    @Test
    void anAddressRejectionChangesNoCounterAndTheContactBudgetIsUntouched() {
        BookingRateLimiter limiter = limiter();
        exhaustBooking(limiter, ADDRESS, "studio-a");
        int retained = limiter.retainedEntries();

        for (int attempt = 1; attempt <= 25; attempt++) {
            assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isFalse();
        }

        // No counter was created or extended, and no contact counter exists at all.
        assertThat(limiter.retainedEntries()).isEqualTo(retained).isEqualTo(2);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(limiter.admitBookingContact("studio-a", "0888 123 456", null).allowed()).isTrue();
        }
    }

    @Test
    void anAbsentOrUncanonicalizableContactChargesNothingAndIsAdmitted() {
        BookingRateLimiter limiter = limiter();

        for (int attempt = 1; attempt <= 50; attempt++) {
            assertThat(limiter.admitBookingContact("studio-a", null, null).allowed()).isTrue();
            assertThat(limiter.admitBookingContact("studio-a", "", "  ").allowed()).isTrue();
            assertThat(limiter.admitBookingContact("studio-a", "not a phone", "not an email").allowed())
                    .isTrue();
        }
        assertThat(limiter.retainedEntries()).isZero();
    }

    @Test
    void threeHundredAvailabilityReadsPerAddressAndBusinessThenTheNextIsRejected() {
        BookingRateLimiter limiter = limiter(new RateLimitSettings(WINDOW, 10, 5, 30, 300, 1_000, 50_000));

        for (int read = 1; read <= 300; read++) {
            assertThat(limiter.admitAvailabilityRequest(ADDRESS, "studio-a").allowed())
                    .as("read %d", read).isTrue();
        }

        Admission rejected = limiter.admitAvailabilityRequest(ADDRESS, "studio-a");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(900);
        assertThat(limiter.admitAvailabilityRequest(ADDRESS, "studio-b").allowed()).isTrue();
    }

    @Test
    void booksAndReadsHaveSeparateBudgets() {
        BookingRateLimiter limiter = limiter();
        exhaustBooking(limiter, ADDRESS, "studio-a");

        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isFalse();
        assertThat(limiter.admitAvailabilityRequest(ADDRESS, "studio-a").allowed()).isTrue();

        BookingRateLimiter reads = limiter(new RateLimitSettings(WINDOW, 10, 5, 30, 3, 100, 1_000));
        for (int read = 1; read <= 3; read++) {
            reads.admitAvailabilityRequest(ADDRESS, "studio-a");
        }
        assertThat(reads.admitAvailabilityRequest(ADDRESS, "studio-a").allowed()).isFalse();
        assertThat(reads.admitBookingRequest(ADDRESS, "studio-a").allowed()).isTrue();
    }

    // ---- aggregate protection across Businesses ----

    @Test
    void anAddressCannotExceedTheBookingAggregateAcrossBusinesses() {
        BookingRateLimiter limiter = limiter();

        // Three attempts at each of ten Businesses stay below every per-Business budget.
        for (int business = 1; business <= 10; business++) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                assertThat(limiter.admitBookingRequest(ADDRESS, "studio-" + business).allowed()).isTrue();
            }
        }

        Admission rejected = limiter.admitBookingRequest(ADDRESS, "studio-11");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(900);
        assertThat(limiter.admitBookingRequest("203.0.113.77", "studio-11").allowed()).isTrue();
        // Reads have their own aggregate, so a booking flood does not block reading.
        assertThat(limiter.admitAvailabilityRequest(ADDRESS, "studio-11").allowed()).isTrue();
    }

    @Test
    void anAddressCannotExceedTheReadAggregateAcrossBusinesses() {
        BookingRateLimiter limiter = limiter();

        for (int business = 1; business <= 6; business++) {
            for (int read = 1; read <= 100; read++) {
                assertThat(limiter.admitAvailabilityRequest(ADDRESS, "studio-" + business).allowed())
                        .as("business %d read %d", business, read).isTrue();
            }
        }

        assertThat(limiter.admitAvailabilityRequest(ADDRESS, "studio-7").allowed()).isFalse();
        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-7").allowed()).isTrue();
    }

    @Test
    void sprayingUnknownSlugsCreatesAtMostTheAggregateNumberOfKeysPerAddress() {
        BookingRateLimiter limiter = limiter();

        int admitted = 0;
        for (int slug = 1; slug <= 500; slug++) {
            if (limiter.admitBookingRequest(ADDRESS, "unknown-" + slug).allowed()) {
                admitted++;
            }
        }

        assertThat(admitted).isEqualTo(30);
        // One aggregate counter plus one counter per admitted slug; rejected requests add none.
        assertThat(limiter.retainedEntries()).isEqualTo(31);
    }

    @Test
    void malformedSlugsShareOneBucketAndCreateNoPerValueKey() {
        BookingRateLimiter limiter = limiter(new RateLimitSettings(WINDOW, 1_000, 5, 1_000, 300, 600, 1_000));

        for (int index = 0; index < 200; index++) {
            limiter.admitBookingRequest(ADDRESS, "Bad_Slug!" + index);
            limiter.admitBookingRequest(ADDRESS, "-leading" + index);
            limiter.admitBookingRequest(ADDRESS, "x".repeat(101 + index));
            limiter.admitBookingRequest(ADDRESS, "ключ" + index);
        }
        limiter.admitBookingRequest(ADDRESS, null);

        // The aggregate and the single shared malformed bucket; nothing per value.
        assertThat(limiter.retainedEntries()).isEqualTo(2);
    }

    @Test
    void anIpv6AddressIsLimitedAsItsSixtyFourBitPrefix() {
        BookingRateLimiter limiter = limiter();
        exhaustBooking(limiter, "2001:db8:1:2:aaaa:bbbb:cccc:dddd", "studio-a");

        assertThat(limiter.admitBookingRequest("2001:db8:1:2:1:2:3:4", "studio-a").allowed()).isFalse();
        assertThat(limiter.admitBookingRequest("2001:0db8:0001:0002::9", "studio-a").allowed()).isFalse();
        assertThat(limiter.admitBookingRequest("2001:db8:1:3:1:2:3:4", "studio-a").allowed()).isTrue();
    }

    @Test
    void anIpv4MappedIpv6AddressSharesTheIpv4Budget() {
        BookingRateLimiter limiter = limiter();
        exhaustBooking(limiter, "203.0.113.10", "studio-a");

        assertThat(limiter.admitBookingRequest("::ffff:203.0.113.10", "studio-a").allowed()).isFalse();
    }

    @Test
    void anUnparsableAddressNeverResolvesANameAndSharesOneBucket() {
        assertThat(BookingRateLimiter.addressBucket("localhost")).isEqualTo(BookingRateLimiter.addressBucket("x"));
        assertThat(BookingRateLimiter.addressBucket(null)).isEqualTo(BookingRateLimiter.addressBucket(""));
        assertThat(BookingRateLimiter.addressBucket("1".repeat(65)))
                .isEqualTo(BookingRateLimiter.addressBucket("not an address"));
        assertThat(BookingRateLimiter.addressBucket("203.0.113.10"))
                .isNotEqualTo(BookingRateLimiter.addressBucket("not an address"));
    }

    // ---- window, boundary, expiry ----

    @Test
    void theWindowEndsExactlyFifteenMinutesAfterTheFirstCharge() {
        BookingRateLimiter limiter = limiter();
        exhaustBooking(limiter, ADDRESS, "studio-a");

        advance(WINDOW.minusMillis(1));
        Admission justBefore = limiter.admitBookingRequest(ADDRESS, "studio-a");
        assertThat(justBefore.allowed()).isFalse();
        assertThat(justBefore.retryAfterSeconds()).isEqualTo(1);

        advance(Duration.ofMillis(1));
        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isTrue();
        // A fresh window starts at the new first charge and allows a full budget again.
        for (int attempt = 2; attempt <= 10; attempt++) {
            assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isTrue();
        }
        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isFalse();
    }

    @Test
    void retryAfterShrinksWithTheRemainingWindowAndRoundsUp() {
        BookingRateLimiter limiter = limiter();
        exhaustBooking(limiter, ADDRESS, "studio-a");

        advance(Duration.ofMinutes(5));
        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").retryAfterSeconds()).isEqualTo(600);
        advance(Duration.ofMillis(500));
        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").retryAfterSeconds()).isEqualTo(600);
        advance(Duration.ofMillis(600));
        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").retryAfterSeconds()).isEqualTo(599);
    }

    @Test
    void rejectedRequestsNeverExtendOrRestartAWindow() {
        BookingRateLimiter limiter = limiter();
        exhaustBooking(limiter, ADDRESS, "studio-a");

        for (int minute = 1; minute < 15; minute++) {
            advance(Duration.ofMinutes(1));
            assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isFalse();
        }
        advance(Duration.ofMinutes(1));

        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isTrue();
    }

    @Test
    void aWindowStartsAtItsFirstChargeNotAtTheClockGrid() {
        BookingRateLimiter limiter = limiter();
        advance(Duration.ofMinutes(7));
        exhaustBooking(limiter, ADDRESS, "studio-a");

        advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isFalse();
        advance(Duration.ofSeconds(1));
        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isTrue();
    }

    @Test
    void expiredCountersAreRemovedSoMemoryReturnsToZero() {
        BookingRateLimiter limiter = limiter();
        for (int address = 1; address <= 100; address++) {
            limiter.admitBookingRequest("198.51.100." + address, "studio-a");
            limiter.admitBookingContact("studio-a", "0888 000 " + (100 + address), null);
        }
        assertThat(limiter.retainedEntries()).isEqualTo(300);

        advance(WINDOW.minusMillis(1));
        assertThat(limiter.retainedEntries()).isEqualTo(300);
        advance(Duration.ofMillis(1));
        assertThat(limiter.retainedEntries()).isZero();
    }

    @Test
    void expiryRemovesOnlyTheCountersWhoseWindowHasEnded() {
        BookingRateLimiter limiter = limiter();
        limiter.admitBookingRequest("198.51.100.1", "studio-a");
        advance(Duration.ofMinutes(10));
        limiter.admitBookingRequest("198.51.100.2", "studio-a");

        advance(Duration.ofMinutes(5));

        // The first pair (aggregate and address-business) expired, the second did not.
        assertThat(limiter.retainedEntries()).isEqualTo(2);
    }

    @Test
    void aClockThatMovesBackwardsNeverExpiresACounterEarlyOrShortensAWindow() {
        BookingRateLimiter limiter = limiter();
        exhaustBooking(limiter, ADDRESS, "studio-a");

        clock.set(T0.minus(Duration.ofHours(2)));
        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isFalse();
        assertThat(limiter.retainedEntries()).isEqualTo(2);

        // Time continues from the last time the limiter saw, not from the stepped-back clock.
        clock.set(T0.plus(WINDOW).minusSeconds(1));
        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isFalse();
        clock.set(T0.plus(WINDOW));
        assertThat(limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()).isTrue();
    }

    // ---- capacity ----

    @Test
    void atCapacityANewKeyIsRejectedFailClosedWhileExistingKeysKeepWorking() {
        // Capacity 16 holds eight addresses (an aggregate and an address-business counter each).
        BookingRateLimiter limiter = limiter(settings(16));
        for (int address = 1; address <= 8; address++) {
            assertThat(limiter.admitBookingRequest("198.51.100." + address, "studio-a").allowed()).isTrue();
        }
        assertThat(limiter.retainedEntries()).isEqualTo(16);

        advance(Duration.ofMinutes(1));
        Admission saturated = limiter.admitBookingRequest("198.51.100.9", "studio-a");
        assertThat(saturated.allowed()).isFalse();
        assertThat(saturated.retryAfterSeconds()).isEqualTo(840);
        assertThat(limiter.retainedEntries()).isEqualTo(16);

        // A caller already counted is still served (and still limited) at saturation.
        assertThat(limiter.admitBookingRequest("198.51.100.1", "studio-a").allowed()).isTrue();
        assertThat(limiter.retainedEntries()).isEqualTo(16);
    }

    @Test
    void saturationNeverEvictsAnActiveCounterSoALimitCannotBeBypassed() {
        BookingRateLimiter limiter = limiter(settings(16));
        exhaustBooking(limiter, "198.51.100.1", "studio-a");
        for (int address = 2; address <= 8; address++) {
            limiter.admitBookingRequest("198.51.100." + address, "studio-a");
        }
        assertThat(limiter.retainedEntries()).isEqualTo(16);

        // Hammering with fresh addresses must neither admit them nor reset the exhausted address.
        for (int address = 100; address < 300; address++) {
            assertThat(limiter.admitBookingRequest("198.51.101." + address, "studio-a").allowed()).isFalse();
        }

        assertThat(limiter.admitBookingRequest("198.51.100.1", "studio-a").allowed()).isFalse();
        assertThat(limiter.retainedEntries()).isEqualTo(16);
    }

    @Test
    void capacityFreesAsTheOldestCountersExpire() {
        BookingRateLimiter limiter = limiter(settings(16));
        for (int address = 1; address <= 8; address++) {
            limiter.admitBookingRequest("198.51.100." + address, "studio-a");
        }
        assertThat(limiter.admitBookingRequest("198.51.100.9", "studio-a").allowed()).isFalse();

        advance(WINDOW);

        assertThat(limiter.admitBookingRequest("198.51.100.9", "studio-a").allowed()).isTrue();
        assertThat(limiter.retainedEntries()).isEqualTo(2);
    }

    @Test
    void theSaturatedRetryAfterIsTheTimeUntilTheOldestCounterExpires() {
        BookingRateLimiter limiter = limiter(settings(16));
        limiter.admitBookingRequest("198.51.100.1", "studio-a");
        advance(Duration.ofMinutes(10));
        for (int address = 2; address <= 8; address++) {
            limiter.admitBookingRequest("198.51.100." + address, "studio-a");
        }

        Admission saturated = limiter.admitBookingRequest("198.51.100.9", "studio-a");

        assertThat(saturated.allowed()).isFalse();
        assertThat(saturated.retryAfterSeconds()).isEqualTo(300);
    }

    @Test
    void aDecisionThatNeedsTwoNewCountersIsRejectedAtomicallyWhenOnlyOneIsFree() {
        BookingRateLimiter limiter = limiter(settings(16));
        for (int address = 1; address <= 7; address++) {
            limiter.admitBookingRequest("198.51.100." + address, "studio-a");
        }
        limiter.admitBookingContact("studio-a", "0888 123 456", null);
        assertThat(limiter.retainedEntries()).isEqualTo(15);

        // Two phone and email counters are needed but one slot is free: nothing is charged.
        assertThat(limiter.admitBookingContact("studio-a", "0888 999 999", "new@example.com").allowed())
                .isFalse();
        assertThat(limiter.retainedEntries()).isEqualTo(15);
        assertThat(limiter.admitBookingContact("studio-a", "0888 999 999", null).allowed()).isTrue();
        assertThat(limiter.retainedEntries()).isEqualTo(16);
    }

    // ---- atomicity ----

    @Test
    void aRejectedDecisionChargesNoCounterEvenWhenAnotherBudgetOfItHasRoom() {
        FixedWindowCounters counters = new FixedWindowCounters(clock, WINDOW, 100);
        Digest exhausted = new Digest(1, 1);
        Digest roomy = new Digest(2, 2);
        assertThat(counters.tryCharge(List.of(new FixedWindowCounters.Charge(exhausted, 1))).allowed())
                .isTrue();

        for (int attempt = 0; attempt < 50; attempt++) {
            assertThat(counters.tryCharge(List.of(
                            new FixedWindowCounters.Charge(roomy, 5),
                            new FixedWindowCounters.Charge(exhausted, 1)))
                    .allowed()).isFalse();
        }

        assertThat(counters.retainedEntries()).isEqualTo(1);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(counters.tryCharge(List.of(new FixedWindowCounters.Charge(roomy, 5))).allowed())
                    .isTrue();
        }
    }

    @Test
    void aDecisionMustNotChargeTheSameCounterTwice() {
        FixedWindowCounters counters = new FixedWindowCounters(clock, WINDOW, 100);
        Digest key = new Digest(3, 3);

        assertThatThrownBy(() -> counters.tryCharge(List.of(
                        new FixedWindowCounters.Charge(key, 5), new FixedWindowCounters.Charge(key, 5))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentRequestsNeverExceedABudget() throws Exception {
        BookingRateLimiter limiter = limiter();
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();
            List<Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (limiter.admitBookingRequest(ADDRESS, "studio-a").allowed()) {
                        admitted.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }

            assertThat(admitted.get()).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentContactRequestsNeverExceedTheContactBudgetAndNeverDeadlock() throws Exception {
        BookingRateLimiter limiter = limiter();
        int threads = 48;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();
            List<Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                // Half supply both identifiers in the other order of use, half only one of them.
                boolean both = thread % 2 == 0;
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    Admission admission = both
                            ? limiter.admitBookingContact("studio-a", "0888 123 456", "ivan@example.com")
                            : limiter.admitBookingContact("studio-a", null, "ivan@example.com");
                    if (admission.allowed()) {
                        admitted.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }

            // The email is shared by every request, so exactly five are admitted in total.
            assertThat(admitted.get()).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- privacy of the retained state ----

    @Test
    void theRetainedStateHoldsOnlyOpaqueDigestsAndNeverARawValue() throws Exception {
        BookingRateLimiter limiter = limiter();
        limiter.admitBookingRequest("203.0.113.10", "secret-studio");
        limiter.admitAvailabilityRequest("2001:db8:1:2:3:4:5:6", "secret-studio");
        limiter.admitBookingContact("secret-studio", "0888 123 456", "private.person@example.com");

        Field countersField = BookingRateLimiter.class.getDeclaredField("counters");
        countersField.setAccessible(true);
        Object counters = countersField.get(limiter);
        Field mapField = FixedWindowCounters.class.getDeclaredField("counters");
        mapField.setAccessible(true);
        Map<?, ?> state = (Map<?, ?>) mapField.get(counters);

        assertThat(state).hasSize(6);
        for (Object key : state.keySet()) {
            assertThat(key).isInstanceOf(Digest.class);
            assertThat(key.toString()).isEqualTo("Digest[redacted]");
        }
        String everything = state.toString();
        assertThat(everything)
                .doesNotContain("203.0.113")
                .doesNotContain("2001")
                .doesNotContain("secret-studio")
                .doesNotContain("888")
                .doesNotContain("private.person");
    }

    @Test
    void theDigestsDependOnAPerProcessSecretAndAreLengthSafe() {
        BookingRateLimiter first = new BookingRateLimiter(clock, RateLimitSettings.DEFAULTS, SECRET);
        BookingRateLimiter second = new BookingRateLimiter(
                clock, RateLimitSettings.DEFAULTS, "ffffffffffffffffffffffffffffffff".getBytes());
        // The same inputs under different secrets are unrelated keys, so state cannot be matched
        // across processes; this is observed through behavior: independent budgets.
        exhaustBooking(first, ADDRESS, "studio-a");
        assertThat(second.admitBookingRequest(ADDRESS, "studio-a").allowed()).isTrue();

        // ("ab","c") and ("a","bc") must never collide: length-prefixed parts.
        BookingRateLimiter limiter = limiter();
        exhaustBooking(limiter, "198.51.100.1", "ab-c");
        assertThat(limiter.admitBookingRequest("198.51.100.1", "a-bc").allowed()).isTrue();
    }

    @Test
    void anAdmissionIsConsistent() {
        assertThat(Admission.permitted().allowed()).isTrue();
        assertThat(Admission.rejected(7).retryAfterSeconds()).isEqualTo(7);
        assertThatThrownBy(() -> new Admission(true, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Admission(false, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void exhaustBooking(BookingRateLimiter limiter, String address, String slug) {
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(limiter.admitBookingRequest(address, slug).allowed()).isTrue();
        }
    }
}
