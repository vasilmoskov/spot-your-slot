package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The booking request body bound (ADR-0026) through MockMvc, with a 2048-byte limit so the boundary is
 * cheap to build.
 *
 * <p><b>Evidence boundary.</b> MockMvc proves the application logic: the exact byte boundary, bytes
 * rather than characters, the fixed 413 contract, charging, and that nothing reaches the database. It
 * cannot prove how a container delivers chunked bodies or a missing, huge, or lying
 * {@code Content-Length}: MockMvc derives the declared length from the content it holds, so those cases
 * are only meaningful over a real server and are in {@code PublicBookingBodyLimitContainerIntegrationTests}.
 */
@TestPropertySource(properties = {
    "spotyourslot.booking.max-request-body-bytes=2048",
    "spotyourslot.booking.rate-limit.booking-per-address-business=4",
    "spotyourslot.booking.rate-limit.booking-per-contact=2"
})
class PublicBookingBodyLimitApiIntegrationTests extends PublicBookingApiIntegrationTest {
    private static final int LIMIT = 2048;

    /** The valid body of the request, padded with JSON whitespace to exactly {@code bytes} bytes. */
    private byte[] padded(Req request, int bytes) {
        byte[] json = body(request).toString().getBytes(StandardCharsets.UTF_8);
        assertThat(json.length).isLessThanOrEqualTo(bytes);
        byte[] padded = new byte[bytes];
        System.arraycopy(json, 0, padded, 0, json.length);
        java.util.Arrays.fill(padded, json.length, bytes, (byte) ' ');
        return padded;
    }

    private MvcResult send(String slug, byte[] content, String remote) throws Exception {
        MockHttpServletRequestBuilder builder = post(bookingsUrl(slug))
                .contentType(MediaType.APPLICATION_JSON)
                .content(content);
        return mvc.perform(from(builder, remote)).andReturn();
    }

