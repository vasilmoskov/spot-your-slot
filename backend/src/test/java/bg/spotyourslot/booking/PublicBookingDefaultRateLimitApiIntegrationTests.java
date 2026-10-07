package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The shipped defaults are wired: 10 booking attempts per address and Business and 5 per canonical
 * contact within 15 minutes, and 300 availability reads per address and Business.
 */
class PublicBookingDefaultRateLimitApiIntegrationTests extends PublicBookingApiIntegrationTest {
    @Test
    void tenBookingAttemptsPerAddressAndBusinessAreAdmittedAndTheEleventhIsRefused() throws Exception {
        String remote = freshAddress();
        String url = bookingsUrl(slugOf(tenant.business()));
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(postJsonFrom(url, "{", remote).getResponse().getStatus()).as("attempt %d", attempt)
                    .isEqualTo(400);
        }

        MvcResult refused = postJsonFrom(url, "{", remote);

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getHeader("Retry-After")).isEqualTo("900");
    }

    @Test
    void fiveBookingAttemptsPerContactAreAdmittedAndTheSixthIsRefused() throws Exception {
        String phone = "0888 400 001";
        int attempt = 0;
        for (String start : java.util.List.of("09:00", "09:30", "10:00", "10:30", "11:00")) {
            MvcResult result = book(req().phone(phone).start(start));
            assertThat(result.getResponse().getStatus()).as("attempt %d", ++attempt).isEqualTo(201);
        }

        MvcResult refused = book(req().phone(phone).start("11:30"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getHeader("Retry-After")).isEqualTo("900");
    }

    @Test
    void threeHundredReadsPerAddressAndBusinessAreAdmittedAndTheNextIsRefused() throws Exception {
        String remote = freshAddress();
        String url = optionsUrl(slugOf(tenant.business()), tenant.service());
        for (int read = 1; read <= 300; read++) {
            int status = getJsonFrom(url, remote).getResponse().getStatus();
            if (status != 200) {
                // The default read aggregate (600) is far above this, so no refusal may occur early.
                throw new AssertionError("read " + read + " was refused with " + status);
            }
        }

        assertThat(getJsonFrom(url, remote).getResponse().getStatus()).isEqualTo(429);
    }
}
