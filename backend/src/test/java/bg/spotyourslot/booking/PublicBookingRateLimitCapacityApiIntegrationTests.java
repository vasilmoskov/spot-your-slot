package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The limiter's capacity behavior through HTTP (ADR-0026): a capacity of 16 counters holds eight
 * addresses (an aggregate and a Business counter each). Each test starts one whole window later than
 * the previous one, so the counters of earlier tests have expired and the capacity is exact.
 */
@TestPropertySource(properties = {
    "spotyourslot.booking.rate-limit.availability-per-address-business=3",
    "spotyourslot.booking.rate-limit.max-entries=16",
    "spotyourslot.booking.rate-limit.window-seconds=900"
})
class PublicBookingRateLimitCapacityApiIntegrationTests extends PublicBookingApiIntegrationTest {
    private static final AtomicInteger WINDOWS = new AtomicInteger();

    @BeforeEach
    void startInAFreshWindow() {
        clock.set(BookingHookConfiguration.NOW.plus(Duration.ofMinutes(15L * WINDOWS.incrementAndGet())));
    }

    private MvcResult optionsFrom(String remote, String slug) throws Exception {
        return getJsonFrom(optionsUrl(slug, tenant.service()), remote);
    }

    @Test
    void atCapacityANewAddressIsRefusedAndACountedOneKeepsItsBudget() throws Exception {
        String slug = slugOf(tenant.business());
        List<String> counted = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String remote = freshAddress();
            counted.add(remote);
            assertThat(optionsFrom(remote, slug).getResponse().getStatus()).isEqualTo(200);
        }

        MvcResult saturated = optionsFrom(freshAddress(), slug);

        assertThat(saturated.getResponse().getStatus()).isEqualTo(429);
        assertThat(saturated.getResponse().getHeader("Retry-After")).isEqualTo("900");
        assertThat(tree(saturated).get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(tree(saturated).get("detail").asString()).isEqualTo("Твърде много опити. Опитайте по-късно.");
        // A counted address is still served and still limited by its own budget (3 per Business).
        assertThat(optionsFrom(counted.get(0), slug).getResponse().getStatus()).isEqualTo(200);
        assertThat(optionsFrom(counted.get(0), slug).getResponse().getStatus()).isEqualTo(200);
        assertThat(optionsFrom(counted.get(0), slug).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void saturationNeverEvictsAnActiveCounterSoALimitCannotBeBypassed() throws Exception {
        String slug = slugOf(tenant.business());
        String limited = freshAddress();
        for (int read = 1; read <= 3; read++) {
            assertThat(optionsFrom(limited, slug).getResponse().getStatus()).isEqualTo(200);
        }
        for (int index = 0; index < 7; index++) {
            optionsFrom(freshAddress(), slug);
        }

        for (int index = 0; index < 20; index++) {
            assertThat(optionsFrom(freshAddress(), slug).getResponse().getStatus()).isEqualTo(429);
        }

        assertThat(optionsFrom(limited, slug).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void capacityIsFreedWhenTheOldestWindowEndsAndRetryAfterSaysWhen() throws Exception {
        String slug = slugOf(tenant.business());
        for (int index = 0; index < 8; index++) {
            optionsFrom(freshAddress(), slug);
        }
        clock.set(clock.instant().plus(Duration.ofMinutes(10)));

        MvcResult saturated = optionsFrom(freshAddress(), slug);
        assertThat(saturated.getResponse().getStatus()).isEqualTo(429);
        assertThat(saturated.getResponse().getHeader("Retry-After")).isEqualTo("300");

        clock.set(clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(optionsFrom(freshAddress(), slug).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aSaturatedLimiterRefusesNewBookingKeysBeforeTheyReachTheDatabase() throws Exception {
        String slug = slugOf(tenant.business());
        for (int index = 0; index < 8; index++) {
            optionsFrom(freshAddress(), slug);
        }

        MvcResult refused = book(req());

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
    }
}