    private void assertTooLarge(MvcResult result) throws Exception {
        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeaderValues("Set-Cookie")).isEmpty();
        JsonNode body = tree(result);
        assertThat(keys(body)).containsExactlyInAnyOrder("title", "status", "detail", "instance", "code");
        assertThat(body.get("code").asString()).isEqualTo("REQUEST_TOO_LARGE");
        assertThat(body.get("detail").asString()).isEqualTo("Заявката е твърде голяма.");
        assertThat(body.get("title").asString()).isEqualTo("Заявката не може да бъде изпълнена.");
        assertThat(body.get("instance").asString()).isEqualTo(FIXED_INSTANCE);
        assertThat(body.get("status").asInt()).isEqualTo(413);
    }

    // ---- the boundary ----

    @Test
    void aBodyOfExactlyTheLimitIsAcceptedAndOneByteMoreIsRefused() throws Exception {
        Req fits = req().start("09:00");
        Req over = req().start("09:30");

        MvcResult accepted = send(fits.slug, padded(fits, LIMIT), freshAddress());
        MvcResult refused = send(over.slug, padded(over, LIMIT + 1), freshAddress());

        assertThat(accepted.getResponse().getStatus()).isEqualTo(201);
        assertTooLarge(refused);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void theLimitCountsBytesNotCharacters() throws Exception {
        // 1100 Cyrillic letters are 1100 characters but 2200 bytes: above the 2048-byte limit.
        Req wide = req().note("я".repeat(1100));
        byte[] content = body(wide).toString().getBytes(StandardCharsets.UTF_8);
        assertThat(body(wide).toString().length()).isLessThan(LIMIT);
        assertThat(content.length).isGreaterThan(LIMIT);

        assertTooLarge(send(wide.slug, content, freshAddress()));
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void aValidMultibyteBulgarianPayloadWithinTheLimitIsBooked() throws Exception {
        // 500 code points is the longest allowed note: 1000 bytes of Cyrillic, more bytes than characters.
        Req request = req().name("Йорданка Щерьова-Мълчанова").note("ъ".repeat(500));
        byte[] content = body(request).toString().getBytes(StandardCharsets.UTF_8);
        assertThat(content.length).isBetween(1000, LIMIT);
        assertThat(body(request).toString().length()).isLessThan(content.length);

        MvcResult result = send(request.slug, content, freshAddress());

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT customer_note FROM appointment").query(String.class).single())
                .isEqualTo("ъ".repeat(500));
        assertThat(jdbc.sql("SELECT display_name FROM customer").query(String.class).single())
                .isEqualTo("Йорданка Щерьова-Мълчанова");
    }

    // ---- no work, charging, and the unchanged rules ----

    @Test
    void anOversizedRequestReachesNeitherTheOrchestrationNorTheDatabase() throws Exception {
        Req over = req();
        TransactionLog.clear();
        clock.resetReads();

        MvcResult result = onBookingThread("oversized",
                () -> send(over.slug, padded(over, LIMIT * 8), freshAddress()));

        assertTooLarge(result);
        assertThat(TransactionLog.begins(threadOf("oversized"))).isEmpty();
        assertThat(hooks.observations()).isEmpty();
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
    }

    @Test
    void anOversizedRequestIsChargedOnceToTheAddressAndNeverToTheContactBudget() throws Exception {
        String remote = freshAddress();
        String phone = "0888 700 001";
        // Each oversized body starts with the phone, which is never parsed, so no contact counter exists.
        for (int attempt = 1; attempt <= 2; attempt++) {
            Req big = req().phone(phone).start(String.format("%02d:00", attempt + 8));
            assertTooLarge(send(big.slug, padded(big, LIMIT + 1), remote));
        }
        // The contact budget is 2: both valid bookings with that phone are still admitted.
        for (String start : List.of("11:00", "11:30")) {
            Req valid = req().phone(phone).start(start);
            assertThat(send(valid.slug, padded(valid, 600), remote).getResponse().getStatus())
                    .isEqualTo(201);
        }

        // The address-and-Business budget is 4: two oversized plus two valid used it exactly once each.
        Req fifth = req().phone("0888 700 002").start("09:00");
        MvcResult limited = send(fifth.slug, padded(fifth, 600), remote);
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(tree(limited).get("code").asString()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void sessionIndependenceCsrfAndCorsAreUnchangedForAnOversizedRequest() throws Exception {
        var owner = validSession(businessOwner(tenant.business()), tenant.business());
        List<String> sessions = sessionRows();
        Req big = req();
        byte[] content = padded(big, LIMIT + 1);

        // No CSRF token and a valid owner cookie: the exemption holds, so the size is what refuses it.
        MvcResult withSession = mvc.perform(from(post(bookingsUrl(big.slug)).cookie(owner)
                .contentType(MediaType.APPLICATION_JSON).content(content), freshAddress())).andReturn();
        MvcResult approvedOrigin = mvc.perform(from(post(bookingsUrl(big.slug)).header("Origin", APPROVED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(content), freshAddress())).andReturn();
        MvcResult foreignOrigin = mvc.perform(from(post(bookingsUrl(big.slug)).header("Origin", "https://x.invalid")
                .contentType(MediaType.APPLICATION_JSON).content(content), freshAddress())).andReturn();

        assertTooLarge(withSession);
        assertTooLarge(approvedOrigin);
        assertThat(approvedOrigin.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(APPROVED_ORIGIN);
        assertThat(foreignOrigin.getResponse().getStatus()).isEqualTo(403);
        assertThat(foreignOrigin.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(sessionRows()).isEqualTo(sessions);
    }

    @Test
    void thePrivateEndpointsAndOtherVerbsAreNotBounded() throws Exception {
        var owner = validSession(businessOwner(tenant.business()), tenant.business());
        String huge = "{\"name\":\"Услуга\",\"description\":\"" + "x".repeat(LIMIT * 10)
                + "\",\"durationMinutes\":30,\"price\":10}";

        MockHttpServletResponse response = mvc.perform(post("/api/business/services").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(huge))
                .andReturn().getResponse();

        // Refused by its own validation (description too long), not by the public body bound.
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("VALIDATION_ERROR").doesNotContain("REQUEST_TOO_LARGE");
        // A body-less GET of a public read is unaffected as well.
        assertThat(getJson(optionsUrl(slugOf(tenant.business()), tenant.service())).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @SuppressWarnings("unused")
    void theMaterializedTreeNeverExceedsTheBoundBecauseTheStreamFailsFirst() throws Exception {
        // 100 000 nested array openers: a parser would have to read far past the bound to build them.
        Req deep = req();
        ObjectNode shell = body(deep);
        String hostile = "{\"attemptId\":" + "[".repeat(100_000);

        MvcResult result = send(deep.slug, hostile.getBytes(StandardCharsets.UTF_8), freshAddress());

        assertTooLarge(result);
        assertThat(shell).isNotNull();
    }
}
